package com.relab.core

/**
 * Outcome audit for Panna and both directional Half-Sangam using only candidate
 * lists frozen in the prediction snapshot. This diagnostic never regenerates ranks
 * from chart history and does not modify production weights.
 */
data class FrozenPannaHalfSangamAudit(
    val snapshotKey: String,
    val market: Market,
    val sessionId: String,
    val targetDate: java.time.LocalDate,
    val openPannaExactRank: Int?,
    val closePannaExactRank: Int?,
    val openPannaCloseAnkExactRank: Int?,
    val openAnkClosePannaExactRank: Int?,
    val candidateLimit: Int
) {
    val openPannaExactTop10: Boolean get() = openPannaExactRank?.let { it <= 10 } == true
    val openPannaExactTop100: Boolean get() = openPannaExactRank?.let { it <= 100 } == true
    val closePannaExactTop10: Boolean get() = closePannaExactRank?.let { it <= 10 } == true
    val closePannaExactTop100: Boolean get() = closePannaExactRank?.let { it <= 100 } == true
    val openPannaCloseAnkExactTop10: Boolean get() = openPannaCloseAnkExactRank?.let { it <= 10 } == true
    val openPannaCloseAnkExactTop100: Boolean get() = openPannaCloseAnkExactRank?.let { it <= 100 } == true
    val openAnkClosePannaExactTop10: Boolean get() = openAnkClosePannaExactRank?.let { it <= 10 } == true
    val openAnkClosePannaExactTop100: Boolean get() = openAnkClosePannaExactRank?.let { it <= 100 } == true
}

object FrozenPannaHalfSangamAuditor {
    private data class HalfCandidate(val panna: String, val ank: Int, val score: Double)

    fun analyze(
        snapshot: PredictionSnapshot,
        outcome: PredictionSnapshotOutcome,
        openCandidates: List<ReverseEngineeringCandidate>,
        closeCandidates: List<ReverseEngineeringCandidate>,
        candidateLimit: Int = 100
    ): FrozenPannaHalfSangamAudit {
        require(outcome.snapshotKey == snapshot.snapshotKey) { "Outcome key does not match frozen prediction snapshot" }
        require(!outcome.publishedAt.isBefore(snapshot.createdAt)) { "Published outcome timestamp cannot precede prediction snapshot creation" }
        require(candidateLimit in 10..100) { "Candidate limit must be between 10 and 100" }
        validate(openCandidates, "open")
        validate(closeCandidates, "close")
        val parts = outcome.actualResult.split('-')
        require(parts.size == 3 && parts[0].matches(Regex("\\d{3}")) &&
            parts[1].matches(Regex("\\d{2}")) && parts[2].matches(Regex("\\d{3}"))) {
            "Published result must use openPanna-jodi-closePanna format"
        }
        val openRanked = ordered(openCandidates)
        val closeRanked = ordered(closeCandidates)
        val actualOpen = parts[0]
        val actualClose = parts[2]
        val actualOpenAnk = ank(actualOpen)
        val actualCloseAnk = ank(actualClose)
        val openHalf = halfRanked(openRanked.take(candidateLimit), closeRanked.take(candidateLimit))
        val closeHalf = halfRanked(closeRanked.take(candidateLimit), openRanked.take(candidateLimit))
        val openPannaCloseAnkRank = openHalf.indexOfFirst {
            it.panna == actualOpen && it.ank == actualCloseAnk
        }.takeIf { it >= 0 }?.plus(1)
        val openAnkClosePannaRank = closeHalf.indexOfFirst {
            it.panna == actualClose && it.ank == actualOpenAnk
        }.takeIf { it >= 0 }?.plus(1)
        return FrozenPannaHalfSangamAudit(
            snapshotKey = snapshot.snapshotKey,
            market = snapshot.market,
            sessionId = snapshot.sessionId,
            targetDate = snapshot.targetDate,
            openPannaExactRank = openRanked.indexOfFirst { it.panna == actualOpen }
                .takeIf { it >= 0 }?.plus(1),
            closePannaExactRank = closeRanked.indexOfFirst { it.panna == actualClose }
                .takeIf { it >= 0 }?.plus(1),
            openPannaCloseAnkExactRank = openPannaCloseAnkRank,
            openAnkClosePannaExactRank = openAnkClosePannaRank,
            candidateLimit = candidateLimit
        )
    }

    private fun ordered(candidates: List<ReverseEngineeringCandidate>) =
        candidates.sortedWith(compareByDescending<ReverseEngineeringCandidate> { it.score }.thenBy { it.panna })

    private fun halfRanked(
        pannaCandidates: List<ReverseEngineeringCandidate>,
        oppositeSideCandidates: List<ReverseEngineeringCandidate>
    ): List<HalfCandidate> {
        val ankScores = oppositeSideCandidates.groupBy { ank(it.panna) }
            .mapValues { (_, rows) -> rows.sumOf { it.score } }
        val maxScore = ankScores.values.maxOrNull()?.coerceAtLeast(1e-12) ?: 1.0
        return pannaCandidates.flatMap { panna ->
            ankScores.map { (ank, score) -> HalfCandidate(panna.panna, ank, panna.score * score / maxScore) }
        }.sortedWith(compareByDescending<HalfCandidate> { it.score }.thenBy { it.panna }.thenBy { it.ank })
    }

    private fun validate(candidates: List<ReverseEngineeringCandidate>, side: String) {
        require(candidates.map { it.panna }.distinct().size == candidates.size) { "Duplicate frozen $side Panna candidates" }
        candidates.forEach {
            require(it.panna.matches(Regex("\\d{3}"))) { "Frozen $side candidates must be three-digit Pannas" }
            require(it.score.isFinite() && it.score >= 0.0) { "Frozen $side candidate scores must be finite and non-negative" }
        }
    }

    private fun ank(panna: String) = panna.sumOf { it.digitToInt() } % 10
}
