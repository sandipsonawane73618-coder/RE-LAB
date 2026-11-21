package com.relab.core

import kotlin.math.pow

/** Aggregates independent model rankings into a transparent consensus order. */
data class ConsensusModelRanking(val modelId: String, val rankedCandidates: List<String>)
data class ConsensusCandidate(
    val candidate: String, val participatingModels: Int, val averageRank: Double,
    val normalizedConsensusScore: Double, val rankDisagreement: Double
)
data class SmartConsensusReport(
    val abstained: Boolean, val minimumModelsRequired: Int, val modelCount: Int,
    val candidates: List<ConsensusCandidate>, val note: String
)

/** Rank aggregation is an ordering aid, not a probability estimator. */
object SmartConsensusRanking {
    fun rank(
        modelRankings: List<ConsensusModelRanking>,
        limit: Int = 10,
        minimumModels: Int = 2,
        rankDecay: Double = 0.85,
        minimumCandidateSupport: Int = minimumModels,
        minimumRelativeScore: Double = 0.0,
        maximumRankDisagreement: Double = Double.POSITIVE_INFINITY
    ): SmartConsensusReport {
        require(limit in 1..100) { "Consensus limit must be 1..100" }
        require(minimumModels >= 2) { "Consensus requires at least two independent models" }
        require(rankDecay in 0.0..1.0 && rankDecay.isFinite()) { "Rank decay must be finite in 0..1" }
        require(minimumCandidateSupport >= 1) { "Candidate support must be positive" }
        require(minimumRelativeScore in 0.0..100.0 && minimumRelativeScore.isFinite()) {
            "Minimum relative score must be finite in 0..100"
        }
        require(!maximumRankDisagreement.isNaN() && maximumRankDisagreement >= 0.0) {
            "Maximum rank disagreement must be non-negative"
        }
        require(modelRankings.map { it.modelId }.distinct().size == modelRankings.size) { "Model IDs must be unique" }
        require(modelRankings.all { it.modelId.isNotBlank() }) { "Model ID cannot be blank" }
        require(modelRankings.all { it.rankedCandidates.all { c -> c.isNotBlank() } }) { "Candidate IDs cannot be blank" }
        require(modelRankings.all { it.rankedCandidates.distinct().size == it.rankedCandidates.size }) {
            "Each model ranking must contain unique candidates"
        }

        fun abstain(note: String) = SmartConsensusReport(
            true, minimumModels, modelRankings.size, emptyList(), note
        )
        if (modelRankings.size < minimumModels) {
            return abstain("Abstained: only ${modelRankings.size} model(s) supplied; need $minimumModels independent rankings.")
        }

        val rankMap = mutableMapOf<String, MutableList<Int>>()
        modelRankings.forEach { ranking ->
            ranking.rankedCandidates.forEachIndexed { index, candidate ->
                rankMap.getOrPut(candidate) { mutableListOf() } += index + 1
            }
        }
        val raw = rankMap.map { (candidate, ranks) ->
            val avg = ranks.average()
            val disagreement = if (ranks.size < 2) 0.0 else
                kotlin.math.sqrt(ranks.map { (it - avg) * (it - avg) }.average())
            val score = ranks.sumOf { rankDecay.pow(it - 1) } / modelRankings.size
            ConsensusCandidate(candidate, ranks.size, avg, score, disagreement)
        }
        val maxScore = raw.maxOfOrNull { it.normalizedConsensusScore } ?: 0.0
        val scored = raw.map {
            it.copy(normalizedConsensusScore = if (maxScore <= 0.0) 0.0
                else it.normalizedConsensusScore / maxScore * 100.0)
        }
        val eligible = scored.filter {
            it.participatingModels >= minimumCandidateSupport &&
                it.normalizedConsensusScore >= minimumRelativeScore &&
                it.rankDisagreement <= maximumRankDisagreement
        }
        if (eligible.isEmpty()) {
            return abstain(
                "Abstained: no candidate met support >= $minimumCandidateSupport, " +
                    "relative score >= $minimumRelativeScore, and rank disagreement <= $maximumRankDisagreement. " +
                    "No probability or prediction is emitted."
            )
        }
        val ranked = eligible.sortedWith(
            compareByDescending<ConsensusCandidate> { it.normalizedConsensusScore }
                .thenByDescending { it.participatingModels }
                .thenBy { it.rankDisagreement }
                .thenBy { it.candidate }
        )
        return SmartConsensusReport(
            false, minimumModels, modelRankings.size, ranked.take(limit),
            "Rank-decay aggregation with abstention gates: support >= $minimumCandidateSupport, " +
                "relative score >= $minimumRelativeScore, rank disagreement <= $maximumRankDisagreement. " +
                "Scores are relative ranking indices, not probabilities; correlated models or shared data may reduce independence."
        )
    }
}
