package com.relab.core

import java.time.LocalDate

enum class FamilyMovementChannel { JODI, OPEN_PANNA, CLOSE_PANNA }

data class FamilyMovementChannelReport(
    val market: Market,
    val sessionId: String,
    val channel: FamilyMovementChannel,
    val requestedTargets: Int,
    val evaluatedTargets: Int,
    val abstainedTargets: Int,
    val top1Hits: Int,
    val top3Hits: Int,
    val top5Hits: Int,
    val top10Hits: Int,
    val exactFrequencyBaselineTop1Hits: Int,
    val exactFrequencyBaselineTop3Hits: Int,
    val exactFrequencyBaselineTop5Hits: Int,
    val exactFrequencyBaselineTop10Hits: Int
) {
    fun hitRate(hits: Int): Double? = if (evaluatedTargets == 0) null else hits.toDouble() / evaluatedTargets
}

/**
 * Chronological test of the observed digit-family + reversal equivalence.
 * At each target, equivalence-class frequencies are built only from earlier
 * rows in the same market/session. The target is scored before it enters history.
 * This evaluates historical coverage, not a calibrated probability or guarantee.
 */
object FamilyMovementWalkForwardEvaluator {
    fun evaluate(
        records: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        minimumHistory: Int = 30
    ): List<FamilyMovementChannelReport> {
        require(minimumHistory >= 1) { "minimumHistory must be positive" }
        val eligible = records.filter { it.date.isBefore(targetDateExclusive) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
        eligible.groupBy { Triple(it.market, it.sessionId, it.date) }.forEach { (key, rows) ->
            require(rows.size == 1) {
                "Duplicate rows for ${key.first}/${key.second} on ${key.third} must be reconciled before family movement evaluation"
            }
        }

        fun value(record: ChartRecord, channel: FamilyMovementChannel): String = when (channel) {
            FamilyMovementChannel.JODI -> record.jodi
            FamilyMovementChannel.OPEN_PANNA -> record.openPanna
            FamilyMovementChannel.CLOSE_PANNA -> record.closePanna
        }

        val reports = mutableListOf<FamilyMovementChannelReport>()
        eligible.groupBy { it.market to it.sessionId }
            .toSortedMap(compareBy<Pair<Market, String>> { it.first.name }.thenBy { it.second })
            .forEach { (identity, unsorted) ->
                val rows = unsorted.sortedBy { it.date }
                FamilyMovementChannel.entries.forEach { channel ->
                    val exactCounts = mutableMapOf<String, Int>()
                    val classCounts = mutableMapOf<String, Int>()
                    var evaluated = 0
                    var abstained = 0
                    var top1 = 0
                    var top3 = 0
                    var top5 = 0
                    var top10 = 0
                    var base1 = 0
                    var base3 = 0
                    var base5 = 0
                    var base10 = 0

                    rows.forEach { target ->
                        if (exactCounts.values.sum() >= minimumHistory) {
                            val actual = value(target, channel)
                            val actualKey = FamilyMovementMath.key(actual)
                            val rankedClasses = classCounts.entries.sortedWith(
                                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
                            ).map { it.key }
                            val exactRanking = exactCounts.entries.sortedWith(
                                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
                            ).map { it.key }
                            val actualVariants = FamilyMovementMath.variants(actual).toSet()
                            evaluated++
                            if (rankedClasses.take(1).contains(actualKey)) top1++
                            if (rankedClasses.take(3).contains(actualKey)) top3++
                            if (rankedClasses.take(5).contains(actualKey)) top5++
                            if (rankedClasses.take(10).contains(actualKey)) top10++
                            // Exact-frequency baseline predicts exact values, without family expansion.
                            if (exactRanking.take(1).any(actualVariants::contains)) base1++
                            if (exactRanking.take(3).any(actualVariants::contains)) base3++
                            if (exactRanking.take(5).any(actualVariants::contains)) base5++
                            if (exactRanking.take(10).any(actualVariants::contains)) base10++
                        } else {
                            abstained++
                        }

                        // Update history only after the held-out target was scored.
                        val actual = value(target, channel)
                        exactCounts[actual] = (exactCounts[actual] ?: 0) + 1
                        val key = FamilyMovementMath.key(actual)
                        classCounts[key] = (classCounts[key] ?: 0) + 1
                    }

                    reports += FamilyMovementChannelReport(
                        market = identity.first, sessionId = identity.second, channel = channel,
                        requestedTargets = rows.size, evaluatedTargets = evaluated,
                        abstainedTargets = abstained, top1Hits = top1, top3Hits = top3,
                        top5Hits = top5, top10Hits = top10,
                        exactFrequencyBaselineTop1Hits = base1,
                        exactFrequencyBaselineTop3Hits = base3,
                        exactFrequencyBaselineTop5Hits = base5,
                        exactFrequencyBaselineTop10Hits = base10
                    )
                }
            }
        return reports
    }
}
