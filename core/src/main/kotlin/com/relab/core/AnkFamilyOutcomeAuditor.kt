package com.relab.core

/** Separates exact ank matches from same-family matches without changing prediction ranking. */
enum class AnkOutcomeMatch {
    EXACT_HIT,
    FAMILY_HIT,
    MISS
}

data class AnkFamilyOutcomeAudit(
    val predictedAnk: Int,
    val actualAnk: Int,
    val predictedFamily: Int,
    val actualFamily: Int,
    val match: AnkOutcomeMatch,
    val exactRank: Int?,
    val familyRank: Int?,
    val topLimit: Int
) {
    val exactHitWithinTopLimit: Boolean
        get() = exactRank != null && exactRank <= topLimit

    val familyHitWithinTopLimit: Boolean
        get() = familyRank != null && familyRank <= topLimit
}

/**
 * Uses the repository's locked ChartMath.family mapping.
 * A family hit is diagnostic only and is never counted as an exact hit.
 */
object AnkFamilyOutcomeAuditor {
    fun analyze(
        predictedAnk: Int,
        actualAnk: Int,
        rankedCandidates: List<Int>,
        topLimit: Int = 10
    ): AnkFamilyOutcomeAudit {
        require(predictedAnk in 0..9) { "Predicted ank must be a single digit" }
        require(actualAnk in 0..9) { "Actual ank must be a single digit" }
        require(rankedCandidates.all { it in 0..9 }) { "Candidates must be single digits" }
        require(topLimit in 1..100) { "Top limit must be between 1 and 100" }

        val exactRank = rankedCandidates.indexOf(actualAnk).takeIf { it >= 0 }?.plus(1)
        val predictedFamily = ChartMath.family(predictedAnk)
        val actualFamily = ChartMath.family(actualAnk)
        val familyRank = rankedCandidates.indexOfFirst { ChartMath.family(it) == actualFamily }
            .takeIf { it >= 0 }?.plus(1)
        val match = when {
            predictedAnk == actualAnk -> AnkOutcomeMatch.EXACT_HIT
            predictedFamily == actualFamily -> AnkOutcomeMatch.FAMILY_HIT
            else -> AnkOutcomeMatch.MISS
        }
        return AnkFamilyOutcomeAudit(
            predictedAnk, actualAnk, predictedFamily, actualFamily,
            match, exactRank, familyRank, topLimit
        )
    }
}
