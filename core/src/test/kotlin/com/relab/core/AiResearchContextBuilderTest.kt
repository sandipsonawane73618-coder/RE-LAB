package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AiResearchContextBuilderTest {
    private fun observation(market: Market, day: Int, status: ObservationStatus = ObservationStatus.PUBLISHED) =
        SourceObservation(
            market = market, sessionId = "main",
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            sourceId = "publisher-${market.name}", retrievedAt = Instant.parse("2026-01-20T00:00:00Z"),
            rawText = if (status == ObservationStatus.PUBLISHED) "123-06-456" else "**",
            normalizedResult = if (status == ObservationStatus.PUBLISHED) "123-06-456" else null,
            status = status
        )

    private fun record(market: Market, day: Int) = ChartRecord(
        market = market, date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        openPanna = "123", jodi = "06", closePanna = "456",
        sourceId = "publisher-${market.name}", sessionId = "main"
    )

    @Test fun includesActualRowsForAllNineMarketsAndSeparatesRawFromDerivedEvidence() {
        val observations = ReverseEngineeringModel.enabledMarkets.map { observation(it, 1) }
        val records = ReverseEngineeringModel.enabledMarkets.map { record(it, 1) }
        val context = AiResearchContextBuilder.build(observations, records, LocalDate.of(2026, 2, 1))
        ReverseEngineeringModel.enabledMarkets.forEach { assertTrue("Missing ${it.name}", context.contains("=== MARKET: ${it.name} ===")) }
        assertTrue(context.contains("CHART RECORDS"))
        assertTrue(context.contains("RAW SOURCE OBSERVATIONS"))
        assertTrue(context.contains("123-06-456"))
        assertTrue(context.contains("not independent confirmations"))
        assertTrue(context.contains("EVIDENCE_REF=[SRIDEVI/main/2026-01-02/publisher-SRIDEVI]"))
    }

    @Test fun excludesTargetAndFutureRows() {
        val old = observation(Market.SRIDEVI, 1)
        val target = observation(Market.SRIDEVI, 59)
        val future = observation(Market.SRIDEVI, 60)
        val records = listOf(record(Market.SRIDEVI, 1), record(Market.SRIDEVI, 59), record(Market.SRIDEVI, 60))
        val context = AiResearchContextBuilder.build(
            listOf(old, target, future), records, LocalDate.of(2026, 3, 1)
        )
        assertTrue(context.contains("2026-01-02"))
        assertFalse(context.contains("2026-03-01 |"))
        assertFalse(context.contains("2026-03-02 |"))
    }

    @Test fun preservesNonPublishedMarkersAndCapsRowsPerMarket() {
        val rows = (0..9).map { observation(Market.KALYAN, it, if (it == 9) ObservationStatus.PENDING else ObservationStatus.PUBLISHED) }
        val context = AiResearchContextBuilder.build(
            rows, (0..9).map { record(Market.KALYAN, it) },
            LocalDate.of(2026, 2, 1), maxRecordsPerMarketSession = 2, maxObservationsPerMarketSession = 2
        )
        assertTrue(context.contains("PENDING"))
        assertTrue(context.contains("Source status counts"))
        assertTrue(context.length <= 14_000)
        assertFalse(context.contains("2026-01-01 |"))
    }


    @Test fun includesOnlyScannerRankingsForTheRequestedTargetDate() {
        val targetDate = LocalDate.of(2026, 2, 1)
        val candidate = ReverseEngineeringCandidate(
            panna = "123", familySequence = "012", pannaType = ChartMath.pannaType("123"), ank = 6,
            score = 91.0, exactPannaEvidence = 0.01, familyEvidence = 0.2, ankEvidence = 0.1,
            positionEvidence = 0.3, pannaTypeEvidence = 0.4, reversePannaEvidence = 0.05,
            weekdayEvidence = null, previousTransitionEvidence = 0.02,
            openCloseConditionalEvidence = null, crossMarketConditionalEvidence = 0.03
        )
        val ranking = ReverseEngineeringRanking(
            market = Market.SRIDEVI, sessionId = "main", side = ReverseEngineeringSide.OPEN,
            targetDate = targetDate, trainingRecords = 40, minimumHistoryRequired = 30,
            abstained = false, candidates = listOf(candidate),
            familyCandidates = listOf(ReverseEngineeringFamilyCandidate("012", 88.0, 4, null, 2, null, 1)),
            activeEvidenceLayers = listOf("Digit-family sequence", "Previous-state transition"),
            note = "Relative evidence index only."
        )
        val context = AiResearchContextBuilder.build(
            emptyList(), emptyList(), targetDate,
            scannerRankings = listOf(ranking, ranking.copy(targetDate = targetDate.plusDays(1)))
        )
        assertTrue(context.contains("SCANNER OPEN"))
        assertTrue(context.contains("SCANNER_REF=[SCANNER/SRIDEVI/main/OPEN/2026-02-01]"))
        assertTrue(context.contains("training rows=40"))
        assertTrue(context.contains("Digit-family sequence"))
        assertTrue(context.contains("123 | 012 | 91.0000"))
        assertTrue(context.contains("NOT calibrated probabilities"))
        assertFalse(context.contains("target=2026-02-02"))
    }

    @Test fun enforcesCharacterLimit() {
        val observations = ReverseEngineeringModel.enabledMarkets.flatMap { market ->
            (0..30).map { observation(market, it) }
        }
        val records = ReverseEngineeringModel.enabledMarkets.flatMap { market ->
            (0..30).map { record(market, it) }
        }
        val context = AiResearchContextBuilder.build(
            observations, records, LocalDate.of(2026, 2, 1), maxRecordsPerMarketSession = 30,
            maxObservationsPerMarketSession = 30, maxChars = 3000
        )
        assertTrue(context.length <= 3000)
        assertTrue(context.contains("Evidence pack reached configured character limit"))
        ReverseEngineeringModel.enabledMarkets.forEach { market ->
            assertTrue("Truncated evidence pack omitted ${market.name}", context.contains("=== MARKET: ${market.name} ==="))
        }
    }
}
