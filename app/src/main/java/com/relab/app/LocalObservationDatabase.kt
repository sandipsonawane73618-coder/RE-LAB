package com.relab.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.relab.core.Market
import com.relab.core.ObservationStatus
import com.relab.core.PredictionSnapshot
import com.relab.core.PredictionSnapshotOutcome
import com.relab.core.SourceObservation
import com.relab.core.SourceObservationMerger
import java.time.Instant
import java.time.LocalDate

/**
 * Durable, offline store for source observations.
 *
 * Every source observation is stored as its own row. No uniqueness constraint is
 * applied to market/session/date because competing and duplicate source records
 * are evidence that must remain available for reconciliation.
 */
class LocalObservationDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_OBSERVATIONS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                market TEXT NOT NULL,
                session_id TEXT NOT NULL,
                chart_date TEXT NOT NULL,
                source_id TEXT NOT NULL,
                source_record_id TEXT,
                source_url TEXT,
                retrieved_at TEXT NOT NULL,
                published_at TEXT,
                raw_text TEXT NOT NULL,
                normalized_result TEXT,
                status TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_observations_market_session_date " +
                "ON $TABLE_OBSERVATIONS(market, session_id, chart_date)"
        )
        createPredictionTables(db)
        db.execSQL(
            """
            CREATE TABLE $TABLE_METADATA (
                metadata_key TEXT PRIMARY KEY NOT NULL,
                metadata_value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema changes must be additive. Never silently discard a user's history.
        if (oldVersion < 2) {
            createPredictionTables(db)
        }
    }

    private fun createPredictionTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PREDICTION_SNAPSHOTS (
                snapshot_key TEXT PRIMARY KEY NOT NULL,
                market TEXT NOT NULL,
                session_id TEXT NOT NULL,
                target_date TEXT NOT NULL,
                created_at TEXT NOT NULL,
                history_through_date TEXT NOT NULL,
                model_version TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_sha256 TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PREDICTION_OUTCOMES (
                snapshot_key TEXT PRIMARY KEY NOT NULL,
                actual_result TEXT NOT NULL,
                published_at TEXT NOT NULL,
                source_status TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    /** First-write-wins: later recomputations can never rewrite a frozen prediction. */
    fun savePredictionSnapshot(snapshot: PredictionSnapshot): Boolean {
        val values = ContentValues().apply {
            put("snapshot_key", snapshot.snapshotKey)
            put("market", snapshot.market.name)
            put("session_id", snapshot.sessionId)
            put("target_date", snapshot.targetDate.toString())
            put("created_at", snapshot.createdAt.toString())
            put("history_through_date", snapshot.historyThroughDate.toString())
            put("model_version", snapshot.modelVersion)
            put("payload_json", snapshot.payloadJson)
            put("payload_sha256", snapshot.payloadSha256)
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_PREDICTION_SNAPSHOTS, null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    /** An outcome is stored separately and only for an already-frozen prediction. */
    fun savePredictionOutcome(outcome: PredictionSnapshotOutcome): Boolean {
        val database = writableDatabase
        database.query(
            TABLE_PREDICTION_SNAPSHOTS, arrayOf("snapshot_key"),
            "snapshot_key = ?", arrayOf(outcome.snapshotKey), null, null, null
        ).use { if (!it.moveToFirst()) return false }
        val values = ContentValues().apply {
            put("snapshot_key", outcome.snapshotKey)
            put("actual_result", outcome.actualResult)
            put("published_at", outcome.publishedAt.toString())
            put("source_status", outcome.sourceStatus)
        }
        return database.insertWithOnConflict(
            TABLE_PREDICTION_OUTCOMES, null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun loadPredictionSnapshotCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_PREDICTION_SNAPSHOTS", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun replaceDataset(csv: String, observations: List<SourceObservation>) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete(TABLE_OBSERVATIONS, null, null)
            observations.forEach { observation ->
                val values = ContentValues().apply {
                    put("market", observation.market.name)
                    put("session_id", observation.sessionId)
                    put("chart_date", observation.date.toString())
                    put("source_id", observation.sourceId)
                    put("source_record_id", observation.sourceRecordId)
                    put("source_url", observation.sourceUrl)
                    put("retrieved_at", observation.retrievedAt.toString())
                    put("published_at", observation.publishedAt?.toString())
                    put("raw_text", observation.rawText)
                    put("normalized_result", observation.normalizedResult)
                    put("status", observation.status.name)
                }
                database.insertOrThrow(TABLE_OBSERVATIONS, null, values)
            }
            putMetadata(database, KEY_SOURCE_CSV, csv)
            putMetadata(database, KEY_IMPORTED_AT, Instant.now().toString())
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Appends another CSV import. Existing exact observations are not duplicated,
     * while distinct source values and separate retrieval snapshots are retained.
     */
    fun appendDataset(csv: String, incoming: List<SourceObservation>): List<SourceObservation> {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val existing = readObservations(database)
            val merged = SourceObservationMerger.append(existing, incoming)
            merged.observations.drop(existing.size).forEach { observation ->
                val values = ContentValues().apply {
                    put("market", observation.market.name)
                    put("session_id", observation.sessionId)
                    put("chart_date", observation.date.toString())
                    put("source_id", observation.sourceId)
                    put("source_record_id", observation.sourceRecordId)
                    put("source_url", observation.sourceUrl)
                    put("retrieved_at", observation.retrievedAt.toString())
                    put("published_at", observation.publishedAt?.toString())
                    put("raw_text", observation.rawText)
                    put("normalized_result", observation.normalizedResult)
                    put("status", observation.status.name)
                }
                database.insertOrThrow(TABLE_OBSERVATIONS, null, values)
            }
            putMetadata(database, KEY_SOURCE_CSV, csv)
            putMetadata(database, KEY_IMPORTED_AT, Instant.now().toString())
            database.setTransactionSuccessful()
            return merged.observations
        } finally {
            database.endTransaction()
        }
    }

    fun loadObservations(): List<SourceObservation> = readObservations(readableDatabase)

    private fun readObservations(database: SQLiteDatabase): List<SourceObservation> {
        val result = mutableListOf<SourceObservation>()
        database.query(
            TABLE_OBSERVATIONS,
            arrayOf(
                "market", "session_id", "chart_date", "source_id", "source_record_id",
                "source_url", "retrieved_at", "published_at", "raw_text",
                "normalized_result", "status"
            ),
            null, null, null, null,
            "chart_date ASC, id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SourceObservation(
                    market = Market.valueOf(cursor.getString(0)),
                    sessionId = cursor.getString(1),
                    date = LocalDate.parse(cursor.getString(2)),
                    sourceId = cursor.getString(3),
                    sourceRecordId = cursor.getString(4),
                    sourceUrl = cursor.getString(5),
                    retrievedAt = Instant.parse(cursor.getString(6)),
                    publishedAt = cursor.getString(7)?.let(Instant::parse),
                    rawText = cursor.getString(8),
                    normalizedResult = cursor.getString(9),
                    status = ObservationStatus.valueOf(cursor.getString(10))
                )
            }
        }
        return result
    }

    fun loadOriginalCsv(): String? = readMetadata(KEY_SOURCE_CSV)

    fun clearDataset() {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete(TABLE_OBSERVATIONS, null, null)
            database.delete(TABLE_METADATA, null, null)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun readMetadata(key: String): String? {
        readableDatabase.query(
            TABLE_METADATA,
            arrayOf("metadata_value"),
            "metadata_key = ?",
            arrayOf(key),
            null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun putMetadata(database: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues().apply {
            put("metadata_key", key)
            put("metadata_value", value)
        }
        database.insertWithOnConflict(TABLE_METADATA, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        private const val DATABASE_NAME = "relab_local_history.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_OBSERVATIONS = "source_observations"
        private const val TABLE_METADATA = "app_metadata"
        private const val TABLE_PREDICTION_SNAPSHOTS = "prediction_snapshots"
        private const val TABLE_PREDICTION_OUTCOMES = "prediction_outcomes"
        private const val KEY_SOURCE_CSV = "source_csv"
        private const val KEY_IMPORTED_AT = "imported_at"
    }
}
