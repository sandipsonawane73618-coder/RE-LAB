package com.relab.core

data class RankedPatternEvidence(
    val evidence: PatternEvidenceRecord,
    val rankScore: Double,
    val supportFactor: Double,
    val recencyFactor: Double,
    val stabilityFactor: Double,
    val assessment: PatternEvidenceAssessment
)

enum class PatternEvidenceAssessment {
    OUTPERFORMS_BASELINE,
    AT_BASELINE,
    BELOW_BASELINE,
    INSUFFICIENT_EVIDENCE
}

/**
 * Evidence-first ranking. Scores are ordering aids, not probabilities.
 * A pattern cannot be labelled as outperforming unless its chronological
 * walk-forward hit rate is strictly above the matched global-frequency baseline.
 */
object PatternEvidenceRanking {
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
            val enough = item.evaluatedOccurrences >= minimumEvaluations &&
                item.hitRate != null && item.baselineHitRate != null
            val support = item.evaluatedOccurrences.toDouble() /
                (item.evaluatedOccurrences + minimumEvaluations * 2.0)
            val recency = 1.0 / (1.0 + item.ageInChartRows / recencyScaleInRows)
            val volatility = (item.rollingHitRateStdDev ?: 0.5).coerceIn(0.0, 0.5)
            val stability = 1.0 - volatility
            val lift = if (enough) item.hitRate!! - item.baselineHitRate!! else 0.0
            // Keep unproven records below even negative-but-measured evidence.
            val score = if (enough) lift * support * recency * stability * 100.0 else -101.0
            val assessment = when {
                !enough -> PatternEvidenceAssessment.INSUFFICIENT_EVIDENCE
                lift > 0.0 -> PatternEvidenceAssessment.OUTPERFORMS_BASELINE
                lift < 0.0 -> PatternEvidenceAssessment.BELOW_BASELINE
                else -> PatternEvidenceAssessment.AT_BASELINE
            }
            RankedPatternEvidence(item, score, support, recency, stability, assessment)
        }.sortedWith(
            compareByDescending<RankedPatternEvidence> { it.rankScore }
                .thenByDescending { it.evidence.evaluatedOccurrences }
                .thenByDescending { it.evidence.lastSeenDate }
                .thenBy { it.evidence.pattern }
        )
    }
}
