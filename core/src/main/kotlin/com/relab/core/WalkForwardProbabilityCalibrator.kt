package com.relab.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A probability forecast frozen before its target result. Outcomes are attached
 * separately and may only train later forecasts after they were published.
 */
data class CalibrationForecast(
    val forecastId: String,
    val market: Market,
    val sessionId: String,
    val channel: PatternEvidenceChannel,
    val outcomeMode: PatternOutcomeMode,
    val targetDate: LocalDate,
    val createdAt: Instant,
    val historyThroughDate: LocalDate,
    val rawProbability: Double,
    val outcome: Int? = null,
    val outcomePublishedAt: Instant? = null
) {
    init {
        require(forecastId.isNotBlank()) { "Forecast ID is required" }
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(rawProbability.isFinite() && rawProbability in 0.0..1.0) {
            "Raw probability must be finite and between 0 and 1"
        }
        require(historyThroughDate.isBefore(targetDate)) {
            "Forecast history must end strictly before its target date"
        }
        require(createdAt.atZone(ZoneOffset.UTC).toLocalDate().isBefore(targetDate)) {
            "Forecast must be created before its target date"
        }
        require((outcome == null) == (outcomePublishedAt == null)) {
            "Outcome and publication timestamp must be supplied together"
        }
        require(outcome == null || outcome == 0 || outcome == 1) {
            "Outcome must be binary 0/1"
        }
        if (outcomePublishedAt != null) {
            require(outcomePublishedAt.isAfter(createdAt)) {
                "Outcome cannot be published before the forecast"
            }
            require(outcomePublishedAt.atZone(ZoneOffset.UTC).toLocalDate().isAfter(targetDate)) {
                "Outcome must be published after the target date"
            }
        }
    }
}

data class CalibratedForecastResult(
    val forecastId: String,
    val targetDate: LocalDate,
    val rawProbability: Double,
    val calibratedProbability: Double?,
    val calibrationSupport: Int,
    val actualOutcome: Int?,
    val abstained: Boolean
)

data class WalkForwardCalibrationReport(
    val market: Market,
    val sessionId: String,
    val channel: PatternEvidenceChannel,
    val outcomeMode: PatternOutcomeMode,
    val evaluationStart: LocalDate,
    val evaluationEndExclusive: LocalDate,
    val inputForecasts: Int,
    val inScopeForecasts: Int,
    val evaluatedForecasts: Int,
    val calibratedForecasts: Int,
    val abstentions: Int,
    val rawMetrics: ProbabilityCalibrationReport?,
    val calibratedMetrics: ProbabilityCalibrationReport?,
    val forecasts: List<CalibratedForecastResult>,
    val note: String
)

/**
 * Expanding-window, strictly chronological calibration.
 *
 * Each forecast is calibrated only from earlier forecasts in the same
 * market/session/channel/outcome-mode whose results were published before the
 * current forecast was created. A raw ranking score must never be supplied as
 * a probability; this engine accepts only explicit probability forecasts.
 */
object WalkForwardProbabilityCalibrator {
    fun evaluate(
        forecasts: List<CalibrationForecast>,
        market: Market,
        sessionId: String,
        channel: PatternEvidenceChannel,
        outcomeMode: PatternOutcomeMode,
        evaluationStart: LocalDate,
        evaluationEndExclusive: LocalDate,
        binCount: Int = 10,
        minimumCalibrationSamples: Int = 20,
        laplaceAlpha: Double = 1.0
    ): WalkForwardCalibrationReport {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(evaluationStart.isBefore(evaluationEndExclusive)) {
            "Evaluation end must be after evaluation start"
        }
        require(binCount in 1..100) { "Bin count must be between 1 and 100" }
        require(minimumCalibrationSamples >= 1) { "Minimum calibration support must be positive" }
        require(laplaceAlpha > 0.0 && laplaceAlpha.isFinite()) {
            "Laplace alpha must be positive and finite"
        }
        require(forecasts.map { it.forecastId }.distinct().size == forecasts.size) {
            "Duplicate forecast IDs are not allowed"
        }

        val scoped = forecasts.filter {
            it.market == market && it.sessionId == sessionId &&
                it.channel == channel && it.outcomeMode == outcomeMode
        }.sortedWith(compareBy<CalibrationForecast> { it.targetDate }.thenBy { it.createdAt }.thenBy { it.forecastId })

        val evaluation = scoped.filter {
            !it.targetDate.isBefore(evaluationStart) && it.targetDate.isBefore(evaluationEndExclusive) &&
                it.outcome != null
        }
        val results = evaluation.map { current ->
            // No same-date training, no unpublished result, and no result that
            // became available after this forecast was created.
            val prior = scoped.filter { historical ->
                historical.targetDate.isBefore(current.targetDate) &&
                    historical.outcome != null &&
                    historical.outcomePublishedAt!!.isBefore(current.createdAt)
            }
            val bin = probabilityBin(current.rawProbability, binCount)
            val binRows = prior.filter { probabilityBin(it.rawProbability, binCount) == bin }
            val calibrated = if (binRows.size >= minimumCalibrationSamples) {
                val hits = binRows.count { it.outcome == 1 }
                (hits + laplaceAlpha) / (binRows.size + 2.0 * laplaceAlpha)
            } else null
            CalibratedForecastResult(
                forecastId = current.forecastId,
                targetDate = current.targetDate,
                rawProbability = current.rawProbability,
                calibratedProbability = calibrated,
                calibrationSupport = binRows.size,
                actualOutcome = current.outcome,
                abstained = calibrated == null
            )
        }

        val scored = results.filter { it.actualOutcome != null }
        val rawMetrics = if (scored.isEmpty()) null else ProbabilityCalibration.evaluate(
            scored.map { it.rawProbability }, scored.map { it.actualOutcome!! }, binCount
        )
        val calibratedRows = scored.filter { it.calibratedProbability != null }
        val calibratedMetrics = if (calibratedRows.isEmpty()) null else ProbabilityCalibration.evaluate(
            calibratedRows.map { it.calibratedProbability!! },
            calibratedRows.map { it.actualOutcome!! },
            binCount
        )
        val note = if (calibratedRows.isEmpty()) {
            "Calibration abstained: no evaluation forecast had enough strictly prior, published, same-scope calibration samples. Raw metrics are diagnostics only."
        } else {
            "Calibrated metrics use held-out forecasts only. Every calibration lookup used earlier target dates and outcomes published before forecast creation; scope is isolated to $market/$sessionId/$channel/$outcomeMode. No future data or cross-market pooling."
        }
        return WalkForwardCalibrationReport(
            market, sessionId, channel, outcomeMode, evaluationStart, evaluationEndExclusive,
            forecasts.size, scoped.size, scored.size, calibratedRows.size,
            scored.size - calibratedRows.size, rawMetrics, calibratedMetrics, results, note
        )
    }

    private fun probabilityBin(probability: Double, binCount: Int): Int =
        (probability * binCount).toInt().coerceIn(0, binCount - 1)
}
