package com.relab.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class PatternEvidenceChannel { OPEN_PANNA, CLOSE_PANNA, JODI }

enum class PatternOutcomeMode { EXACT, FAMILY_MOVEMENT }

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
    val rollingHitRateStdDev: Double?,
    val outcomeMode: PatternOutcomeMode = PatternOutcomeMode.EXACT
)

/**
 * Builds auditable next-observation evidence from exact or family-movement sequences.
 *
 * A pattern is the previous N consecutive valid observations in one market,
 * session and channel. Invalid source rows are hard barriers: windows never
 * jump across them. The outcome is the next valid observation in that same
 * uninterrupted segment. The first occurrence is retained as support but is
 * not scored; later occurrences are scored only against earlier outcomes for
 * that same pattern. Baselines use only channel outcomes strictly before the
 * scored observation. Calendar gaps are not synthesized.
 */
object PatternEvidenceDatabase {
    private data class ValidRow(val record: ChartRecord, val rawIndex: Int)

    fun analyze(
        records: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        windowLengths: IntRange = 2..5,
        recentOccurrenceWindow: Int = 20,
        outcomeMode: PatternOutcomeMode = PatternOutcomeMode.EXACT
    ): List<PatternEvidenceRecord> {
        require(!windowLengths.isEmpty() && windowLengths.first >= 1) {
            "At least one positive pattern window length is required"
        }
        require(recentOccurrenceWindow >= 1) { "Recent occurrence window must be positive" }

        val inScope = records.filter { it.date.isBefore(targetDateExclusive) }
        inScope.groupBy { Triple(it.market, it.sessionId, it.date) }
            .forEach { (key, rows) ->
                require(rows.size == 1) {
                    "Duplicate dates for ${key.first}/${key.second} on ${key.third} must be reconciled before evidence analysis"
                }
            }

        val results = mutableListOf<PatternEvidenceRecord>()
        val channels = PatternEvidenceChannel.entries
        inScope.groupBy { it.market to it.sessionId }
            .forEach { (identity, unsortedRows) ->
                val rawRows = unsortedRows.sortedBy { it.date }
                val validRows = rawRows.mapIndexedNotNull { index, row ->
                    if (ChartMath.validate(row) is ValidationResult.Valid) ValidRow(row, index) else null
                }
                if (validRows.isEmpty()) return@forEach

                channels.forEach { channel ->
                    val values = validRows.map { valid ->
                        val raw = when (channel) {
                            PatternEvidenceChannel.OPEN_PANNA -> valid.record.openPanna
                            PatternEvidenceChannel.CLOSE_PANNA -> valid.record.closePanna
                            PatternEvidenceChannel.JODI -> valid.record.jodi
                        }
                        when (outcomeMode) {
                            PatternOutcomeMode.EXACT -> raw
                            PatternOutcomeMode.FAMILY_MOVEMENT -> FamilyMovementMath.key(raw)
                        }
                    }

                    // Split at every invalid raw observation. No sequence may bridge it.
                    val segments = mutableListOf<List<Int>>()
                    var current = mutableListOf<Int>()
                    validRows.forEachIndexed { validIndex, valid ->
                        val previousRawIndex = current.lastOrNull()?.let { validRows[it].rawIndex }
                        if (previousRawIndex != null &&
                            valid.rawIndex != previousRawIndex + 1
                        ) {
                            if (current.isNotEmpty()) segments += current.toList()
                            current = mutableListOf()
                        }
                        current += validIndex
                    }
                    if (current.isNotEmpty()) segments += current.toList()

                    windowLengths.forEach windowLoop@ { windowLength ->
                        data class Event(
                            val date: LocalDate,
                            val outcome: String,
                            val sourceId: String,
                            val validIndex: Int,
                            val rawIndex: Int,
                            val windowSourceIds: List<String>
                        )
                        val eventsByPattern = linkedMapOf<String, MutableList<Event>>()
                        segments.forEach { segment ->
                            if (segment.size <= windowLength) return@forEach
                            for (segmentIndex in windowLength until segment.size) {
                                val targetValidIndex = segment[segmentIndex]
                                val windowIndexes = segment.subList(segmentIndex - windowLength, segmentIndex)
                                val pattern = windowIndexes.joinToString(">") { values[it] }
                                val target = validRows[targetValidIndex]
                                val event = Event(
                                    date = target.record.date,
                                    outcome = values[targetValidIndex],
                                    sourceId = target.record.sourceId,
                                    validIndex = targetValidIndex,
                                    rawIndex = target.rawIndex,
                                    windowSourceIds = windowIndexes.map { validRows[it].record.sourceId }
                                )
                                eventsByPattern.getOrPut(pattern) { mutableListOf() } += event
                            }
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

                                    val priorChannelOutcomes = values.take(event.validIndex)
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
                            val evaluated = hits + misses
                            results += PatternEvidenceRecord(
                                market = identity.first,
                                sessionId = identity.second,
                                channel = channel,
                                windowLength = windowLength,
                                pattern = pattern,
                                occurrences = events.size,
                                evaluatedOccurrences = evaluated,
                                hits = hits,
                                misses = misses,
                                hitRate = if (evaluated == 0) null else hits.toDouble() / evaluated,
                                outcomeCounts = rankedOutcomes,
                                lastSeenDate = last.date,
                                ageInChartRows = rawRows.lastIndex - last.rawIndex,
                                ageInDays = ChronoUnit.DAYS.between(last.date, validRows.last().record.date),
                                distinctSourceCount = events.flatMap { it.windowSourceIds + it.sourceId }.distinct().size,
                                recentOutcomeStability = stability,
                                baselineHits = baselineHits,
                                baselineHitRate = if (evaluated == 0) null else baselineHits.toDouble() / evaluated,
                                baselineLift = if (evaluated == 0) null else
                                    hits.toDouble() / evaluated - baselineHits.toDouble() / evaluated,
                                recencyWeightedHitRate = weightedHitRate,
                                recentHitRate = recentHitRate,
                                rollingHitRateStdDev = rollingStdDev,
                                outcomeMode = outcomeMode
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
