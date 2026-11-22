package com.relab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MarketRegimeWalkForwardEvaluatorTest {
    private val start = LocalDate.of(2025, 1, 1)

    private fun record(day: Int, open: String, close: String): ChartRecord {
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market = Market.SRIDEVI,
            date = start.plusDays(day.toLong()),
            openPanna = open,
            jodi = "$openAnk$closeAnk",
            closePanna = close,
            sourceId = "source"
        )
    }

    private fun sample(): List<ChartRecord> =
        (0 until 15).map { day -> record(day, "123", "234") } +
            (15 until 25).map { day -> record(day, "999", "888") }

    @Test
    fun eachSnapshotUsesOnlyRowsStrictlyBeforeItsTargetDate() {
        val snapshots = MarketRegimeWalkForwardEvaluator.evaluate(
            sample(), start.plusDays(25), minimumRecords = 10
        )
        val target = snapshots.single { it.targetDate == start.plusDays(20) }
        assertEquals(20, target.historyRecords)
        assertEquals(20, target.report.validRecords)
        assertNotNull(target.report.distributionShift)
    }

    @Test
    fun addingFutureRowsCannotChangeAnEarlierSnapshot() {
        val rows = sample()
        val earlier = MarketRegimeWalkForwardEvaluator.evaluate(
            rows, start.plusDays(25), minimumRecords = 10
        ).single { it.targetDate == start.plusDays(20) }

        val futureRows = rows + (25 until 35).map { day ->
            record(day, if (day % 2 == 0) "111" else "222", if (day % 2 == 0) "333" else "444")
        }
        val withFuture = MarketRegimeWalkForwardEvaluator.evaluate(
            futureRows, start.plusDays(35), minimumRecords = 10
        ).single { it.targetDate == start.plusDays(20) }

        assertEquals(earlier.historyRecords, withFuture.historyRecords)
        assertEquals(earlier.report.regime, withFuture.report.regime)
        assertEquals(earlier.report.distributionShift, withFuture.report.distributionShift)
    }

    @Test
    fun insufficientHistoryIsExplicitAndDoesNotCreateShiftScore() {
        val snapshots = MarketRegimeWalkForwardEvaluator.evaluate(
            sample().take(6), start.plusDays(6), minimumRecords = 10
        )
        assertTrue(snapshots.isNotEmpty())
        assertTrue(snapshots.all { it.report.regime == MarketRegime.INSUFFICIENT_HISTORY })
        assertTrue(snapshots.all { it.report.distributionShift == null })
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateMarketSessionDateMustBeReconciled() {
        val row = record(0, "123", "234")
        MarketRegimeWalkForwardEvaluator.evaluate(
            listOf(row, row.copy(sourceId = "second")), start.plusDays(2)
        )
    }
}
