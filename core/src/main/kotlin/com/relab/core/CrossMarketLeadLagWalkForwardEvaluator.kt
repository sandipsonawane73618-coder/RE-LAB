package com.relab.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

/** Walk-forward result for one source/target market pair and chart channel. */
data class CrossMarketWalkForwardReport(
    val sourceMarket: Market,
    val targetMarket: Market,
    val sessionId: String,
    val channel: CrossMarketEvidenceChannel,
    val requestedTargets: Int,
    val evaluatedTargets: Int,
    val abstainedTargets: Int,
    val hits: Int,
    val misses: Int,
    val hitRate: Double?,
    val confidence95Lower: Double?,
    val confidence95Upper: Double?,
    val baselineHits: Int,
    val baselineHitRate: Double?,
    val lift: Double?,
    val noEarlierSourceTargets: Int,
    val staleSourceTargets: Int,
    val insufficientSignalHistoryTargets: Int,
    val distinctSourceCount: Int
)

/**
 * Strict chronological evaluation of cross-market lead/lag signals.
 *
 * Every target row is scored before it is added to target history. Its source
 * signal comes from the latest source-market row strictly earlier than that
 * target date and within maxSourceAgeDays. A signal is eligible only after
 * minSignalOccurrences earlier target outcomes have been observed for it.
 * The matched baseline is the modal outcome across all earlier target rows.
 *
 * Confidence bounds are 95% Wilson intervals. These quantify sampling
 * uncertainty only; they do not establish causality or independent sources.
 */
object CrossMarketLeadLagWalkForwardEvaluator {
    fun evaluate(
        records: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        maxSourceAgeDays: Long = 7,
        minSignalOccurrences: Int = 5
    ): List<CrossMarketWalkForwardReport> {
        require(maxSourceAgeDays >= 1) { "Maximum source age must be positive" }
        require(minSignalOccurrences >= 1) { "Minimum signal history must be positive" }

        val valid = records.asSequence()
            .filter { it.date.isBefore(targetDateExclusive) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
            .sortedWith(compareBy<ChartRecord> { it.sessionId }
                .thenBy { it.market.name }.thenBy { it.date })
            .toList()
        valid.groupBy { Triple(it.market, it.sessionId, it.date) }.forEach { (key, rows) ->
            require(rows.size == 1) {
                "Duplicate rows for ${key.first}/${key.second} on ${key.third} must be reconciled before walk-forward evaluation"
            }
        }

        fun channelValue(row: ChartRecord, channel: CrossMarketEvidenceChannel): String =
            when (channel) {
                CrossMarketEvidenceChannel.OPEN_PANNA_FAMILY -> row.openPanna
                    .map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
                CrossMarketEvidenceChannel.CLOSE_PANNA_FAMILY -> row.closePanna
                    .map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
                CrossMarketEvidenceChannel.JODI -> row.jodi
            }

        fun mode(values: List<String>): String = values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key

        fun wilson(hits: Int, total: Int): Pair<Double?, Double?> {
            if (total == 0) return null to null
            val z = 1.959963984540054
            val p = hits.toDouble() / total
            val denominator = 1.0 + z * z / total
            val center = (p + z * z / (2.0 * total)) / denominator
            val margin = z * sqrt((p * (1.0 - p) / total) + z * z / (4.0 * total * total)) / denominator
            return (center - margin).coerceAtLeast(0.0) to (center + margin).coerceAtMost(1.0)
        }

        val reports = mutableListOf<CrossMarketWalkForwardReport>()
        val sessions = valid.map { it.sessionId }.distinct().sorted()
        for (session in sessions) {
            val sessionRows = valid.filter { it.sessionId == session }
            val markets = sessionRows.map { it.market }.distinct().sortedBy { it.name }
            for (sourceMarket in markets) for (targetMarket in markets) {
                if (sourceMarket == targetMarket) continue
                val sourceRows = sessionRows.filter { it.market == sourceMarket }.sortedBy { it.date }
                val targetRows = sessionRows.filter { it.market == targetMarket }.sortedBy { it.date }
                if (targetRows.isEmpty()) continue
                for (channel in CrossMarketEvidenceChannel.entries) {
                    var sourceIndex = -1
                    val priorTargetOutcomes = mutableListOf<String>()
                    val priorOutcomesBySignal = mutableMapOf<String, MutableList<String>>()
                    val usedSourceIds = linkedSetOf<String>()
                    var hits = 0
                    var baselineHits = 0
                    var evaluated = 0
                    var noSource = 0
                    var stale = 0
                    var insufficient = 0
                    targetRows.forEach { target ->
                        while (sourceIndex + 1 < sourceRows.size &&
                            sourceRows[sourceIndex + 1].date.isBefore(target.date)
                        ) sourceIndex++
                        if (sourceIndex < 0) {
                            noSource++
                        } else {
                            val source = sourceRows[sourceIndex]
                            val age = ChronoUnit.DAYS.between(source.date, target.date)
                            if (age !in 1..maxSourceAgeDays) {
                                stale++
                            } else {
                                val signal = channelValue(source, channel)
                                val matchingHistory = priorOutcomesBySignal[signal].orEmpty()
                                if (matchingHistory.size < minSignalOccurrences || priorTargetOutcomes.isEmpty()) {
                                    insufficient++
                                } else {
                                    val predicted = mode(matchingHistory)
                                    val baseline = mode(priorTargetOutcomes)
                                    val actual = channelValue(target, channel)
                                    if (predicted == actual) hits++
                                    if (baseline == actual) baselineHits++
                                    evaluated++
                                    usedSourceIds += source.sourceId
                                }
                            }
                        }
                        // The held-out target is added only after its prediction is scored.
                        if (sourceIndex >= 0) {
                            val source = sourceRows[sourceIndex]
                            val age = ChronoUnit.DAYS.between(source.date, target.date)
                            if (age in 1..maxSourceAgeDays) {
                                val signal = channelValue(source, channel)
                                priorOutcomesBySignal.getOrPut(signal) { mutableListOf() }
                                    .add(channelValue(target, channel))
                            }
                        }
                        priorTargetOutcomes += channelValue(target, channel)
                    }
                    val abstained = targetRows.size - evaluated
                    val misses = evaluated - hits
                    val (lower, upper) = wilson(hits, evaluated)
                    reports += CrossMarketWalkForwardReport(
                        sourceMarket, targetMarket, session, channel,
                        requestedTargets = targetRows.size,
                        evaluatedTargets = evaluated,
                        abstainedTargets = abstained,
                        hits = hits,
                        misses = misses,
                        hitRate = if (evaluated == 0) null else hits.toDouble() / evaluated,
                        confidence95Lower = lower,
                        confidence95Upper = upper,
                        baselineHits = baselineHits,
                        baselineHitRate = if (evaluated == 0) null else baselineHits.toDouble() / evaluated,
                        lift = if (evaluated == 0) null else (hits - baselineHits).toDouble() / evaluated,
                        noEarlierSourceTargets = noSource,
                        staleSourceTargets = stale,
                        insufficientSignalHistoryTargets = insufficient,
                        distinctSourceCount = usedSourceIds.size
                    )
                }
            }
        }
        return reports.sortedWith(
            compareBy<CrossMarketWalkForwardReport> { it.targetMarket.name }
                .thenBy { it.sourceMarket.name }
                .thenBy { it.channel.name }
        )
    }
}
