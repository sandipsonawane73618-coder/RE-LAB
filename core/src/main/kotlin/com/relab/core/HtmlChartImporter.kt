package com.relab.core

import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

data class HtmlChartImportReport(
    val observations: List<SourceObservation>,
    val skippedRows: Int,
    val issues: List<String>
)

/** Extracts dated daily rows and Monday-Sunday weekly chart cells without inventing results. */
object HtmlChartImporter {
    private val resultPattern = Regex("""(?<!\d)(\d{3})\s*[-–/]\s*(\d{2})\s*[-–/]\s*(\d{3})(?!\d)""")
    private val rangePattern = Regex("""(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})""")
    private val datePatterns = listOf(
        Regex("""(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)"""),
        Regex("""(?<!\d)(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})(?!\d)"""),
        Regex("""(?i)\b(\d{1,2})\s+([A-Z]{3,9})\s+(\d{4})\b""")
    )
    private val months = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
        "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
    )

    fun import(
        html: String,
        pageUrl: String,
        market: Market,
        sessionId: String,
        retrievedAt: Instant = Instant.now()
    ): HtmlChartImportReport {
        require(html.isNotBlank()) { "The downloaded page is empty" }
        require(sessionId.isNotBlank()) { "Session ID is required" }
        val uri = java.net.URI(pageUrl)
        require(uri.scheme == "https" || uri.scheme == "http") { "Only HTTP and HTTPS URLs are supported" }
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?: throw IllegalArgumentException("URL host is missing")
        val document = Jsoup.parse(html, pageUrl)
        val observations = mutableListOf<SourceObservation>()
        val issues = mutableListOf<String>()
        var skipped = 0

        document.select("table tr").forEachIndexed { index, row ->
            val cells = row.select("th,td").map { it.text().trim() }
            if (cells.isEmpty()) return@forEachIndexed
            val raw = cells.joinToString(" | ")
            val weekStart = parseDate(cells.first())
            if (weekStart != null && cells.size >= 8) {
                cells.drop(1).take(7).forEachIndexed { dayOffset, cell ->
                    val date = weekStart.plusDays(dayOffset.toLong())
                    val compact = cell.filter { it.isDigit() || it == '*' }
                    if (compact.isEmpty() || compact.all { it == '*' }) {
                        if (cell.isNotBlank()) {
                            observations += observation(market, sessionId, date, host, pageUrl, retrievedAt, cell, null, ObservationStatus.PENDING)
                        }
                        return@forEachIndexed
                    }
                    if (compact.length != 8 || !compact.all { it.isDigit() }) {
                        if (cell.isNotBlank()) issues += "Week row ${index + 1}, day ${dayOffset + 1}: unrecognized cell '$cell'"
                        skipped++
                        return@forEachIndexed
                    }
                    val result = "${compact.substring(0, 3)}-${compact.substring(3, 5)}-${compact.substring(5, 8)}"
                    val status = validateResult(result, market, date, host, retrievedAt, sessionId)
                    observations += observation(market, sessionId, date, host, pageUrl, retrievedAt, cell, result.takeIf { status == ObservationStatus.PUBLISHED }, status)
                    if (status == ObservationStatus.MALFORMED) issues += "Week row ${index + 1}, day ${dayOffset + 1}: Panna/Jodi validation failed ($result)"
                }
                return@forEachIndexed
            }

            // Parse the dedicated date cell first. Parsing the entire row can let
            // unrelated result text interfere with locale-specific date detection.
            val date = parseDate(cells.first()) ?: parseDate(raw)
            val match = resultPattern.find(raw)
            if (date == null || match == null) {
                skipped++
                return@forEachIndexed
            }
            val result = "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
            val status = validateResult(result, market, date, host, retrievedAt, sessionId)
            observations += observation(market, sessionId, date, host, pageUrl, retrievedAt, raw, result.takeIf { status == ObservationStatus.PUBLISHED }, status)
            if (status == ObservationStatus.MALFORMED) issues += "Table row ${index + 1}: Panna/Jodi validation failed ($result)"
        }
        return HtmlChartImportReport(observations.distinctBy { listOf(it.date, it.sourceId, it.rawText) }, skipped, issues)
    }

    private fun validateResult(result: String, market: Market, date: LocalDate, host: String, at: Instant, session: String): ObservationStatus {
        val parts = result.split('-')
        val record = ChartRecord(market = market, date = date, openPanna = parts[0], jodi = parts[1], closePanna = parts[2], sourceId = host, observedAt = at, sessionId = session)
        return if (ChartMath.validate(record) is ValidationResult.Valid) ObservationStatus.PUBLISHED else ObservationStatus.MALFORMED
    }

    private fun observation(market: Market, session: String, date: LocalDate, host: String, url: String, at: Instant, raw: String, result: String?, status: ObservationStatus) =
        SourceObservation(market = market, sessionId = session, date = date, sourceId = host, sourceUrl = url, retrievedAt = at, rawText = raw, normalizedResult = result, status = status)

    private fun year(value: String): Int {
        val y = value.toInt()
        return if (value.length == 2) 2000 + y else y
    }

    private fun parseDate(text: String): LocalDate? {
        // Prefer ISO dates before the generic day/month/year range matcher.
        datePatterns[0].find(text)?.let {
            return runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
        }
        rangePattern.find(text)?.let { m ->
            return runCatching { LocalDate.of(year(m.groupValues[3]), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
        }
        datePatterns[1].find(text)?.let {
            return runCatching { LocalDate.of(year(it.groupValues[3]), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull()
        }
        datePatterns[2].find(text)?.let {
            val month = months[it.groupValues[2].take(3).uppercase(Locale.ROOT)] ?: return null
            return runCatching { LocalDate.of(it.groupValues[3].toInt(), month, it.groupValues[1].toInt()) }.getOrNull()
        }
        return null
    }
}
