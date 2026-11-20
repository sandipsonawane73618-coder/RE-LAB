package com.relab.app
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.util.UUID

data class AiResearchEntry(val createdAt: String, val question: String, val geminiAnalysis: String, val groqReview: String, val finalSynthesis: String)
data class AiResearchSession(
    val id: String, val createdAt: String, val question: String, val evidence: String, val priorMemory: String,
    val geminiAnalysis: String, val groqReview: String, val finalSynthesis: String, val state: String, val lastError: String?
) {
    fun checkpoint() = AiResearchCheckpoint(geminiAnalysis, groqReview, finalSynthesis)
}
data class AiResearchLoopSession(
    val id: String, val createdAt: String, val question: String, val evidence: String, val priorMemory: String,
    val maxRounds: Int, val rounds: List<AiResearchLoopRoundCheckpoint>, val state: String, val lastError: String?
) {
    fun checkpoint() = AiResearchLoopCheckpoint(rounds)
}

/** Separate AI notebook and resumable journals; never edits the chart-observation ledger. */
class AiResearchMemoryStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "relab_ai_research_memory.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE ai_research_runs (
            id INTEGER PRIMARY KEY AUTOINCREMENT, created_at TEXT NOT NULL, question TEXT NOT NULL,
            gemini_analysis TEXT NOT NULL, groq_review TEXT NOT NULL, final_synthesis TEXT NOT NULL,
            verification_state TEXT NOT NULL DEFAULT 'UNVERIFIED')""".trimIndent())
        db.execSQL("CREATE INDEX idx_ai_research_created ON ai_research_runs(created_at DESC)")
        createSessionsTable(db)
        createLoopTables(db)
    }
    private fun createSessionsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS ai_research_sessions (
            id TEXT PRIMARY KEY NOT NULL, created_at TEXT NOT NULL, question TEXT NOT NULL, evidence TEXT NOT NULL,
            prior_memory TEXT NOT NULL, gemini_analysis TEXT NOT NULL DEFAULT '', groq_review TEXT NOT NULL DEFAULT '',
            final_synthesis TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT 'PENDING', last_error TEXT)""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_research_sessions ON ai_research_sessions(state, created_at DESC)")
    }
    private fun createLoopTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS ai_research_loops (
            id TEXT PRIMARY KEY NOT NULL, created_at TEXT NOT NULL, question TEXT NOT NULL, evidence TEXT NOT NULL,
            prior_memory TEXT NOT NULL, max_rounds INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'PENDING',
            verification_state TEXT NOT NULL DEFAULT 'UNVERIFIED', last_error TEXT)""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ai_research_loops_state ON ai_research_loops(state, created_at DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS ai_research_loop_rounds (
            loop_id TEXT NOT NULL, round_number INTEGER NOT NULL,
            gemini_analysis TEXT NOT NULL DEFAULT '', groq_review TEXT NOT NULL DEFAULT '',
            final_synthesis TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT 'PENDING',
            PRIMARY KEY(loop_id, round_number),
            FOREIGN KEY(loop_id) REFERENCES ai_research_loops(id) ON DELETE CASCADE)""".trimIndent())
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSessionsTable(db)
        if (oldVersion < 3) createLoopTables(db)
    }

    fun createSession(question: String, evidence: String, priorMemory: String): AiResearchSession {
        val session = AiResearchSession(UUID.randomUUID().toString(), Instant.now().toString(), question, evidence, priorMemory, "", "", "", "PENDING", null)
        val values = ContentValues().apply {
            put("id", session.id); put("created_at", session.createdAt); put("question", question)
            put("evidence", evidence); put("prior_memory", priorMemory); put("state", "PENDING")
        }
        writableDatabase.insertOrThrow("ai_research_sessions", null, values)
        return session
    }
    fun saveStage(id: String, stage: String, output: String) {
        val column = when (stage) {
            "GEMINI_ANALYSIS" -> "gemini_analysis"; "GROQ_REVIEW" -> "groq_review"; "FINAL_SYNTHESIS" -> "final_synthesis"
            else -> error("Unknown research checkpoint stage")
        }
        val values = ContentValues().apply { put(column, output); put("state", "PENDING"); putNull("last_error") }
        check(writableDatabase.update("ai_research_sessions", values, "id=?", arrayOf(id)) == 1) { "Research session not found" }
    }
    fun markInterrupted(id: String, error: String) {
        val values = ContentValues().apply { put("state", "INTERRUPTED"); put("last_error", error.take(1000)) }
        writableDatabase.update("ai_research_sessions", values, "id=?", arrayOf(id))
    }
    fun markCompleted(id: String, question: String, result: AiResearchResult) {
        val db = writableDatabase; db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("created_at", Instant.now().toString()); put("question", question)
                put("gemini_analysis", result.geminiAnalysis); put("groq_review", result.groqReview)
                put("final_synthesis", result.finalSynthesis); put("verification_state", "UNVERIFIED")
            }
            db.insertOrThrow("ai_research_runs", null, values)
            val state = ContentValues().apply { put("state", "COMPLETED"); putNull("last_error") }
            check(db.update("ai_research_sessions", state, "id=?", arrayOf(id)) == 1) { "Research session not found" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun latestResumable(): AiResearchSession? {
        readableDatabase.query("ai_research_sessions", arrayOf("id","created_at","question","evidence","prior_memory",
            "gemini_analysis","groq_review","final_synthesis","state","last_error"),
            "state IN ('PENDING','INTERRUPTED')", null, null, null, "created_at DESC", "1").use { c ->
            if (!c.moveToFirst()) return null
            return AiResearchSession(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),
                c.getString(5),c.getString(6),c.getString(7),c.getString(8),c.getString(9))
        }
    }

    fun createLoopSession(question: String, evidence: String, priorMemory: String, maxRounds: Int): AiResearchLoopSession {
        require(question.isNotBlank()) { "Research question is required" }
        require(evidence.isNotBlank()) { "Evidence pack is required" }
        require(maxRounds in 1..AiResearchLoopEngine.MAX_ALLOWED_ROUNDS) { "Invalid auto-loop round count" }
        val session = AiResearchLoopSession(UUID.randomUUID().toString(), Instant.now().toString(),
            question, evidence, priorMemory, maxRounds, emptyList(), "PENDING", null)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", session.id); put("created_at", session.createdAt); put("question", question)
                put("evidence", evidence); put("prior_memory", priorMemory); put("max_rounds", maxRounds)
                put("state", "PENDING"); put("verification_state", "UNVERIFIED")
            }
            db.insertOrThrow("ai_research_loops", null, values)
            for (round in 1..maxRounds) {
                val roundValues = ContentValues().apply { put("loop_id", session.id); put("round_number", round) }
                db.insertOrThrow("ai_research_loop_rounds", null, roundValues)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return session
    }

    fun saveLoopStage(id: String, roundNumber: Int, stage: String, output: String) {
        require(roundNumber in 1..AiResearchLoopEngine.MAX_ALLOWED_ROUNDS)
        val column = when (stage) {
            "GEMINI_ANALYSIS" -> "gemini_analysis"; "GROQ_REVIEW" -> "groq_review"; "FINAL_SYNTHESIS" -> "final_synthesis"
            else -> error("Unknown auto-loop checkpoint stage")
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put(column, output); put("state", "PENDING") }
            check(db.update("ai_research_loop_rounds", values, "loop_id=? AND round_number=?",
                arrayOf(id, roundNumber.toString())) == 1) { "Auto-loop round not found" }
            val session = ContentValues().apply { put("state", "PENDING"); putNull("last_error") }
            check(db.update("ai_research_loops", session, "id=?", arrayOf(id)) == 1) { "Auto-loop session not found" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun markLoopInterrupted(id: String, error: String) {
        val values = ContentValues().apply { put("state", "INTERRUPTED"); put("last_error", error.take(1000)) }
        check(writableDatabase.update("ai_research_loops", values, "id=?", arrayOf(id)) == 1) { "Auto-loop session not found" }
    }

    fun markLoopCompleted(id: String, result: AiResearchLoopResult) {
        require(result.rounds.isNotEmpty()) { "Cannot complete an empty research loop" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val loop = db.query("ai_research_loops", arrayOf("question"), "id=?", arrayOf(id), null, null, null).use { c ->
                check(c.moveToFirst()) { "Auto-loop session not found" }; c.getString(0)
            }
            val gemini = result.rounds.joinToString("\n\n") { "ROUND ${it.roundNumber}\n${it.result.geminiAnalysis}" }
            val groq = result.rounds.joinToString("\n\n") { "ROUND ${it.roundNumber}\n${it.result.groqReview}" }
            val values = ContentValues().apply {
                put("created_at", Instant.now().toString()); put("question", loop)
                put("gemini_analysis", gemini); put("groq_review", groq)
                put("final_synthesis", result.report()); put("verification_state", "UNVERIFIED")
            }
            db.insertOrThrow("ai_research_runs", null, values)
            val state = ContentValues().apply { put("state", "COMPLETED"); put("verification_state", "UNVERIFIED"); putNull("last_error") }
            check(db.update("ai_research_loops", state, "id=?", arrayOf(id)) == 1)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun latestResumableLoop(): AiResearchLoopSession? {
        val db = readableDatabase
        val session = db.query("ai_research_loops",
            arrayOf("id","created_at","question","evidence","prior_memory","max_rounds","state","last_error"),
            "state IN ('PENDING','INTERRUPTED')", null, null, null, "created_at DESC", "1").use { c ->
            if (!c.moveToFirst()) return null
            AiResearchLoopSession(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),
                c.getInt(5), emptyList(),c.getString(6),c.getString(7))
        }
        val rounds = mutableListOf<AiResearchLoopRoundCheckpoint>()
        db.query("ai_research_loop_rounds", arrayOf("round_number","gemini_analysis","groq_review","final_synthesis"),
            "loop_id=?", arrayOf(session.id), null, null, "round_number ASC").use { r ->
            while (r.moveToNext()) rounds += AiResearchLoopRoundCheckpoint(
                r.getInt(0), AiResearchCheckpoint(r.getString(1), r.getString(2), r.getString(3))
            )
        }
        return session.copy(rounds = rounds)
    }

    fun latest(limit: Int = 5): List<AiResearchEntry> {
        val rows = mutableListOf<AiResearchEntry>()
        readableDatabase.query("ai_research_runs", arrayOf("created_at", "question", "gemini_analysis", "groq_review", "final_synthesis"),
            null, null, null, null, "created_at DESC", limit.coerceIn(1, 20).toString()).use { cursor ->
            while (cursor.moveToNext()) rows += AiResearchEntry(cursor.getString(0), cursor.getString(1),
                cursor.getString(2), cursor.getString(3), cursor.getString(4))
        }
        return rows
    }
}
