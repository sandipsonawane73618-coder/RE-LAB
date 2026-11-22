package com.relab.core

import java.time.LocalDate

enum class AdaptiveCandidateStatus {
    SUPPORTED,
    WATCH,
    SUPPRESSED,
    INSUFFICIENT_EVIDENCE
}

data class AdaptivePatternCandidate(
    val market: Market,
    val sessionId: String,
    val channel: PatternEvidenceChannel,
    val outcomeMode: PatternOutcomeMode,
    val windowLength: Int,
    val pattern: String,
    val adaptiveScore: Double,
    val support: Int,
    val hits: Int,
    val baselineHits: Int,
    val assessment: PatternEvidenceAssessment,
    val status: AdaptiveCandidateStatus,
    val hitRateLower95: Double?,
    val hitRateUpper95: Double?,
    val baselineLower95: Double?,
    val baselineUpper95: Double?
)

data class AdaptiveRankingReport(
    val market: Market,
    val sessionId: String,
    val channel: PatternEvidenceChannel,
    val targetDate: LocalDate,
    val inputEvidence: Int,
    val eligibleEvidence: Int,
    val supported: Int,
    val watch: Int,
    val suppressed: Int,
    val insufficient: Int,
    val abstained: Boolean,
    val candidates: List<AdaptivePatternCandidate>,
    val note: String
)

/**
 * Scope-isolated adaptive ordering over chronological PatternEvidenceDatabase output.
 *
 * It never pools markets, sessions, channels, or outcome modes. Only patterns whose
 * Wilson lower bound clears the matched baseline upper bound can be promoted.
 * Inconclusive patterns remain visible as WATCH; weak or unsupported evidence is
 * never converted into a probability.
 */
object AdaptiveRankingEngine {
    fun rank(
        evidence: List<PatternEvidenceRecord>,
        market: Market,
        sessionId: String,
        channel: PatternEvidenceChannel,
        targetDate: LocalDate,
        minimumEvaluations: Int = 20,
        limit: Int = 10,
        windowLength: Int? = null,
        outcomeMode: PatternOutcomeMode? = null,
        recencyScaleInRows: Double = 20.0
    ): AdaptiveRankingReport {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(minimumEvaluations >= 1) { "Minimum evaluations must be positive" }
        require(limit in 1..100) { "Candidate limit must be between 1 and 100" }
        require(windowLength == null || windowLength >= 1) { "Window length must be positive" }

        val scoped = evidence.filter {
            it.market == market &&
                it.sessionId == sessionId &&
                it.channel == channel &&
                it.lastSeenDate.isBefore(targetDate) &&
                (windowLength == null || it.windowLength == windowLength) &&
                (outcomeMode == null || it.outcomeMode == outcomeMode)
        }
        val duplicateKeys = scoped.groupBy {
            Triple(it.outcomeMode, it.windowLength, it.pattern)
        }.filterValues { it.size > 1 }.keys
        require(duplicateKeys.isEmpty()) {
            "Duplicate pattern evidence rows in requested market/session/channel scope"
        }

        val ranked = PatternEvidenceRanking.rank(
            scoped, minimumEvaluations, recencyScaleInRows
        )
        val candidates = ranked.map { item ->
            val assessment = item.assessment
            val status = when (assessment) {
                PatternEvidenceAssessment.OUTPERFORMS_BASELINE -> AdaptiveCandidateStatus.SUPPORTED
                PatternEvidenceAssessment.INCONCLUSIVE -> AdaptiveCandidateStatus.WATCH
                PatternEvidenceAssessment.BELOW_BASELINE -> AdaptiveCandidateStatus.SUPPRESSED
                PatternEvidenceAssessment.INSUFFICIENT_EVIDENCE -> AdaptiveCandidateStatus.INSUFFICIENT_EVIDENCE
            }
            AdaptivePatternCandidate(
                market = item.evidence.market,
                sessionId = item.evidence.sessionId,
                channel = item.evidence.channel,
                outcomeMode = item.evidence.outcomeMode,
                windowLength = item.evidence.windowLength,
                pattern = item.evidence.pattern,
                adaptiveScore = item.rankScore,
                support = item.evidence.evaluatedOccurrences,
                hits = item.evidence.hits,
                baselineHits = item.evidence.baselineHits,
                assessment = assessment,
                status = status,
                hitRateLower95 = item.hitRateLower95,
                hitRateUpper95 = item.hitRateUpper95,
                baselineLower95 = item.baselineLower95,
                baselineUpper95 = item.baselineUpper95
            )
        }.sortedWith(
            compareBy<AdaptivePatternCandidate> { statusOrder(it.status) }
                .thenByDescending { it.adaptiveScore }
                .thenByDescending { it.support }
                .thenBy { it.windowLength }
                .thenBy { it.pattern }
        )

        val supported = candidates.count { it.status == AdaptiveCandidateStatus.SUPPORTED }
        val watch = candidates.count { it.status == AdaptiveCandidateStatus.WATCH }
        val suppressed = candidates.count { it.status == AdaptiveCandidateStatus.SUPPRESSED }
        val insufficient = candidates.count { it.status == AdaptiveCandidateStatus.INSUFFICIENT_EVIDENCE }
        val visible = candidates.take(limit)
        return AdaptiveRankingReport(
            market = market,
            sessionId = sessionId,
            channel = channel,
            targetDate = targetDate,
            inputEvidence = evidence.size,
            eligibleEvidence = scoped.size,
            supported = supported,
            watch = watch,
            suppressed = suppressed,
            insufficient = insufficient,
            abstained = supported == 0,
            candidates = visible,
            note = if (supported == 0) {
                "No pattern cleared the independent 95% Wilson baseline-separation rule; adaptive ranking abstained. Historical evidence is not a calibrated probability or guarantee."
            } else {
                "Supported patterns are ordered by baseline-separated lift, support, recency and stability. All results are isolated to $market/$sessionId/$channel before $targetDate; scores are ranking indices, not probabilities."
            }
        )
    }

    private fun statusOrder(status: AdaptiveCandidateStatus): Int = when (status) {
        AdaptiveCandidateStatus.SUPPORTED -> 0
        AdaptiveCandidateStatus.WATCH -> 1
        AdaptiveCandidateStatus.SUPPRESSED -> 2
        AdaptiveCandidateStatus.INSUFFICIENT_EVIDENCE -> 3
    }
}
