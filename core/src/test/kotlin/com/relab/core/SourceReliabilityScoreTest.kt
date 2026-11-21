package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SourceReliabilityScoreTest {
    private fun row(
        source: String,
        day: Int,
        result: String? = null,
        status: ObservationStatus = ObservationStatus.PUBLISHED,
        retrieved: String = "2026-11-01T10:00:00Z",
        published: String? = null
    ) = SourceObservation(
        market = Market.SRIDEVI,
        sessionId = "main",
        date = LocalDate.of(2026, 11, 1).plusDays(day.toLong()),
        sourceId = source,
        retrievedAt = Instant.parse(retrieved),
        publishedAt = published?.let(Instant::parse),
        rawText = result ?: status.name,
        normalizedResult = if (status == ObservationStatus.PUBLISHED) result ?: "111-11-111" else null,
        status = status
    )

    @Test fun combinesCompletenessTimingAndIndependentCorroborationWithDocumentedWeights() {
        val rows = listOf(
            row("publisher-a", 1, "111-11-111", retrieved = "2026-11-01T10:00:00Z", published = "2026-11-01T09:00:00Z"),
            row("publisher-b", 1, "111-11-111", retrieved = "2026-11-01T10:00:00Z", published = "2026-11-01T09:00:00Z"),
            row("publisher-a", 2, "222-22-222", retrieved = "2026-11-02T15:00:00Z", published = "2026-11-02T11:00:00Z"),
            row("publisher-b", 2, "333-33-333", retrieved = "2026-11-02T15:00:00Z", published = "2026-11-02T11:00:00Z"),
            row("publisher-a", 3, status = ObservationStatus.MISSING_FROM_SOURCE),
            row("publisher-a", 4, status = ObservationStatus.PENDING),
            row("publisher-a", 5, status = ObservationStatus.HOLIDAY_CLOSED)
        )

        val score = SourceReliabilityScore.summarize(rows).single { it.sourceId == "publisher-a" }

        assertEquals(3, score.eligibleObservations)
        assertEquals(2, score.publishedObservations)
        assertEquals(1, score.missingObservations)
        assertEquals(2.0 / 3.0, score.publicationCompletenessRate!!, 0.000001)
        assertEquals(1.0, score.publicationTimestampCoverageRate!!, 0.0)
        assertEquals(0.5, score.timelyPublicationRate!!, 0.0)
        assertEquals(0.5, score.independentCorroborationRate!!, 0.0)
        assertEquals(2, score.corroborationComparableDates)
        assertEquals(64.1666667, score.score!!, 0.00001)
        assertEquals(4, score.includedComponents)
        assertEquals(SourceEvidenceLevel.LOW_SAMPLE, score.evidenceLevel)
    }

    @Test fun negativeSubMinuteLagIsNotCountedAsTimely() {
        val rows = listOf(
            row("publisher-a", 1, "111-11-111",
                retrieved = "2026-11-01T09:59:30Z", published = "2026-11-01T10:00:00Z")
        )
        val score = SourceReliabilityScore.summarize(rows).single()
        assertEquals(1.0, score.publicationTimestampCoverageRate!!, 0.0)
        assertEquals(0.0, score.timelyPublicationRate!!, 0.0)
    }

    @Test fun missingTimestampIsNeverSubstitutedWithRetrievalTime() {
        val score = SourceReliabilityScore.summarize(
            listOf(row("publisher-a", 1, "111-11-111"))
        ).single()
        assertEquals(0.0, score.publicationTimestampCoverageRate!!, 0.0)
        assertNull(score.timelyPublicationRate)
        assertNull(score.independentCorroborationRate)
        assertEquals(2, score.includedComponents)
    }

    @Test fun emptyInputAndInvalidLagAreHandled() {
        assertTrue(SourceReliabilityScore.summarize(emptyList()).isEmpty())
        try {
            SourceReliabilityScore.summarize(emptyList(), maximumPublicationLagMinutes = -1)
            fail("Negative lag threshold must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun marketAndSessionRemainSeparate() {
        val rows = listOf(
            row("publisher-a", 1, "111-11-111"),
            row("publisher-a", 1, "111-11-111").copy(sessionId = "night"),
            row("publisher-a", 1, "111-11-111").copy(market = Market.KALYAN)
        )
        val scores = SourceReliabilityScore.summarize(rows)
        assertEquals(3, scores.size)
        assertEquals(setOf(Market.SRIDEVI, Market.KALYAN), scores.map { it.market }.toSet())
        assertEquals(setOf("main", "night"), scores.filter { it.market == Market.SRIDEVI }.map { it.sessionId }.toSet())
    }
}
