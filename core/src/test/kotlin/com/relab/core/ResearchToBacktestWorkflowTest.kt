package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ResearchToBacktestWorkflowTest {
    private fun row(day: Int, open: String): ChartRecord {
        val close = "444"
        val jodi = "${open.sumOf { it.digitToInt() } % 10}${close.sumOf { it.digitToInt() } % 10}"
        return ChartRecord(
            market = Market.SRIDEVI,
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            openPanna = open,
            jodi = jodi,
            closePanna = close,
            sourceId = "source-$day"
        )
    }

    @Test
    fun repeatedAiPatternIsBacktestedChronologicallyAgainstPriorOnlyBaseline() {
        val rows = listOf(
            row(0, "111"), row(1, "222"), row(2, "333"),
            row(3, "111"), row(4, "222"), row(5, "333"),
            row(6, "999")
        )
        val proposal = ResearchPatternProposal(
            id = "ai-1", market = Market.SRIDEVI, sessionId = "main",
            channel = PatternEvidenceChannel.OPEN_PANNA, pattern = "111>222",
            rationale = "Repeated two-step sequence"
        )
        val result = ProposedPatternBacktester.evaluate(
            rows, proposal, LocalDate.of(2026, 1, 7), minimumEvaluations = 1, minimumLift = 0.0
        )
        assertEquals(2, result.supportOccurrences)
        assertEquals(1, result.evaluatedOccurrences)
        assertEquals(1, result.hits)
        assertEquals(0, result.misses)
        assertEquals(0, result.baselineHits)
        assertEquals(ResearchVerificationStatus.VERIFIED, result.status)
        assertNotNull(result.wilsonLowerBound)
    }

    @Test
    fun familyMovementProposalMatchesFamilyAndReverseVariantsWithoutChangingExactMode() {
        val rows = listOf(
            row(0, "111"), row(1, "222"), row(2, "333"),
            row(3, "666"), row(4, "777"), row(5, "888")
        )
        val exact = ProposedPatternBacktester.evaluate(
            rows,
            ResearchPatternProposal("exact", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "111>222"),
            LocalDate.of(2026, 1, 7), minimumEvaluations = 1
        )
        val family = ProposedPatternBacktester.evaluate(
            rows,
            ResearchPatternProposal(
                "family", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA,
                "111>222", PatternOutcomeMode.FAMILY_MOVEMENT
            ),
            LocalDate.of(2026, 1, 7), minimumEvaluations = 1
        )
        assertEquals(0, exact.evaluatedOccurrences)
        assertEquals(1, family.evaluatedOccurrences)
        assertEquals(1, family.hits)
    }

    @Test
    fun targetDateAndFutureRowsAreExcluded() {
        val rows = listOf(
            row(0, "111"), row(1, "222"), row(2, "333"),
            row(3, "111"), row(4, "222"), row(5, "333")
        )
        val result = ProposedPatternBacktester.evaluate(
            rows,
            ResearchPatternProposal("cutoff", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "111>222"),
            LocalDate.of(2026, 1, 6), minimumEvaluations = 1
        )
        assertEquals(1, result.supportOccurrences)
        assertEquals(0, result.evaluatedOccurrences)
        assertEquals(ResearchVerificationStatus.UNCERTAIN, result.status)
    }

    @Test
    fun insufficientEvidenceRemainsUncertain() {
        val rows = listOf(row(0, "111"), row(1, "222"), row(2, "333"), row(3, "111"), row(4, "222"), row(5, "333"))
        val result = ProposedPatternBacktester.evaluate(
            rows,
            ResearchPatternProposal("small", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "111>222"),
            LocalDate.of(2026, 1, 7), minimumEvaluations = 10
        )
        assertEquals(ResearchVerificationStatus.UNCERTAIN, result.status)
    }

    @Test
    fun duplicateDatesAreRejectedInsteadOfSilentlyReconciled() {
        val first = row(0, "111")
        try {
            ProposedPatternBacktester.evaluate(
                listOf(first, first.copy(sourceId = "duplicate")),
                ResearchPatternProposal("duplicate", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "111"),
                LocalDate.of(2026, 2, 1)
            )
            fail("Duplicate dates must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate dates"))
        }
    }

    @Test
    fun workflowConnectsScannerEvidenceAndAiAssessmentInOneReport() {
        val rows = listOf(
            row(0, "111"), row(1, "222"), row(2, "333"),
            row(3, "111"), row(4, "222"), row(5, "333")
        )
        val proposal = ResearchPatternProposal(
            "workflow-1", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "111>222"
        )
        val report = ResearchToBacktestWorkflow.run(
            records = rows,
            scannerRankings = emptyList(),
            proposals = listOf(proposal),
            targetDateExclusive = LocalDate.of(2026, 1, 7),
            minimumEvaluations = 1,
            minimumLift = 0.0
        )
        assertEquals(1, report.assessments.size)
        assertEquals(ResearchVerificationStatus.VERIFIED, report.assessments.single().status)
        assertTrue(report.exactPatternEvidence.isNotEmpty())
        assertTrue(report.familyPatternEvidence.isNotEmpty())
        assertTrue(report.compactSummary().contains("VERIFIED"))
        assertTrue(ResearchToBacktestCsvExporter.export(report).contains("workflow-1"))
    }
}
