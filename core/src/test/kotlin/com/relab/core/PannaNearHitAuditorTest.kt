package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class PannaNearHitAuditorTest {
    private fun candidate(panna: String, score: Double) = ReverseEngineeringCandidate(
        panna, panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString(""),
        ChartMath.pannaType(panna), panna.sumOf { it.digitToInt() } % 10, score,
        0.01, 0.02, 0.03, 0.04, 0.05, 0.06,
        null, null, null, null
    )

    @Test
    fun keepsExactHitSeparateFromFamilyAndNearMatches() {
        val report = PannaNearHitAuditor.analyze(
            "167",
            listOf(candidate("612", 99.0), candidate("167", 80.0), candidate("278", 70.0))
        )
        assertEquals(2, report.exactRank)
        assertTrue(report.hasExactHit)
        assertEquals(1, report.exactHitCount)
        assertEquals(2, report.familySequenceHitCount)
        assertFalse(report.nearestCandidates.first().exactHit)
        assertTrue(report.nearestCandidates.any { it.exactHit })
        assertEquals(3, report.nearestCandidates.first().matchingFamilyPositions)
        assertTrue(report.diagnosticNote.contains("not exact hits"))
    }

    @Test
    fun reportsReverseAndPositionSignalsWithoutCallingThemHits() {
        val report = PannaNearHitAuditor.analyze(
            "123",
            listOf(candidate("321", 90.0), candidate("129", 80.0))
        )
        val reverse = report.nearestCandidates.first { it.panna == "321" }
        assertTrue(reverse.reverseExactMatch)
        assertEquals(0, reverse.matchingDigitPositions)
        val positional = report.nearestCandidates.first { it.panna == "129" }
        assertEquals(2, positional.matchingDigitPositions)
        assertFalse(report.hasExactHit)
        assertEquals(0, report.exactHitCount)
    }

    @Test
    fun rejectsInvalidInputsAndDuplicateCandidates() {
        try {
            PannaNearHitAuditor.analyze("12", emptyList())
            fail("Malformed actual panna must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            PannaNearHitAuditor.analyze("123", listOf(candidate("111", 1.0), candidate("111", 2.0)))
            fail("Duplicate candidates must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
