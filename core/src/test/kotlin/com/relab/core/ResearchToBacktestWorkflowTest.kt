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

    private fun repeatedRows(): List<ChartRecord> = (0 until 40).flatMap { cycle ->
        listOf(row(cycle * 3, "111"), row(cycle * 3 + 1, "222"), row(cycle * 3 + 2, "333"))
    }

    @Test
    fun repeatedAiPatternIsBacktestedChronologicallyAgainstPriorOnlyBaseline() {
        val rows = repeatedRows()
        val proposal = ResearchPatternProposal(
            id = "ai-1", market = Market.SRIDEVI, sessionId = "main",
            channel = PatternEvidenceChannel.OPEN_PANNA, pattern = "111>222",
            rationale = "Repeated two-step sequence"
        )
        val result = ProposedPatternBacktester.evaluate(
            rows, proposal, LocalDate.of(2026, 1, 1).plusDays(120), minimumEvaluations = 20, minimumLift = 0.05
        )
        assertEquals(40, result.supportOccurrences)
        assertEquals(39, result.evaluatedOccurrences)
        assertEquals(39, result.hits)
        assertEquals(0, result.misses)
        assertEquals(0, result.baselineHits)
        assertEquals(ResearchVerificationStatus.VERIFIED, result.status)
        assertNotNull(result.wilsonLowerBound)
    }

    @Test
    fun familyMovementProposalMatchesFamilyAndReverseVariantsWithoutChangingExactMode() {
        val rows = (0 until 40).flatMap { cycle ->
            val values = if (cycle % 2 == 0) listOf("111", "222", "333") else listOf("666", "777", "888")
            values.mapIndexed { index, value -> row(cycle * 3 + index, value) }
        }
        val exact = ProposedPatternBacktester.evaluate(
            rows,
            ResearchPatternProposal("exact", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "111>222"),
            LocalDate.of(2026, 1, 1).plusDays(120), minimumEvaluations = 20
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
        assertEquals(39, family.evaluatedOccurrences)
        assertEquals(39, family.hits)
        assertEquals(ResearchVerificationStatus.VERIFIED, family.status)
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
        val rows = repeatedRows()
        val proposal = ResearchPatternProposal(
            "workflow-1", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "111>222"
        )
        val report = ResearchToBacktestWorkflow.run(
            records = rows,
            scannerRankings = emptyList(),
            proposals = listOf(proposal),
            targetDateExclusive = LocalDate.of(2026, 1, 1).plusDays(120),
            minimumEvaluations = 20,
            minimumLift = 0.05
        )
        assertEquals(1, report.assessments.size)
        assertEquals(ResearchVerificationStatus.VERIFIED, report.assessments.single().status)
        assertTrue(report.exactPatternEvidence.isNotEmpty())
        assertTrue(report.familyPatternEvidence.isNotEmpty())
        assertTrue(report.compactSummary().contains("VERIFIED"))
        assertTrue(ResearchToBacktestCsvExporter.export(report).contains("workflow-1"))
    }
}
