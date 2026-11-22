package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AdaptiveRankingEngineTest {
    private val target = LocalDate.of(2026, 11, 20)

    private fun evidence(
        market: Market = Market.SRIDEVI,
        session: String = "main",
        channel: PatternEvidenceChannel = PatternEvidenceChannel.OPEN_PANNA,
        pattern: String = "111>222",
        hits: Int = 100,
        baseline: Int = 0,
        evaluated: Int = 100,
        lastSeen: LocalDate = LocalDate.of(2026, 11, 10),
        mode: PatternOutcomeMode = PatternOutcomeMode.EXACT
    ) = PatternEvidenceRecord(
        market = market, sessionId = session, channel = channel,
        windowLength = 2, pattern = pattern,
        occurrences = evaluated + 1, evaluatedOccurrences = evaluated,
        hits = hits, misses = evaluated - hits,
        hitRate = hits.toDouble() / evaluated,
        outcomeCounts = emptyList(), lastSeenDate = lastSeen,
        ageInChartRows = 1, ageInDays = 1, distinctSourceCount = 2,
        recentOutcomeStability = null, baselineHits = baseline,
        baselineHitRate = baseline.toDouble() / evaluated,
        baselineLift = (hits - baseline).toDouble() / evaluated,
        recencyWeightedHitRate = null, recentHitRate = null,
        rollingHitRateStdDev = 0.0, outcomeMode = mode
    )

    @Test fun onlyBaselineSeparatedPatternsArePromoted() {
        val rows = listOf(
            evidence(pattern = "111>222"),
            evidence(pattern = "333>444", hits = 55, baseline = 50),
            evidence(pattern = "555>666", hits = 0, baseline = 100)
        )
        val report = AdaptiveRankingEngine.rank(
            rows, Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA,
            target, minimumEvaluations = 20
        )
        assertEquals(1, report.supported)
        assertEquals(1, report.watch)
        assertEquals(1, report.suppressed)
        assertEquals("111>222", report.candidates.first().pattern)
        assertEquals(AdaptiveCandidateStatus.SUPPORTED, report.candidates.first().status)
    }

    @Test fun marketSessionChannelAndFutureEvidenceCannotLeak() {
        val rows = listOf(
            evidence(pattern = "111>222"),
            evidence(market = Market.KALYAN, pattern = "222>333"),
            evidence(session = "night", pattern = "333>444"),
            evidence(channel = PatternEvidenceChannel.CLOSE_PANNA, pattern = "444>555"),
            evidence(pattern = "555>666", lastSeen = target)
        )
        val report = AdaptiveRankingEngine.rank(
            rows, Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, target
        )
        assertEquals(1, report.eligibleEvidence)
        assertEquals(listOf("111>222"), report.candidates.map { it.pattern })
        assertTrue(report.candidates.all {
            it.market == Market.SRIDEVI &&
                it.sessionId == "main" &&
                it.channel == PatternEvidenceChannel.OPEN_PANNA
        })
    }

    @Test fun insufficientEvidenceAbstainsRatherThanPromoting() {
        val report = AdaptiveRankingEngine.rank(
            listOf(evidence(hits = 3, baseline = 0, evaluated = 5)),
            Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, target,
            minimumEvaluations = 20
        )
        assertTrue(report.abstained)
        assertEquals(1, report.insufficient)
        assertEquals(AdaptiveCandidateStatus.INSUFFICIENT_EVIDENCE, report.candidates.single().status)
    }

    @Test fun noEvidenceProducesExplicitAbstention() {
        val report = AdaptiveRankingEngine.rank(
            emptyList(), Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, target
        )
        assertTrue(report.abstained)
        assertTrue(report.candidates.isEmpty())
        assertEquals(0, report.eligibleEvidence)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateEvidenceIdentityIsRejected() {
        val row = evidence()
        AdaptiveRankingEngine.rank(
            listOf(row, row), Market.SRIDEVI, "main",
            PatternEvidenceChannel.OPEN_PANNA, target
        )
    }

    @Test fun familyMovementAndExactEvidenceRemainSeparate() {
        val rows = listOf(
            evidence(pattern = "111>222", mode = PatternOutcomeMode.EXACT),
            evidence(pattern = "111>222", mode = PatternOutcomeMode.FAMILY_MOVEMENT, hits = 0, baseline = 100)
        )
        val report = AdaptiveRankingEngine.rank(
            rows, Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA,
            target, outcomeMode = PatternOutcomeMode.FAMILY_MOVEMENT
        )
        assertEquals(1, report.eligibleEvidence)
        assertEquals(PatternOutcomeMode.FAMILY_MOVEMENT, report.candidates.single().outcomeMode)
        assertEquals(AdaptiveCandidateStatus.SUPPRESSED, report.candidates.single().status)
    }
}
