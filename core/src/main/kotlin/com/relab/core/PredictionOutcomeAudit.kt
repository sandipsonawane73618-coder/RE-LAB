package com.relab.core

/**
 * Deterministic post-result audit for a prediction snapshot.
 *
 * Scores are relative evidence indices from ReverseEngineeringModel, not calibrated
 * probabilities. The audit never changes the original prediction or historical result.
 */
data class PredictionEvidenceLayerComparison(
    val layer: String,
    val actualRank: Int,
    val actualValue: Double,
    val leaderValue: Double,
    val weakerThanLeader: Boolean
)

data class PredictionOutcomeSideAudit(
    val actualValue: String,
    val topCandidate: String?,
    val topCandidateScore: Double?,
    val actualRank: Int?,
    val actualScore: Double?,
    val hitWithinTopLimit: Boolean,
    val topLimit: Int,
    val explanation: String,
    val evidenceComparisons: List<PredictionEvidenceLayerComparison> = emptyList()
) {
    val weakerEvidenceLayers: List<String>
        get() = evidenceComparisons.filter { it.weakerThanLeader }.map { it.layer }
}

data class PredictionOutcomeAuditReport(
    val open: PredictionOutcomeSideAudit,
    val close: PredictionOutcomeSideAudit,
    val jodi: PredictionOutcomeSideAudit
) {
    val failedSides: List<String>
        get() = buildList {
            if (!open.hitWithinTopLimit) add("OPEN")
            if (!close.hitWithinTopLimit) add("CLOSE")
            if (!jodi.hitWithinTopLimit) add("JODI")
        }

    val allHit: Boolean
        get() = failedSides.isEmpty()

    fun compactAnalysis(): String = buildList {
        add(if (allHit) "All audited channels hit within top-${open.topLimit}." else "Failed channels: ${failedSides.joinToString()}.")
        listOf("OPEN" to open, "CLOSE" to close, "JODI" to jodi).forEach { (label, audit) ->
            add("$label: ${audit.explanation}")
            if (audit.evidenceComparisons.isNotEmpty()) {
                val weaker = audit.weakerEvidenceLayers
                add(
                    if (weaker.isEmpty()) "$label evidence: actual candidate was not weaker than the leader on compared layers."
                    else "$label evidence weaker than leader: ${weaker.joinToString()}."
                )
            } else if (!audit.hitWithinTopLimit) {
                add("$label evidence breakdown unavailable: actual candidate was not in the supplied panna candidate pool.")
            }
        }
        add("Diagnostic only: evidence comparisons are not causal proof, ranking evidence is not a calibrated probability, and no model is automatically retrained or historical data rewritten.")
    }.joinToString("\n")
}

object PredictionOutcomeAudit {
    fun analyze(
        actualOpenPanna: String,
        actualClosePanna: String,
        actualJodi: String,
        openCandidates: List<ReverseEngineeringCandidate>,
        closeCandidates: List<ReverseEngineeringCandidate>,
        jodiCandidates: List<ReverseEngineeringJodiCandidate>,
        topLimit: Int = 10
    ): PredictionOutcomeAuditReport {
        require(topLimit in 1..100) { "Top limit must be between 1 and 100" }
        require(actualOpenPanna.matches(Regex("\\d{3}"))) { "Actual open panna must contain three digits" }
        require(actualClosePanna.matches(Regex("\\d{3}"))) { "Actual close panna must contain three digits" }
        require(actualJodi.matches(Regex("\\d{2}"))) { "Actual jodi must contain two digits" }
        return PredictionOutcomeAuditReport(
            auditPanna(actualOpenPanna, openCandidates, topLimit),
            auditPanna(actualClosePanna, closeCandidates, topLimit),
            auditJodi(actualJodi, jodiCandidates, topLimit)
        )
    }

    private data class EvidenceLayer(
        val name: String,
        val value: (ReverseEngineeringCandidate) -> Double?
    )

    private val evidenceLayers = listOf(
        EvidenceLayer("Exact panna recurrence") { it.exactPannaEvidence },
        EvidenceLayer("Digit-family sequence") { it.familyEvidence },
        EvidenceLayer("Ank distribution") { it.ankEvidence },
        EvidenceLayer("Digit-position distribution") { it.positionEvidence },
        EvidenceLayer("Panna type") { it.pannaTypeEvidence },
        EvidenceLayer("Reverse-panna recurrence") { it.reversePannaEvidence },
        EvidenceLayer("Target weekday") { it.weekdayEvidence },
        EvidenceLayer("Previous-state transition") { it.previousTransitionEvidence },
        EvidenceLayer("Open-close conditional") { it.openCloseConditionalEvidence },
        EvidenceLayer("Cross-market conditional") { it.crossMarketConditionalEvidence }
    )

    private fun auditPanna(
        actual: String,
        candidates: List<ReverseEngineeringCandidate>,
        topLimit: Int
    ): PredictionOutcomeSideAudit {
        val ranked = candidates.sortedWith(compareByDescending<ReverseEngineeringCandidate> { it.score }.thenBy { it.panna })
        val actualCandidate = ranked.firstOrNull { it.panna == actual }
        val rank = ranked.indexOfFirst { it.panna == actual }.takeIf { it >= 0 }?.plus(1)
        val actualScore = actualCandidate?.score
        val leader = ranked.firstOrNull()
        val hit = rank != null && rank <= topLimit
        val comparisons = if (actualCandidate == null || leader == null) emptyList() else
            evidenceLayers.mapNotNull { layer ->
                val actualValue = layer.value(actualCandidate) ?: return@mapNotNull null
                val available = ranked.mapNotNull { candidate ->
                    layer.value(candidate)?.let { candidate.panna to it }
                }
                if (available.isEmpty()) return@mapNotNull null
                val evidenceRank = available.sortedWith(
                    compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }
                ).indexOfFirst { it.first == actual }.takeIf { it >= 0 }?.plus(1)
                    ?: return@mapNotNull null
                val leaderValue = layer.value(leader) ?: available.maxOf { it.second }
                PredictionEvidenceLayerComparison(
                    layer.name, evidenceRank, actualValue, leaderValue,
                    actualValue + 1e-12 < leaderValue
                )
            }
        val explanation = when {
            hit -> "Actual $actual ranked #$rank (relative score ${format(actualScore)}); included in top-$topLimit."
            rank != null -> "Actual $actual ranked #$rank (score ${format(actualScore)}), outside top-$topLimit; leader was ${leader?.panna ?: "—"}."
            else -> "Actual $actual was outside the supplied candidate window; leader was ${leader?.panna ?: "—"}."
        }
        return PredictionOutcomeSideAudit(actual, leader?.panna, leader?.score, rank, actualScore, hit, topLimit, explanation, comparisons)
    }

    private fun auditJodi(
        actual: String,
        candidates: List<ReverseEngineeringJodiCandidate>,
        topLimit: Int
    ): PredictionOutcomeSideAudit {
        val ranked = candidates.sortedWith(compareByDescending<ReverseEngineeringJodiCandidate> { it.score }.thenBy { it.jodi })
        val rank = ranked.indexOfFirst { it.jodi == actual }.takeIf { it >= 0 }?.plus(1)
        val actualScore = ranked.firstOrNull { it.jodi == actual }?.score
        val leader = ranked.firstOrNull()
        val hit = rank != null && rank <= topLimit
        val explanation = when {
            hit -> "Actual $actual ranked #$rank (relative score ${format(actualScore)}); included in top-$topLimit."
            rank != null -> "Actual $actual ranked #$rank (score ${format(actualScore)}), outside top-$topLimit; leader was ${leader?.jodi ?: "—"}."
            else -> "Actual $actual was outside the supplied candidate window; leader was ${leader?.jodi ?: "—"}."
        }
        return PredictionOutcomeSideAudit(actual, leader?.jodi, leader?.score, rank, actualScore, hit, topLimit, explanation)
    }

    private fun format(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "n/a"
}
