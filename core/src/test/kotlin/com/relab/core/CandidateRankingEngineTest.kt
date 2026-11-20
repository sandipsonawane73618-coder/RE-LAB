package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CandidateRankingEngineTest {
    private fun observations(): List<SourceObservation> {
        val results = listOf(
            "277-65-249", "578-07-368", "800-81-137",
            "566-73-148", "288-82-679", "279-81-236"
        )
        return results.flatMapIndexed { index, result ->
            val date = LocalDate.of(2026, 11, 1).plusDays(index.toLong())
            listOf("publisher-a", "publisher-b").map { source ->
                SourceObservation(
                    market = Market.SRIDEVI,
                    sessionId = "main",
                    date = date,
                    sourceId = source,
                    retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
                    rawText = result,
                    normalizedResult = result,
                    status = ObservationStatus.PUBLISHED
                )
            }
        }
    }

    @Test fun ranksOnlyConsensusRowsStrictlyBeforeTargetDate() {
        val rows = observations()
        val ranked = CandidateRankingEngine.rank(
            rows, Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.OPEN, limit = 20, minimumHistory = 5
        )
        assertFalse(ranked.abstained)
        assertEquals(6, ranked.trainingRecords)
        assertEquals(20, ranked.candidates.size)
        assertTrue(ranked.candidates.all { it.panna.length == 3 && it.score in 0.0..100.0 })
    }

    @Test fun abstainsWhenVerifiedHistoryIsTooSmall() {
        val ranked = CandidateRankingEngine.rank(
            observations().take(4), Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.CLOSE, minimumHistory = 5
        )
        assertTrue(ranked.abstained)
        assertTrue(ranked.candidates.isEmpty())
        assertEquals(2, ranked.trainingRecords)
    }

    @Test fun excludesSingleSourceAndConflictingDates() {
        val base = observations()
        val singleSource = base.filterNot { it.date == LocalDate.of(2026, 11, 1) && it.sourceId == "publisher-b" }
        val conflict = singleSource + SourceObservation(
            market = Market.SRIDEVI, sessionId = "main",
            date = LocalDate.of(2026, 11, 2), sourceId = "publisher-c",
            retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
            rawText = "111-33-222", normalizedResult = "111-33-222",
            status = ObservationStatus.PUBLISHED
        )
        val ranked = CandidateRankingEngine.rank(
            conflict, Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.OPEN, minimumHistory = 1
        )
        assertEquals(4, ranked.trainingRecords)
    }

    @Test fun derivesJodiFromPannaAnkSums() {
        val open = listOf(PannaCandidate("277", "222", PannaType.DP, 80.0, 2, 3, 1, 1))
        val close = listOf(PannaCandidate("249", "244", PannaType.SP, 70.0, 1, 2, 1, 0))
        val jodi = CandidateRankingEngine.deriveJodiCandidates(open, close).single()
        assertEquals("65", jodi.jodi)
        assertEquals(1, jodi.supportingPannaPairs)
    }

    @Test fun exploratoryRankingCanUseSinglePublisherWithoutCallingItConsensus() {
        val singlePublisher = observations().filter { it.sourceId == "publisher-a" }
        val strict = CandidateRankingEngine.rank(
            singlePublisher, Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.OPEN, minimumHistory = 5
        )
        assertTrue(strict.abstained)
        val exploratory = CandidateRankingEngine.rank(
            singlePublisher, Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.OPEN,
            minimumHistory = 5, allowSingleSourceExploration = true
        )
        assertFalse(exploratory.abstained)
        assertEquals(6, exploratory.trainingRecords)
        assertEquals(10, exploratory.candidates.size)
        assertTrue(ReconciledChartBuilder.build(singlePublisher).records.isEmpty())
        assertEquals(6, ReconciledChartBuilder.buildExploratoryRecords(singlePublisher).size)
    }

    @Test fun futureRecordsDoNotChangeEarlierRanking() {
        val base = observations()
        val target = LocalDate.of(2026, 11, 7)
        val first = CandidateRankingEngine.rank(base, Market.SRIDEVI, "main", target, PannaSide.OPEN)
        val future = base + listOf("999-87-888", "999-87-888").mapIndexed { index, result ->
            SourceObservation(
                market = Market.SRIDEVI, sessionId = "main",
                date = LocalDate.of(2026, 11, 10), sourceId = "future-$index",
                retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
                rawText = result, normalizedResult = result,
                status = ObservationStatus.PUBLISHED
            )
        }
        val second = CandidateRankingEngine.rank(future, Market.SRIDEVI, "main", target, PannaSide.OPEN)
        assertEquals(first.candidates, second.candidates)
    }
    @Test fun canonicalRankingUsesOnePublisherWithoutCallingItConsensus() {
        val singlePublisher = observations().filter { it.sourceId == "publisher-a" }
        val ranked = CandidateRankingEngine.rankCanonical(
            singlePublisher, Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.OPEN, minimumHistory = 5
        )
        assertFalse(ranked.abstained)
        assertEquals(6, ranked.trainingRecords)
        assertTrue(ranked.note.contains("publisher-a"))
        assertTrue(ranked.note.contains("unverified"))
    }

    @Test fun exactHistoricalFrequencyRemainsPrimaryOverFamilySupport() {
        val pannas = listOf(
            "111", "111", "111",
            "277", "277", "277", "277", "277", "277",
            "222", "222"
        )
        val rows = pannas.flatMapIndexed { index, panna ->
            val date = LocalDate.of(2026, 10, 1).plusDays(index.toLong())
            val openAnk = panna.sumOf { it.digitToInt() } % 10
            listOf("publisher-a", "publisher-b").map { source ->
                SourceObservation(
                    market = Market.SRIDEVI,
                    sessionId = "main",
                    date = date,
                    sourceId = source,
                    retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
                    rawText = "$panna-${openAnk}0-000",
                    normalizedResult = "$panna-${openAnk}0-000",
                    status = ObservationStatus.PUBLISHED
                )
            }
        }
        val ranked = CandidateRankingEngine.rank(
            rows, Market.SRIDEVI, "main",
            LocalDate.of(2026, 10, 20), PannaSide.OPEN,
            limit = 100, minimumHistory = 5
        )
        val position111 = ranked.candidates.indexOfFirst { it.panna == "111" }
        val position222 = ranked.candidates.indexOfFirst { it.panna == "222" }
        assertFalse(ranked.abstained)
        assertEquals(3, ranked.candidates.first { it.panna == "111" }.exactHistoricalCount)
        assertEquals(2, ranked.candidates.first { it.panna == "222" }.exactHistoricalCount)
        assertEquals(8, ranked.candidates.first { it.panna == "222" }.familyHistoricalCount)
        assertTrue("Exact frequency must outrank larger family support", position111 < position222)
    }

    @Test(expected = IllegalArgumentException::class)
    fun canonicalRankingRejectsMixedPublishers() {
        CandidateRankingEngine.rankCanonical(
            observations(), Market.SRIDEVI, "main",
            LocalDate.of(2026, 11, 8), PannaSide.OPEN, minimumHistory = 5
        )
    }
    @Test fun familyMovementSupportIsVisibleButDoesNotChangeExactFrequencyScore() {
        val pannas = listOf("277", "277", "277", "111", "111", "222")
        val rows = pannas.flatMapIndexed { index, panna ->
            val date = LocalDate.of(2026, 10, 1).plusDays(index.toLong())
            val openAnk = panna.sumOf { it.digitToInt() } % 10
            listOf("publisher-a", "publisher-b").map { source ->
                SourceObservation(
                    market = Market.SRIDEVI, sessionId = "main", date = date,
                    sourceId = source, retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
                    rawText = "$panna-$openAnk" + "0-000",
                    normalizedResult = "$panna-$openAnk" + "0-000",
                    status = ObservationStatus.PUBLISHED
                )
            }
        }
        val ranked = CandidateRankingEngine.rank(
            rows, Market.SRIDEVI, "main", LocalDate.of(2026, 10, 20),
            PannaSide.OPEN, limit = 100, minimumHistory = 5
        )
        val original = ranked.candidates.single { it.panna == "277" }
        val movementEquivalent = ranked.candidates.single { it.panna == "227" }
        assertEquals(3, original.exactHistoricalCount)
        assertEquals(0, movementEquivalent.exactHistoricalCount)
        assertEquals(3, movementEquivalent.familyMovementHistoricalCount)
        assertEquals(FamilyMovementMath.key("277"), movementEquivalent.familyMovementClass)
        assertEquals(original.score, 100.0, 0.0)
        assertEquals(movementEquivalent.score, 0.0, 0.0)
    }

}
