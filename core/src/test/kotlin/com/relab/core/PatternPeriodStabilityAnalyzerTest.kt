package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PatternPeriodStabilityAnalyzerTest {
    private fun row(day: Int, open: String): ChartRecord {
        val close = "277"
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market = Market.SRIDEVI,
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            openPanna = open,
            jodi = "$openAnk$closeAnk",
            closePanna = close,
            sourceId = "source-$day"
        )
    }

    @Test fun keepsPatternIdentityAndOutcomeModesSeparateAcrossPeriods() {
        val sequence = listOf("111", "222", "333")
        val rows = (0 until 27).map { row(it, sequence[it % sequence.size]) }
        val result = PatternPeriodStabilityAnalyzer.analyze(
            rows, LocalDate.of(2026, 2, 1), windowLengths = 2..2,
            minimumEvaluationsPerPeriod = 1
        )
        assertTrue(result.any {
            it.channel == PatternEvidenceChannel.OPEN_PANNA &&
                it.outcomeMode == PatternOutcomeMode.EXACT
        })
        assertTrue(result.any { it.outcomeMode == PatternOutcomeMode.FAMILY_MOVEMENT })
        assertTrue(result.all { it.periods.map(PatternPeriodMetric::period) == listOf(1, 2, 3) })
        result.forEach { item ->
            assertEquals(3, item.periods.size)
            assertTrue(item.periods.all {
                it.hits + it.misses == it.evaluated
                it.baselineHits in 0..it.evaluated
            })
        }
    }

    @Test fun insufficientPeriodSamplesNeverReceiveStableOrWeakLabels() {
        val rows = listOf(
            row(0, "111"), row(1, "222"), row(2, "333"),
            row(3, "111"), row(4, "222"), row(5, "333")
        )
        val result = PatternPeriodStabilityAnalyzer.analyze(
            rows, LocalDate.of(2026, 2, 1), windowLengths = 2..2,
            minimumEvaluationsPerPeriod = 10
        )
        assertTrue(result.all {
            it.assessment == PatternPeriodAssessment.INSUFFICIENT_SAMPLE
        })
    }

    @Test fun duplicateDatesAreRejectedInsteadOfSilentlyMerged() {
        val first = row(0, "111")
        try {
            PatternPeriodStabilityAnalyzer.analyze(
                listOf(first, first.copy(sourceId = "duplicate")),
                LocalDate.of(2026, 2, 1), windowLengths = 1..1
            )
            fail("duplicate dates must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate dates"))
        }
    }
}
