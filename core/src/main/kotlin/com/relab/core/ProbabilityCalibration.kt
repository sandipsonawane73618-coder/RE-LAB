package com.relab.core

import kotlin.math.ln

data class ProbabilityCalibrationBin(
    val lowerInclusive: Double,
    val upperExclusive: Double,
    val sampleCount: Int,
    val meanPredictedProbability: Double,
    val observedRate: Double
)

data class ProbabilityCalibrationReport(
    val samples: Int,
    val brierScore: Double,
    val logLoss: Double,
    val expectedCalibrationError: Double,
    val bins: List<ProbabilityCalibrationBin>
)

/**
 * Offline binary probability diagnostics. A probability must be a true model
 * probability (not an arbitrary evidence score). Outcomes are 1 for hit and 0
 * for miss. Metrics are diagnostics, not a promise of future performance.
 */
object ProbabilityCalibration {
    fun evaluate(
        probabilities: List<Double>,
        outcomes: List<Int>,
        binCount: Int = 10,
        epsilon: Double = 1e-15
    ): ProbabilityCalibrationReport {
        require(probabilities.isNotEmpty()) { "At least one probability is required" }
        require(probabilities.size == outcomes.size) { "Probability and outcome counts must match" }
        require(binCount in 1..100) { "Bin count must be between 1 and 100" }
        require(epsilon > 0.0 && epsilon < 0.5 && epsilon.isFinite()) { "Epsilon must be in (0, 0.5)" }
        require(probabilities.all { it.isFinite() && it in 0.0..1.0 }) {
            "Probabilities must be finite and between 0 and 1"
        }
        require(outcomes.all { it == 0 || it == 1 }) { "Outcomes must be binary 0/1" }

        val brier = probabilities.indices.sumOf { index ->
            val error = probabilities[index] - outcomes[index]
            error * error
        } / probabilities.size
        val logLoss = probabilities.indices.sumOf { index ->
            val p = probabilities[index].coerceIn(epsilon, 1.0 - epsilon)
            if (outcomes[index] == 1) -ln(p) else -ln(1.0 - p)
        } / probabilities.size

        val bins = (0 until binCount).mapNotNull { bin ->
            val lower = bin.toDouble() / binCount
            val upper = (bin + 1).toDouble() / binCount
            val indices = probabilities.indices.filter { index ->
                probabilities[index] >= lower &&
                    (probabilities[index] < upper || bin == binCount - 1 && probabilities[index] == 1.0)
            }
            if (indices.isEmpty()) null else {
                val meanPredicted = indices.sumOf { probabilities[it] } / indices.size
                val observed = indices.sumOf { outcomes[it] } .toDouble() / indices.size
                ProbabilityCalibrationBin(lower, upper, indices.size, meanPredicted, observed)
            }
        }
        val ece = bins.sumOf { bin ->
            bin.sampleCount.toDouble() / probabilities.size *
                kotlin.math.abs(bin.meanPredictedProbability - bin.observedRate)
        }
        return ProbabilityCalibrationReport(probabilities.size, brier, logLoss, ece, bins)
    }
}
