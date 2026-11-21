package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CrossMarketLeadLagWalkForwardEvaluatorTest {
    private fun row(market: Market, day: Int, open: String, session: String = "main"): ChartRecord {
        val close = "277"
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market = market,
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            openPanna = open,
            jodi = "$openAnk$closeAnk",
            closePanna = close,
            sourceId = "$market-$day",
            sessionId = session
        )
    }

    @Test fun scoresOnlyAfterEarlierSignalHistoryAndReportsWilsonInterval() {
        val records = buildList {
            repeat(8) { index ->
                add(row(Market.SRIDEVI, index * 2, "111"))
                add(row(Market.KALYAN, index * 2 + 1, "222"))
            }
            add(row(Market.KALYAN, 30, "333")) // beyond the exclusive cutoff
        }
        val report = CrossMarketLeadLagWalkForwardEvaluator.evaluate(
            records, LocalDate.of(2026, 1, 20), maxSourceAgeDays = 3, minSignalOccurrences = 5
        ).single {
            it.sourceMarket == Market.SRIDEVI &&
                it.targetMarket == Market.KALYAN &&
                it.channel == CrossMarketEvidenceChannel.OPEN_PANNA_FAMILY
        }

        assertEquals(8, report.requestedTargets)
        assertEquals(3, report.evaluatedTargets)
        assertEquals(5, report.abstainedTargets)
        assertEquals(3, report.hits)
        assertEquals(3, report.baselineHits)
        assertEquals(0.0, report.lift!!, 0.0)
        assertNotNull(report.confidence95Lower)
        assertNotNull(report.confidence95Upper)
        assertTrue(report.confidence95Lower!! < report.hitRate!!)
        assertTrue(report.confidence95Upper!! >= report.hitRate!!)
        assertEquals(3, report.distinctSourceCount)
        assertEquals(3, report.distinctSourceObservationCount)
        assertEquals(CrossMarketEvidenceLevel.LOW_SAMPLE, report.evidenceLevel)
    }

    @Test fun sameDayFutureAndStaleSourcesCannotCreatePredictions() {
        val records = listOf(
            row(Market.SRIDEVI, 1, "111"),
            row(Market.KALYAN, 1, "222"), // same-day source is ineligible
            row(Market.KALYAN, 10, "222"),
            row(Market.SRIDEVI, 20, "333") // future relative to target rows
        )
        val reports = CrossMarketLeadLagWalkForwardEvaluator.evaluate(
            records, LocalDate.of(2026, 1, 15), maxSourceAgeDays = 3, minSignalOccurrences = 1
        ).filter { it.sourceMarket == Market.SRIDEVI && it.targetMarket == Market.KALYAN }
        assertTrue(reports.all { it.evaluatedTargets == 0 })
        assertTrue(reports.all { it.staleSourceTargets + it.noEarlierSourceTargets > 0 })
    }

    @Test fun duplicateMarketSessionDateRowsAreRejected() {
        val first = row(Market.SRIDEVI, 1, "111")
        try {
            CrossMarketLeadLagWalkForwardEvaluator.evaluate(
                listOf(first, first.copy(sourceId = "duplicate")),
                LocalDate.of(2026, 2, 1)
            )
            fail("Duplicate observations must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate rows"))
        }
    }
}
