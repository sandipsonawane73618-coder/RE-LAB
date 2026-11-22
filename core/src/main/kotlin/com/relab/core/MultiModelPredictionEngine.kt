package com.relab.core

import java.time.LocalDate

enum class PredictionChannel { OPEN_PANNA, CLOSE_PANNA, JODI }

data class ModelPrediction(val modelId: String, val candidates: List<String>, val note: String)

data class MultiModelPrediction(
    val market: Market, val sessionId: String, val targetDate: LocalDate,
    val channel: PredictionChannel, val modelPredictions: List<ModelPrediction>,
    val consensus: SmartConsensusReport, val trainingRows: Int, val note: String
)

/** A model receives only the already-filtered history preceding one target date. */
interface HistoricalCandidateModel {
    val id: String
    fun rank(history: List<ChartRecord>, channel: PredictionChannel, limit: Int): List<String>
}

/** Frequency baseline: exact observed values, with deterministic lexical tie-breaks. */
class ExactFrequencyModel : HistoricalCandidateModel {
    override val id = "exact-frequency"
    override fun rank(history: List<ChartRecord>, channel: PredictionChannel, limit: Int): List<String> =
        history.map { it.value(channel) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit).map { it.key }
}

/** Recency-weighted counts; newer observations contribute more, without using future rows. */
class RecencyWeightedModel : HistoricalCandidateModel {
    override val id = "recency-weighted-frequency"
    override fun rank(history: List<ChartRecord>, channel: PredictionChannel, limit: Int): List<String> {
        val scores = mutableMapOf<String, Double>()
        history.forEachIndexed { index, row ->
            val age = history.lastIndex - index
            val weight = 1.0 / (1.0 + age.toDouble() / 10.0)
            val value = row.value(channel)
            scores[value] = (scores[value] ?: 0.0) + weight
        }
        return scores.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit).map { it.key }
    }
}

/** First-order transition model: ranks outcomes observed after the latest exact value. */
class PreviousResultTransitionModel : HistoricalCandidateModel {
    override val id = "previous-result-transition"
    override fun rank(history: List<ChartRecord>, channel: PredictionChannel, limit: Int): List<String> {
        if (history.size < 2) return emptyList()
        val previous = history.last().value(channel)
        return history.zipWithNext().filter { (before, _) -> before.value(channel) == previous }
            .map { (_, after) -> after.value(channel) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit).map { it.key }
    }
}

private fun ChartRecord.value(channel: PredictionChannel): String = when (channel) {
    PredictionChannel.OPEN_PANNA -> openPanna
    PredictionChannel.CLOSE_PANNA -> closePanna
    PredictionChannel.JODI -> jodi
}

/**
 * Deterministic multi-model ranking. Consensus is a relative rank, never a calibrated
 * probability. Models share historical observations, so they are not independent evidence.
 */
class MultiModelPredictionEngine(
    private val models: List<HistoricalCandidateModel> = listOf(
        ExactFrequencyModel(), RecencyWeightedModel(), PreviousResultTransitionModel()
    ),
    private val minimumHistory: Int = 5,
    private val minimumModels: Int = 2
) {
    init {
        require(models.map { it.id }.distinct().size == models.size) { "Model IDs must be unique" }
        require(models.all { it.id.isNotBlank() }) { "Model IDs cannot be blank" }
        require(minimumHistory >= 1) { "Minimum history must be positive" }
        require(minimumModels >= 2) { "Consensus requires at least two models" }
    }

    fun predict(
        records: List<ChartRecord>, market: Market, sessionId: String, targetDate: LocalDate,
        channel: PredictionChannel, limit: Int = 10
    ): MultiModelPrediction {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(limit in 1..100) { "Candidate limit must be 1..100" }
        val history = records.asSequence()
            .filter { it.market == market && it.sessionId == sessionId && it.date.isBefore(targetDate) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
            .sortedBy { it.date }.toList()
        require(history.map { it.date }.distinct().size == history.size) {
            "Duplicate dates must be reconciled before multi-model prediction"
        }
        return predictFromEarlierHistory(history, market, sessionId, targetDate, channel, limit)
    }

    /**
     * Fast path for chronological evaluators: history must already be valid, sorted,
     * unique, and contain only rows for this market/session strictly before targetDate.
     * This avoids re-filtering, re-validating, and sorting the full dataset for every
     * walk-forward target.
     */
    internal fun predictFromEarlierHistory(
        history: List<ChartRecord>, market: Market, sessionId: String, targetDate: LocalDate,
        channel: PredictionChannel, limit: Int = 10
    ): MultiModelPrediction {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(limit in 1..100) { "Candidate limit must be 1..100" }
        // Keep empty model outputs visible: the evaluator must count their abstentions.
        val predictions = if (history.size < minimumHistory) emptyList() else models.map { model ->
            ModelPrediction(model.id, model.rank(history, channel, limit).distinct().take(limit),
                "Historical ranking only; not a probability.")
        }
        val consensus = SmartConsensusRanking.rank(
            predictions.map { ConsensusModelRanking(it.modelId, it.candidates) },
            limit = limit, minimumModels = minimumModels
        )
        val note = when {
            history.size < minimumHistory -> "Abstained: need $minimumHistory earlier valid rows; found ${history.size}."
            consensus.abstained -> "No consensus prediction. ${consensus.note}"
            else -> "Consensus is a relative rank, not probability. Models share historical data and are not statistically independent."
        }
        return MultiModelPrediction(market, sessionId, targetDate, channel, predictions,
            consensus, history.size, note)
    }
}
