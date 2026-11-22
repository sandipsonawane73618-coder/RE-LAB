package com.relab.core

/** Descriptive sample-size band; it is not a statistical guarantee or calibration label. */
enum class WalkForwardSampleBand {
    NO_EVALUATED_TARGETS,
    VERY_SMALL,
    EARLY_SAMPLE,
    BROADER_SAMPLE,
    LARGER_SAMPLE
}

data class MarketWalkForwardPeriodComparison(
    val period: Int,
    val firstTargetDate: java.time.LocalDate?,
    val lastTargetDate: java.time.LocalDate?,
    val requestedTargets: Int,
    val evaluatedTargets: Int,
    val abstainedTargets: Int,
    val exactPairTop10Hits: Int,
    val exactPairTop100Hits: Int,
    val openFamilyTop10Hits: Int,
    val closeFamilyTop10Hits: Int,
    val jodiDiagnosticMatches: Int
) {
    fun rate(hits: Int): Double? =
        if (evaluatedTargets == 0) null else hits.toDouble() / evaluatedTargets
}

data class MarketWalkForwardComparison(
    val market: Market,
    val sessionCount: Int,
    val requestedTargets: Int,
    val evaluatedTargets: Int,
    val abstainedTargets: Int,
    val exactPairTop10Hits: Int,
    val exactPairTop100Hits: Int,
    val openFamilyTop10Hits: Int,
    val closeFamilyTop10Hits: Int,
    val jodiDiagnosticMatches: Int,
    val sampleBand: WalkForwardSampleBand,
    val chronologicalPeriods: List<MarketWalkForwardPeriodComparison> = emptyList()
) {
    fun rate(hits: Int): Double? =
        if (evaluatedTargets == 0) null else hits.toDouble() / evaluatedTargets
}

/** Aggregates existing per-session chronological reports without pooling hidden or excluded rows. */
object MarketWalkForwardComparisonBuilder {
    fun build(reports: List<SangamWalkForwardReport>): List<MarketWalkForwardComparison> {
        require(reports.groupBy { it.market to it.sessionId }.values.all { it.size == 1 }) {
            "Duplicate market/session walk-forward reports are not allowed"
        }
        return reports.groupBy { it.market }
            .toSortedMap(compareBy { it.name })
            .map { (market, sessions) ->
                val evaluated = sessions.sumOf { it.evaluatedTargets }
                val band = when {
                    evaluated == 0 -> WalkForwardSampleBand.NO_EVALUATED_TARGETS
                    evaluated < 30 -> WalkForwardSampleBand.VERY_SMALL
                    evaluated < 100 -> WalkForwardSampleBand.EARLY_SAMPLE
                    evaluated < 300 -> WalkForwardSampleBand.BROADER_SAMPLE
                    else -> WalkForwardSampleBand.LARGER_SAMPLE
                }
                val periods = (1..3).map { period ->
                    val parts = sessions.flatMap { session ->
                        session.chronologicalPeriods.filter { it.period == period }
                    }
                    MarketWalkForwardPeriodComparison(
                        period = period,
                        firstTargetDate = parts.mapNotNull { it.firstTargetDate }.minOrNull(),
                        lastTargetDate = parts.mapNotNull { it.lastTargetDate }.maxOrNull(),
                        requestedTargets = parts.sumOf { it.requestedTargets },
                        evaluatedTargets = parts.sumOf { it.evaluatedTargets },
                        abstainedTargets = parts.sumOf { it.abstainedTargets },
                        exactPairTop10Hits = parts.sumOf { it.exactPairTop10Hits },
                        exactPairTop100Hits = parts.sumOf { it.exactPairTop100Hits },
                        openFamilyTop10Hits = parts.sumOf { it.openFamilyTop10Hits },
                        closeFamilyTop10Hits = parts.sumOf { it.closeFamilyTop10Hits },
                        jodiDiagnosticMatches = parts.sumOf { it.jodiPairTop100Hits }
                    )
                }
                MarketWalkForwardComparison(
                    market = market,
                    sessionCount = sessions.size,
                    requestedTargets = sessions.sumOf { it.requestedTargets },
                    evaluatedTargets = evaluated,
                    abstainedTargets = sessions.sumOf { it.abstainedTargets },
                    exactPairTop10Hits = sessions.sumOf { it.exactPairTop10Hits },
                    exactPairTop100Hits = sessions.sumOf { it.exactPairTop100Hits },
                    openFamilyTop10Hits = sessions.sumOf { it.openFamilyTop10Hits },
                    closeFamilyTop10Hits = sessions.sumOf { it.closeFamilyTop10Hits },
                    jodiDiagnosticMatches = sessions.sumOf { it.jodiPairTop100Hits },
                    sampleBand = band,
                    chronologicalPeriods = periods
                )
            }
    }
}
