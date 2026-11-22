package com.relab.core

import kotlin.random.Random

/**
 * Deterministic permutation challenge for checking whether a fixed sequence of
 * predictions aligns with observed outcomes more often than shuffled outcomes.
 *
 * The null keeps the observed outcome distribution intact but breaks its
 * alignment with the predictions. This is a diagnostic, not proof of causality
 * and not a future-probability estimate.
 */
data class RandomnessChallengeResult(
    val observations: Int,
    val observedHits: Int,
    val expectedRandomHits: Double,
    val permutations: Int,
    val pValue: Double,
    val seed: Long
)

object RandomnessChallenge {
    fun evaluate(
        predictions: List<String>,
        actuals: List<String>,
        permutations: Int = 2_000,
        seed: Long = 0x52454C41424L
    ): RandomnessChallengeResult {
        require(predictions.isNotEmpty()) { "At least one prediction is required" }
        require(predictions.size == actuals.size) { "Prediction and outcome counts must match" }
        require(predictions.none(String::isBlank) && actuals.none(String::isBlank)) {
            "Predictions and outcomes must be non-blank"
        }
        require(permutations in 100..100_000) { "Permutation count must be between 100 and 100000" }

        val observedHits = predictions.indices.count { predictions[it] == actuals[it] }
        val random = Random(seed)
        val shuffled = actuals.toMutableList()
        var atLeastAsGood = 0
        var totalHits = 0L

        repeat(permutations) {
            // Fisher-Yates shuffle preserves the exact marginal outcome counts.
            for (index in shuffled.lastIndex downTo 1) {
                val swapIndex = random.nextInt(index + 1)
                val value = shuffled[index]
                shuffled[index] = shuffled[swapIndex]
                shuffled[swapIndex] = value
            }
            val hits = predictions.indices.count { predictions[it] == shuffled[it] }
            totalHits += hits
            if (hits >= observedHits) atLeastAsGood++
        }

        // Plus-one correction prevents a zero Monte Carlo p-value.
        val pValue = (atLeastAsGood + 1.0) / (permutations + 1.0)
        return RandomnessChallengeResult(
            observations = predictions.size,
            observedHits = observedHits,
            expectedRandomHits = totalHits.toDouble() / permutations,
            permutations = permutations,
            pValue = pValue,
            seed = seed
        )
    }
}

/** Benjamini-Hochberg false-discovery-rate adjustment for a family of tests. */
object FalseDiscoveryRate {
    /**
     * Returns adjusted q-values keyed by the original input key. Null p-values
     * are omitted; duplicate keys are rejected to avoid silently losing tests.
     */
    fun benjaminiHochberg(pValues: Map<String, Double?>): Map<String, Double> {
        require(pValues.keys.none(String::isBlank)) { "Test keys must be non-blank" }
        require(pValues.values.filterNotNull().all { it.isFinite() && it in 0.0..1.0 }) {
            "P-values must be finite and between 0 and 1"
        }
        val ranked = pValues.mapNotNull { (key, value) -> value?.let { Triple(key, it, 0) } }
            .sortedWith(compareBy<Triple<String, Double, Int>> { it.second }.thenBy { it.first })
        if (ranked.isEmpty()) return emptyMap()

        val count = ranked.size
        val adjusted = mutableMapOf<String, Double>()
        var runningMinimum = 1.0
        for (index in ranked.indices.reversed()) {
            val (key, pValue) = ranked[index]
            val rank = index + 1
            val qValue = (pValue * count / rank).coerceIn(0.0, 1.0)
            runningMinimum = minOf(runningMinimum, qValue)
            adjusted[key] = runningMinimum
        }
        return adjusted
    }
}
