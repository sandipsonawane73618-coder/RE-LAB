package com.relab.core

import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Canonical public panel-chart catalogue and a conservative importer for the
 * weekly HTML tables published by SattaMatkaJodi.
 *
 * The catalogue deliberately stores panel URLs (not Jodi-only chart URLs).
 * Parsing never manufactures missing/holiday cells: only complete, internally
 * consistent open-panna/Jodi/close-panna triples become ChartRecords.
 */
data class PublicPanelChartSource(
    val market: Market,
    val url: String,
    val displayName: String
)

data class PanelChartImportIssue(
    val sourceRow: Int,
    val dayOffset: Int?,
    val message: String
)

data class PanelChartImportResult(
    val market: Market,
    val sourceUrl: String,
    val records: List<ChartRecord>,
    val issues: List<PanelChartImportIssue>
) {
    val rejectedCellCount: Int get() = issues.size
}

object PublicPanelChartImporter {
    private const val SOURCE_HOST = "sattamatkajodi.com"

    val sources: List<PublicPanelChartSource> = listOf(
        PublicPanelChartSource(Market.KALYAN, "https://sattamatkajodi.com/record/kalyan-penal-chart.php", "Kalyan Panel"),
        PublicPanelChartSource(Market.SRIDEVI, "https://sattamatkajodi.com/record/sridevi-satta-penal-chart.php", "Sridevi Panel"),
        PublicPanelChartSource(Market.TIME_BAZAR, "https://sattamatkajodi.com/time-bazar-panel-chart.php", "Time Bazar Panel"),
        PublicPanelChartSource(Market.MILAN_DAY, "https://sattamatkajodi.com/record/milan-day-penal-chart.php", "Milan Day Panel"),
        PublicPanelChartSource(Market.MILAN_NIGHT, "https://sattamatkajodi.com/record/milan-night-penal-chart.php", "Milan Night Panel"),
        PublicPanelChartSource(Market.RAJDHANI_DAY, "https://sattamatkajodi.com/record/rajdhani-day-penal-chart.php", "Rajdhani Day Panel"),
        PublicPanelChartSource(Market.RAJDHANI_NIGHT, "https://sattamatkajodi.com/record/rajdhani-night-penal-chart.php", "Rajdhani Night Panel"),
        PublicPanelChartSource(Market.MAIN_BAZAR, "https://sattamatkajodi.com/main-bazar-panel-chart.php", "Main Bazar Panel"),
        PublicPanelChartSource(Market.SRIDEVI_NIGHT, "https://sattamatkajodi.com/record/sridevi-night-satta-penal-chart.php", "Sridevi Night Panel")
    )

    private val datePattern = Regex("(\\d{1,2})\\s*/\\s*(\\d{1,2})\\s*/\\s*(\\d{2,4})")
    private val dateRangePattern = Regex(
        "(\\d{1,2}\\s*/\\s*\\d{1,2}\\s*/\\s*\\d{2,4})\\s*(?:to|[-–])\\s*(\\d{1,2}\\s*/\\s*\\d{1,2}\\s*/\\s*\\d{2,4})",
        RegexOption.IGNORE_CASE
    )

    fun parse(html: String, sourceUrl: String, observedAt: java.time.Instant? = null): PanelChartImportResult {
        require(html.isNotBlank()) { "HTML document is empty" }
        val uri = java.net.URI(sourceUrl)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Panel source must use HTTPS" }
        require(uri.host.equals(SOURCE_HOST, ignoreCase = true)) { "Unapproved panel source host" }
        val source = sources.firstOrNull { it.url.equals(sourceUrl.substringBefore('?'), ignoreCase = true) }
            ?: throw IllegalArgumentException("URL is not in the approved nine-market panel catalogue")
        val document = Jsoup.parse(html, sourceUrl)
        val records = linkedMapOf<LocalDate, ChartRecord>()
        val issues = mutableListOf<PanelChartImportIssue>()
        var rowNumber = 0

        document.select("tr").forEach { row ->
            rowNumber++
            val cells = row.select("> th, > td")
            if (cells.size < 22) return@forEach
            val rangeText = cells.firstOrNull()?.text().orEmpty().replace('\\u00a0', ' ')
            val range = dateRangePattern.find(rangeText) ?: return@forEach
            val startDate = parseDate(range.groupValues[1]) ?: run {
                issues += PanelChartImportIssue(rowNumber, null, "Unrecognized week start date")
                return@forEach
            }
            val endDate = parseDate(range.groupValues[2]) ?: run {
                issues += PanelChartImportIssue(rowNumber, null, "Unrecognized week end date")
                return@forEach
            }
            if (endDate.isBefore(startDate) || endDate.toEpochDay() - startDate.toEpochDay() !in 0L..6L) {
                issues += PanelChartImportIssue(rowNumber, null, "Week date range is not a valid 1–7 day range")
                return@forEach
            }

            for (dayOffset in 0..6) {
                val date = startDate.plusDays(dayOffset.toLong())
                if (date.isAfter(endDate)) continue
                val base = 1 + dayOffset * 3
                if (base + 2 >= cells.size) {
                    issues += PanelChartImportIssue(rowNumber, dayOffset, "Missing panel cells")
                    continue
                }
                val open = digitsOnly(cells[base].text())
                val jodi = digitsOnly(cells[base + 1].text())
                val close = digitsOnly(cells[base + 2].text())
                if (open.isEmpty() && jodi.isEmpty() && close.isEmpty()) continue
                if (open.length != 3 || jodi.length != 2 || close.length != 3) {
                    issues += PanelChartImportIssue(rowNumber, dayOffset, "Incomplete or non-numeric panel cell; retained as missing")
                    continue
                }

                val record = ChartRecord(
                    market = source.market,
                    date = date,
                    openPanna = open,
                    jodi = jodi,
                    closePanna = close,
                    sourceId = source.url,
                    sourceRecordId = "${source.market.name}:${date}",
                    observedAt = observedAt,
                    sessionId = "main"
                )
                when (val validation = ChartMath.validate(record)) {
                    is ValidationResult.Invalid -> {
                        issues += PanelChartImportIssue(rowNumber, dayOffset, validation.reasons.joinToString("; "))
                    }
                    is ValidationResult.Valid -> {
                        val previous = records.putIfAbsent(date, record)
                        if (previous != null && previous != record) {
                            records.remove(date)
                            issues += PanelChartImportIssue(rowNumber, dayOffset, "Conflicting duplicate date; excluded from import")
                        } else if (previous != null) {
                            issues += PanelChartImportIssue(rowNumber, dayOffset, "Duplicate identical date; retained once")
                        }
                    }
                }
            }
        }

        return PanelChartImportResult(
            market = source.market,
            sourceUrl = source.url,
            records = records.values.sortedBy { it.date },
            issues = issues
        )
    }

    private fun digitsOnly(value: String): String =
        value.filter(Char::isDigit)

    private fun parseDate(value: String): LocalDate? {
        val match = datePattern.matchEntire(value.trim()) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val rawYear = match.groupValues[3].toIntOrNull() ?: return null
        val year = if (match.groupValues[3].length == 2) 2000 + rawYear else rawYear
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }
}
