package com.relab.core

import java.time.Instant
import java.time.LocalDate

/** One realized, auditable binary outcome for a single pattern forecast. */
data class PatternDriftObservation(
    val observationId: String,
    val market: Market,
    val sessionId: String,
    val channel: PatternEvidenceChannel,
    val outcomeMode: PatternOutcomeMode,
    val pattern: String,
    val targetDate: LocalDate,
    val outcomePublishedAt: Instant,
    val hit: Boolean
) {
    init {
        require(observationId.isNotBlank()) { "Observation ID is required" }
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(pattern.isNotBlank()) { "Pattern is required" }
        require(!outcomePublishedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate().isBefore(targetDate)) {
            "Outcome cannot be published before its target date"
        }
    }
}

enum class PatternDriftStatus {
    DEGRADING,
    IMPROVING,
    NO_CONFIRMED_DRIFT,
    INSUFFICIENT_EVIDENCE
}

data class PatternDriftReport(
    val market: Market,
    val sessionId: String,
    val channel: PatternEvidenceChannel,
    val outcomeMode: PatternOutcomeMode,
    val pattern: String,
    val asOf: Instant,
    val baselineSupport: Int,
    val recentSupport: Int,
    val baselineHitRate: Double?,
    val recentHitRate: Double?,
    val baselineLower95: Double?,
    val baselineUpper95: Double?,
    val recentLower95: Double?,
    val recentUpper95: Double?,
    val status: PatternDriftStatus,
    val note: String
)

/**
 * Compares two adjacent, non-overlapping chronological windows.
 * Only outcomes published strictly before asOf are visible. Degradation is
 * confirmed only when the recent Wilson upper bound is below the baseline
 * Wilson lower bound; overlapping intervals are never called drift.
 */
object PatternDriftDetector {
    private data class Interval(val lower: Double, val upper: Double)

    fun evaluate(
        observations: List<PatternDriftObservation>,
        market: Market,
        sessionId: String,
        channel: PatternEvidenceChannel,
        outcomeMode: PatternOutcomeMode,
        pattern: String,
        asOf: Instant,
        baselineWindow: Int = 30,
        recentWindow: Int = 10,
        minimumWindowSupport: Int = 10
    ): PatternDriftReport {
        require(sessionId.isNotBlank() && pattern.isNotBlank()) { "Session and pattern are required" }
        require(baselineWindow >= 1 && recentWindow >= 1) { "Window sizes must be positive" }
        require(minimumWindowSupport >= 1) { "Minimum support must be positive" }
        val scoped = observations.filter {
            it.market == market && it.sessionId == sessionId &&
                it.channel == channel && it.outcomeMode == outcomeMode &&
                it.pattern == pattern && it.outcomePublishedAt.isBefore(asOf) &&
                it.targetDate.isBefore(asOf.atZone(java.time.ZoneOffset.UTC).toLocalDate())
        }
        require(scoped.map { it.observationId }.distinct().size == scoped.size) {
            "Duplicate observation IDs in requested scope"
        }
        val ordered = scoped.sortedWith(compareBy<PatternDriftObservation> { it.targetDate }
            .thenBy { it.outcomePublishedAt }.thenBy { it.observationId })
        val recent = ordered.takeLast(recentWindow)
        val baseline = ordered.dropLast(recent.size).takeLast(baselineWindow)
        val enough = baseline.size >= minimumWindowSupport && recent.size >= minimumWindowSupport
        val baselineHits = baseline.count { it.hit }
        val recentHits = recent.count { it.hit }
        val baselineInterval = if (enough) wilson(baselineHits, baseline.size) else null
        val recentInterval = if (enough) wilson(recentHits, recent.size) else null
        val status = when {
            !enough -> PatternDriftStatus.INSUFFICIENT_EVIDENCE
            recentInterval!!.upper < baselineInterval!!.lower -> PatternDriftStatus.DEGRADING
            recentInterval.lower > baselineInterval.upper -> PatternDriftStatus.IMPROVING
            else -> PatternDriftStatus.NO_CONFIRMED_DRIFT
        }
        return PatternDriftReport(
            market, sessionId, channel, outcomeMode, pattern, asOf,
            baseline.size, recent.size,
            baseline.takeIf { it.isNotEmpty() }?.let { baselineHits.toDouble() / it.size },
            recent.takeIf { it.isNotEmpty() }?.let { recentHits.toDouble() / it.size },
            baselineInterval?.lower, baselineInterval?.upper,
            recentInterval?.lower, recentInterval?.upper,
            status,
            when (status) {
                PatternDriftStatus.DEGRADING -> "Recent performance is statistically separated below its prior window; suppress this pattern pending fresh evidence."
                PatternDriftStatus.IMPROVING -> "Recent performance is statistically separated above its prior window; this is a drift signal, not a guarantee."
                PatternDriftStatus.NO_CONFIRMED_DRIFT -> "Adjacent windows do not show statistically separated performance; do not infer stability beyond this sample."
                PatternDriftStatus.INSUFFICIENT_EVIDENCE -> "Drift assessment abstained because both adjacent windows need at least $minimumWindowSupport published outcomes."
            }
        )
    }

    private fun wilson(successes: Int, total: Int): Interval {
        val z = 1.959963984540054
        val n = total.toDouble()
        val p = successes / n
        val denominator = 1.0 + z * z / n
        val center = (p + z * z / (2.0 * n)) / denominator
        val margin = z * kotlin.math.sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n)) / denominator
        return Interval((center - margin).coerceAtLeast(0.0), (center + margin).coerceAtMost(1.0))
    }
}

enum class PredictionSafetyStatus { ALLOW_WITH_CAUTION, ABSTAIN }

data class PredictionSafetyDecision(
    val status: PredictionSafetyStatus,
    val reasons: List<String>,
    val note: String
)

/** Final conservative gate; never converts a ranking score into a probability. */
object PredictionAbstentionPolicy {
    fun evaluate(
        candidate: AdaptivePatternCandidate,
        drift: PatternDriftReport,
        calibratedProbability: Double?,
        calibrationSupport: Int,
        minimumCalibrationSupport: Int = 20
    ): PredictionSafetyDecision {
        require(minimumCalibrationSupport >= 1) { "Minimum calibration support must be positive" }
        require(calibrationSupport >= 0) { "Calibration support cannot be negative" }
        require(calibratedProbability == null ||
            (calibratedProbability.isFinite() && calibratedProbability in 0.0..1.0)) {
            "Calibrated probability must be finite and between 0 and 1"
        }
        val reasons = mutableListOf<String>()
        if (candidate.status != AdaptiveCandidateStatus.SUPPORTED) {
            reasons += "Candidate has not cleared the independent baseline-separation rule."
        }
        if (drift.status == PatternDriftStatus.DEGRADING) {
            reasons += "Recent pattern performance shows confirmed degradation."
        }
        if (drift.status == PatternDriftStatus.INSUFFICIENT_EVIDENCE) {
            reasons += "Drift status is unknown because evidence is insufficient."
        }
        if (calibratedProbability == null) {
            reasons += "No held-out calibrated probability is available."
        }
        if (calibrationSupport < minimumCalibrationSupport) {
            reasons += "Calibration support is below the required minimum."
        }
        val abstain = reasons.isNotEmpty()
        return PredictionSafetyDecision(
            if (abstain) PredictionSafetyStatus.ABSTAIN else PredictionSafetyStatus.ALLOW_WITH_CAUTION,
            reasons,
            if (abstain) "Prediction withheld. Do not replace missing evidence with a score or guessed probability."
            else "All configured evidence gates passed. This remains a historical estimate, not a guarantee."
        )
    }
}
