package com.relab.core

import java.time.LocalDate

enum class PatternPeriodAssessment {
    SUDDEN_DECLINE,
    WEAK,
    STABLE,
    CHANGING,
    INSUFFICIENT_SAMPLE
}

data class PatternPeriodMetric(
    val period: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val occurrences: Int,
    val evaluated: Int,
    val hits: Int,
    val misses: Int,
    val baselineHits: Int
) {
    val hitRate: Double? get() = if (evaluated == 0) null else hits.toDouble() / evaluated
    val baselineRate: Double? get() = if (evaluated == 0) null else baselineHits.toDouble() / evaluated
}

data class PatternPeriodStability(
    val market: Market,
    val sessionId: String,
    val channel: PatternEvidenceChannel,
    val windowLength: Int,
    val pattern: String,
    val outcomeMode: PatternOutcomeMode,
    val periods: List<PatternPeriodMetric>,
    val assessment: PatternPeriodAssessment
)

/**
 * Compares the same exact pattern identity across three chronological thirds.
 * Each period is calculated as the delta between cumulative, strictly-prior
 * PatternEvidenceDatabase snapshots. This preserves the original walk-forward
 * scoring and never lets a target outcome leak into its own prediction.
 *
 * Labels are descriptive only. No "strong" label or predictive probability exists.
 */
object PatternPeriodStabilityAnalyzer {
    fun analyze(
        records: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        windowLengths: IntRange = 2..5,
        minimumEvaluationsPerPeriod: Int = 10,
        minimumWeakBaselineGap: Double = 0.10,
        suddenDeclineThreshold: Double = 0.25,
        stableRangeThreshold: Double = 0.15
    ): List<PatternPeriodStability> {
        require(minimumEvaluationsPerPeriod >= 1)
        require(minimumWeakBaselineGap in 0.0..1.0)
        require(suddenDeclineThreshold in 0.0..1.0)
        require(stableRangeThreshold in 0.0..1.0)

        val valid = records.asSequence()
            .filter { it.date.isBefore(targetDateExclusive) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
            .sortedWith(compareBy<ChartRecord> { it.market.name }.thenBy { it.sessionId }.thenBy { it.date })
            .toList()
        valid.groupBy { Triple(it.market, it.sessionId, it.date) }.forEach { (key, rows) ->
            require(rows.size == 1) {
                "Duplicate dates for ${key.first}/${key.second} on ${key.third} must be reconciled before period comparison"
            }
        }

        val output = mutableListOf<PatternPeriodStability>()
        valid.groupBy { it.market to it.sessionId }.forEach { (identity, unsorted) ->
            val rows = unsorted.sortedBy { it.date }
            if (rows.size < 3) return@forEach
            val boundaries = (1..3).map { period ->
                val endExclusiveIndex = rows.size * period / 3
                rows[endExclusiveIndex - 1].date
            }
            PatternOutcomeMode.entries.forEach { mode ->
                val cumulative = boundaries.map { lastDate ->
                    PatternEvidenceDatabase.analyze(
                        rows, lastDate.plusDays(1), windowLengths = windowLengths,
                        outcomeMode = mode
                    ).associateBy { key(it) }
                }
                val keys = cumulative.flatMap { it.keys }.toSortedSet(
                    compareBy<PatternEvidenceKey> { it.market.name }
                        .thenBy { it.sessionId }.thenBy { it.channel.name }
                        .thenBy { it.windowLength }.thenBy { it.pattern }
                        .thenBy { it.outcomeMode.name }
                )
                keys.forEach { evidenceKey ->
                    val metrics = (0..2).map { index ->
                        val current = cumulative[index][evidenceKey]
                        val previous = if (index == 0) null else cumulative[index - 1][evidenceKey]
                        val occurrences = (current?.occurrences ?: 0) - (previous?.occurrences ?: 0)
                        val evaluated = (current?.evaluatedOccurrences ?: 0) - (previous?.evaluatedOccurrences ?: 0)
                        val hits = (current?.hits ?: 0) - (previous?.hits ?: 0)
                        val misses = (current?.misses ?: 0) - (previous?.misses ?: 0)
                        val baselineHits = (current?.baselineHits ?: 0) - (previous?.baselineHits ?: 0)
                        PatternPeriodMetric(
                            period = index + 1,
                            firstDate = rows[(rows.size * index / 3)].date,
                            lastDate = boundaries[index],
                            occurrences = occurrences,
                            evaluated = evaluated,
                            hits = hits,
                            misses = misses,
                            baselineHits = baselineHits
                        )
                    }
                    val assessed = assess(
                        metrics, minimumEvaluationsPerPeriod,
                        minimumWeakBaselineGap, suddenDeclineThreshold, stableRangeThreshold
                    )
                    output += PatternPeriodStability(
                        identity.first, identity.second, evidenceKey.channel,
                        evidenceKey.windowLength, evidenceKey.pattern, evidenceKey.outcomeMode,
                        metrics, assessed
                    )
                }
            }
        }
        return output.sortedWith(
            compareBy<PatternPeriodStability> { it.market.name }
                .thenBy { it.sessionId }.thenBy { it.channel.name }
                .thenBy { it.outcomeMode.name }.thenBy { it.windowLength }
                .thenBy { it.assessment.name }.thenBy { it.pattern }
        )
    }

    private data class PatternEvidenceKey(
        val market: Market,
        val sessionId: String,
        val channel: PatternEvidenceChannel,
        val windowLength: Int,
        val pattern: String,
        val outcomeMode: PatternOutcomeMode
    )

    private fun key(item: PatternEvidenceRecord) = PatternEvidenceKey(
        item.market, item.sessionId, item.channel, item.windowLength, item.pattern, item.outcomeMode
    )

    private fun assess(
        periods: List<PatternPeriodMetric>,
        minimum: Int,
        weakGap: Double,
        decline: Double,
        stableRange: Double
    ): PatternPeriodAssessment {
        if (periods.size != 3 || periods.any { it.evaluated < minimum || it.hitRate == null || it.baselineRate == null }) {
            return PatternPeriodAssessment.INSUFFICIENT_SAMPLE
        }
        val rates = periods.map { it.hitRate!! }
        val baselines = periods.map { it.baselineRate!! }
        if (rates[1] - rates[2] >= decline) return PatternPeriodAssessment.SUDDEN_DECLINE
        if (rates.indices.all { rates[it] <= baselines[it] - weakGap }) return PatternPeriodAssessment.WEAK
        if (rates.maxOrNull()!! - rates.minOrNull()!! <= stableRange) return PatternPeriodAssessment.STABLE
        return PatternPeriodAssessment.CHANGING
    }
}
