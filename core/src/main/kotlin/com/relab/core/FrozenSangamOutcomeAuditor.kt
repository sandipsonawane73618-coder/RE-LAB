package com.relab.core

/**
 * Links a separately stored published result to the immutable prediction captured
 * for the same market/session/date. Candidate lists must be decoded from the
 * snapshot payload, never regenerated from current chart history.
 */
data class FrozenSangamOutcomeAudit(
    val snapshotKey: String,
    val market: Market,
    val sessionId: String,
    val targetDate: java.time.LocalDate,
    val modelVersion: String,
    val publishedAt: java.time.Instant,
    val sourceStatus: String,
    val audit: SangamOutcomeAuditReport,
    val openPannaAudit: PannaNearHitAuditReport,
    val closePannaAudit: PannaNearHitAuditReport,
    val actualOpenRankInCloseCandidates: Int? = null,
    val actualCloseRankInOpenCandidates: Int? = null,
    val jodiExactRank: Int? = null,
    val jodiFamilyMovementRank: Int? = null
)

object FrozenSangamOutcomeAuditor {
    fun analyze(
        snapshot: PredictionSnapshot,
        outcome: PredictionSnapshotOutcome,
        openCandidates: List<ReverseEngineeringCandidate>,
        closeCandidates: List<ReverseEngineeringCandidate>,
        pairLimit: Int = 100,
        jodiCandidates: List<ReverseEngineeringJodiCandidate> =
            ReverseEngineeringModel.deriveJodiCandidates(openCandidates, closeCandidates, 10)
    ): FrozenSangamOutcomeAudit {
        require(outcome.snapshotKey == snapshot.snapshotKey) {
            "Outcome key does not match the frozen prediction snapshot"
        }
        require(!outcome.publishedAt.isBefore(snapshot.createdAt)) {
            "Published outcome timestamp cannot precede prediction snapshot creation"
        }
        val parts = outcome.actualResult.split('-')
        require(
            parts.size == 3 &&
                parts[0].matches(Regex("\\d{3}")) &&
                parts[1].matches(Regex("\\d{2}")) &&
                parts[2].matches(Regex("\\d{3}"))
        ) {
            "Published result must use openPanna-jodi-closePanna format"
        }
        val openPannaAudit = PannaNearHitAuditor.analyze(parts[0], openCandidates, nearestLimit = 5)
        val closePannaAudit = PannaNearHitAuditor.analyze(parts[2], closeCandidates, nearestLimit = 5)
        val rankedOpen = openCandidates.sortedWith(compareByDescending<ReverseEngineeringCandidate> { it.score }.thenBy { it.panna })
        val rankedClose = closeCandidates.sortedWith(compareByDescending<ReverseEngineeringCandidate> { it.score }.thenBy { it.panna })
        val rankedJodi = jodiCandidates.sortedWith(compareByDescending<ReverseEngineeringJodiCandidate> { it.score }.thenBy { it.jodi })
        require(rankedJodi.map { it.jodi }.distinct().size == rankedJodi.size) {
            "Duplicate frozen Jodi candidates are not allowed"
        }
        rankedJodi.forEach {
            require(it.jodi.matches(Regex("\\d{2}")) && it.score.isFinite() && it.score >= 0.0) {
                "Frozen Jodi candidates must be valid two-digit values with finite non-negative scores"
            }
        }
        fun rankPanna(value: String, rows: List<ReverseEngineeringCandidate>): Int? =
            rows.indexOfFirst { it.panna == value }.takeIf { it >= 0 }?.plus(1)
        fun jodiFamilyKey(value: String): String =
            value.map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
        val jodiExactRank = rankedJodi.indexOfFirst { it.jodi == parts[1] }.takeIf { it >= 0 }?.plus(1)
        val jodiFamilyMovementRank = rankedJodi.indexOfFirst {
            jodiFamilyKey(it.jodi) == jodiFamilyKey(parts[1])
        }.takeIf { it >= 0 }?.plus(1)
        val audit = SangamOutcomeAuditor.analyze(
            actualOpenPanna = parts[0],
            actualClosePanna = parts[2],
            openCandidates = openCandidates,
            closeCandidates = closeCandidates,
            pairLimit = pairLimit
        )
        require(audit.actualJodi == parts[1]) {
            "Published Jodi does not match the Panna-derived open/close anks"
        }
        return FrozenSangamOutcomeAudit(
            snapshotKey = snapshot.snapshotKey,
            market = snapshot.market,
            sessionId = snapshot.sessionId,
            targetDate = snapshot.targetDate,
            modelVersion = snapshot.modelVersion,
            publishedAt = outcome.publishedAt,
            sourceStatus = outcome.sourceStatus,
            audit = audit,
            openPannaAudit = openPannaAudit,
            closePannaAudit = closePannaAudit,
            actualOpenRankInCloseCandidates = rankPanna(parts[0], rankedClose),
            actualCloseRankInOpenCandidates = rankPanna(parts[2], rankedOpen),
            jodiExactRank = jodiExactRank,
            jodiFamilyMovementRank = jodiFamilyMovementRank
        )
    }
}
