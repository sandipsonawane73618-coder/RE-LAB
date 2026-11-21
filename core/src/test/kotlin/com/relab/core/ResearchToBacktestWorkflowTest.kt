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
    fun pairedExactBinomialHandlesBalancedAndOneSidedDiscordance() {
        assertNull(ProposedPatternBacktester.pairedExactBinomialPValue(0, 0))
        assertEquals(1.0, ProposedPatternBacktester.pairedExactBinomialPValue(5, 5)!!, 1e-12)
        assertEquals(0.0625, ProposedPatternBacktester.pairedExactBinomialPValue(5, 0)!!, 1e-12)
        assertEquals(0.0625, ProposedPatternBacktester.pairedExactBinomialPValue(0, 5)!!, 1e-12)
        assertEquals(0.001953125, ProposedPatternBacktester.pairedExactBinomialPValue(10, 0)!!, 1e-12)
    }

    @Test
    fun pairedExactBinomialIsBoundedAndMemorySafeForLargeCounts() {
        val balanced = ProposedPatternBacktester.pairedExactBinomialPValue(100_000, 100_000)!!
        assertTrue(balanced in 0.99..1.0)
        val oneSided = ProposedPatternBacktester.pairedExactBinomialPValue(100_000, 0)!!
        assertTrue(oneSided in 0.0..1.0)
        assertTrue(oneSided.isFinite())
    }

    @Test
    fun pairedExactBinomialRejectsNegativeCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            ProposedPatternBacktester.pairedExactBinomialPValue(-1, 2)
        }
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
        assertEquals(39, result.patternOnlyHits)
        assertEquals(0, result.baselineOnlyHits)
        assertNotNull(result.pairedExactPValue)
        assertTrue(result.pairedExactPValue!! < 0.01)
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
            LocalDate.of(2026, 1, 1).plusDays(120), minimumEvaluations = 20
        )
        assertEquals(19, exact.evaluatedOccurrences)
        assertEquals(ResearchVerificationStatus.UNCERTAIN, exact.status)
        assertEquals(39, family.evaluatedOccurrences)
        assertEquals(39, family.hits)
        assertEquals(ResearchVerificationStatus.VERIFIED, family.status)
    }

    @Test
    fun emptyProposalSetStillProducesScannerAndPatternEvidenceReport() {
        val report = ResearchToBacktestWorkflow.run(
            records = repeatedRows(), scannerRankings = emptyList(), proposals = emptyList(),
            targetDateExclusive = LocalDate.of(2026, 1, 1).plusDays(120)
        )
        assertTrue(report.assessments.isEmpty())
        assertTrue(report.exactPatternEvidence.isNotEmpty())
        assertTrue(report.familyPatternEvidence.isNotEmpty())
        assertTrue(report.compactSummary().contains("No executable AI proposals"))
        assertEquals(repeatedRows().size, report.validConsensusRowsBeforeCutoff)
    }
    @Test
    fun invalidAiProposalIsRejectedWithoutAbortingValidProposals() {
        val rows = repeatedRows()
        val valid = ResearchPatternProposal(
            "valid", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "111>222"
        )
        val invalid = ResearchPatternProposal(
            "invalid", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "11>222"
        )
        val report = ResearchToBacktestWorkflow.run(
            records = rows, scannerRankings = emptyList(), proposals = listOf(invalid, valid),
            targetDateExclusive = LocalDate.of(2026, 1, 1).plusDays(120), minimumEvaluations = 20
        )
        val rejected = report.assessments.single { it.proposal.id == "invalid" }
        val verified = report.assessments.single { it.proposal.id == "valid" }
        assertEquals(ResearchVerificationStatus.REJECTED, rejected.status)
        assertTrue(rejected.rejectionReason.orEmpty().contains("exactly 3 digits"))
        assertEquals(ResearchVerificationStatus.VERIFIED, verified.status)
        assertTrue(report.compactSummary().contains("REASON="))
        assertTrue(report.compactSummary().contains("invalid proposals=1"))
        assertTrue(report.compactSummary().contains("statistical rejections=0"))
        assertTrue(report.compactSummary().contains("HISTORICALLY_VERIFIED"))
        assertTrue(report.compactSummary().contains("INVALID_PROPOSAL"))
        val csv = ResearchToBacktestCsvExporter.export(report)
        assertTrue(csv.contains("diagnostic_category"))
        assertTrue(csv.contains("INVALID_PROPOSAL"))
        assertTrue(csv.contains("HISTORICALLY_VERIFIED"))
        assertTrue(csv.contains("rejection_reason"))
    }
    @Test
    fun lowSampleDiagnosticUsesConfiguredMinimumAndAppearsInCsv() {
        val rows = repeatedRows()
        val proposal = ResearchPatternProposal(
            "low-sample", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "111>222"
        )
        val report = ResearchToBacktestWorkflow.run(
            records = rows, scannerRankings = emptyList(), proposals = listOf(proposal),
            targetDateExclusive = LocalDate.of(2026, 1, 1).plusDays(120),
            minimumEvaluations = 100
        )
        val result = report.assessments.single()
        assertEquals(39, result.evaluatedOccurrences)
        assertEquals(ResearchVerificationStatus.UNCERTAIN, result.status)
        assertTrue(report.compactSummary().contains("LOW_SAMPLE_UNCERTAIN"))
        val csv = ResearchToBacktestCsvExporter.export(report)
        assertTrue(csv.contains("UNCERTAIN,LOW_SAMPLE_UNCERTAIN"))
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
        assertEquals(rows.size, report.datasetRecordsProvided)
        assertEquals(rows.size, report.validConsensusRowsBeforeCutoff)
        assertEquals(0, report.excludedOnOrAfterCutoff)
        assertEquals(1, report.coveredMarketSessions)
        val csv = ResearchToBacktestCsvExporter.export(report)
        assertTrue(csv.contains("workflow-1"))
        assertTrue(csv.contains("pattern_only_hits"))
        assertTrue(csv.contains("paired_exact_p_value"))
    }
    @Test
    fun workflowRejectsScannerMarketSessionOutsideConsensusRowsAndImpossibleTrainingCounts() {
        val rows = repeatedRows()
        val target = LocalDate.of(2026, 1, 1).plusDays(120)
        val invalidMarketSession = ReverseEngineeringRanking(
            market = Market.KALYAN, sessionId = "missing-session", side = ReverseEngineeringSide.OPEN,
            targetDate = target, trainingRecords = 0, minimumHistoryRequired = 30,
            abstained = true, candidates = emptyList(), familyCandidates = emptyList(),
            activeEvidenceLayers = emptyList(), note = "test"
        )
        try {
            ResearchToBacktestWorkflow.run(rows, listOf(invalidMarketSession), emptyList(), target)
            fail("Scanner scope outside eligible consensus history must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("market/session present"))
        }

        val impossibleTrainingCount = invalidMarketSession.copy(
            market = Market.SRIDEVI, sessionId = "main", trainingRecords = rows.size + 1
        )
        try {
            ResearchToBacktestWorkflow.run(rows, listOf(impossibleTrainingCount), emptyList(), target)
            fail("Scanner training count exceeding available eligible rows must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("training record count"))
        }
    }


    private fun csvColumns(line: String): List<String> {
        val columns = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    columns += field.toString()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
            index++
        }
        check(!quoted) { "Unclosed CSV quote" }
        columns += field.toString()
        return columns
    }

    @Test
    fun proposalCsvRowsMatchHeaderWidthAndEscapeQuotedFields() {
        val rows = repeatedRows()
        val valid = ResearchPatternProposal(
            "csv-valid", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "111>222",
            rationale = "sequence, with \"quoted\" evidence"
        )
        val invalid = ResearchPatternProposal(
            "csv-invalid", Market.SRIDEVI, "main", PatternEvidenceChannel.OPEN_PANNA, "bad",
            rationale = "invalid, \"quoted\" proposal"
        )
        val report = ResearchToBacktestWorkflow.run(
            records = rows,
            scannerRankings = emptyList(),
            proposals = listOf(valid, invalid),
            targetDateExclusive = LocalDate.of(2026, 1, 1).plusDays(120)
        )
        val csvRows = ResearchToBacktestCsvExporter.export(report).split("\\n")
        val header = csvColumns(csvRows.first())
        assertEquals(27, header.size)
        assertEquals(3, csvRows.size)
        csvRows.drop(1).forEach { line ->
            assertEquals(header.size, csvColumns(line).size)
        }
        assertTrue(csvRows.any { it.contains("\"sequence, with \"\"quoted\"\" evidence\"") })
    }


}
