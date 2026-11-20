package com.relab.core

data class RankedPatternEvidence(
    val evidence: PatternEvidenceRecord,
    val rankScore: Double,
    val supportFactor: Double,
    val recencyFactor: Double,
    val stabilityFactor: Double,
    val hitRateLower95: Double?,
    val hitRateUpper95: Double?,
    val baselineLower95: Double?,
    val baselineUpper95: Double?,
    val assessment: PatternEvidenceAssessment
)

enum class PatternEvidenceAssessment {
    OUTPERFORMS_BASELINE,
    BELOW_BASELINE,
    INCONCLUSIVE,
    INSUFFICIENT_EVIDENCE
}

/**
 * Evidence-first ranking. Scores are ordering aids, not probabilities.
 *
 * Point-estimate lift alone is not enough to claim an edge. The deliberately
 * conservative rule requires the pattern's 95% Wilson lower bound to exceed
 * the matched baseline's 95% Wilson upper bound (and vice versa for BELOW).
 * Overlapping intervals are INCONCLUSIVE, not a win or a loss.
 */
object PatternEvidenceRanking {
    private data class Interval(val lower: Double, val upper: Double)

    private fun wilson(successes: Int, total: Int): Interval? {
        if (total <= 0) return null
        require(successes in 0..total) { "Successes must be between zero and total" }
        val z = 1.959963984540054
        val n = total.toDouble()
        val p = successes / n
        val denominator = 1.0 + z * z / n
        val center = (p + z * z / (2.0 * n)) / denominator
        val margin = z * kotlin.math.sqrt(
            p * (1.0 - p) / n + z * z / (4.0 * n * n)
        ) / denominator
        return Interval((center - margin).coerceAtLeast(0.0), (center + margin).coerceAtMost(1.0))
    }

    fun rank(
        evidence: List<PatternEvidenceRecord>,
        minimumEvaluations: Int = 3,
        recencyScaleInRows: Double = 20.0
    ): List<RankedPatternEvidence> {
        require(minimumEvaluations >= 1) { "Minimum evaluations must be positive" }
        require(recencyScaleInRows > 0.0 && recencyScaleInRows.isFinite()) {
            "Recency scale must be positive and finite"
        }
        return evidence.map { item ->
            val validCounts = item.evaluatedOccurrences > 0 &&
                item.occurrences >= item.evaluatedOccurrences &&
                item.hits >= 0 && item.misses >= 0 &&
                item.hits + item.misses == item.evaluatedOccurrences &&
                item.baselineHits in 0..item.evaluatedOccurrences
            val expectedHitRate = if (validCounts) item.hits.toDouble() / item.evaluatedOccurrences else null
            val expectedBaselineRate = if (validCounts) item.baselineHits.toDouble() / item.evaluatedOccurrences else null
            val validRates = item.hitRate != null && item.baselineHitRate != null &&
                item.hitRate.isFinite() && item.baselineHitRate.isFinite() &&
                item.hitRate in 0.0..1.0 && item.baselineHitRate in 0.0..1.0 &&
                expectedHitRate != null && expectedBaselineRate != null &&
                kotlin.math.abs(item.hitRate - expectedHitRate) <= 1e-9 &&
                kotlin.math.abs(item.baselineHitRate - expectedBaselineRate) <= 1e-9
            val enough = item.evaluatedOccurrences >= minimumEvaluations && validCounts && validRates
            val patternInterval = if (enough) wilson(item.hits, item.evaluatedOccurrences) else null
            val baselineInterval = if (enough) wilson(item.baselineHits, item.evaluatedOccurrences) else null
            val support = item.evaluatedOccurrences.toDouble() /
                (item.evaluatedOccurrences + minimumEvaluations * 2.0)
            val recency = 1.0 / (1.0 + item.ageInChartRows / recencyScaleInRows)
            val volatility = (item.rollingHitRateStdDev ?: 0.5).coerceIn(0.0, 0.5)
            val stability = 1.0 - volatility
            val lift = if (enough) item.hitRate!! - item.baselineHitRate!! else 0.0
            val assessment = when {
                !enough -> PatternEvidenceAssessment.INSUFFICIENT_EVIDENCE
                patternInterval!!.lower > baselineInterval!!.upper ->
                    PatternEvidenceAssessment.OUTPERFORMS_BASELINE
                patternInterval.upper < baselineInterval.lower ->
                    PatternEvidenceAssessment.BELOW_BASELINE
                else -> PatternEvidenceAssessment.INCONCLUSIVE
            }
            val score = when (assessment) {
                PatternEvidenceAssessment.OUTPERFORMS_BASELINE,
                PatternEvidenceAssessment.BELOW_BASELINE -> lift * support * recency * stability * 100.0
                PatternEvidenceAssessment.INCONCLUSIVE -> 0.0
                PatternEvidenceAssessment.INSUFFICIENT_EVIDENCE -> -101.0
            }
            RankedPatternEvidence(
                evidence = item,
                rankScore = score,
                supportFactor = support,
                recencyFactor = recency,
                stabilityFactor = stability,
                hitRateLower95 = patternInterval?.lower,
                hitRateUpper95 = patternInterval?.upper,
                baselineLower95 = baselineInterval?.lower,
                baselineUpper95 = baselineInterval?.upper,
                assessment = assessment
            )
        }.sortedWith(
            compareByDescending<RankedPatternEvidence> { it.rankScore }
                .thenByDescending { it.evidence.evaluatedOccurrences }
                .thenByDescending { it.evidence.lastSeenDate }
                .thenBy { it.evidence.pattern }
        )
    }
}
