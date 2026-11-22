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
        assertTrue(requests.first().contains(AiResearchEngine.PRIMARY_GEMINI_MODEL))
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

    @Test fun retriesFirstGeminiStageOnActiveFlashLiteAfterPrimary503() {
        val requests = mutableListOf<String>()
        val checkpoints = mutableListOf<String>()
        val engine = AiResearchEngine { endpoint, _, _ ->
            requests += endpoint
            when {
                endpoint.contains(AiResearchEngine.PRIMARY_GEMINI_MODEL) && requests.size == 1 ->
                    throw AiProviderHttpException(503, "AI provider HTTP 503: high demand")
                endpoint.contains(AiResearchEngine.FALLBACK_GEMINI_MODEL) ->
                    """{"candidates":[{"content":{"parts":[{"text":"Fallback draft [SRIDEVI/main/2026-01-02/publisher-a]."}]}}]}"""
                endpoint.contains("groq") ->
                    """{"choices":[{"message":{"content":"Independent review [SRIDEVI/main/2026-01-02/publisher-a]."}}]}"""
                else ->
                    """{"candidates":[{"content":{"parts":[{"text":"Final synthesis [SRIDEVI/main/2026-01-02/publisher-a]."}]}}]}"""
            }
        }

        val result = engine.runCrossReview(
            "gemini-test-key", "groq-test-key", "Check evidence", evidence, "",
            onCheckpoint = { stage, _ -> checkpoints += stage }
        )

        assertEquals(4, requests.size)
        assertTrue(requests[0].contains(AiResearchEngine.PRIMARY_GEMINI_MODEL))
        assertTrue(requests[1].contains(AiResearchEngine.FALLBACK_GEMINI_MODEL))
        assertTrue(requests[2].contains("groq"))
        assertTrue(requests[3].contains(AiResearchEngine.PRIMARY_GEMINI_MODEL))
        assertTrue(result.geminiAnalysis.contains("Fallback draft"))
        assertEquals(listOf("GEMINI_ANALYSIS", "GROQ_REVIEW", "FINAL_SYNTHESIS"), checkpoints)
    }

    @Test fun doesNotFallbackForPermanentGeminiClientError() {
        val requests = mutableListOf<String>()
        val checkpoints = mutableListOf<String>()
        val engine = AiResearchEngine { endpoint, _, _ ->
            requests += endpoint
            throw AiProviderHttpException(400, "AI provider HTTP 400: invalid request")
        }

        try {
            engine.runCrossReview(
                "gemini-test-key", "groq-test-key", "Check evidence", evidence, "",
                onCheckpoint = { stage, _ -> checkpoints += stage }
            )
            fail("Expected permanent Gemini request failure")
        } catch (expected: AiProviderHttpException) {
            assertEquals(400, expected.statusCode)
        }

        assertEquals(1, requests.size)
        assertTrue(requests.single().contains(AiResearchEngine.PRIMARY_GEMINI_MODEL))
        assertTrue("Failed first stage must not be checkpointed", checkpoints.isEmpty())
    }

    @Test fun fallbackFailureDoesNotCheckpointFirstStage() {
        val requests = mutableListOf<String>()
        val checkpoints = mutableListOf<String>()
        val engine = AiResearchEngine { endpoint, _, _ ->
            requests += endpoint
            if (endpoint.contains(AiResearchEngine.PRIMARY_GEMINI_MODEL)) {
                throw AiProviderHttpException(503, "AI provider HTTP 503: primary unavailable")
            }
            throw AiProviderHttpException(503, "AI provider HTTP 503: fallback unavailable")
        }

        try {
            engine.runCrossReview(
                "gemini-test-key", "groq-test-key", "Check evidence", evidence, "",
                onCheckpoint = { stage, _ -> checkpoints += stage }
            )
            fail("Expected both Gemini models to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains(AiResearchEngine.PRIMARY_GEMINI_MODEL))
            assertTrue(expected.message.orEmpty().contains(AiResearchEngine.FALLBACK_GEMINI_MODEL))
            assertTrue(expected.message.orEmpty().contains("HTTP 503"))
        }

        assertEquals(2, requests.size)
        assertTrue(checkpoints.isEmpty())
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
