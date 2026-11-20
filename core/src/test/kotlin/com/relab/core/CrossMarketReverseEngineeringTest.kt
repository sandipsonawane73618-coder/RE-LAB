package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CrossMarketReverseEngineeringTest {
    private fun row(market: Market, open: String, close: String) : ChartRecord {
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market = market,
            date = LocalDate.of(2026, 11, 18),
            openPanna = open,
            jodi = "$openAnk$closeAnk",
            closePanna = close,
            sourceId = "fixture"
        )
    }

    @Test fun comparesOnlySameDateAndSessionAcrossTheFiveEnabledMarkets() {
        val reports = CrossMarketReverseEngineering.analyze(
            listOf(
                row(Market.SRIDEVI, "277", "249"),
                row(Market.KALYAN, "277", "249"),
                row(Market.MAIN_BAZAR, "578", "368"),
                row(Market.MILAN_DAY, "277", "249")
            )
        )

        assertEquals(6, reports.size)
        val srideviKalyan = reports.single {
            it.firstMarket == Market.SRIDEVI && it.secondMarket == Market.KALYAN
        }
        assertEquals(1, srideviKalyan.sharedDates)
        assertEquals(1, srideviKalyan.openFamilyMatches)
        assertEquals(1, srideviKalyan.closeFamilyMatches)
        assertEquals(1, srideviKalyan.jodiMatches)
        assertEquals("222", srideviKalyan.topOpenRelations.single().fromFamilySequence)
        assertEquals("222", srideviKalyan.topOpenRelations.single().toFamilySequence)
        assertTrue(reports.any {
            it.firstMarket == Market.MILAN_DAY || it.secondMarket == Market.MILAN_DAY
        })
    }

    @Test fun doesNotCompareDifferentDatesOrSessions() {
        val base = row(Market.SRIDEVI, "277", "249")
        val otherDate = row(Market.KALYAN, "277", "249").copy(
            date = LocalDate.of(2026, 11, 17)
        )
        val otherSession = row(Market.KALYAN, "277", "249").copy(sessionId = "night")

        assertTrue(CrossMarketReverseEngineering.analyze(listOf(base, otherDate, otherSession)).isEmpty())
    }
}
