package com.relab.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class HtmlChartImporterTest {
    private val url = "https://charts.example/archive"
    private val retrieved = Instant.parse("2026-11-17T10:00:00Z")

    @Test fun extractsValidDatedRowsAndKeepsSourceProvenance() {
        val html = """
            <html><table>
              <tr><th>Date</th><th>Result</th></tr>
              <tr><td>2026-11-09</td><td>277-65-249</td></tr>
              <tr><td>10/11/2026</td><td>578-07-368</td></tr>
              <tr><td>11 Nov 2026</td><td>800-81-137</td></tr>
            </table></html>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI, "main", retrieved)
        assertEquals(3, report.observations.size)
        assertEquals("charts.example", report.observations.first().sourceId)
        assertEquals(url, report.observations.first().sourceUrl)
        assertEquals(ObservationStatus.PUBLISHED, report.observations.first().status)
        assertEquals("277-65-249", report.observations.first().normalizedResult)
    }

    @Test fun invalidMathIsRetainedAsMalformedNotPublished() {
        val html = """<table><tr><td>2026-11-09</td><td>277-99-249</td></tr></table>"""
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI, "main", retrieved)
        assertEquals(1, report.observations.size)
        assertEquals(ObservationStatus.MALFORMED, report.observations.single().status)
        assertNull(report.observations.single().normalizedResult)
        assertEquals(1, report.issues.size)
    }

    @Test fun ignoresRowsWithoutExplicitDateAndResult() {
        val html = """<table><tr><td>Date</td><td>Open</td><td>Close</td></tr><tr><td>Today</td><td>Pending</td></tr></table>"""
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI, "main", retrieved)
        assertTrue(report.observations.isEmpty())
        assertEquals(2, report.skippedRows)
    }

    @Test fun rejectsNonHttpSchemes() {
        try {
            HtmlChartImporter.import("<table/>", "file:///tmp/chart.html", Market.SRIDEVI, "main", retrieved)
            fail("Expected URL scheme rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("HTTP"))
        }
    }
}
