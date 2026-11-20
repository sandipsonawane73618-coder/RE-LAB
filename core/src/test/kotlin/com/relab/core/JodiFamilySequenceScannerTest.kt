package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class JodiFamilySequenceScannerTest {
    private fun row(
        market: Market,
        day: Int,
        open: String,
        close: String,
        session: String = "main",
        source: String = "$market-$day"
    ): ChartRecord {
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market = market,
            date = LocalDate.of(2026, 11, day),
            openPanna = open,
            jodi = "$openAnk$closeAnk",
            closePanna = close,
            sourceId = source,
            sessionId = session
        )
    }

    @Test fun extractsJodiAndBothPannaFamilyWindowsWithProvenance() {
        val rows = listOf(
            row(Market.SRIDEVI, 1, "166", "277"),
            row(Market.SRIDEVI, 2, "277", "388"),
            row(Market.SRIDEVI, 3, "388", "499")
        )
        val report = JodiFamilySequenceScanner.scan(rows).single()
        val open = report.windows.single {
            it.channel == SequenceChannel.OPEN_PANNA_FAMILY &&
                it.sequence == "111>222>333"
        }
        assertEquals(2L, open.calendarGapDays)
        assertEquals(listOf("SRIDEVI-1", "SRIDEVI-2", "SRIDEVI-3"), open.sourceIds)
        assertTrue(report.windows.any {
            it.channel == SequenceChannel.CLOSE_PANNA_FAMILY &&
                it.sequence == "222>333>444"
        })
        assertTrue(report.windows.any {
            it.channel == SequenceChannel.JODI && it.sequence.split('>').size == 3
        })
        assertEquals(3, report.acceptedRecords)
        assertEquals(0, report.rejectedRecords)
    }

    @Test fun preservesCalendarGapsInsteadOfPretendingRowsAreConsecutiveDays() {
        val report = JodiFamilySequenceScanner.scan(listOf(
            row(Market.SRIDEVI, 1, "166", "277"),
            row(Market.SRIDEVI, 4, "277", "388")
        )).single()
        val pair = report.windows.single {
            it.channel == SequenceChannel.OPEN_PANNA_FAMILY &&
                it.sequence == "111>222"
        }
        assertEquals(3L, pair.calendarGapDays)
        assertEquals(LocalDate.of(2026, 11, 1), pair.startDate)
        assertEquals(LocalDate.of(2026, 11, 4), pair.endDate)
    }

    @Test fun neverMixesMarketsOrSessionsInSequenceWindows() {
        val reports = JodiFamilySequenceScanner.scan(listOf(
            row(Market.SRIDEVI, 1, "166", "277"),
            row(Market.SRIDEVI, 2, "277", "388"),
            row(Market.KALYAN, 1, "388", "499"),
            row(Market.KALYAN, 2, "499", "166", session = "night")
        ))
        assertEquals(3, reports.size)
        assertTrue(reports.all { report ->
            report.windows.all { it.sourceIds.all { id -> id.startsWith(report.market.name) } }
        })
        assertTrue(reports.flatMap { it.windows }.none {
            it.sequence == "111>222" && it.market == Market.KALYAN
        })
    }

    @Test fun invalidRowsAreExcludedAndCounted() {
        val valid = row(Market.SRIDEVI, 1, "166", "277")
        val invalid = row(Market.SRIDEVI, 2, "277", "388").copy(jodi = "99")
        val report = JodiFamilySequenceScanner.scan(listOf(valid, invalid)).single()
        assertEquals(1, report.acceptedRecords)
        assertEquals(1, report.rejectedRecords)
        assertTrue(report.windows.isEmpty())
    }

    @Test fun rejectsDuplicateDatesUntilSourceReconciliation() {
        val first = row(Market.SRIDEVI, 1, "166", "277")
        val duplicate = row(Market.SRIDEVI, 1, "277", "388", source = "other-source")
        try {
            JodiFamilySequenceScanner.scan(listOf(first, duplicate))
            fail("Expected duplicate-date rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("reconcile"))
        }
    }

    @Test fun crossMarketAlignmentRequiresSameDateAndSessionAndKeepsEvidence() {
        val records = listOf(
            row(Market.SRIDEVI, 1, "166", "277"),
            row(Market.KALYAN, 1, "166", "277"),
            row(Market.MAIN_BAZAR, 2, "166", "277"),
            row(Market.MILAN_DAY, 1, "277", "388", session = "night")
        )
        val aligned = JodiFamilySequenceScanner.crossMarketAlignment(records)
        assertEquals(1, aligned.size)
        val match = aligned.single()
        assertEquals(Market.KALYAN, match.firstMarket)
        assertEquals(Market.SRIDEVI, match.secondMarket)
        assertTrue(match.jodiMatch)
        assertTrue(match.openFamilyMatch)
        assertTrue(match.closeFamilyMatch)
        assertEquals(listOf("KALYAN-1", "SRIDEVI-1"), match.sourceIds)
    }

    @Test fun crossMarketAlignmentRejectsUnreconciledDuplicateRows() {
        val first = row(Market.SRIDEVI, 1, "166", "277")
        val duplicate = first.copy(sourceId = "duplicate")
        try {
            JodiFamilySequenceScanner.crossMarketAlignment(listOf(first, duplicate))
            fail("Expected duplicate-date rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("reconciled"))
        }
    }
}
