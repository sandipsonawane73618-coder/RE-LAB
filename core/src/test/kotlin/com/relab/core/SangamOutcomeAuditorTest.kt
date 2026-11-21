package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class SangamOutcomeAuditorTest {
    private fun candidate(panna: String, score: Double) = ReverseEngineeringCandidate(
        panna, panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString(""),
        ChartMath.pannaType(panna), panna.sumOf { it.digitToInt() } % 10, score,
        0.01, 0.02, 0.03, 0.04, 0.05, 0.06,
        null, null, null, null
    )

    @Test
    fun auditsExactOpenClosePairSeparatelyFromJodiAndFamilyMatches() {
        val report = SangamOutcomeAuditor.analyze(
            actualOpenPanna = "123",
            actualClosePanna = "456",
            openCandidates = listOf(candidate("129", 90.0), candidate("123", 80.0)),
            closeCandidates = listOf(candidate("459", 95.0), candidate("456", 70.0)),
            pairLimit = 2
        )
        assertEquals("65", report.actualJodi)
        assertEquals(4, report.exactPairRank)
        assertFalse(report.exactPairHit)
        assertEquals(1, report.openFamilySequenceMatchCount)
        assertEquals(1, report.closeFamilySequenceMatchCount)
        assertTrue(report.jodiMatchCount >= 1)
        assertEquals(2, report.rankedPairs.size)
        assertTrue(report.diagnosticNote.contains("not exact Sangam hits"))
    }

    @Test
    fun exactPairRanksFirstWhenBothActualSidesLead() {
        val report = SangamOutcomeAuditor.analyze(
            "123", "456",
            listOf(candidate("123", 90.0), candidate("129", 80.0)),
            listOf(candidate("456", 95.0), candidate("459", 70.0)),
            pairLimit = 1
        )
        assertEquals(1, report.exactPairRank)
        assertTrue(report.exactPairHit)
        assertEquals(1, report.rankedPairs.size)
        assertEquals("123", report.rankedPairs.single().openPanna)
        assertEquals("456", report.rankedPairs.single().closePanna)
    }

    @Test
    fun rejectsMalformedPannaDuplicateCandidatesAndInvalidScores() {
        try {
            SangamOutcomeAuditor.analyze("12", "456", emptyList(), emptyList())
            fail("Malformed actual panna must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            SangamOutcomeAuditor.analyze(
                "123", "456",
                listOf(candidate("111", 1.0), candidate("111", 2.0)),
                emptyList()
            )
            fail("Duplicate candidates must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            SangamOutcomeAuditor.analyze("123", "456", listOf(candidate("111", Double.NaN)), emptyList())
            fail("Non-finite scores must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
