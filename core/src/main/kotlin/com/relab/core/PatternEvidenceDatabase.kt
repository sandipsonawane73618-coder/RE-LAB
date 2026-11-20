package com.relab.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class PatternEvidenceChannel { OPEN_PANNA, CLOSE_PANNA, JODI }

data class PatternOutcomeCount(val outcome: String, val count: Int)

data class PatternEvidenceRecord(
    val market: Market,
    val sessionId: String,
    val channel: PatternEvidenceChannel,
    val windowLength: Int,
    val pattern: String,
    val occurrences: Int,
    val evaluatedOccurrences: Int,
    val hits: Int,
    val misses: Int,
    val hitRate: Double?,
    val outcomeCounts: List<PatternOutcomeCount>,
    val lastSeenDate: LocalDate,
    val ageInChartRows: Int,
    val ageInDays: Long,
    val distinctSourceCount: Int,
    val recentOutcomeStability: Double?,
    val baselineHits: Int,
    val baselineHitRate: Double?,
    val baselineLift: Double?,
    val recencyWeightedHitRate: Double?,
    val recentHitRate: Double?,
    val rollingHitRateStdDev: Double?
)

/**
 * Builds auditable next-observation evidence from exact historical sequences.
 *
 * A pattern is the previous N observed chart values in one market/session/channel.
 * Its outcome is the current row's value. Each hit/miss is scored only against
 * matching occurrences strictly earlier than that outcome; the first occurrence
 * is retained as support but is not scored. Calendar gaps are not synthesized.
 */
object PatternEvidenceDatabase {
    fun analyze(
        records: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        windowLengths: IntRange = 2..5,
        recentOccurrenceWindow: Int = 20
    ): List<PatternEvidenceRecord> {
        require(!windowLengths.isEmpty() && windowLengths.first >= 1) {
            "At least one positive pattern window length is required"
        }
        require(recentOccurrenceWindow >= 1) { "Recent occurrence window must be positive" }

        val validRows = records.asSequence()
            .filter { it.date.isBefore(targetDateExclusive) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
            .sortedWith(compareBy<ChartRecord> { it.market.name }
                .thenBy { it.sessionId }.thenBy { it.date })
            .toList()

        validRows.groupBy { Triple(it.market, it.sessionId, it.date) }
            .forEach { (key, rows) ->
                require(rows.size == 1) {
                    "Duplicate dates for ${key.first}/${key.second} on ${key.third} must be reconciled before evidence analysis"
                }
            }

        val results = mutableListOf<PatternEvidenceRecord>()
        val channels = PatternEvidenceChannel.entries
        validRows.groupBy { it.market to it.sessionId }
            .forEach { (identity, rowsUnsorted) ->
                val rows = rowsUnsorted.sortedBy { it.date }
                channels.forEach { channel ->
                    val values = rows.map { row ->
                        when (channel) {
                            PatternEvidenceChannel.OPEN_PANNA -> row.openPanna
                            PatternEvidenceChannel.CLOSE_PANNA -> row.closePanna
                            PatternEvidenceChannel.JODI -> row.jodi
                        }
                    }
                    windowLengths.forEach { windowLength ->
                        if (rows.size <= windowLength) return@forEach
                        data class Event(
                            val date: LocalDate,
                            val outcome: String,
                            val sourceId: String,
                            val rowIndex: Int
                        )
                        val eventsByPattern = linkedMapOf<String, MutableList<Event>>()
                        for (targetIndex in windowLength until rows.size) {
                            val pattern = values.subList(targetIndex - windowLength, targetIndex)
                                .joinToString(">")
                            val event = Event(
                                date = rows[targetIndex].date,
                                outcome = values[targetIndex],
                                sourceId = rows[targetIndex].sourceId,
                                rowIndex = targetIndex
                            )
                            eventsByPattern.getOrPut(pattern) { mutableListOf() } += event
                        }

                        eventsByPattern.forEach { (pattern, events) ->
                            val historicalOutcomes = mutableListOf<String>()
                            val outcomeCounts = linkedMapOf<String, Int>()
                            var hits = 0
                            var misses = 0
                            var baselineHits = 0
                            val scoredPatternHits = mutableListOf<Boolean>()
                            events.forEach { event ->
                                if (historicalOutcomes.isNotEmpty()) {
                                    val predicted = historicalOutcomes.groupingBy { it }.eachCount()
                                        .entries
                                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                                            .thenBy { it.key })
                                        .first().key
                                    val patternHit = predicted == event.outcome
                                    if (patternHit) hits++ else misses++
                                    scoredPatternHits += patternHit
                                    val priorChannelOutcomes = values.take(event.rowIndex)
                                    val baselinePrediction = priorChannelOutcomes.groupingBy { it }.eachCount()
                                        .entries
                                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                                            .thenBy { it.key })
                                        .first().key
                                    if (baselinePrediction == event.outcome) baselineHits++
                                }
                                historicalOutcomes += event.outcome
                                outcomeCounts[event.outcome] = (outcomeCounts[event.outcome] ?: 0) + 1
                            }
                            val recent = historicalOutcomes.takeLast(recentOccurrenceWindow)
                            val stability = if (recent.isEmpty()) null else
                                recent.groupingBy { it }.eachCount().values.max().toDouble() / recent.size
                            val recentHits = scoredPatternHits.takeLast(recentOccurrenceWindow)
                            val recentHitRate = recentHits.takeIf { it.isNotEmpty() }
                                ?.count { it }?.toDouble()?.div(recentHits.size)
                            val weightedHitRate = if (scoredPatternHits.isEmpty()) null else {
                                val halfLife = maxOf(1.0, recentOccurrenceWindow / 2.0)
                                val weights = scoredPatternHits.indices.map { index ->
                                    Math.pow(0.5, (scoredPatternHits.lastIndex - index) / halfLife)
                                }
                                scoredPatternHits.indices.sumOf { index ->
                                    (if (scoredPatternHits[index]) 1.0 else 0.0) * weights[index]
                                } / weights.sum()
                            }
                            val rollingWindow = minOf(5, scoredPatternHits.size)
                            val rollingRates = if (rollingWindow < 2) emptyList() else
                                (0..(scoredPatternHits.size - rollingWindow)).map { start ->
                                    scoredPatternHits.subList(start, start + rollingWindow)
                                        .count { it }.toDouble() / rollingWindow
                                }
                            val rollingMean = rollingRates.average()
                            val rollingStdDev = if (rollingRates.size < 2) null else
                                kotlin.math.sqrt(rollingRates.map { (it - rollingMean) * (it - rollingMean) }.average())
                            val last = events.last()
                            val rankedOutcomes = outcomeCounts.entries
                                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                                    .thenBy { it.key })
                                .map { PatternOutcomeCount(it.key, it.value) }
                            results += PatternEvidenceRecord(
                                market = identity.first,
                                sessionId = identity.second,
                                channel = channel,
                                windowLength = windowLength,
                                pattern = pattern,
                                occurrences = events.size,
                                evaluatedOccurrences = hits + misses,
                                hits = hits,
                                misses = misses,
                                hitRate = if (hits + misses == 0) null else hits.toDouble() / (hits + misses),
                                outcomeCounts = rankedOutcomes,
                                lastSeenDate = last.date,
                                ageInChartRows = rows.lastIndex - last.rowIndex,
                                ageInDays = ChronoUnit.DAYS.between(last.date, rows.last().date),
                                distinctSourceCount = events.flatMap { event ->
                                    rows.subList(event.rowIndex - windowLength, event.rowIndex)
                                        .map { it.sourceId } + event.sourceId
                                }.distinct().size,
                                recentOutcomeStability = stability,
                                baselineHits = baselineHits,
                                baselineHitRate = if (hits + misses == 0) null else baselineHits.toDouble() / (hits + misses),
                                baselineLift = if (hits + misses == 0) null else
                                    hits.toDouble() / (hits + misses) - baselineHits.toDouble() / (hits + misses),
                                recencyWeightedHitRate = weightedHitRate,
                                recentHitRate = recentHitRate,
                                rollingHitRateStdDev = rollingStdDev
                            )
                        }
                    }
                }
            }
        return results.sortedWith(
            compareBy<PatternEvidenceRecord> { it.market.name }
                .thenBy { it.sessionId }
                .thenBy { it.channel.name }
                .thenBy { it.windowLength }
                .thenByDescending { it.evaluatedOccurrences }
                .thenByDescending { it.occurrences }
                .thenBy { it.pattern }
        )
    }
}
