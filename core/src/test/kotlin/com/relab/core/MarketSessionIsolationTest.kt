package com.relab.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSessionIsolationTest {
    private fun row(
        market: Market,
        session: String,
        day: Int,
        open: String
    ): ChartRecord {
        val close = "277"
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market = market,
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            openPanna = open,
            jodi = "$openAnk$closeAnk",
            closePanna = close,
            sourceId = "$market-$session-$day",
            sessionId = session
        )
    }

    private fun history(market: Market, session: String) = listOf(
        row(market, session, 0, "111"),
        row(market, session, 1, "222"),
        row(market, session, 2, "111"),
        row(market, session, 3, "222"),
        row(market, session, 4, "111"),
        row(market, session, 5, "222")
    )

    @Test
    fun patternEvidenceNeverPoolsIdenticalPatternsAcrossMarketsOrSessions() {
        val first = history(Market.KALYAN, "day")
        val second = history(Market.KALYAN, "night").map {
            it.copy(date = it.date.plusDays(10))
        }
        val third = history(Market.SRIDEVI, "day").map {
            it.copy(date = it.date.plusDays(20))
        }

        val combined = PatternEvidenceDatabase.analyze(
            first + second + third,
            LocalDate.of(2026, 3, 1),
            windowLengths = 2..2
        ).filter {
            it.channel == PatternEvidenceChannel.OPEN_PANNA && it.pattern == "111>222"
        }

        assertEquals(3, combined.size)
        assertEquals(
            setOf("KALYAN/day", "KALYAN/night", "SRIDEVI/day"),
            combined.map { "${it.market}/${it.sessionId}" }.toSet()
        )
        assertTrue(combined.all { it.occurrences == 2 })
        assertTrue(combined.all { it.evaluatedOccurrences == 1 })
    }

    @Test
    fun walkForwardAndDirectionalScannersKeepMarketSessionReportsSeparate() {
        val first = history(Market.KALYAN, "day")
        val second = history(Market.KALYAN, "night").map {
            it.copy(date = it.date.plusDays(10))
        }
        val third = history(Market.SRIDEVI, "day").map {
            it.copy(date = it.date.plusDays(20))
        }
        val rows = first + second + third

        val walkForward = SangamWalkForwardEvaluator.evaluate(
            rows,
            targetDateExclusive = LocalDate.of(2026, 3, 1),
            minimumHistory = 1
        )
        assertEquals(3, walkForward.size)
        assertEquals(
            setOf("KALYAN/day", "KALYAN/night", "SRIDEVI/day"),
            walkForward.map { "${it.market}/${it.sessionId}" }.toSet()
        )
        assertTrue(walkForward.all { it.requestedTargets == 6 })

        val directional = ChartPatternGridScanner.scan(
            rows,
            side = ChartPatternSide.OPEN_PANNA,
            minimumLength = 2
        )
        assertEquals(3, directional.size)
        assertEquals(
            setOf("KALYAN/day", "KALYAN/night", "SRIDEVI/day"),
            directional.map { "${it.market}/${it.sessionId}" }.toSet()
        )
        assertTrue(directional.all { it.acceptedRecords == 6 })
    }
}
