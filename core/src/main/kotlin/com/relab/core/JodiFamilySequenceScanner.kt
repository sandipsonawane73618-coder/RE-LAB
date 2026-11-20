package com.relab.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class SequenceChannel { OPEN_PANNA_FAMILY, CLOSE_PANNA_FAMILY, JODI }

data class SequenceWindowEvidence(
    val market: Market,
    val sessionId: String,
    val channel: SequenceChannel,
    val sequence: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val calendarGapDays: Long,
    val sourceIds: List<String>
)

data class MarketSequenceReport(
    val market: Market,
    val sessionId: String,
    val acceptedRecords: Int,
    val rejectedRecords: Int,
    val windows: List<SequenceWindowEvidence>
)

data class CrossMarketSequenceEvidence(
    val firstMarket: Market,
    val secondMarket: Market,
    val date: LocalDate,
    val sessionId: String,
    val firstJodi: String,
    val secondJodi: String,
    val firstOpenFamily: String,
    val secondOpenFamily: String,
    val firstCloseFamily: String,
    val secondCloseFamily: String,
    val jodiMatch: Boolean,
    val openFamilyMatch: Boolean,
    val closeFamilyMatch: Boolean,
    val sourceIds: List<String>
)

/**
 * Extracts chronological Jodi and Panna-family windows, plus exact same-date
 * cross-market alignment evidence. It never bridges an absent record as if it
 * were observed: calendarGapDays is retained on every within-market window.
 */
object JodiFamilySequenceScanner {
    fun scan(records: List<ChartRecord>, maximumWindowLength: Int = 3): List<MarketSequenceReport> {
        require(maximumWindowLength in 2..10) { "maximumWindowLength must be between 2 and 10" }
        return records.groupBy { it.market to it.sessionId }
            .entries
            .sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
            .map { (identity, rows) ->
                val valid = rows.filter { ChartMath.validate(it) is ValidationResult.Valid }
                    .sortedBy { it.date }
                require(valid.map { it.date }.distinct().size == valid.size) {
                    "Duplicate dates for ${identity.first}/${identity.second}: reconcile source observations before sequence scanning"
                }
                val windows = mutableListOf<SequenceWindowEvidence>()
                val channels = listOf(
                    SequenceChannel.OPEN_PANNA_FAMILY,
                    SequenceChannel.CLOSE_PANNA_FAMILY,
                    SequenceChannel.JODI
                )
                channels.forEach { channel ->
                    val values = valid.map { record ->
                        val value = when (channel) {
                            SequenceChannel.OPEN_PANNA_FAMILY -> family(record.openPanna)
                            SequenceChannel.CLOSE_PANNA_FAMILY -> family(record.closePanna)
                            SequenceChannel.JODI -> record.jodi
                        }
                        record to value
                    }
                    for (length in 2..maximumWindowLength) {
                        for (start in 0..(values.size - length)) {
                            val slice = values.subList(start, start + length)
                            windows += SequenceWindowEvidence(
                                market = identity.first,
                                sessionId = identity.second,
                                channel = channel,
                                sequence = slice.joinToString(">") { it.second },
                                startDate = slice.first().first.date,
                                endDate = slice.last().first.date,
                                calendarGapDays = ChronoUnit.DAYS.between(
                                    slice.first().first.date, slice.last().first.date
                                ),
                                sourceIds = slice.map { it.first.sourceId }
                            )
                        }
                    }
                }
                MarketSequenceReport(
                    identity.first, identity.second, valid.size, rows.size - valid.size,
                    windows.sortedWith(compareBy<SequenceWindowEvidence> { it.channel.name }
                        .thenBy { it.startDate }.thenBy { it.sequence })
                )
            }
    }

    fun crossMarketAlignment(records: List<ChartRecord>): List<CrossMarketSequenceEvidence> {
        val valid = records.filter { ChartMath.validate(it) is ValidationResult.Valid }
        valid.groupBy { Triple(it.market, it.sessionId, it.date) }.forEach { (key, rows) ->
            require(rows.size == 1) {
                "Duplicate cross-market rows for ${key.first}/${key.second} on ${key.third} must be reconciled first"
            }
        }
        val byDateSession = valid.groupBy { it.date to it.sessionId }
        return byDateSession.values.flatMap { sameSessionRows ->
            val ordered = sameSessionRows.sortedBy { it.market.name }
            buildList {
                for (i in ordered.indices) for (j in i + 1 until ordered.size) {
                    val first = ordered[i]
                    val second = ordered[j]
                    val firstOpen = family(first.openPanna)
                    val secondOpen = family(second.openPanna)
                    val firstClose = family(first.closePanna)
                    val secondClose = family(second.closePanna)
                    add(CrossMarketSequenceEvidence(
                        firstMarket = first.market,
                        secondMarket = second.market,
                        date = first.date,
                        sessionId = first.sessionId,
                        firstJodi = first.jodi,
                        secondJodi = second.jodi,
                        firstOpenFamily = firstOpen,
                        secondOpenFamily = secondOpen,
                        firstCloseFamily = firstClose,
                        secondCloseFamily = secondClose,
                        jodiMatch = first.jodi == second.jodi,
                        openFamilyMatch = firstOpen == secondOpen,
                        closeFamilyMatch = firstClose == secondClose,
                        sourceIds = listOf(first.sourceId, second.sourceId)
                    ))
                }
            }
        }.sortedWith(compareBy<CrossMarketSequenceEvidence> { it.date }
            .thenBy { it.sessionId }.thenBy { it.firstMarket.name }.thenBy { it.secondMarket.name })
    }

    private fun family(panna: String): String =
        panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
}
