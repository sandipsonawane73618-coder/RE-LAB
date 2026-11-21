package com.relab.app

import org.junit.Assert.*
import org.junit.Test

class AiResearchEngineTest {
    private val evidence = "row EVIDENCE_REF=[SRIDEVI/main/2026-01-02/publisher-a]"

    @Test fun auditsCitationsInAllThreeStagesAndPersistsRawStageCheckpoints() {
        val requests = mutableListOf<String>()
        val requestBodies = mutableListOf<String>()
        val checkpoints = mutableListOf<String>()
        val engine = AiResearchEngine { endpoint, _, body ->
            requests += endpoint
            requestBodies += body
            when {
                endpoint.contains("generativelanguage") && requests.size == 1 ->
                    """{"candidates":[{"content":{"parts":[{"text":"Draft [SRIDEVI/main/2026-01-02/publisher-a]."}]}}]}"""
                endpoint.contains("groq") ->
                    """{"choices":[{"message":{"content":"Review [SRIDEVI/main/2026-01-02/publisher-a] and [FAKE/main/2026-01-03/injected]."}}]}"""
                endpoint.contains("generativelanguage") ->
                    """{"candidates":[{"content":{"parts":[{"text":"Synthesis [SRIDEVI/main/2026-01-02/publisher-a]."}]}}]}"""
                else -> error("Unexpected provider endpoint: $endpoint")
            }
        }

        val result = engine.runCrossReview(
            "gemini-test-key", "groq-test-key", "Check evidence", evidence, "",
            onCheckpoint = { stage, _ -> checkpoints += stage }
        )

        assertEquals(3, requests.size)
        val geminiRequest = com.google.gson.JsonParser.parseString(requestBodies.first()).asJsonObject
        val generationConfig = geminiRequest.getAsJsonObject("generationConfig")
        assertEquals(1400, generationConfig.get("maxOutputTokens").asInt)
        assertFalse("Gemini request must not send unsupported temperature", generationConfig.has("temperature"))
        assertEquals(listOf("GEMINI_ANALYSIS", "GROQ_REVIEW", "FINAL_SYNTHESIS"), checkpoints)
        assertTrue(result.geminiAnalysis.contains("INITIAL GEMINI ANALYSIS"))
        assertTrue(result.geminiAnalysis.contains("Verified citations: 1"))
        assertTrue(result.groqReview.contains("GROQ INDEPENDENT REVIEW"))
        assertTrue(result.groqReview.contains("Unsupported citations: 1"))
        assertTrue(result.groqReview.contains("FAKE/main/2026-01-03/injected"))
        assertTrue(result.finalSynthesis.contains("FINAL GEMINI SYNTHESIS"))
        assertTrue(result.finalSynthesis.contains("Verified citations: 1"))
    }

    @Test fun completedCheckpointResumesWithoutCallingProvidersButStillAuditsAllStages() {
        var calls = 0
        val engine = AiResearchEngine { _, _, _ ->
            calls++
            error("Completed stages must not call providers")
        }

        val result = engine.runCrossReview(
            "gemini-test-key", "groq-test-key", "Resume", evidence, "",
            checkpoint = AiResearchCheckpoint(
                geminiAnalysis = "Draft [SRIDEVI/main/2026-01-02/publisher-a].",
                groqReview = "Review [UNKNOWN/main/2026-01-03/fake].",
                finalSynthesis = "Summary without machine-checkable citations."
            )
        )

        assertEquals(0, calls)
        assertTrue(result.geminiAnalysis.contains("Verified citations: 1"))
        assertTrue(result.groqReview.contains("Unsupported citations: 1"))
        assertTrue(result.finalSynthesis.contains("WARNING: No machine-checkable chart/scanner citations were found."))
    }

    @Test fun rejectsMissingProviderKeysBeforeAnyNetworkCall() {
        var calls = 0
        val engine = AiResearchEngine { _, _, _ -> calls++; error("Must not call provider") }
        try {
            engine.runCrossReview("", "groq-test-key", "Question", evidence, "")
            fail("Expected missing-key validation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("both Gemini and Groq"))
        }
        assertEquals(0, calls)
    }
}
