package com.relab.core

/** Distinguishes a missing candidate from a candidate that ranked outside Top 10. */
enum class PredictionMissType {
    TOP_10_HIT,
    CANDIDATE_MISS,
    RANKING_MISS
}

object PredictionMissClassifier {
    fun panna(exactRank: Int?): PredictionMissType = when {
        exactRank == null -> PredictionMissType.CANDIDATE_MISS
        exactRank <= 10 -> PredictionMissType.TOP_10_HIT
        else -> PredictionMissType.RANKING_MISS
    }

    fun combination(
        exactRank: Int?,
        primaryCandidatePresent: Boolean,
        secondaryCandidatePresent: Boolean
    ): PredictionMissType = when {
        !primaryCandidatePresent || !secondaryCandidatePresent -> PredictionMissType.CANDIDATE_MISS
        exactRank == null || exactRank > 10 -> PredictionMissType.RANKING_MISS
        else -> PredictionMissType.TOP_10_HIT
    }
}
