package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CrossMarketLeadLagEvidenceEngineTest {
    private fun row(market: Market, day: Int, open: String): ChartRecord {
        val close = "277"
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market = market,
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            openPanna = open,
            jodi = "$openAnk$closeAnk",
            closePanna = close,
            sourceId = "$market-$day"
        )
    }

    @Test fun onlyEarlierSourceRowsCanExplainLaterTargetOutcomes() {
        val sourceSignals = listOf("111", "111", "222", "222", "222", "111")
        val targetOutcomes = listOf("222", "222", "111", "111", "111", "222")
        val records = buildList {
            sourceSignals.indices.forEach { i ->
                add(row(Market.SRIDEVI, i * 2, sourceSignals[i]))
                add(row(Market.KALYAN, i * 2 + 1, targetOutcomes[i]))
            }
        }
        val evidence = CrossMarketLeadLagEvidenceEngine.analyze(
            records, LocalDate.of(2026, 2, 1), maxSourceAgeDays = 3
        )
        val signal = evidence.single {
            it.sourceMarket == Market.SRIDEVI &&
                it.targetMarket == Market.KALYAN &&
                it.channel == CrossMarketEvidenceChannel.OPEN_PANNA_FAMILY &&
                it.sourceSignal == "111"
        }
        assertEquals(3, signal.occurrences)
        assertEquals(2, signal.evaluatedOccurrences)
        assertEquals(2, signal.hits)
        assertEquals(1, signal.baselineHits)
        assertEquals(0.5, signal.lift!!, 0.0)
        assertEquals(LocalDate.of(2026, 1, 12), signal.latestTargetDate)
        assertTrue(signal.latestSourceDate.isBefore(signal.latestTargetDate))
    }

    @Test fun sameDaySourceAndFutureRowsAreNeverUsed() {
        val records = listOf(
            row(Market.SRIDEVI, 1, "111"),
            row(Market.KALYAN, 1, "222"),
            row(Market.SRIDEVI, 2, "333"),
            row(Market.KALYAN, 3, "444"),
            row(Market.SRIDEVI, 10, "555"),
            row(Market.KALYAN, 10, "666")
        )
        val evidence = CrossMarketLeadLagEvidenceEngine.analyze(
            records, LocalDate.of(2026, 1, 5), maxSourceAgeDays = 3
        )
        assertTrue(evidence.all { it.latestSourceDate.isBefore(it.latestTargetDate) })
        assertTrue(evidence.none { it.latestTargetDate >= LocalDate.of(2026, 1, 5) })
    }

    @Test fun staleSourceRowsAreExcluded() {
        val records = listOf(
            row(Market.SRIDEVI, 1, "111"),
            row(Market.KALYAN, 20, "222")
        )
        val evidence = CrossMarketLeadLagEvidenceEngine.analyze(
            records, LocalDate.of(2026, 2, 1), maxSourceAgeDays = 7
        )
        assertTrue(evidence.isEmpty())
    }

    @Test fun duplicateSourceRowsAreRejected() {
        val first = row(Market.SRIDEVI, 1, "111")
        try {
            CrossMarketLeadLagEvidenceEngine.analyze(
                listOf(first, first.copy(sourceId = "duplicate")),
                LocalDate.of(2026, 2, 1)
            )
            fail("Duplicate market/session/date observations must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate rows"))
        }
    }
}
