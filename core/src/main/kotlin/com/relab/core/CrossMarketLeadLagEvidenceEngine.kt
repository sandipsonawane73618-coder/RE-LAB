package com.relab.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class CrossMarketEvidenceChannel { OPEN_PANNA_FAMILY, CLOSE_PANNA_FAMILY, JODI }

data class CrossMarketLeadLagEvidence(
    val sourceMarket: Market,
    val targetMarket: Market,
    val sessionId: String,
    val channel: CrossMarketEvidenceChannel,
    val sourceSignal: String,
    val occurrences: Int,
    val evaluatedOccurrences: Int,
    val hits: Int,
    val misses: Int,
    val hitRate: Double?,
    val baselineHits: Int,
    val baselineHitRate: Double?,
    val lift: Double?,
    val latestSourceDate: LocalDate,
    val latestTargetDate: LocalDate,
    val latestSourceAgeDays: Long,
    val distinctSourceCount: Int
)

/**
 * Measures whether an already-published result in one market is useful for a
 * later result in another market. Same-day source values are never used.
 *
 * For each target observation, the source signal is the latest source-market
 * observation strictly before its date and no older than maxSourceAgeDays.
 * Pattern-specific modal outcomes are compared with the target market's
 * chronological global-frequency baseline, both using prior observations only.
 */
object CrossMarketLeadLagEvidenceEngine {
    fun analyze(
        records: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        maxSourceAgeDays: Long = 7
    ): List<CrossMarketLeadLagEvidence> {
        require(maxSourceAgeDays >= 1) { "Maximum source age must be positive" }
        val valid = records.asSequence()
            .filter { it.date.isBefore(targetDateExclusive) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
            .sortedWith(compareBy<ChartRecord> { it.market.name }.thenBy { it.sessionId }.thenBy { it.date })
            .toList()
        valid.groupBy { Triple(it.market, it.sessionId, it.date) }.forEach { (key, rows) ->
            require(rows.size == 1) {
                "Duplicate rows for ${key.first}/${key.second} on ${key.third} must be reconciled before lead-lag analysis"
            }
        }
        val groups = valid.groupBy { it.sessionId to it.market }
        val results = mutableListOf<CrossMarketLeadLagEvidence>()
        val channels = CrossMarketEvidenceChannel.entries
        val sessions = valid.map { it.sessionId }.distinct().sorted()
        sessions.forEach { session ->
            val markets = valid.filter { it.sessionId == session }.map { it.market }.distinct().sortedBy { it.name }
            for (sourceMarket in markets) for (targetMarket in markets) {
                if (sourceMarket == targetMarket) continue
                val sourceRows = groups[session to sourceMarket].orEmpty().sortedBy { it.date }
                val targetRows = groups[session to targetMarket].orEmpty().sortedBy { it.date }
                if (sourceRows.isEmpty() || targetRows.isEmpty()) continue
                channels.forEach { channel ->
                    fun value(row: ChartRecord): String = when (channel) {
                        CrossMarketEvidenceChannel.OPEN_PANNA_FAMILY -> row.openPanna
                            .map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
                        CrossMarketEvidenceChannel.CLOSE_PANNA_FAMILY -> row.closePanna
                            .map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
                        CrossMarketEvidenceChannel.JODI -> row.jodi
                    }
                    val events = mutableListOf<Triple<ChartRecord, ChartRecord, String>>()
                    var sourceIndex = -1
                    targetRows.forEach targetLoop@ { target ->
                        while (sourceIndex + 1 < sourceRows.size &&
                            sourceRows[sourceIndex + 1].date.isBefore(target.date)
                        ) sourceIndex++
                        if (sourceIndex < 0) return@targetLoop
                        val source = sourceRows[sourceIndex]
                        val age = ChronoUnit.DAYS.between(source.date, target.date)
                        if (age in 1..maxSourceAgeDays) events += Triple(source, target, value(source))
                    }
                    val baselineByTargetDate = mutableMapOf<LocalDate, String>()
                    val globalTargetCounts = linkedMapOf<String, Int>()
                    events.forEach { (_, target, _) ->
                        if (globalTargetCounts.isNotEmpty()) {
                            baselineByTargetDate[target.date] = globalTargetCounts.entries
                                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                                .first().key
                        }
                        val targetValue = value(target)
                        globalTargetCounts[targetValue] = (globalTargetCounts[targetValue] ?: 0) + 1
                    }
                    events.groupBy { it.third }.forEach { (signal, signalEvents) ->
                        val priorOutcomes = mutableListOf<String>()
                        var hits = 0
                        var misses = 0
                        var baselineHits = 0
                        signalEvents.forEach { (_, target, _) ->
                            val outcome = value(target)
                            if (priorOutcomes.isNotEmpty()) {
                                val predicted = priorOutcomes.groupingBy { it }.eachCount().entries
                                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                                    .first().key
                                if (predicted == outcome) hits++ else misses++
                                val baselinePrediction = baselineByTargetDate[target.date]
                                if (baselinePrediction == outcome) baselineHits++
                            }
                            priorOutcomes += outcome
                        }
                        val evaluated = hits + misses
                        val latest = signalEvents.last()
                        val latestAge = ChronoUnit.DAYS.between(latest.first.date, latest.second.date)
                        results += CrossMarketLeadLagEvidence(
                            sourceMarket = sourceMarket,
                            targetMarket = targetMarket,
                            sessionId = session,
                            channel = channel,
                            sourceSignal = signal,
                            occurrences = signalEvents.size,
                            evaluatedOccurrences = evaluated,
                            hits = hits,
                            misses = misses,
                            hitRate = if (evaluated == 0) null else hits.toDouble() / evaluated,
                            baselineHits = baselineHits,
                            baselineHitRate = if (evaluated == 0) null else baselineHits.toDouble() / evaluated,
                            lift = if (evaluated == 0) null else (hits - baselineHits).toDouble() / evaluated,
                            latestSourceDate = latest.first.date,
                            latestTargetDate = latest.second.date,
                            latestSourceAgeDays = latestAge,
                            distinctSourceCount = signalEvents.map { it.first.sourceId }.distinct().size
                        )
                    }
                }
            }
        }
        return results.sortedWith(
            compareBy<CrossMarketLeadLagEvidence> { it.targetMarket.name }
                .thenBy { it.sourceMarket.name }
                .thenBy { it.channel.name }
                .thenByDescending { it.evaluatedOccurrences }
                .thenByDescending { it.lift ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.sourceSignal }
        )
    }
}
