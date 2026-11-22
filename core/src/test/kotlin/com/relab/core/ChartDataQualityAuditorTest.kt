package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ChartDataQualityAuditorTest {
    private fun record(
        date: String,
        open: String = "123",
        jodi: String = "65",
        close: String = "456",
        source: String = "archive"
    ) = ChartRecord(
        Market.SRIDEVI, LocalDate.parse(date), open, jodi, close, source,
        sessionId = "main"
    )

    private fun observation(
        date: String,
        result: String,
        publishedAt: String? = "2026-11-17T08:00:00Z",
        retrievedAt: String = "2026-11-17T09:00:00Z",
        source: String = "archive"
    ) = SourceObservation(
        market = Market.SRIDEVI,
        sessionId = "main",
        date = LocalDate.parse(date),
        sourceId = source,
        sourceRecordId = "row-$date",
        retrievedAt = Instant.parse(retrievedAt),
        publishedAt = publishedAt?.let(Instant::parse),
        rawText = result,
        normalizedResult = result,
        status = ObservationStatus.PUBLISHED
    )

    @Test fun reportsInvalidChartRowsWithoutRemovingThem() {
        val valid = record("2026-11-16")
        val invalid = record("2026-11-17", jodi = "00")
        val report = ChartDataQualityAuditor.audit(listOf(valid, invalid), emptyList())

        assertEquals(2, report.chartRecordCount)
        assertEquals(1, report.validChartRecordCount)
        assertEquals(1, report.invalidChartRecords.size)
        assertTrue(report.hasBlockingIssues)
    }

    @Test fun distinguishesSameResultDuplicatesFromConflictingResults() {
        val date = "2026-11-17"
        val rows = listOf(
            record(date, source = "publisher-a"),
            record(date, source = "publisher-b"),
            record(date, open = "222", jodi = "65", close = "456", source = "publisher-c")
        )
        val report = ChartDataQualityAuditor.audit(rows, emptyList())

        assertEquals(1, report.duplicateChartDays.size)
        assertEquals(DuplicateChartDayState.CONFLICTING_RESULTS, report.duplicateChartDays.single().state)
        assertEquals(3, report.duplicateChartDays.single().records.size)
    }

    @Test fun reportsCoverageWithoutAssumingMarketCalendarOrInventingMissingDays() {
        val rows = listOf(record("2026-11-10"), record("2026-11-17"))
        val coverage = ChartDataQualityAuditor.audit(rows, emptyList()).timelineCoverage.single()

        assertEquals(LocalDate.parse("2026-11-10"), coverage.firstDate)
        assertEquals(LocalDate.parse("2026-11-17"), coverage.lastDate)
        assertEquals(2, coverage.distinctDates)
    }

    @Test fun flagsImpossiblePublicationAfterRetrievalAndMalformedPublishedRows() {
        val latePublication = observation(
            "2026-11-17", "123-65-456",
            publishedAt = "2026-11-17T10:00:00Z",
            retrievedAt = "2026-11-17T09:00:00Z"
        )
        val malformed = latePublication.copy(
            sourceRecordId = "bad",
            publishedAt = Instant.parse("2026-11-17T08:00:00Z"),
            normalizedResult = "123-00-456"
        )
        val report = ChartDataQualityAuditor.audit(emptyList(), listOf(latePublication, malformed))

        assertEquals(1, report.publicationAfterRetrieval.size)
        assertEquals(1, report.invalidPublishedObservations.size)
        assertTrue(report.hasBlockingIssues)
    }

    @Test fun repeatedResultAcrossDatesIsReviewOnlyAndDoesNotBlockByItself() {
        val rows = listOf(
            observation("2026-11-10", "123-65-456"),
            observation("2026-11-11", "123-65-456"),
            observation("2026-11-12", "123-65-456")
        )
        val report = ChartDataQualityAuditor.audit(emptyList(), rows)

        assertEquals(1, report.repeatedPublishedResultReviewGroups.size)
        assertFalse(report.hasBlockingIssues)
        assertEquals(1, report.reviewIssueCount)
    }

    @Test fun keepsMarketAndSessionCoverageIndependent() {
        val other = record("2026-11-17").copy(sessionId = "night")
        val report = ChartDataQualityAuditor.audit(listOf(record("2026-11-16"), other), emptyList())

        assertEquals(2, report.timelineCoverage.size)
        assertNotEquals(report.timelineCoverage[0].sessionId, report.timelineCoverage[1].sessionId)
    }
}
