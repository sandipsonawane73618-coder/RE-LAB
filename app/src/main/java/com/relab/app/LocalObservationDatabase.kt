package com.relab.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.relab.core.Market
import com.relab.core.ObservationStatus
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
            // Reserved for the next additive schema migration.
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
        private const val DATABASE_VERSION = 1
        private const val TABLE_OBSERVATIONS = "source_observations"
        private const val TABLE_METADATA = "app_metadata"
        private const val KEY_SOURCE_CSV = "source_csv"
        private const val KEY_IMPORTED_AT = "imported_at"
    }
}
