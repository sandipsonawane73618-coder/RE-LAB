package com.relab.core

import java.time.LocalDate

data class WalkForwardModelMetrics(
    val modelId: String, val requestedTargets: Int, val evaluatedTargets: Int,
    val abstainedTargets: Int, val top1Hits: Int, val top3Hits: Int, val top5Hits: Int
) {
    fun top1Rate(): Double? = if (evaluatedTargets == 0) null else top1Hits.toDouble() / evaluatedTargets
    fun top3Rate(): Double? = if (evaluatedTargets == 0) null else top3Hits.toDouble() / evaluatedTargets
    fun top5Rate(): Double? = if (evaluatedTargets == 0) null else top5Hits.toDouble() / evaluatedTargets
}

data class MultiModelWalkForwardReport(
    val market: Market, val sessionId: String, val channel: PredictionChannel,
    val targetDateExclusive: LocalDate, val minimumHistory: Int,
    val models: List<WalkForwardModelMetrics>, val consensus: WalkForwardModelMetrics,
    val note: String
)

/**
 * Expanding-window evaluation. Every target is predicted using records strictly earlier
 * than that target; its actual value is scored before it can enter any later history.
 */
object MultiModelWalkForwardEvaluator {
    fun evaluate(
        records: List<ChartRecord>, market: Market, sessionId: String, channel: PredictionChannel,
        targetDateExclusive: LocalDate, minimumHistory: Int = 30, limit: Int = 10,
        engine: MultiModelPredictionEngine = MultiModelPredictionEngine(minimumHistory = minimumHistory)
    ): MultiModelWalkForwardReport {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(minimumHistory >= 1) { "Minimum history must be positive" }
        require(limit in 1..100) { "Candidate limit must be 1..100" }
        val eligible = records.filter {
            it.market == market && it.sessionId == sessionId && it.date.isBefore(targetDateExclusive) &&
                ChartMath.validate(it) is ValidationResult.Valid
        }.sortedBy { it.date }
        require(eligible.map { it.date }.distinct().size == eligible.size) {
            "Duplicate dates must be reconciled before walk-forward evaluation"
        }
        val modelCounts = mutableMapOf<String, IntArray>() // requested,evaluated,top1,top3,top5
        val consensusCounts = IntArray(5)
        eligible.forEachIndexed { index, target ->
            if (index >= minimumHistory) {
                val prediction = engine.predict(records, market, sessionId, target.date, channel, limit)
                val actual = target.channelValue(channel)
                prediction.modelPredictions.forEach { model ->
                    val counts = modelCounts.getOrPut(model.modelId) { IntArray(5) }
                    counts[0]++
                    if (model.candidates.isNotEmpty()) {
                        counts[1]++
                        if (actual in model.candidates.take(1)) counts[2]++
                        if (actual in model.candidates.take(3)) counts[3]++
                        if (actual in model.candidates.take(5)) counts[4]++
                    }
                }
                consensusCounts[0]++
                if (!prediction.consensus.abstained && prediction.consensus.candidates.isNotEmpty()) {
                    consensusCounts[1]++
                    val ranked = prediction.consensus.candidates.map { it.candidate }
                    if (actual in ranked.take(1)) consensusCounts[2]++
                    if (actual in ranked.take(3)) consensusCounts[3]++
                    if (actual in ranked.take(5)) consensusCounts[4]++
                }
            }
        }
        val modelMetrics = modelCounts.toSortedMap().map { (id, counts) ->
            WalkForwardModelMetrics(id, counts[0], counts[1], counts[0] - counts[1],
                counts[2], counts[3], counts[4])
        }
        val consensusMetrics = WalkForwardModelMetrics("smart-consensus",
            consensusCounts[0], consensusCounts[1], consensusCounts[0] - consensusCounts[1],
            consensusCounts[2], consensusCounts[3], consensusCounts[4])
        return MultiModelWalkForwardReport(
            market, sessionId, channel, targetDateExclusive, minimumHistory,
            modelMetrics, consensusMetrics,
            "Chronological expanding-window evaluation; held-out outcomes enter history only after scoring. Hit rates are coverage metrics, not probabilities."
        )
    }
}

private fun ChartRecord.channelValue(channel: PredictionChannel): String = when (channel) {
    PredictionChannel.OPEN_PANNA -> openPanna
    PredictionChannel.CLOSE_PANNA -> closePanna
    PredictionChannel.JODI -> jodi
}
