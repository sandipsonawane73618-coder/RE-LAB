package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class JodiFamilyOutcomeAuditorTest {
    private fun candidate(jodi: String, score: Double) =
        ReverseEngineeringJodiCandidate(jodi, score, 1)

    @Test fun recognizes69And14AsFamilyMatchWithoutCallingItExact() {
        val audit = JodiFamilyOutcomeAuditor.analyze(
            actualJodi = "14",
            rankedCandidates = listOf(candidate("79", 100.0), candidate("69", 90.0), candidate("59", 80.0))
        )
        assertEquals(JodiFamilyOutcomeMatch.FAMILY_HIT, audit.match)
        assertNull(audit.exactRank)
        assertEquals(2, audit.familyRank)
        assertFalse(audit.exactHitWithinTopLimit)
        assertTrue(audit.familyHitWithinTopLimit)
    }

    @Test fun exactMatchTakesPrecedenceOverFamilyMatch() {
        val audit = JodiFamilyOutcomeAuditor.analyze(
            actualJodi = "69",
            rankedCandidates = listOf(candidate("14", 100.0), candidate("69", 90.0))
        )
        assertEquals(JodiFamilyOutcomeMatch.EXACT_HIT, audit.match)
        assertEquals(2, audit.exactRank)
        assertEquals(1, audit.familyRank)
    }

    @Test fun rejectsInvalidAndDuplicateCandidates() {
        assertThrows(IllegalArgumentException::class.java) {
            JodiFamilyOutcomeAuditor.analyze("1", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            JodiFamilyOutcomeAuditor.analyze("14", listOf(candidate("69", 2.0), candidate("69", 1.0)))
        }
    }
}
