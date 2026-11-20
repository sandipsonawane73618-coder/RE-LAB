package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SourceIndependenceAuditTest {
    private fun row(
        publisher: String,
        day: Int,
        result: String,
        session: String = "main",
        retrieved: String = "2026-11-20T10:00:00Z"
    ) = SourceObservation(
        market = Market.SRIDEVI,
        sessionId = session,
        date = LocalDate.of(2026, 11, 1).plusDays(day.toLong()),
        sourceId = publisher,
        sourceUrl = "https://$publisher.example/chart",
        retrievedAt = Instant.parse(retrieved),
        rawText = result,
        normalizedResult = result,
        status = ObservationStatus.PUBLISHED
    )

    @Test fun onePublisherAndItsMultiplePagesNeverCountAsIndependentSources() {
        val rows = listOf(
            row("canonical-publisher", 1, "111-34-222"),
            row("canonical-publisher", 2, "333-66-444").copy(sourceUrl = "https://canonical-publisher.example/jodi")
        )
        val report = SourceIndependenceAudit.summarize(rows).single()
        assertEquals(1, report.independentPublisherCount)
        assertEquals(listOf("canonical-publisher"), report.publisherIds)
        assertTrue(report.pairAgreements.isEmpty())
        assertEquals(2, report.singlePublisherDates)
        assertEquals(0, report.consensusDates)
    }

    @Test fun pairwiseAgreementAndConflictUseOnlySharedDates() {
        val rows = listOf(
            row("publisher-a", 1, "111-34-222"),
            row("publisher-b", 1, "111-34-222"),
            row("publisher-a", 2, "333-66-444"),
            row("publisher-b", 2, "444-88-555"),
            row("publisher-a", 3, "555-55-555")
        )
        val report = SourceIndependenceAudit.summarize(rows).single()
        assertEquals(2, report.independentPublisherCount)
        assertEquals(2, report.multiPublisherDates)
        assertEquals(1, report.consensusDates)
        assertEquals(1, report.conflictDates)
        val pair = report.pairAgreements.single()
        assertEquals(2, pair.sharedPublishedDates)
        assertEquals(1, pair.agreedDates)
        assertEquals(1, pair.conflictingDates)
        assertEquals(0, pair.ambiguousDates)
        assertEquals(0.5, pair.agreementRate!!, 0.0)
    }

    @Test fun changedSnapshotsFromOnePublisherAreAmbiguousNotIndependentDisagreement() {
        val rows = listOf(
            row("publisher-a", 1, "111-34-222"),
            row("publisher-a", 1, "333-66-444", retrieved = "2026-11-20T11:00:00Z"),
            row("publisher-b", 1, "111-34-222")
        )
        val report = SourceIndependenceAudit.summarize(rows).single()
        val pair = report.pairAgreements.single()
        assertEquals(1, pair.sharedPublishedDates)
        assertEquals(0, pair.agreedDates)
        assertEquals(0, pair.conflictingDates)
        assertEquals(1, pair.ambiguousDates)
        assertNull(pair.agreementRate)
        assertEquals(1, report.conflictDates)
    }

    @Test fun sessionsAndMarketsRemainSeparated() {
        val rows = listOf(
            row("publisher-a", 1, "111-34-222"),
            row("publisher-b", 1, "111-34-222"),
            row("publisher-a", 1, "111-34-222", session = "night").copy(market = Market.KALYAN)
        )
        val reports = SourceIndependenceAudit.summarize(rows)
        assertEquals(2, reports.size)
        assertEquals(1, reports.single { it.market == Market.SRIDEVI }.pairAgreements.size)
        assertEquals(1, reports.single { it.market == Market.KALYAN }.independentPublisherCount)
    }
}
