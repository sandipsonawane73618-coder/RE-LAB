package com.relab.core

/** Separates exact Jodi matches from family-equivalent matches. */
enum class JodiFamilyOutcomeMatch { EXACT_HIT, FAMILY_HIT, MISS }

data class JodiFamilyOutcomeAudit(
    val actualJodi: String,
    val exactRank: Int?,
    val familyRank: Int?,
    val match: JodiFamilyOutcomeMatch,
    val topLimit: Int
) {
    val exactHitWithinTopLimit: Boolean
        get() = exactRank != null && exactRank <= topLimit

    val familyHitWithinTopLimit: Boolean
        get() = familyRank != null && familyRank <= topLimit
}

/**
 * Audits the canonical family/reversal equivalence already used by
 * FamilyMovementMath. Family hits are diagnostics and never exact hits.
 */
object JodiFamilyOutcomeAuditor {
    fun analyze(
        actualJodi: String,
        rankedCandidates: List<ReverseEngineeringJodiCandidate>,
        topLimit: Int = 10
    ): JodiFamilyOutcomeAudit {
        require(actualJodi.matches(Regex("\\d{2}"))) { "Actual Jodi must contain exactly two digits" }
        require(topLimit in 1..100) { "Top limit must be between 1 and 100" }
        require(rankedCandidates.all { it.jodi.matches(Regex("\\d{2}")) }) {
            "Predicted Jodi candidates must contain exactly two digits"
        }
        require(rankedCandidates.map { it.jodi }.distinct().size == rankedCandidates.size) {
            "Predicted Jodi candidates must be unique"
        }

        val exactRank = rankedCandidates.indexOfFirst { it.jodi == actualJodi }
            .takeIf { it >= 0 }?.plus(1)
        val actualFamily = FamilyMovementMath.key(actualJodi)
        val familyRank = rankedCandidates.indexOfFirst {
            FamilyMovementMath.key(it.jodi) == actualFamily
        }.takeIf { it >= 0 }?.plus(1)
        val match = when {
            exactRank != null && exactRank <= topLimit -> JodiFamilyOutcomeMatch.EXACT_HIT
            familyRank != null && familyRank <= topLimit -> JodiFamilyOutcomeMatch.FAMILY_HIT
            else -> JodiFamilyOutcomeMatch.MISS
        }
        return JodiFamilyOutcomeAudit(actualJodi, exactRank, familyRank, match, topLimit)
    }
}
