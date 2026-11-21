package com.relab.core

/**
 * Market/session-isolated feedback derived only from immutable, post-publication
 * audits of frozen predictions. It records exact, family and channel-swap
 * diagnostics separately; it never rewrites snapshots or changes model weights.
 */
data class PredictionLearningFeedback(
    val market: Market,
    val sessionId: String,
    val auditedOutcomes: Int,
    val openExactHits: Int,
    val openFamilyMovementHits: Int,
    val closeExactHits: Int,
    val closeFamilyMovementHits: Int,
    val reciprocalOpenCloseSwapHints: Int,
    val jodiExactHits: Int,
    val jodiFamilyMovementOnlyHits: Int
) {
    val sampleStatus: String
        get() = if (auditedOutcomes < 10) "OBSERVE_ONLY" else "RESEARCH_READY"

    fun compactSummary(): String =
        "$market/$sessionId | n=$auditedOutcomes | OPEN exact=$openExactHits family=$openFamilyMovementHits | " +
            "CLOSE exact=$closeExactHits family=$closeFamilyMovementHits | reciprocal Open/Close swap hints=$reciprocalOpenCloseSwapHints | " +
            "JODI exact=$jodiExactHits family-only=$jodiFamilyMovementOnlyHits | $sampleStatus"
}

object PredictionSelfLearningEngine {
    /**
     * Counts only post-result feedback. A reciprocal channel-swap hint requires
     * both exact side misses and each actual panna appearing in the opposite
     * frozen side's candidate list within topLimit.
     */
    fun analyze(
        audits: List<FrozenSangamOutcomeAudit>,
        topLimit: Int = 10
    ): List<PredictionLearningFeedback> {
        require(topLimit in 1..100) { "Top limit must be between 1 and 100" }
        return audits.groupBy { it.market to it.sessionId }
            .entries
            .sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
            .map { (identity, rows) ->
                PredictionLearningFeedback(
                    market = identity.first,
                    sessionId = identity.second,
                    auditedOutcomes = rows.size,
                    openExactHits = rows.count { it.openPannaAudit.exactRank?.let { rank -> rank <= topLimit } == true },
                    openFamilyMovementHits = rows.count { it.openPannaAudit.familySequenceTop10Hit },
                    closeExactHits = rows.count { it.closePannaAudit.exactRank?.let { rank -> rank <= topLimit } == true },
                    closeFamilyMovementHits = rows.count { it.closePannaAudit.familySequenceTop10Hit },
                    reciprocalOpenCloseSwapHints = rows.count { audit ->
                        audit.openPannaAudit.exactRank?.let { it <= topLimit } != true &&
                            audit.closePannaAudit.exactRank?.let { it <= topLimit } != true &&
                            audit.actualOpenRankInCloseCandidates?.let { it <= topLimit } == true &&
                            audit.actualCloseRankInOpenCandidates?.let { it <= topLimit } == true
                    },
                    jodiExactHits = rows.count { it.jodiExactRank?.let { rank -> rank <= topLimit } == true },
                    jodiFamilyMovementOnlyHits = rows.count { audit ->
                        audit.jodiExactRank?.let { it <= topLimit } != true &&
                            audit.jodiFamilyMovementRank?.let { it <= topLimit } == true
                    }
                )
            }
    }
}
