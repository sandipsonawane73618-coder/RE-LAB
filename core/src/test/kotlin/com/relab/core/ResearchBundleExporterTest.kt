package com.relab.core

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchBundleExporterTest {
    @Test
    fun bundleContainsCompleteLedgerResearchAndIntegrityIndex() {
        val observation = SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "main",
            date = LocalDate.parse("2026-11-16"),
            sourceId = "publisher-a",
            retrievedAt = Instant.parse("2026-11-16T10:00:00Z"),
            publishedAt = Instant.parse("2026-11-16T09:00:00Z"),
            rawText = "577-99-667",
            normalizedResult = "577-99-667",
            status = ObservationStatus.PUBLISHED
        )
        val bytes = ResearchBundleExporter.export(
            listOf(observation), emptyList(), emptyList(), emptyList(),
            Instant.parse("2026-11-17T12:00:00Z")
        )
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }

        assertTrue(entries.keys.containsAll(listOf(
            "source-observations.csv", "derived-consensus-research.csv",
            "walk-forward-audit.csv", "historical-coverage-audit.csv",
            "source-publication-timing.csv", "research-manifest.json",
            "family-movement-walk-forward.csv",
            "research-to-backtest.csv", "research-to-backtest-report.txt",
            "reverse-model-panna-rankings.csv", "reverse-model-family-rankings.csv",
            "reverse-model-jodi-rankings.csv", "reverse-model-walk-forward.csv",
            "README.txt", "bundle-index.json"
        )))
        val ledger = String(entries.getValue("source-observations.csv"), Charsets.UTF_8)
        assertTrue(ledger.contains("publisher-a"))
        assertTrue(ledger.contains("577-99-667"))
        val manifest = String(entries.getValue("research-manifest.json"), Charsets.UTF_8)
        assertTrue(manifest.contains("source_ledger_sha256"))
        val index = String(entries.getValue("bundle-index.json"), Charsets.UTF_8)
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(entries.getValue("source-observations.csv"))
            .joinToString("") { "%02x".format(it) }
        assertTrue(index.contains(expectedHash))
        assertTrue(String(entries.getValue("README.txt"), Charsets.UTF_8).contains("not guaranteed predictions"))
        assertTrue(String(entries.getValue("research-to-backtest.csv"), Charsets.UTF_8).contains("verification_status"))
        assertTrue(String(entries.getValue("research-to-backtest-report.txt"), Charsets.UTF_8).contains("RESEARCH-TO-BACKTEST REPORT"))
        assertNotNull(bytes)
    }
}
