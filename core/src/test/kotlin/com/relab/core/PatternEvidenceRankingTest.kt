package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PatternEvidenceRankingTest {
    private fun evidence(
        pattern: String,
        hits: Int,
        baselineHits: Int,
        evaluated: Int
    ) = PatternEvidenceRecord(
        market = Market.SRIDEVI,
        sessionId = "main",
        channel = PatternEvidenceChannel.OPEN_PANNA,
        windowLength = 2,
        pattern = pattern,
        occurrences = evaluated + 1,
        evaluatedOccurrences = evaluated,
        hits = hits,
        misses = evaluated - hits,
        hitRate = if (evaluated == 0) null else hits.toDouble() / evaluated,
        outcomeCounts = emptyList(),
        lastSeenDate = LocalDate.of(2026, 11, 1),
        ageInChartRows = 0,
        ageInDays = 0,
        distinctSourceCount = 1,
        recentOutcomeStability = null,
        baselineHits = baselineHits,
        baselineHitRate = if (evaluated == 0) null else baselineHits.toDouble() / evaluated,
        baselineLift = if (evaluated == 0) null else (hits - baselineHits).toDouble() / evaluated,
        recencyWeightedHitRate = null,
        recentHitRate = null,
        rollingHitRateStdDev = 0.0
    )

    @Test fun pointEstimateLiftAloneDoesNotClaimOutperformance() {
        val ranked = PatternEvidenceRanking.rank(
            listOf(evidence("A>B", hits = 6, baselineHits = 5, evaluated = 10))
        ).single()

        assertEquals(PatternEvidenceAssessment.INCONCLUSIVE, ranked.assessment)
        assertEquals(0.0, ranked.rankScore, 0.0)
        assertNotNull(ranked.hitRateLower95)
        assertNotNull(ranked.baselineUpper95)
    }

    @Test fun clearlySeparatedIntervalsCanOutperformBaseline() {
        val ranked = PatternEvidenceRanking.rank(
            listOf(evidence("A>B", hits = 100, baselineHits = 0, evaluated = 100))
        ).single()

        assertEquals(PatternEvidenceAssessment.OUTPERFORMS_BASELINE, ranked.assessment)
        assertTrue(ranked.hitRateLower95!! > ranked.baselineUpper95!!)
        assertTrue(ranked.rankScore > 0.0)
    }

    @Test fun clearlyInferiorPatternIsBelowBaseline() {
        val ranked = PatternEvidenceRanking.rank(
            listOf(evidence("A>B", hits = 0, baselineHits = 100, evaluated = 100))
        ).single()

        assertEquals(PatternEvidenceAssessment.BELOW_BASELINE, ranked.assessment)
        assertTrue(ranked.hitRateUpper95!! < ranked.baselineLower95!!)
        assertTrue(ranked.rankScore < 0.0)
    }

    @Test fun insufficientOrInvalidCountsCannotReceiveAnEdgeLabel() {
        val tooFew = evidence("A>B", hits = 1, baselineHits = 0, evaluated = 1)
        val invalid = evidence("C>D", hits = 11, baselineHits = 0, evaluated = 10)

        val ranked = PatternEvidenceRanking.rank(listOf(tooFew, invalid))
        assertTrue(ranked.all { it.assessment == PatternEvidenceAssessment.INSUFFICIENT_EVIDENCE })
        assertTrue(ranked.all { it.rankScore == -101.0 })
    }
    @Test fun inconsistentStoredRatesOrHitMissTotalsAreInsufficient() {
        val valid = evidence("A>B", hits = 8, baselineHits = 2, evaluated = 10)
        val mismatchedPatternRate = valid.copy(hitRate = 0.2)
        val mismatchedBaselineRate = valid.copy(baselineHitRate = 0.9)
        val mismatchedTotals = valid.copy(misses = 1)

        val ranked = PatternEvidenceRanking.rank(
            listOf(mismatchedPatternRate, mismatchedBaselineRate, mismatchedTotals)
        )
        assertTrue(ranked.all {
            it.assessment == PatternEvidenceAssessment.INSUFFICIENT_EVIDENCE
        })
        assertTrue(ranked.all { it.rankScore == -101.0 })
        assertTrue(ranked.all { it.hitRateLower95 == null && it.baselineUpper95 == null })
    }

}
