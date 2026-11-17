package com.relab.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ResearchManifestExporterTest {
    @Test
    fun manifestIncludesCountsRangesAndStableLedgerChecksum() {
        val observation = SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "morning",
            date = LocalDate.parse("2026-11-16"),
            sourceId = "archive-A",
            retrievedAt = Instant.parse("2026-11-16T10:00:00Z"),
            publishedAt = Instant.parse("2026-11-16T09:00:00Z"),
            rawText = "577-99-667",
            normalizedResult = "577-99-667",
            status = ObservationStatus.PUBLISHED
        )
        val generated = Instant.parse("2026-11-17T12:00:00Z")
        val first = ResearchManifestExporter.export(listOf(observation), emptyList(), emptyList(), generated)
        val second = ResearchManifestExporter.export(listOf(observation), emptyList(), emptyList(), generated)

        assertEquals(first, second)
        assertContains(first, "\"source_observation_count\": 1")
        assertContains(first, "\"SINGLE_SOURCE_UNVERIFIED\": 1")
        assertContains(first, "\"consensus_chart_records_exported\": 0")
        assertContains(first, "\"start_date\":\"2026-11-16\"")
        assertContains(first, "\"source_ledger_sha256\": \"")
        assertContains(first, ResearchManifestExporter.RULESET_VERSION)
    }

    @Test
    fun manifestEscapesSessionNamesAsValidJsonStrings() {
        val observation = SourceObservation(
            market = Market.KALYAN,
            sessionId = "evening \"special\"",
            date = LocalDate.parse("2026-11-16"),
            sourceId = "source",
            retrievedAt = Instant.parse("2026-11-16T12:00:00Z"),
            rawText = "pending",
            status = ObservationStatus.PENDING
        )
        val manifest = ResearchManifestExporter.export(
            listOf(observation), emptyList(), emptyList(), Instant.parse("2026-11-17T12:00:00Z")
        )
        assertContains(manifest, "evening \\\"special\\\"")
        assertContains(manifest, "\"PENDING\": 1")
    }
}
