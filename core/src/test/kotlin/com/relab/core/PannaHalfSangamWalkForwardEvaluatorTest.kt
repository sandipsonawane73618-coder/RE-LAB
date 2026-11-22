package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PannaHalfSangamWalkForwardEvaluatorTest {
    private fun row(day: Int, open: String = "123", close: String = "456", market: Market = Market.SRIDEVI, session: String = "main") = ChartRecord(
        market = market,
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        openPanna = open,
        jodi = "%02d".format((open.sumOf { it.digitToInt() } % 10) * 10 + (close.sumOf { it.digitToInt() } % 10)),
        closePanna = close,
        sourceId = "source-$day",
        sessionId = session
    )

    @Test
    fun evaluatesPannaBothHalfSangamDirectionsAndSangamAfterMinimumHistory() {
        val records = (1..35).map(::row)
        val report = PannaHalfSangamWalkForwardEvaluator.evaluate(
            records, LocalDate.of(2026, 3, 1), minimumHistory = 30
        ).single()
        assertEquals(35, report.requestedTargets)
        assertEquals(5, report.openPanna.evaluatedTargets)
        assertEquals(30, report.openPanna.abstainedTargets)
        assertEquals(5, report.closePanna.evaluatedTargets)
        assertEquals(5, report.openPannaCloseAnk.evaluatedTargets)
        assertEquals(5, report.openAnkClosePanna.evaluatedTargets)
        assertEquals(5, report.sangam.evaluatedTargets)
        assertEquals(5, report.openPanna.exactTop10Hits)
        assertEquals(5, report.closePanna.exactTop100Hits)
        assertEquals(5, report.openPannaCloseAnk.exactTop10Hits)
        assertEquals(5, report.openAnkClosePanna.exactTop10Hits)
        assertEquals(5, report.sangam.exactTop10Hits)
        assertEquals(1.0, report.openPanna.exactRankDiagnostics.candidatePoolCoverage()!!, 0.0)
        assertEquals(1.0, report.openPanna.exactRankDiagnostics.meanReciprocalRank()!!, 0.0)
        assertEquals(5, report.openPanna.exactRankDiagnostics.rank1)
        assertEquals(0, report.openPanna.exactRankDiagnostics.absentFromCandidatePool)
    }

    @Test
    fun keepsMarketAndSessionResultsSeparate() {
        val records = (1..35).flatMap { day ->
            listOf(row(day, market = Market.SRIDEVI, session = "day"), row(day, market = Market.KALYAN, session = "night"))
        }
        val reports = PannaHalfSangamWalkForwardEvaluator.evaluate(
            records, LocalDate.of(2026, 3, 1), minimumHistory = 30
        )
        assertEquals(2, reports.size)
        assertEquals(setOf(Market.SRIDEVI to "day", Market.KALYAN to "night"), reports.map { it.market to it.sessionId }.toSet())
        assertTrue(reports.all { it.openPanna.evaluatedTargets == 5 })
    }

    @Test
    fun excludesTargetAndFutureRowsAndRejectsDuplicateDates() {
        val records = (1..35).map(::row) + row(40)
        val report = PannaHalfSangamWalkForwardEvaluator.evaluate(
            records, LocalDate.of(2026, 1, 1).plusDays(40), minimumHistory = 30
        ).single()
        assertEquals(35, report.requestedTargets)
        assertEquals(5, report.openPanna.evaluatedTargets)

        try {
            PannaHalfSangamWalkForwardEvaluator.evaluate(
                listOf(row(1), row(1).copy(sourceId = "duplicate")),
                LocalDate.of(2026, 3, 1), minimumHistory = 1
            )
            fail("Duplicate market/session dates must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate rows"))
        }
    }

    @Test
    fun abstainsForInsufficientHistoryWithoutManufacturingRates() {
        val report = PannaHalfSangamWalkForwardEvaluator.evaluate(
            listOf(row(1), row(2)), LocalDate.of(2026, 3, 1), minimumHistory = 30
        ).single()
        assertEquals(0, report.openPanna.evaluatedTargets)
        assertEquals(2, report.openPanna.abstainedTargets)
        assertNull(report.openPanna.exactTop10Rate())
        assertNull(report.openPannaCloseAnk.exactTop100Rate())
        assertNull(report.openPanna.exactRankDiagnostics.candidatePoolCoverage())
        assertNull(report.openPanna.exactRankDiagnostics.meanReciprocalRank())
    }
    @Test
    fun recordsExactPannaRanksBeyondTop100InsteadOfCallingThemCandidateMisses() {
        val history = (1..30).map { row(it, open = "123", close = "456") }
        val heldOut = row(31, open = "000", close = "999")
        val report = PannaHalfSangamWalkForwardEvaluator.evaluate(
            history + heldOut,
            heldOut.date.plusDays(1),
            minimumHistory = 30
        ).single()
        assertEquals(1, report.openPanna.evaluatedTargets)
        assertEquals(0, report.openPanna.exactRankDiagnostics.absentFromCandidatePool)
        assertEquals(1, report.openPanna.exactRankDiagnostics.rankAbove100)
        assertEquals(0, report.openPanna.exactTop100Hits)
    }


}
