package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PatternAnalysisTest {
    private fun row(date: Int, open: String, close: String): ChartRecord {
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            Market.SRIDEVI, LocalDate.of(2026, 11, date),
            open, "$openAnk$closeAnk", close, "fixture"
        )
    }

    @Test fun calculatesFrequenciesAndSupport() {
        val result = PatternAnalysis.analyze(
            listOf(row(9, "277", "249"), row(10, "277", "368"), row(11, "578", "137")),
            minimumSupport = 2
        ).single()
        assertEquals(3, result.acceptedRecords)
        assertEquals("277", result.openExactPanna.first().pattern)
        assertEquals(2, result.openExactPanna.first().count)
        assertTrue(result.openExactPanna.first().meetsMinimumSupport)
        assertEquals(2, result.openFamilySequences.first().count)
    }

    @Test fun buildsDigitAndSequenceTransitions() {
        val result = PatternAnalysis.analyze(
            listOf(row(9, "277", "249"), row(10, "578", "368"))
        ).single()
        assertEquals(1, result.openPannaFamilyTransitions["222>023"])
        assertEquals(1, result.openFamilyDigitTransitions[2][0])
        assertEquals(1, result.openFamilyDigitTransitions[2][2])
    }

    @Test fun walkForwardUsesOnlyEarlierRows() {
        val result = PatternAnalysis.analyze(
            listOf(
                row(9, "277", "249"),
                row(10, "578", "368"),
                row(11, "277", "137")
            )
        ).single()
        assertEquals(2, result.walkForwardRows.size)
        assertEquals(LocalDate.of(2026, 11, 10), result.walkForwardRows[0].date)
        assertEquals("222", result.walkForwardRows[0].globalModePrediction)
        assertEquals("023", result.walkForwardRows[0].actualOpenFamilySequence)
    }

    @Test fun rejectsUnreconciledDuplicateDates() {
        val duplicate = row(9, "277", "249")
        try {
            PatternAnalysis.analyze(listOf(duplicate, duplicate))
            fail("Expected duplicate-date rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("reconcile"))
        }
    }

    @Test fun rejectsMathematicallyInvalidRowsButCountsThem() {
        val good = row(9, "277", "249")
        val bad = good.copy(jodi = "99")
        val result = PatternAnalysis.analyze(listOf(good, bad)).single()
        assertEquals(1, result.acceptedRecords)
        assertEquals(1, result.rejectedRecords)
    }
}
