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
    val audit: SangamOutcomeAuditReport
)

object FrozenSangamOutcomeAuditor {
    fun analyze(
        snapshot: PredictionSnapshot,
        outcome: PredictionSnapshotOutcome,
        openCandidates: List<ReverseEngineeringCandidate>,
        closeCandidates: List<ReverseEngineeringCandidate>,
        pairLimit: Int = 100
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
            audit = audit
        )
    }
}
