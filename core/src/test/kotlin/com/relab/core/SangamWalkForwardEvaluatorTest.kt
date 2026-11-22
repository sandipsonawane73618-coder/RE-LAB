package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SangamWalkForwardEvaluatorTest {
    private fun row(day: Int, open: String = "123", close: String = "456") = ChartRecord(
        market = Market.SRIDEVI,
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        openPanna = open,
        jodi = "%02d".format((open.sumOf { it.digitToInt() } % 10) * 10 + (close.sumOf { it.digitToInt() } % 10)),
        closePanna = close,
        sourceId = "source-$day",
        sessionId = "main"
    )

    @Test
    fun evaluatesOnlyAfterMinimumPriorHistoryAndFindsRepeatedExactPair() {
        val records = (1..35).map(::row)
        val report = SangamWalkForwardEvaluator.evaluate(
            records, LocalDate.of(2026, 3, 1), minimumHistory = 30
        ).single()
        assertEquals(35, report.requestedTargets)
        assertEquals(5, report.evaluatedTargets)
        assertEquals(30, report.abstainedTargets)
        assertEquals(5, report.exactPairTop10Hits)
        assertEquals(5, report.exactPairTop100Hits)
        assertEquals(1.0, report.rate(report.exactPairTop10Hits)!!, 0.0)
    }

    @Test
    fun reportsThreeChronologicalPeriodsWithoutChangingOverallTotals() {
        val records = (1..36).map(::row)
        val report = SangamWalkForwardEvaluator.evaluate(
            records, LocalDate.of(2026, 3, 1), minimumHistory = 3
        ).single()

        assertEquals(3, report.chronologicalPeriods.size)
        assertEquals(listOf(12, 12, 12), report.chronologicalPeriods.map { it.requestedTargets })
        assertEquals(33, report.chronologicalPeriods.sumOf { it.evaluatedTargets })
        assertEquals(3, report.chronologicalPeriods.sumOf { it.abstainedTargets })
        assertEquals(report.evaluatedTargets, report.chronologicalPeriods.sumOf { it.evaluatedTargets })
        assertEquals(report.exactPairTop10Hits, report.chronologicalPeriods.sumOf { it.exactPairTop10Hits })
        assertEquals(1, report.chronologicalPeriods.first().period)
        assertEquals(3, report.chronologicalPeriods.last().period)
        assertTrue(report.chronologicalPeriods[0].lastTargetDate!!.isBefore(report.chronologicalPeriods[1].firstTargetDate))
        assertTrue(report.chronologicalPeriods[1].lastTargetDate!!.isBefore(report.chronologicalPeriods[2].firstTargetDate))
    }

    @Test
    fun excludesTargetAndFutureRowsFromEvaluationWindow() {
        val records = (1..35).map(::row) + row(40)
        val report = SangamWalkForwardEvaluator.evaluate(
            records, LocalDate.of(2026, 1, 1).plusDays(40), minimumHistory = 30
        ).single()
        assertEquals(35, report.requestedTargets)
        assertEquals(5, report.evaluatedTargets)
    }

    @Test
    fun rejectsDuplicateMarketSessionDates() {
        val first = row(1)
        try {
            SangamWalkForwardEvaluator.evaluate(
                listOf(first, first.copy(sourceId = "duplicate")),
                LocalDate.of(2026, 3, 1), minimumHistory = 1
            )
            fail("Duplicate dates must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate rows"))
        }
    }

    @Test
    fun abstainsWhenHistoryIsInsufficient() {
        val report = SangamWalkForwardEvaluator.evaluate(
            listOf(row(1), row(2)), LocalDate.of(2026, 3, 1), minimumHistory = 30
        ).single()
        assertEquals(0, report.evaluatedTargets)
        assertEquals(2, report.abstainedTargets)
        assertNull(report.rate(report.exactPairTop10Hits))
    }
}
