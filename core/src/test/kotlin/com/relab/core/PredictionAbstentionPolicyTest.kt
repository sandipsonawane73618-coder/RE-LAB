package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PredictionAbstentionPolicyTest {
    private val target = LocalDate.of(2026, 11, 22)

    private fun candidate(status: AdaptiveCandidateStatus) = AdaptivePatternCandidate(
        Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA,
        PatternOutcomeMode.EXACT, 2, "111>222", 10.0, 100, 80, 20,
        if (status == AdaptiveCandidateStatus.SUPPORTED) PatternEvidenceAssessment.OUTPERFORMS_BASELINE
        else PatternEvidenceAssessment.INCONCLUSIVE,
        status, 0.7, 0.9, 0.1, 0.3
    )

    private fun drift(status: PatternDriftStatus) = PatternDriftReport(
        Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA,
        PatternOutcomeMode.EXACT, "111>222", Instant.parse("2026-11-22T00:00:00Z"),
        20, 20, 0.8, 0.2, 0.6, 0.9, 0.05, 0.4, status, "test"
    )

    @Test fun allowsOnlyWhenEveryGatePasses() {
        val decision = PredictionAbstentionPolicy.evaluate(
            candidate(AdaptiveCandidateStatus.SUPPORTED),
            drift(PatternDriftStatus.NO_CONFIRMED_DRIFT), 0.72, 30
        )
        assertEquals(PredictionSafetyStatus.ALLOW_WITH_CAUTION, decision.status)
        assertTrue(decision.reasons.isEmpty())
    }

    @Test fun abstainsForDegradationAndInsufficientDriftEvidence() {
        listOf(PatternDriftStatus.DEGRADING, PatternDriftStatus.INSUFFICIENT_EVIDENCE).forEach { status ->
            val decision = PredictionAbstentionPolicy.evaluate(
                candidate(AdaptiveCandidateStatus.SUPPORTED), drift(status), 0.72, 30
            )
            assertEquals(PredictionSafetyStatus.ABSTAIN, decision.status)
        }
    }

    @Test fun abstainsForUnsupportedCandidateOrMissingCalibration() {
        val decision = PredictionAbstentionPolicy.evaluate(
            candidate(AdaptiveCandidateStatus.WATCH),
            drift(PatternDriftStatus.NO_CONFIRMED_DRIFT), null, 2
        )
        assertEquals(PredictionSafetyStatus.ABSTAIN, decision.status)
        assertEquals(3, decision.reasons.size)
    }
}
