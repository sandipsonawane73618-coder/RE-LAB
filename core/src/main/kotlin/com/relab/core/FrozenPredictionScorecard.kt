package com.relab.core

/**
 * Aggregate descriptive scorecard over immutable prediction snapshots only.
 * Percentages are hit rates over audited outcomes, not calibrated probabilities.
 */
data class FrozenPredictionScorecard(
    val market: Market,
    val sessionId: String,
    val savedSnapshots: Int,
    val auditedOutcomes: Int,
    val pendingOutcomes: Int,
    val openExactTop10: Int,
    val openExactTop100: Int,
    val openFamilyTop10: Int,
    val openFamilyTop100: Int,
    val openPositionalNearTop10: Int,
    val openPositionalNearTop100: Int,
    val closeExactTop10: Int,
    val closeExactTop100: Int,
    val closeFamilyTop10: Int,
    val closeFamilyTop100: Int,
    val closePositionalNearTop10: Int,
    val closePositionalNearTop100: Int,
    val sangamExactTop10: Int,
    val sangamExactTop100: Int,
    val jodiDiagnosticMatches: Int,
    val pannaHalfAuditedOutcomes: Int,
    val openPannaCloseAnkTop10: Int,
    val openPannaCloseAnkTop100: Int,
    val openAnkClosePannaTop10: Int,
    val openAnkClosePannaTop100: Int
) {
    fun rate(hits: Int): Double? =
        if (auditedOutcomes == 0) null else hits.toDouble() / auditedOutcomes

    fun halfSangamRate(hits: Int): Double? =
        if (pannaHalfAuditedOutcomes == 0) null else hits.toDouble() / pannaHalfAuditedOutcomes
}

/** Builds per-market/session scorecards from audits already linked to frozen snapshots. */
object FrozenPredictionScorecardBuilder {
    fun build(
        snapshots: List<PredictionSnapshot>,
        audits: List<FrozenSangamOutcomeAudit>,
        pannaHalfAudits: List<FrozenPannaHalfSangamAudit> = emptyList()
    ): List<FrozenPredictionScorecard> {
        require(snapshots.map { it.snapshotKey }.distinct().size == snapshots.size) {
            "Duplicate frozen snapshot keys are not allowed"
        }
        require(audits.map { it.snapshotKey }.distinct().size == audits.size) {
            "Duplicate frozen outcome audits are not allowed"
        }
        require(pannaHalfAudits.map { it.snapshotKey }.distinct().size == pannaHalfAudits.size) {
            "Duplicate frozen Panna/Half-Sangam audits are not allowed"
        }
        val snapshotsByKey = snapshots.associateBy { it.snapshotKey }
        audits.forEach { audit ->
            val snapshot = snapshotsByKey[audit.snapshotKey]
                ?: error("Audit does not belong to a supplied frozen snapshot")
            require(snapshot.market == audit.market &&
                snapshot.sessionId == audit.sessionId &&
                snapshot.targetDate == audit.targetDate &&
                snapshot.modelVersion == audit.modelVersion
            ) { "Audit metadata does not match its frozen snapshot" }
        }
        pannaHalfAudits.forEach { audit ->
            val snapshot = snapshotsByKey[audit.snapshotKey]
                ?: error("Panna/Half-Sangam audit does not belong to a supplied frozen snapshot")
            require(snapshot.market == audit.market &&
                snapshot.sessionId == audit.sessionId &&
                snapshot.targetDate == audit.targetDate
            ) { "Panna/Half-Sangam audit metadata does not match its frozen snapshot" }
        }

        return snapshots.groupBy { it.market to it.sessionId }
            .toSortedMap(compareBy<Pair<Market, String>> { it.first.name }.thenBy { it.second })
            .map { (identity, groupSnapshots) ->
                val keys = groupSnapshots.map { it.snapshotKey }.toSet()
                val groupAudits = audits.filter { it.snapshotKey in keys }
                val groupPannaHalfAudits = pannaHalfAudits.filter { it.snapshotKey in keys }
                FrozenPredictionScorecard(
                    market = identity.first,
                    sessionId = identity.second,
                    savedSnapshots = groupSnapshots.size,
                    auditedOutcomes = groupAudits.size,
                    pendingOutcomes = groupSnapshots.size - groupAudits.size,
                    openExactTop10 = groupAudits.count { it.openPannaAudit.exactRank?.let { rank -> rank <= 10 } == true },
                    openExactTop100 = groupAudits.count { it.openPannaAudit.exactRank?.let { rank -> rank <= 100 } == true },
                    openFamilyTop10 = groupAudits.count { it.openPannaAudit.familySequenceTop10Hit },
                    openFamilyTop100 = groupAudits.count { it.openPannaAudit.familySequenceTop100Hit },
                    openPositionalNearTop10 = groupAudits.count { it.openPannaAudit.positionalNearHitTop10 },
                    openPositionalNearTop100 = groupAudits.count { it.openPannaAudit.positionalNearHitTop100 },
                    closeExactTop10 = groupAudits.count { it.closePannaAudit.exactRank?.let { rank -> rank <= 10 } == true },
                    closeExactTop100 = groupAudits.count { it.closePannaAudit.exactRank?.let { rank -> rank <= 100 } == true },
                    closeFamilyTop10 = groupAudits.count { it.closePannaAudit.familySequenceTop10Hit },
                    closeFamilyTop100 = groupAudits.count { it.closePannaAudit.familySequenceTop100Hit },
                    closePositionalNearTop10 = groupAudits.count { it.closePannaAudit.positionalNearHitTop10 },
                    closePositionalNearTop100 = groupAudits.count { it.closePannaAudit.positionalNearHitTop100 },
                    sangamExactTop10 = groupAudits.count { it.audit.exactPairRank?.let { rank -> rank <= 10 } == true },
                    sangamExactTop100 = groupAudits.count { it.audit.exactPairRank?.let { rank -> rank <= 100 } == true },
                    jodiDiagnosticMatches = groupAudits.count { it.audit.jodiMatchCount > 0 },
                    pannaHalfAuditedOutcomes = groupPannaHalfAudits.size,
                    openPannaCloseAnkTop10 = groupPannaHalfAudits.count { it.openPannaCloseAnkExactTop10 },
                    openPannaCloseAnkTop100 = groupPannaHalfAudits.count { it.openPannaCloseAnkExactTop100 },
                    openAnkClosePannaTop10 = groupPannaHalfAudits.count { it.openAnkClosePannaExactTop10 },
                    openAnkClosePannaTop100 = groupPannaHalfAudits.count { it.openAnkClosePannaExactTop100 }
                )
            }
    }
}
