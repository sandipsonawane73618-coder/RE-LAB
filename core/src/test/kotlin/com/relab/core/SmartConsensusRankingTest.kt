package com.relab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartConsensusRankingTest {
    @Test fun convergentTopCandidatesRankHighest() {
        val result = SmartConsensusRanking.rank(listOf(
            ConsensusModelRanking("exact", listOf("123", "456", "789")),
            ConsensusModelRanking("family", listOf("123", "789", "456")),
            ConsensusModelRanking("weekday", listOf("123", "456", "000"))
        ))
        assertFalse(result.abstained)
        assertEquals("123", result.candidates.first().candidate)
        assertEquals(3, result.candidates.first().participatingModels)
    }

    @Test fun absentCandidatesDoNotReceiveRanksFromModels() {
        val result = SmartConsensusRanking.rank(listOf(
            ConsensusModelRanking("a", listOf("12", "34")),
            ConsensusModelRanking("b", listOf("34", "56"))
        ))
        assertEquals(2, result.candidates.first { it.candidate == "34" }.participatingModels)
        assertEquals(1, result.candidates.first { it.candidate == "12" }.participatingModels)
    }

    @Test fun insufficientModelsAbstain() {
        val result = SmartConsensusRanking.rank(listOf(ConsensusModelRanking("only", listOf("123"))))
        assertTrue(result.abstained)
        assertTrue(result.candidates.isEmpty())
    }

    @Test fun rejectsDuplicateModelIdsAndCandidates() {
        assertFails {
            SmartConsensusRanking.rank(listOf(
                ConsensusModelRanking("same", listOf("1")),
                ConsensusModelRanking("same", listOf("2"))
            ))
        }
        assertFails {
            SmartConsensusRanking.rank(listOf(
                ConsensusModelRanking("a", listOf("1", "1")),
                ConsensusModelRanking("b", listOf("2"))
            ))
        }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
