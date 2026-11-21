package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class AnkFamilyOutcomeAuditorTest {
    @Test fun classifiesExactHitSeparatelyFromFamilyHit() {
        val exact = AnkFamilyOutcomeAuditor.analyze(1, 1, listOf(1, 6, 2))
        assertEquals(AnkOutcomeMatch.EXACT_HIT, exact.match)
        assertTrue(exact.exactHitWithinTopLimit)
        assertTrue(exact.familyHitWithinTopLimit)

        val family = AnkFamilyOutcomeAuditor.analyze(1, 6, listOf(1, 6, 2), topLimit = 1)
        assertEquals(AnkOutcomeMatch.FAMILY_HIT, family.match)
        assertFalse(family.exactHitWithinTopLimit)
        assertTrue(family.familyHitWithinTopLimit)
        assertEquals(2, family.exactRank)
    }

    @Test fun respectsLockedFamilyPairsForAllDigits() {
        val pairs = listOf(1 to 6, 2 to 7, 3 to 8, 4 to 9, 5 to 0)
        pairs.forEach { (left, right) ->
            val audit = AnkFamilyOutcomeAuditor.analyze(left, right, listOf(left, right))
            assertEquals("$left/$right", AnkOutcomeMatch.FAMILY_HIT, audit.match)
            assertEquals(ChartMath.family(left), ChartMath.family(right))
        }
    }

    @Test fun doesNotCallDifferentFamilyCandidateAFamilyHit() {
        val audit = AnkFamilyOutcomeAuditor.analyze(1, 2, listOf(1, 6, 3))
        assertEquals(AnkOutcomeMatch.MISS, audit.match)
        assertFalse(audit.familyHitWithinTopLimit)
    }

    @Test fun reportsFamilyRankIndependentlyOfExactRank() {
        val audit = AnkFamilyOutcomeAuditor.analyze(1, 6, listOf(2, 6, 1), topLimit = 2)
        assertEquals(2, audit.familyRank)
        assertEquals(2, audit.exactRank)
        assertTrue(audit.familyHitWithinTopLimit)
        assertTrue(audit.exactHitWithinTopLimit)
    }

    @Test fun rejectsInvalidDigitsAndLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            AnkFamilyOutcomeAuditor.analyze(10, 1, listOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnkFamilyOutcomeAuditor.analyze(1, 1, listOf(1), topLimit = 0)
        }
    }
}
