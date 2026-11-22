package com.relab.app

import org.junit.Assert.*
import org.junit.Test

class AiResearchLoopEngineTest {
    @Test fun runsResearcherAuditorAndImprovementAcrossBoundedRounds() {
        val calls = mutableListOf<Pair<Int, String>>()
        val checkpoints = mutableListOf<String>()
        val loop = AiResearchLoopEngine { _, _, question, evidence, memory, checkpoint, onCheckpoint ->
            val round = Regex("ROUND (\\d+) OF").find(question)?.groupValues?.get(1)?.toInt() ?: 1
            calls += round to memory
            assertEquals("immutable evidence", evidence)
            assertTrue(checkpoint.geminiAnalysis.isEmpty())
            onCheckpoint("GEMINI_ANALYSIS", "research-$round")
            onCheckpoint("GROQ_REVIEW", "audit-$round")
            onCheckpoint("FINAL_SYNTHESIS", "improved-$round")
            AiResearchResult("research-$round", "audit-$round", "improved-$round")
        }

        val result = loop.run(
            "gemini", "groq", "Find reproducible patterns", "immutable evidence", "",
            maxRounds = 3,
            onCheckpoint = { round, stage, _ -> checkpoints += "$round:$stage" }
        )

        assertEquals(listOf(1, 2, 3), result.rounds.map { it.roundNumber })
        assertEquals(3, calls.size)
        assertTrue(calls[1].second.contains("improved-1"))
        assertTrue(calls[2].second.contains("improved-2"))
        assertEquals(9, checkpoints.size)
        assertEquals("UNVERIFIED", result.verificationState)
        assertTrue(result.report().contains("No chart records were modified."))
    }

    @Test fun resumesCompletedRoundWithoutReRunningItsProviders() {
        val calledRounds = mutableListOf<Int>()
        val loop = AiResearchLoopEngine { _, _, question, _, _, checkpoint, onCheckpoint ->
            val round = Regex("ROUND (\\d+) OF").find(question)?.groupValues?.get(1)?.toInt() ?: 1
            calledRounds += round
            if (checkpoint.geminiAnalysis.isBlank()) onCheckpoint("GEMINI_ANALYSIS", "research-$round")
            if (checkpoint.groqReview.isBlank()) onCheckpoint("GROQ_REVIEW", "audit-$round")
            if (checkpoint.finalSynthesis.isBlank()) onCheckpoint("FINAL_SYNTHESIS", "improved-$round")
            AiResearchResult(
                checkpoint.geminiAnalysis.ifBlank { "research-$round" },
                checkpoint.groqReview.ifBlank { "audit-$round" },
                checkpoint.finalSynthesis.ifBlank { "improved-$round" }
            )
        }
        val savedRoundOne = AiResearchLoopRoundCheckpoint(
            1, AiResearchCheckpoint("research-1", "audit-1", "improved-1")
        )

        val result = loop.run(
            "gemini", "groq", "Question", "immutable evidence", "",
            maxRounds = 2,
            checkpoint = AiResearchLoopCheckpoint(listOf(savedRoundOne))
        )

        assertEquals(listOf(1, 2), calledRounds)
        assertEquals("improved-1", result.rounds[0].result.finalSynthesis)
        assertEquals("improved-2", result.rounds[1].result.finalSynthesis)
    }

    @Test fun rejectsUnboundedRoundCountsAndCheckpointRoundsOutsideLimit() {
        val loop = AiResearchLoopEngine { _, _, _, _, _, _, _ ->
            fail("Invalid loop settings must be rejected before any provider call")
            AiResearchResult("", "", "")
        }
        try {
            loop.run("gemini", "groq", "Question", "evidence", "", maxRounds = 4)
            fail("Expected max-round validation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("1 to 3 rounds"))
        }
        try {
            loop.run(
                "gemini", "groq", "Question", "evidence", "", maxRounds = 1,
                checkpoint = AiResearchLoopCheckpoint(listOf(AiResearchLoopRoundCheckpoint(2)))
            )
            fail("Expected checkpoint-range validation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("outside the requested loop limit"))
        }
    }

    @Test fun refusesEmptyEvidenceAndMissingCredentials() {
        val loop = AiResearchLoopEngine { _, _, _, _, _, _, _ ->
            fail("Invalid input must not call providers")
            AiResearchResult("", "", "")
        }
        try {
            loop.run("gemini", "groq", "Question", "", "")
            fail("Expected evidence validation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("evidence pack"))
        }
        try {
            loop.run("", "groq", "Question", "evidence", "")
            fail("Expected key validation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("both Gemini and Groq"))
        }
    }
}
