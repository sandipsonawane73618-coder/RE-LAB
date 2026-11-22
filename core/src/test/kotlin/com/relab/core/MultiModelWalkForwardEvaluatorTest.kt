package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MultiModelWalkForwardEvaluatorTest {
    private fun row(day: Int, open: String): ChartRecord {
        val close = "444"
        val jodi = "${open.sumOf { it.digitToInt() } % 10}${close.sumOf { it.digitToInt() } % 10}"
        return ChartRecord(Market.SRIDEVI, LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            open, jodi, close, "source-$day")
    }

    @Test fun eachTargetUsesEarlierHistoryAndReportsCoverage() {
        val rows = (0 until 20).map { row(it, if (it % 2 == 0) "111" else "222") }
        val report = MultiModelWalkForwardEvaluator.evaluate(rows, Market.SRIDEVI, "main",
            PredictionChannel.OPEN_PANNA, LocalDate.of(2026, 1, 21), minimumHistory = 5)
        assertEquals(15, report.consensus.requestedTargets)
        assertEquals(3, report.models.size)
        assertEquals(15, report.models.first().evaluatedTargets)
        assertTrue(report.note.contains("only after scoring"))
    }

    @Test fun addingRowsAfterCutoffCannotChangeEarlierWalkForwardReport() {
        val rows = (0 until 15).map { row(it, if (it % 2 == 0) "111" else "222") }
        val cutoff = LocalDate.of(2026, 1, 16)
        val first = MultiModelWalkForwardEvaluator.evaluate(rows, Market.SRIDEVI, "main",
            PredictionChannel.OPEN_PANNA, cutoff, minimumHistory = 5)
        val extended = MultiModelWalkForwardEvaluator.evaluate(rows + row(20, "999") + row(21, "888"),
            Market.SRIDEVI, "main", PredictionChannel.OPEN_PANNA, cutoff, minimumHistory = 5)
        assertEquals(first, extended)
    }

    @Test fun boundedEvaluationKeepsEarlierRowsForTrainingButScoresOnlyRequestedWindow() {
        val rows = (0 until 20).map { row(it, if (it % 2 == 0) "111" else "222") }
        val report = MultiModelWalkForwardEvaluator.evaluate(
            rows, Market.SRIDEVI, "main", PredictionChannel.OPEN_PANNA,
            LocalDate.of(2026, 1, 21), minimumHistory = 5, targetStartInclusive = LocalDate.of(2026, 1, 16)
        )
        assertEquals(5, report.consensus.requestedTargets)
        assertEquals(5, report.consensus.evaluatedTargets)
        assertTrue(report.note.contains("only after scoring"))
    }

    @Test fun duplicateDatesAreRejected() {
        val first = row(0, "111")
        assertThrows(IllegalArgumentException::class.java) {
            MultiModelWalkForwardEvaluator.evaluate(listOf(first, first.copy(sourceId = "duplicate")),
                Market.SRIDEVI, "main", PredictionChannel.OPEN_PANNA,
                LocalDate.of(2026, 2, 1), minimumHistory = 1)
        }
    }
}
