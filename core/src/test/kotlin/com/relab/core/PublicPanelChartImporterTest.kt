package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PublicPanelChartImporterTest {
    private fun weekRow(
        range: String = "5/1/26 to 11/1/26",
        open: String = "123",
        jodi: String = "65",
        close: String = "456"
    ): String = buildString {
        append("<tr><td>").append(range).append("</td>")
        repeat(7) {
            append("<td>").append(open.toCharArray().joinToString("<br>")).append("</td>")
            append("<td>").append(jodi).append("</td>")
            append("<td>").append(close.toCharArray().joinToString("<br>")).append("</td>")
        }
        append("</tr>")
    }

    private fun html(vararg rows: String) =
        "<html><body><table>${rows.joinToString("")}</table></body></html>"

    @Test
    fun catalogueContainsExactlyNineDistinctMarketPanelSources() {
        assertEquals(Market.entries.toSet(), PublicPanelChartImporter.sources.map { it.market }.toSet())
        assertEquals(9, PublicPanelChartImporter.sources.size)
        assertEquals(9, PublicPanelChartImporter.sources.map { it.url }.distinct().size)
        assertEquals(
            Market.SRIDEVI_NIGHT,
            Market.fromSourceUrl("https://sattamatkajodi.com/record/sridevi-night-satta-penal-chart.php")
        )
    }

    @Test
    fun parsesWeeklyOpenJodiCloseCellsIntoDatedValidatedRecords() {
        val source = PublicPanelChartImporter.sources.single { it.market == Market.KALYAN }
        val result = PublicPanelChartImporter.parse(html(weekRow()), source.url)

        assertEquals(Market.KALYAN, result.market)
        assertEquals(7, result.records.size)
        assertEquals(LocalDate.of(2026, 1, 5), result.records.first().date)
        assertEquals(LocalDate.of(2026, 1, 11), result.records.last().date)
        assertEquals("123", result.records.first().openPanna)
        assertEquals("65", result.records.first().jodi)
        assertEquals("456", result.records.first().closePanna)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.records.all { ChartMath.validate(it) is ValidationResult.Valid })
    }

    @Test
    fun rejectsInvalidJodiInsteadOfInventingOrSilentlyAcceptingRecord() {
        val source = PublicPanelChartImporter.sources.single { it.market == Market.SRIDEVI_NIGHT }
        val result = PublicPanelChartImporter.parse(
            html(weekRow(jodi = "66")), source.url
        )
        assertTrue(result.records.isEmpty())
        assertEquals(7, result.issues.size)
        assertTrue(result.issues.all { it.message.contains("Jodi mismatch") })
    }

    @Test
    fun conflictingDuplicateDatesAreExcluded() {
        val source = PublicPanelChartImporter.sources.single { it.market == Market.MILAN_DAY }
        val result = PublicPanelChartImporter.parse(
            html(weekRow(), weekRow(open = "124", jodi = "75")), source.url
        )
        assertTrue(result.records.isEmpty())
        assertTrue(result.issues.any { it.message.contains("Conflicting duplicate date") })
    }

    @Test
    fun refusesUnknownOrNonHttpsSources() {
        try {
            PublicPanelChartImporter.parse(html(weekRow()), "https://example.com/chart")
            fail("Unknown host must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("host"))
        }
        try {
            PublicPanelChartImporter.parse(html(weekRow()), "http://sattamatkajodi.com/record/kalyan-penal-chart.php")
            fail("HTTP source must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("HTTPS"))
        }
    }
}
