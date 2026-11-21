package com.relab.core

/**
 * Descriptive nearest-candidate audit for a realized panna.
 *
 * This does not redefine a miss as a hit: only an exact three-digit match is exact.
 * Family and positional similarities are reported separately for research diagnostics.
 */
data class PannaNearHitComparison(
    val panna: String,
    val rank: Int,
    val exactHit: Boolean,
    val familySequenceMatch: Boolean,
    val matchingFamilyPositions: Int,
    val matchingDigitPositions: Int,
    val absoluteDigitDistance: Int,
    val reverseExactMatch: Boolean,
    val samePannaType: Boolean,
    val ankDistance: Int
)

data class PannaNearHitAuditReport(
    val actualPanna: String,
    val exactRank: Int?,
    val nearestCandidates: List<PannaNearHitComparison>,
    val exactHitCount: Int,
    val familySequenceHitCount: Int,
    val diagnosticNote: String
) {
    val hasExactHit: Boolean get() = exactRank != null
}

object PannaNearHitAuditor {
    fun analyze(
        actualPanna: String,
        rankedCandidates: List<ReverseEngineeringCandidate>,
        nearestLimit: Int = 5
    ): PannaNearHitAuditReport {
        require(actualPanna.matches(Regex("\\d{3}"))) { "Actual panna must contain exactly three digits" }
        require(nearestLimit in 1..20) { "Nearest limit must be between 1 and 20" }
        rankedCandidates.forEach {
            require(it.panna.matches(Regex("\\d{3}"))) { "Candidate panna must contain exactly three digits" }
            require(it.score.isFinite()) { "Candidate score must be finite" }
        }
        require(rankedCandidates.map { it.panna }.distinct().size == rankedCandidates.size) {
            "Candidate panna values must be unique"
        }

        val actualFamily = family(actualPanna)
        val actualAnk = ank(actualPanna)
        val ordered = rankedCandidates.sortedWith(
            compareByDescending<ReverseEngineeringCandidate> { it.score }.thenBy { it.panna }
        )
        val exactRank = ordered.indexOfFirst { it.panna == actualPanna }
            .takeIf { it >= 0 }?.plus(1)

        val comparisons = ordered.mapIndexed { index, candidate ->
            val panna = candidate.panna
            val candidateFamily = family(panna)
            PannaNearHitComparison(
                panna = panna,
                rank = index + 1,
                exactHit = panna == actualPanna,
                familySequenceMatch = candidateFamily == actualFamily,
                matchingFamilyPositions = (0..2).count { candidateFamily[it] == actualFamily[it] },
                matchingDigitPositions = (0..2).count { panna[it] == actualPanna[it] },
                absoluteDigitDistance = (0..2).sumOf {
                    kotlin.math.abs(panna[it].digitToInt() - actualPanna[it].digitToInt())
                },
                reverseExactMatch = panna == actualPanna.reversed(),
                samePannaType = ChartMath.pannaType(panna) == ChartMath.pannaType(actualPanna),
                ankDistance = kotlin.math.min(
                    kotlin.math.abs(ank(panna) - actualAnk),
                    10 - kotlin.math.abs(ank(panna) - actualAnk)
                )
            )
        }.sortedWith(
            compareBy<PannaNearHitComparison> { if (it.exactHit) 0 else 1 }
                .thenByDescending { it.matchingFamilyPositions }
                .thenByDescending { it.matchingDigitPositions }
                .thenBy { it.absoluteDigitDistance }
                .thenBy { it.ankDistance }
                .thenBy { it.rank }
        ).take(nearestLimit)

        return PannaNearHitAuditReport(
            actualPanna = actualPanna,
            exactRank = exactRank,
            nearestCandidates = comparisons,
            exactHitCount = if (exactRank == null) 0 else 1,
            familySequenceHitCount = ordered.count { family(it.panna) == actualFamily },
            diagnosticNote = "Exact, family, positional, reverse, panna-type and ank proximity are separate diagnostics. Near/family matches are not exact hits, probabilities, or evidence of predictive advantage."
        )
    }

    private fun family(panna: String): String =
        panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")

    private fun ank(panna: String): Int = panna.sumOf { it.digitToInt() } % 10
}
