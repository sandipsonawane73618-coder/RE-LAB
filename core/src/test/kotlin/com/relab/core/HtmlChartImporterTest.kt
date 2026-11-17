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
        assertEquals("Expected 3 dated rows; got ${report.observations.size}. skipped=${report.skippedRows}, issues=${report.issues}, observations=${report.observations.map { Triple(it.date, it.status, it.rawText) }}", 3, report.observations.size)
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

    @Test fun extractsWeeklyPanelChartCellsAndTwoDigitYears() {
        val html = """
            <table>
              <tr><th>Date</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th><th>SAT</th><th>SUN</th></tr>
              <tr>
                <td>16/11/26 to 22/11/26</td>
                <td>5 7 7 99 6 6 7</td>
                <td>1 1 5 73 2 2 9</td>
                <td>3 6 7 69 2 8 9</td>
                <td></td><td></td><td></td><td></td>
              </tr>
            </table>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.SRIDEVI, "main", retrieved)
        assertEquals(3, report.observations.size)
        assertEquals("2026-11-16", report.observations[0].date.toString())
        assertEquals("577-99-667", report.observations[0].normalizedResult)
        assertEquals("2026-11-17", report.observations[1].date.toString())
        assertEquals("115-73-229", report.observations[1].normalizedResult)
        assertEquals("2026-11-18", report.observations[2].date.toString())
        assertEquals("367-69-289", report.observations[2].normalizedResult)
    }

    @Test fun extractsKalyanSixDayWeeklyPanelWithoutTreatingSundayAsTradingDay() {
        val html = """
            <table>
              <tr><th>Date</th><th>MON</th><th>TUE</th><th>WED</th><th>THU</th><th>FRI</th><th>SAT</th></tr>
              <tr>
                <td>16/11/26 to 21/11/26</td>
                <td>5 7 7 99 6 6 7</td>
                <td>1 1 5 73 2 2 9</td>
                <td>3 6 7 69 2 8 9</td>
                <td>2 7 7 44 5 8 9</td>
                <td>1 2 3 66 4 5 7</td>
                <td>4 4 4 24 1 1 1</td>
              </tr>
            </table>
        """.trimIndent()
        val report = HtmlChartImporter.import(html, url, Market.KALYAN, "main", retrieved)
        assertEquals(6, report.observations.size)
        assertEquals("2026-11-16", report.observations.first().date.toString())
        assertEquals("2026-11-21", report.observations.last().date.toString())
        assertTrue(report.observations.all { it.date.dayOfWeek != java.time.DayOfWeek.SUNDAY })
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
