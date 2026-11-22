package com.relab.core

import java.time.Instant
import java.time.LocalDate

/** A matured, held-out result for one model/configuration on one target date. */
data class LearningOutcome(
    val outcomeId: String,
    val configurationId: String,
    val market: Market,
    val sessionId: String,
    val channel: PatternEvidenceChannel,
    val outcomeMode: PatternOutcomeMode,
    val targetDate: LocalDate,
    val publishedAt: Instant,
    val hit: Boolean
) {
    init {
        require(outcomeId.isNotBlank() && configurationId.isNotBlank()) {
            "Outcome and configuration IDs are required"
        }
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(!publishedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate().isBefore(targetDate)) {
            "Learning outcome cannot be published before its target date"
        }
    }
}

enum class LearningProposalStatus {
    ELIGIBLE_FOR_REVIEW,
    INSUFFICIENT_EVIDENCE,
    NO_VERIFIED_IMPROVEMENT,
    INVALID_COMPARISON
}

data class ControlledLearningProposal(
    val proposalId: String,
    val incumbentConfigurationId: String,
    val challengerConfigurationId: String,
    val market: Market,
    val sessionId: String,
    val channel: PatternEvidenceChannel,
    val outcomeMode: PatternOutcomeMode,
    val evaluationStart: LocalDate,
    val evaluationEndExclusive: LocalDate,
    val pairedSupport: Int,
    val incumbentHits: Int,
    val challengerHits: Int,
    val incumbentHitRate: Double?,
    val challengerHitRate: Double?,
    val incumbentLower95: Double?,
    val incumbentUpper95: Double?,
    val challengerLower95: Double?,
    val challengerUpper95: Double?,
    val status: LearningProposalStatus,
    val note: String
)

enum class LearningApprovalStatus { NOT_APPLIED, APPROVED_FOR_EXTERNAL_ROLLOUT }

data class ControlledLearningDecision(
    val proposal: ControlledLearningProposal,
    val approvalStatus: LearningApprovalStatus,
    val effectiveConfigurationId: String,
    val note: String
)

/**
 * Produces review-only proposals from paired, matured, held-out outcomes.
 * It does not mutate ranking weights, evidence, calibration, or live settings.
 * A challenger must clear the incumbent's 95% Wilson upper bound with its
 * own lower bound, on identical target dates and in one isolated scope.
 */
object ControlledSelfLearningEngine {
    private data class Interval(val lower: Double, val upper: Double)

    fun propose(
        outcomes: List<LearningOutcome>,
        incumbentConfigurationId: String,
        challengerConfigurationId: String,
        market: Market,
        sessionId: String,
        channel: PatternEvidenceChannel,
        outcomeMode: PatternOutcomeMode,
        evaluationStart: LocalDate,
        evaluationEndExclusive: LocalDate,
        asOf: Instant,
        minimumPairedSupport: Int = 30
    ): ControlledLearningProposal {
        require(incumbentConfigurationId.isNotBlank() &&
            challengerConfigurationId.isNotBlank()) { "Configuration IDs are required" }
        require(incumbentConfigurationId != challengerConfigurationId) {
            "Incumbent and challenger must be different configurations"
        }
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(evaluationStart.isBefore(evaluationEndExclusive)) { "Invalid evaluation window" }
        require(minimumPairedSupport >= 1) { "Minimum paired support must be positive" }

        val scoped = outcomes.filter {
            it.market == market && it.sessionId == sessionId &&
                it.channel == channel && it.outcomeMode == outcomeMode &&
                !it.targetDate.isBefore(evaluationStart) &&
                it.targetDate.isBefore(evaluationEndExclusive) &&
                it.publishedAt.isBefore(asOf) &&
                it.targetDate.isBefore(asOf.atZone(java.time.ZoneOffset.UTC).toLocalDate()) &&
                (it.configurationId == incumbentConfigurationId ||
                    it.configurationId == challengerConfigurationId)
        }
        val duplicateIds = scoped.groupBy { it.outcomeId }.filterValues { it.size > 1 }
        require(duplicateIds.isEmpty()) { "Duplicate learning outcome IDs are not allowed" }
        val duplicateConfigDates = scoped.groupBy { it.configurationId to it.targetDate }
            .filterValues { it.size > 1 }
        require(duplicateConfigDates.isEmpty()) {
            "Each configuration must have at most one outcome per target date"
        }
        val byDate = scoped.groupBy { it.targetDate }
        val pairedDates = byDate.filterValues { rows ->
            rows.any { it.configurationId == incumbentConfigurationId } &&
                rows.any { it.configurationId == challengerConfigurationId }
        }.keys
        val incumbent = pairedDates.map { date ->
            byDate.getValue(date).single { it.configurationId == incumbentConfigurationId }
        }
        val challenger = pairedDates.map { date ->
            byDate.getValue(date).single { it.configurationId == challengerConfigurationId }
        }
        val enough = pairedDates.size >= minimumPairedSupport
        val incumbentHits = incumbent.count { it.hit }
        val challengerHits = challenger.count { it.hit }
        val incumbentInterval = if (enough) wilson(incumbentHits, incumbent.size) else null
        val challengerInterval = if (enough) wilson(challengerHits, challenger.size) else null
        val status = when {
            !enough -> LearningProposalStatus.INSUFFICIENT_EVIDENCE
            challengerInterval!!.lower > incumbentInterval!!.upper ->
                LearningProposalStatus.ELIGIBLE_FOR_REVIEW
            else -> LearningProposalStatus.NO_VERIFIED_IMPROVEMENT
        }
        val proposalId = listOf(
            market.name, sessionId, channel.name, outcomeMode.name,
            incumbentConfigurationId, challengerConfigurationId,
            evaluationStart.toString(), evaluationEndExclusive.toString()
        ).joinToString("|")
        return ControlledLearningProposal(
            proposalId, incumbentConfigurationId, challengerConfigurationId,
            market, sessionId, channel, outcomeMode, evaluationStart, evaluationEndExclusive,
            pairedDates.size, incumbentHits, challengerHits,
            incumbent.takeIf { it.isNotEmpty() }?.let { incumbentHits.toDouble() / it.size },
            challenger.takeIf { it.isNotEmpty() }?.let { challengerHits.toDouble() / it.size },
            incumbentInterval?.lower, incumbentInterval?.upper,
            challengerInterval?.lower, challengerInterval?.upper,
            status,
            when (status) {
                LearningProposalStatus.ELIGIBLE_FOR_REVIEW ->
                    "Challenger cleared the conservative held-out baseline rule. Proposal is review-only; no live setting was changed."
                LearningProposalStatus.INSUFFICIENT_EVIDENCE ->
                    "Learning abstained: at least $minimumPairedSupport paired, published, same-scope target dates are required."
                LearningProposalStatus.NO_VERIFIED_IMPROVEMENT ->
                    "No statistically separated held-out improvement was demonstrated. Keep the incumbent."
                LearningProposalStatus.INVALID_COMPARISON ->
                    "Comparison is invalid; no configuration change is permitted."
            }
        )
    }

    /** Approval records intent only; rollout remains an explicit external action. */
    fun decide(
        proposal: ControlledLearningProposal,
        explicitlyApproved: Boolean
    ): ControlledLearningDecision {
        val approved = explicitlyApproved &&
            proposal.status == LearningProposalStatus.ELIGIBLE_FOR_REVIEW
        return ControlledLearningDecision(
            proposal,
            if (approved) LearningApprovalStatus.APPROVED_FOR_EXTERNAL_ROLLOUT
            else LearningApprovalStatus.NOT_APPLIED,
            proposal.incumbentConfigurationId,
            if (approved)
                "Approval recorded for external, separately verified rollout. This engine has not changed any live configuration."
            else
                "Incumbent remains active. No automatic learning or live configuration mutation occurred."
        )
    }

    private fun wilson(successes: Int, total: Int): Interval {
        val z = 1.959963984540054
        val n = total.toDouble()
        val p = successes / n
        val denominator = 1.0 + z * z / n
        val center = (p + z * z / (2.0 * n)) / denominator
        val margin = z * kotlin.math.sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n)) / denominator
        return Interval((center - margin).coerceAtLeast(0.0), (center + margin).coerceAtMost(1.0))
    }
}
