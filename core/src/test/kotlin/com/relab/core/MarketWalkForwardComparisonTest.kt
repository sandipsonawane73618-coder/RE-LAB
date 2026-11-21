package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class MarketWalkForwardComparisonTest {
    private fun report(
        market: Market,
        session: String,
        evaluated: Int,
        top10: Int,
        abstained: Int = 0
    ) = SangamWalkForwardReport(
        market = market,
        sessionId = session,
        requestedTargets = evaluated + abstained,
        evaluatedTargets = evaluated,
        abstainedTargets = abstained,
        exactPairTop10Hits = top10,
        exactPairTop100Hits = top10,
        openFamilyTop10Hits = top10 / 2,
        closeFamilyTop10Hits = top10 / 2,
        jodiPairTop100Hits = top10
    )

    @Test
    fun aggregatesPerMarketWithoutLosingSessionSampleSizes() {
        val result = MarketWalkForwardComparisonBuilder.build(
            listOf(
                report(Market.KALYAN, "day", 20, 4, 2),
                report(Market.KALYAN, "night", 15, 3, 5),
                report(Market.SRIDEVI, "main", 120, 24)
            )
        )

        val kalyan = result.single { it.market == Market.KALYAN }
        assertEquals(2, kalyan.sessionCount)
        assertEquals(42, kalyan.requestedTargets)
        assertEquals(35, kalyan.evaluatedTargets)
        assertEquals(7, kalyan.abstainedTargets)
        assertEquals(7, kalyan.exactPairTop10Hits)
        assertEquals(WalkForwardSampleBand.EARLY_SAMPLE, kalyan.sampleBand)
        assertEquals(0.2, kalyan.rate(kalyan.exactPairTop10Hits)!!, 0.000001)

        val sridevi = result.single { it.market == Market.SRIDEVI }
        assertEquals(WalkForwardSampleBand.BROADER_SAMPLE, sridevi.sampleBand)
        assertEquals(0.2, sridevi.rate(sridevi.exactPairTop10Hits)!!, 0.000001)
    }

    @Test
    fun sampleBandsAreDescriptiveAndNoSampleHasNoRate() {
        val reports = listOf(
            report(Market.SRIDEVI, "a", 0, 0),
            report(Market.KALYAN, "a", 12, 2),
            report(Market.MILAN_DAY, "a", 30, 6),
            report(Market.MILAN_NIGHT, "a", 100, 20),
            report(Market.RAJDHANI_DAY, "a", 300, 60)
        )
        val result = MarketWalkForwardComparisonBuilder.build(reports).associateBy { it.market }

        assertEquals(WalkForwardSampleBand.NO_EVALUATED_TARGETS, result.getValue(Market.SRIDEVI).sampleBand)
        assertNull(result.getValue(Market.SRIDEVI).rate(0))
        assertEquals(WalkForwardSampleBand.VERY_SMALL, result.getValue(Market.KALYAN).sampleBand)
        assertEquals(WalkForwardSampleBand.EARLY_SAMPLE, result.getValue(Market.MILAN_DAY).sampleBand)
        assertEquals(WalkForwardSampleBand.BROADER_SAMPLE, result.getValue(Market.MILAN_NIGHT).sampleBand)
        assertEquals(WalkForwardSampleBand.LARGER_SAMPLE, result.getValue(Market.RAJDHANI_DAY).sampleBand)
    }

    @Test
    fun rejectsDuplicateMarketSessionReports() {
        try {
            MarketWalkForwardComparisonBuilder.build(
                listOf(report(Market.KALYAN, "main", 10, 2), report(Market.KALYAN, "main", 12, 3))
            )
            fail("Duplicate market/session rows must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate market/session"))
        }
    }
}
