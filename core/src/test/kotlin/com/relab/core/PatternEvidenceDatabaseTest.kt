package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PatternEvidenceDatabaseTest {
    private fun row(day: Int, open: String, close: String = "277"): ChartRecord {
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market = Market.SRIDEVI,
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            openPanna = open,
            jodi = "$openAnk$closeAnk",
            closePanna = close,
            sourceId = "source-$day"
        )
    }

    @Test fun scoresOnlyRepeatedPatternsAgainstEarlierOutcomes() {
        val rows = listOf(
            row(0, "111"), row(1, "222"), row(2, "333"),
            row(3, "111"), row(4, "222"), row(5, "333"),
            row(6, "111"), row(7, "222"), row(8, "444")
        )
        val evidence = PatternEvidenceDatabase.analyze(
            rows, LocalDate.of(2026, 2, 1), windowLengths = 2..2
        )
        val repeated = evidence.single {
            it.channel == PatternEvidenceChannel.OPEN_PANNA && it.pattern == "111>222"
        }
        assertEquals(2, repeated.occurrences)
        assertEquals(1, repeated.evaluatedOccurrences)
        assertEquals(1, repeated.hits)
        assertEquals(0, repeated.misses)
        assertEquals(1.0, repeated.hitRate!!, 0.0)
        assertEquals(2, repeated.distinctSourceCount)
        assertEquals(0, repeated.ageInChartRows)
    }

    @Test fun futureRowsAreExcludedByExclusiveTargetCutoff() {
        val rows = listOf(
            row(0, "111"), row(1, "222"), row(2, "333"),
            row(3, "111"), row(4, "222"), row(5, "999")
        )
        val evidence = PatternEvidenceDatabase.analyze(
            rows, LocalDate.of(2026, 1, 6), windowLengths = 2..2
        )
        assertFalse(evidence.any { it.outcomeCounts.any { outcome -> outcome.outcome == "999" } })
        assertTrue(evidence.all { it.lastSeenDate.isBefore(LocalDate.of(2026, 1, 6)) })
    }

    @Test fun duplicateMarketSessionDateRowsAreRejected() {
        val first = row(0, "111")
        try {
            PatternEvidenceDatabase.analyze(
                listOf(first, first.copy(sourceId = "duplicate")),
                LocalDate.of(2026, 2, 1), windowLengths = 1..1
            )
            fail("Duplicate rows must not be silently counted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate dates"))
        }
    }

    @Test fun reportsOpenCloseAndJodiChannelsSeparately() {
        val evidence = PatternEvidenceDatabase.analyze(
            (0..5).map { row(it, listOf("111", "222", "333")[it % 3], close = listOf("444", "555", "666")[it % 3]) },
            LocalDate.of(2026, 2, 1), windowLengths = 2..2
        )
        assertTrue(evidence.any { it.channel == PatternEvidenceChannel.OPEN_PANNA })
        assertTrue(evidence.any { it.channel == PatternEvidenceChannel.CLOSE_PANNA })
        assertTrue(evidence.any { it.channel == PatternEvidenceChannel.JODI })
    }
}
