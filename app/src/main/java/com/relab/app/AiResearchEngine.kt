package com.relab.app
import com.relab.core.AiEvidenceCitationAuditor
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AiResearchResult(val geminiAnalysis: String, val groqReview: String, val finalSynthesis: String)
data class AiResearchCheckpoint(val geminiAnalysis: String = "", val groqReview: String = "", val finalSynthesis: String = "")

/** Gemini proposes; Groq independently challenges; Gemini reconciles. Each finished stage can be persisted. */
class AiResearchEngine {
    fun runCrossReview(
        geminiKey: String, groqKey: String, question: String, context: String, priorMemory: String,
        checkpoint: AiResearchCheckpoint = AiResearchCheckpoint(),
        onCheckpoint: (stage: String, output: String) -> Unit = { _, _ -> }
    ): AiResearchResult {
        require(geminiKey.isNotBlank() && groqKey.isNotBlank()) { "Save both Gemini and Groq API keys first" }
        require(question.isNotBlank()) { "Enter a research question" }
        val evidence = context.take(20_000); val memory = priorMemory.take(5_000); val q = question.take(3_000)
        val evidenceRules = """
            EVIDENCE TRACEABILITY RULES:
            - Cite factual chart-history claims with exact supplied rows as [market/session/date/source-id].
            - Cite aggregate scanner metrics as [SCANNER market/session/side/target-date], and label them aggregate evidence.
            - Never invent citations, sources, chart values or sample counts. Unsupported claims must be labelled UNSUPPORTED.
            - Distinguish PUBLISHED, PENDING, CONFLICTING and exploratory/derived records.
            - A source ID is provenance, not proof of publisher independence.
            - Scanner scores are relative indices, never probabilities.
            - Report missing markets/sessions, abstentions, sample sizes and evidence-pack truncation.
            - Separate OBSERVED, HYPOTHESIS, UNSUPPORTED and NEEDS-TEST.
        """.trimIndent()
        val firstPrompt = """
            Act as RE-LAB's quantitative logic researcher. Analyse only supplied evidence.
            Separate observed facts, hypotheses, alternatives, data-quality risks and tests needed.
            Recommend chronological walk-forward validation and baselines. No certainty or guaranteed predictions.
            $evidenceRules
            QUESTION: $q
            EVIDENCE: $evidence
            PRIOR AI NOTES (UNVERIFIED; never treat as facts): $memory
        """.trimIndent()
        val first = checkpoint.geminiAnalysis.ifBlank { callGemini(geminiKey, firstPrompt).also { onCheckpoint("GEMINI_ANALYSIS", it) } }
        val critiquePrompt = """
            Act as an independent reverse-engineering and statistical reviewer. Analyse original evidence independently,
            then challenge Gemini. Find unsupported claims, confounders, leakage, duplicated or derived signals,
            small-sample problems and alternative explanations. Do not invent data.
            $evidenceRules
            For every Gemini claim, mark SUPPORTED with an exact evidence citation, PARTIALLY SUPPORTED, or UNSUPPORTED.
            Explicitly identify disagreements and alternative explanations.
            QUESTION: $q
            ORIGINAL EVIDENCE: $evidence
            GEMINI ANALYSIS TO AUDIT:
        """.trimIndent() + first.take(12_000)
        val critique = checkpoint.groqReview.ifBlank { callGroq(groqKey, critiquePrompt).also { onCheckpoint("GROQ_REVIEW", it) } }
        val synthesisPrompt = """
            Reconcile both independent reviews. Preserve unresolved disagreements. Label items OBSERVED,
            HYPOTHESIS, UNSUPPORTED or NEEDS-TEST. Give a ranked experiment plan with chronological validation and baselines.
            Never convert speculation into fact or claim model-weight learning or certain predictions.
            Include a claim-audit table: claim | Gemini view | Groq view | evidence citation | status.
            Preserve unresolved disagreements and uncertainty.
            Finish with the next 3 reproducible tests and the minimum data each test requires.
            $evidenceRules
            QUESTION: $q
            EVIDENCE: $evidence
            GEMINI:
        """.trimIndent() + first.take(12_000) + "\nGROQ REVIEW:\n" + critique.take(12_000)
        val synthesis = checkpoint.finalSynthesis.ifBlank { callGemini(geminiKey, synthesisPrompt).also { onCheckpoint("FINAL_SYNTHESIS", it) } }
        val citationAudit = AiEvidenceCitationAuditor.audit(evidence, synthesis)
        val auditSummary = buildString {
            append("DETERMINISTIC CITATION AUDIT (traceability only; verified references do not prove a claim is true):\n")
            append("Supplied evidence references: ${citationAudit.suppliedReferences}\n")
            append("Citations checked: ${citationAudit.citedReferences}\n")
            append("Verified citations: ${citationAudit.verifiedReferences.size}\n")
            append("Unsupported citations: ${citationAudit.unsupportedReferences.size}")
            if (citationAudit.unsupportedReferences.isNotEmpty()) {
                append("\nUnsupported reference tokens: ")
                append(citationAudit.unsupportedReferences.joinToString(", "))
            }
            if (citationAudit.citedReferences == 0) append("\nNo machine-checkable chart/scanner citations were found.")
        }
        return AiResearchResult(first, critique, synthesis + "\n\n" + auditSummary)
    }
    private fun callGemini(key: String, prompt: String): String {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 1400))
        val json = JSONObject(post("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
            mapOf("x-goog-api-key" to key), body.toString()))
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: error("Gemini returned no text")
        return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
            .filter { it.isNotBlank() }.joinToString("\n").ifBlank { error("Gemini returned an empty response") }
    }
    private fun callGroq(key: String, prompt: String): String {
        val body = JSONObject().put("model", GROQ_MODEL).put("temperature", 0.2).put("max_tokens", 1400)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", "You are a rigorous independent evidence reviewer."))
                .put(JSONObject().put("role", "user").put("content", prompt)))
        val json = JSONObject(post("https://api.groq.com/openai/v1/chat/completions",
            mapOf("Authorization" to "Bearer " + key), body.toString()))
        return json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() } ?: error("Groq returned an empty response")
    }
    private fun post(endpoint: String, headers: Map<String, String>, body: String): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 60_000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8"); setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty().take(200_000)
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: response.take(500)
                error("AI provider HTTP " + status + ": " + detail)
            }
            return response
        } finally { conn.disconnect() }
    }
    companion object { const val GROQ_MODEL = "openai/gpt-oss-120b" }
}
