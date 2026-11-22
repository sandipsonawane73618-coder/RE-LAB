package com.relab.core

/** Compact Open Ank shortlist based on support across a market/session's ranked Open Pannas. */
data class OpenAnkCandidate(val ank: Int, val score: Double, val supportingPannas: Int)

/**
 * Aggregates the strongest Panna evidence for each Open Ank. Scores are relative
 * ranking indices, not probabilities or accuracy guarantees.
 */
object OpenAnkRanking {
    fun rank(
        openPannaCandidates: List<ReverseEngineeringCandidate>,
        limit: Int = 5,
        maxPannasPerAnk: Int = 5
    ): List<OpenAnkCandidate> {
        require(limit in 1..10) { "Open Ank limit must be between 1 and 10" }
        require(maxPannasPerAnk >= 1) { "Supporting Panna limit must be positive" }
        require(openPannaCandidates.all { it.ank in 0..9 }) { "Ank must be a digit from 0 to 9" }
        require(openPannaCandidates.all { it.score.isFinite() && it.score >= 0.0 }) {
            "Panna evidence scores must be finite and non-negative"
        }
        return openPannaCandidates.groupBy { it.ank }.map { (ank, candidates) ->
            val strongest = candidates.sortedWith(
                compareByDescending<ReverseEngineeringCandidate> { it.score }.thenBy { it.panna }
            ).take(maxPannasPerAnk)
            OpenAnkCandidate(ank, strongest.sumOf { it.score }, candidates.size)
        }.sortedWith(
            compareByDescending<OpenAnkCandidate> { it.score }
                .thenByDescending { it.supportingPannas }
                .thenBy { it.ank }
        ).take(limit)
    }
}
