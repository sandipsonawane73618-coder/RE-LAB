package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class EndToEndResearchAuditWorkflowTest {
    private fun row(day: Int, open: String, market: Market = Market.SRIDEVI, session: String = "main"): ChartRecord {
        val close = "444"
        val jodi = "${open.sumOf { it.digitToInt() } % 10}${close.sumOf { it.digitToInt() } % 10}"
        return ChartRecord(
            market = market,
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            openPanna = open,
            jodi = jodi,
            closePanna = close,
            sourceId = "source-$day",
            sessionId = session
        )
    }

    private fun rows(): List<ChartRecord> = (0 until 40).flatMap { cycle ->
        listOf(
            row(cycle * 3, "111"),
            row(cycle * 3 + 1, "222"),
            row(cycle * 3 + 2, "333")
        )
    }

    @Test fun connectsResearchEvidenceAiAssessmentAndAllThreeModelChannels() {
        val data = rows()
        val cutoff = LocalDate.of(2026, 1, 1).plusDays(120)
        val proposal = ResearchPatternProposal(
            "joined-ai-proposal", Market.SRIDEVI, "main",
            PatternEvidenceChannel.OPEN_PANNA, "111>222"
        )

        val report = EndToEndResearchAuditWorkflow.run(
            records = data,
            scannerRankings = emptyList(),
            proposals = listOf(proposal),
            targetDateExclusive = cutoff,
            minimumEvaluations = 20,
            minimumModelHistory = 30
        )

        assertEquals(1, report.research.assessments.size)
        assertTrue(report.research.exactPatternEvidence.isNotEmpty())
        assertTrue(report.research.familyPatternEvidence.isNotEmpty())
        assertEquals(
            setOf(PredictionChannel.OPEN_PANNA, PredictionChannel.CLOSE_PANNA, PredictionChannel.JODI),
            report.multiModelWalkForward.map { it.channel }.toSet()
        )
        assertEquals(3, report.multiModelWalkForward.size)
        report.multiModelWalkForward.forEach { result ->
            assertEquals(3, result.models.size)
            assertEquals(90, result.consensus.requestedTargets)
            assertTrue(result.models.all { it.requestedTargets == 90 })
            assertTrue(result.note.contains("after scoring"))
        }
        assertTrue(report.compactSummary().contains("MULTI-MODEL WALK-FORWARD AUDIT"))
        val csv = EndToEndResearchAuditWorkflow.exportWalkForwardCsv(report)
        assertTrue(csv.contains("smart-consensus"))
        assertTrue(csv.contains("exact-frequency"))
        assertTrue(csv.contains("recency-weighted-frequency"))
        assertTrue(csv.contains("previous-result-transition"))
        assertTrue(csv.contains("OPEN_PANNA"))
        assertTrue(csv.contains("CLOSE_PANNA"))
        assertTrue(csv.contains("JODI"))
    }

    @Test fun futureRowsCannotChangeAnyEarlierWalkForwardMetric() {
        val data = rows()
        val cutoff = LocalDate.of(2026, 1, 1).plusDays(120)
        val future = listOf(row(121, "999"), row(122, "888"), row(123, "777"))
        val before = EndToEndResearchAuditWorkflow.run(
            data, emptyList(), emptyList(), cutoff, minimumModelHistory = 30
        )
        val after = EndToEndResearchAuditWorkflow.run(
            data + future, emptyList(), emptyList(), cutoff, minimumModelHistory = 30
        )

        assertEquals(before.multiModelWalkForward, after.multiModelWalkForward)
    }

    @Test fun keepsMarketAndSessionWalkForwardReportsSeparate() {
        val data = rows() +
            rows().map { it.copy(market = Market.KALYAN, sessionId = "night") }
        val report = EndToEndResearchAuditWorkflow.run(
            data,
            emptyList(),
            emptyList(),
            LocalDate.of(2026, 1, 1).plusDays(120),
            minimumModelHistory = 30
        )

        assertEquals(6, report.multiModelWalkForward.size)
        assertEquals(
            setOf(Market.SRIDEVI to "main", Market.KALYAN to "night"),
            report.multiModelWalkForward.map { it.market to it.sessionId }.toSet()
        )
    }

    @Test fun walkForwardCsvEscapesCommaQuotesAndEmbeddedNewlinesInSessionIds() {
        val session = "night, \"special\"\nsecond-line"
        val data = rows().map { it.copy(sessionId = session) }
        val report = EndToEndResearchAuditWorkflow.run(
            data, emptyList(), emptyList(),
            LocalDate.of(2026, 1, 1).plusDays(120),
            minimumModelHistory = 30
        )

        val csv = EndToEndResearchAuditWorkflow.exportWalkForwardCsv(report)
        assertTrue(csv.contains("\"night, \"\"special\"\"\nsecond-line\""))
    }

    @Test fun reportsOriginalSourceReconciliationWithoutPromotingUnverifiedRows() {
        val retrieved = java.time.Instant.parse("2026-01-10T12:00:00Z")
        fun observation(
            date: LocalDate,
            source: String,
            result: String?,
            status: ObservationStatus = if (result == null) ObservationStatus.PENDING else ObservationStatus.PUBLISHED
        ) = SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "main",
            date = date,
            sourceId = source,
            retrievedAt = retrieved,
            rawText = result ?: "pending",
            normalizedResult = result,
            status = status
        )

        val observations = listOf(
            observation(LocalDate.of(2026, 1, 1), "publisher-a", "123-62-444"),
            observation(LocalDate.of(2026, 1, 1), "publisher-b", "123-62-444"),
            observation(LocalDate.of(2026, 1, 2), "publisher-a", "222-62-444"),
            observation(LocalDate.of(2026, 1, 3), "publisher-a", "333-92-444"),
            observation(LocalDate.of(2026, 1, 3), "publisher-b", "999-72-444"),
            observation(LocalDate.of(2026, 1, 4), "publisher-a", null)
        )
        val sourceQuality = ReconciledChartBuilder.build(observations)
        val report = EndToEndResearchAuditWorkflow.run(
            records = sourceQuality.records,
            scannerRankings = emptyList(),
            proposals = emptyList(),
            targetDateExclusive = LocalDate.of(2026, 2, 1),
            minimumModelHistory = 30,
            sourceQuality = sourceQuality,
            sourceObservationCount = observations.size
        )

        assertEquals(1, sourceQuality.records.size)
        assertEquals(1, sourceQuality.singleSourceUnverifiedGroups.size)
        assertEquals(1, sourceQuality.conflictGroups.size)
        assertEquals(1, sourceQuality.noPublishedResultGroups.size)
        assertEquals(6, report.research.sourceObservationCount)
        assertEquals(1, report.research.sourceConsensusGroups)
        assertEquals(1, report.research.sourceSingleSourceGroups)
        assertEquals(1, report.research.sourceConflictGroups)
        assertEquals(1, report.research.sourceNoPublishedGroups)
        assertEquals(0, report.research.sourceInvalidGroups)
        assertTrue(report.compactSummary().contains("observations=6 | consensus=1 | conflicts=1"))
    }

    @Test fun invalidMinimumHistoryAndCandidateLimitAreRejected() {
        val data = rows()
        val cutoff = LocalDate.of(2026, 1, 1).plusDays(120)
        assertThrows(IllegalArgumentException::class.java) {
            EndToEndResearchAuditWorkflow.run(
                data, emptyList(), emptyList(), cutoff, minimumModelHistory = 0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EndToEndResearchAuditWorkflow.run(
                data, emptyList(), emptyList(), cutoff, candidateLimit = 101
            )
        }
    }
}
