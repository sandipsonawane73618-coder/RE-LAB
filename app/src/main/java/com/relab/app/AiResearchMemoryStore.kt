package com.relab.app
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

data class AiResearchEntry(val createdAt: String, val question: String, val geminiAnalysis: String, val groqReview: String, val finalSynthesis: String)

/** Separate AI notebook; it never edits the immutable chart-observation ledger. */
class AiResearchMemoryStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "relab_ai_research_memory.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE ai_research_runs (
            id INTEGER PRIMARY KEY AUTOINCREMENT, created_at TEXT NOT NULL, question TEXT NOT NULL,
            gemini_analysis TEXT NOT NULL, groq_review TEXT NOT NULL, final_synthesis TEXT NOT NULL,
            verification_state TEXT NOT NULL DEFAULT 'UNVERIFIED')""".trimIndent())
        db.execSQL("CREATE INDEX idx_ai_research_created ON ai_research_runs(created_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* additive only */ }
    fun save(question: String, result: AiResearchResult) {
        val values = ContentValues().apply {
            put("created_at", Instant.now().toString()); put("question", question)
            put("gemini_analysis", result.geminiAnalysis); put("groq_review", result.groqReview)
            put("final_synthesis", result.finalSynthesis); put("verification_state", "UNVERIFIED")
        }
        writableDatabase.insertOrThrow("ai_research_runs", null, values)
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
