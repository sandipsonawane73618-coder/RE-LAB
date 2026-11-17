package com.relab.core

import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HtmlChartImportReport(
    val observations: List<SourceObservation>,
    val skippedRows: Int,
    val issues: List<String>
)

/** Extracts dated result rows from ordinary HTML tables; it never guesses missing values. */
object HtmlChartImporter {
    private val resultPattern = Regex("""(?<!\\d)(\\d{3})\\s*[-–/]\\s*(\\d{2})\\s*[-–/]\\s*(\\d{3})(?!\\d)""")
    private val datePatterns = listOf(
        Regex("""(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)"""),
        Regex("""(?<!\\d)(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})(?!\\d)"""),
        Regex("""(?i)\\b(\\d{1,2})\\s+([A-Z]{3,9})\\s+(\\d{4})\\b""")
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
            val date = parseDate(raw)
            if (date == null) {
                skipped++
                return@forEachIndexed
            }
            val resultMatch = resultPattern.find(raw)
            if (resultMatch == null) {
                skipped++
                return@forEachIndexed
            }
            val result = "${resultMatch.groupValues[1]}-${resultMatch.groupValues[2]}-${resultMatch.groupValues[3]}"
            val parts = result.split('-')
            val record = ChartRecord(
                market = market, date = date,
                openPanna = parts[0], jodi = parts[1], closePanna = parts[2],
                sourceId = host, sourceUrl = nullSafeSourceId(host)
                    .let { host }, observedAt = retrievedAt, sessionId = sessionId
            )
            val valid = ChartMath.validate(record) is ValidationResult.Valid
            observations += SourceObservation(
                market = market,
                sessionId = sessionId,
                date = date,
                sourceId = host,
                sourceUrl = pageUrl,
                retrievedAt = retrievedAt,
                rawText = raw,
                normalizedResult = result.takeIf { valid },
                status = if (valid) ObservationStatus.PUBLISHED else ObservationStatus.MALFORMED
            )
            if (!valid) issues += "Table row ${index + 1}: result failed Panna/Jodi validation ($result)"
        }
        return HtmlChartImportReport(
            observations.distinctBy { listOf(it.date, it.sourceId, it.rawText) },
            skipped,
            issues
        )
    }

    private fun nullSafeSourceId(host: String) = host

    private fun parseDate(text: String): LocalDate? {
        datePatterns[0].find(text)?.let {
            return runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
        }
        datePatterns[1].find(text)?.let {
            return runCatching { LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull()
        }
        datePatterns[2].find(text)?.let {
            val month = months[it.groupValues[2].take(3).uppercase(Locale.ROOT)] ?: return null
            return runCatching { LocalDate.of(it.groupValues[3].toInt(), month, it.groupValues[1].toInt()) }.getOrNull()
        }
        return null
    }
}
