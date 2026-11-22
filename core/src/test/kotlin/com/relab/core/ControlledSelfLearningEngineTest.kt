package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ControlledSelfLearningEngineTest {
    private val start = LocalDate.of(2026, 1, 1)
    private val end = LocalDate.of(2026, 3, 1)
    private val asOf = Instant.parse("2026-04-01T00:00:00Z")

    private fun outcomes(incumbentHits: Int, challengerHits: Int, count: Int = 40): List<LearningOutcome> =
        (0 until count).flatMap { index ->
            val date = start.plusDays(index.toLong())
            listOf(
                LearningOutcome(
                    "inc-$index", "incumbent", Market.SRIDEVI, "main",
                    PatternEvidenceChannel.OPEN_PANNA, PatternOutcomeMode.EXACT,
                    date, date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                    index < incumbentHits
                ),
                LearningOutcome(
                    "new-$index", "challenger", Market.SRIDEVI, "main",
                    PatternEvidenceChannel.OPEN_PANNA, PatternOutcomeMode.EXACT,
                    date, date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                    index < challengerHits
                )
            )
        }

    private fun propose(rows: List<LearningOutcome>, min: Int = 30) =
        ControlledSelfLearningEngine.propose(
            rows, "incumbent", "challenger", Market.SRIDEVI, "main",
            PatternEvidenceChannel.OPEN_PANNA, PatternOutcomeMode.EXACT,
            start, end, asOf, min
        )

    @Test fun strongPairedHeldOutImprovementCreatesReviewOnlyProposal() {
        val proposal = propose(outcomes(incumbentHits = 10, challengerHits = 38))
        assertEquals(LearningProposalStatus.ELIGIBLE_FOR_REVIEW, proposal.status)
        assertEquals(40, proposal.pairedSupport)
        assertEquals(10, proposal.incumbentHits)
        assertEquals(38, proposal.challengerHits)
    }

    @Test fun insufficientPairedEvidenceAbstains() {
        val proposal = propose(outcomes(10, 38, count = 12))
        assertEquals(LearningProposalStatus.INSUFFICIENT_EVIDENCE, proposal.status)
    }

    @Test fun overlappingIntervalsDoNotPromoteChallenger() {
        val proposal = propose(outcomes(20, 22))
        assertEquals(LearningProposalStatus.NO_VERIFIED_IMPROVEMENT, proposal.status)
    }

    @Test fun ignoresOtherScopesAndUnpublishedOrFutureResults() {
        val base = outcomes(10, 38)
        val extra = listOf(
            base.first().copy(outcomeId = "other-market", market = Market.KALYAN),
            base.first().copy(outcomeId = "other-session", sessionId = "night"),
            base.first().copy(outcomeId = "unpublished", publishedAt = asOf),
            base.first().copy(
                outcomeId = "future",
                targetDate = LocalDate.of(2026, 4, 1),
                publishedAt = Instant.parse("2026-04-02T00:00:00Z")
            )
        )
        val proposal = propose(base + extra)
        assertEquals(40, proposal.pairedSupport)
        assertEquals(LearningProposalStatus.ELIGIBLE_FOR_REVIEW, proposal.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateConfigurationTargetDateRows() {
        val rows = outcomes(10, 38).toMutableList()
        rows += rows.first().copy(outcomeId = "duplicate")
        propose(rows)
    }

    @Test fun explicitApprovalStillDoesNotMutateEffectiveConfiguration() {
        val proposal = propose(outcomes(10, 38))
        val notApproved = ControlledSelfLearningEngine.decide(proposal, false)
        assertEquals(LearningApprovalStatus.NOT_APPLIED, notApproved.approvalStatus)
        assertEquals("incumbent", notApproved.effectiveConfigurationId)

        val approved = ControlledSelfLearningEngine.decide(proposal, true)
        assertEquals(LearningApprovalStatus.APPROVED_FOR_EXTERNAL_ROLLOUT, approved.approvalStatus)
        assertEquals("incumbent", approved.effectiveConfigurationId)
        assertTrue(approved.note.contains("has not changed"))
    }

    @Test fun approvalCannotBypassEvidenceGate() {
        val proposal = propose(outcomes(20, 22))
        val decision = ControlledSelfLearningEngine.decide(proposal, true)
        assertEquals(LearningApprovalStatus.NOT_APPLIED, decision.approvalStatus)
        assertEquals("incumbent", decision.effectiveConfigurationId)
    }
}
