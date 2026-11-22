package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ChartPatternGridScannerTest {
    private fun row(day: Int, panna: String, market: Market = Market.SRIDEVI): ChartRecord {
        val openAnk = panna.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market = market,
            date = LocalDate.of(2026, 11, day),
            openPanna = panna,
            jodi = "${openAnk}0",
            closePanna = "000",
            sourceId = "source-$day"
        )
    }

    @Test fun findsHorizontalPannaPatternAndPreservesSourceEvidence() {
        val report = ChartPatternGridScanner.scan(
            listOf(row(2, "123")), ChartPatternSide.OPEN_PANNA, maximumStep = 1
        ).single()
        val match = report.occurrences.single {
            it.direction == PatternDirection.HORIZONTAL && it.pattern == "123"
        }
        assertEquals(3, match.cells.size)
        assertEquals(listOf("source-2", "source-2", "source-2"), match.sourceIds)
        assertEquals(1, match.step)
    }

    @Test fun findsVerticalPatternAcrossChronologicalDates() {
        val report = ChartPatternGridScanner.scan(
            listOf(row(1, "123"), row(2, "456"), row(3, "789")),
            ChartPatternSide.OPEN_PANNA,
            maximumStep = 1
        ).single()
        assertTrue(report.occurrences.any {
            it.direction == PatternDirection.VERTICAL && it.pattern == "147"
        })
    }

    @Test fun findsBothDiagonalOrientations() {
        val report = ChartPatternGridScanner.scan(
            listOf(row(1, "123"), row(2, "456"), row(3, "789")),
            ChartPatternSide.OPEN_PANNA,
            maximumStep = 1
        ).single()
        assertTrue(report.occurrences.any {
            it.direction == PatternDirection.DIAGONAL_DOWN_RIGHT && it.pattern == "159"
        })
        assertTrue(report.occurrences.any {
            it.direction == PatternDirection.DIAGONAL_DOWN_LEFT && it.pattern == "357"
        })
    }

    @Test fun scansReverseDirectionWithoutNormalizingTheObservedOrder() {
        val report = ChartPatternGridScanner.scan(
            listOf(row(1, "321")), ChartPatternSide.OPEN_PANNA, maximumStep = 1
        ).single()
        assertTrue(report.occurrences.any {
            it.direction == PatternDirection.HORIZONTAL_REVERSE && it.pattern == "123"
        })
    }

    @Test fun shiftedStrideIsExplicitAndDoesNotBridgeByDefault() {
        val rows = listOf(row(1, "123"), row(2, "999"), row(3, "456"), row(4, "888"), row(5, "789"))
        val strict = ChartPatternGridScanner.scan(rows, ChartPatternSide.OPEN_PANNA, maximumStep = 1).single()
        val shifted = ChartPatternGridScanner.scan(rows, ChartPatternSide.OPEN_PANNA, maximumStep = 2).single()

        assertFalse(strict.occurrences.any {
            it.direction == PatternDirection.VERTICAL && it.pattern == "147"
        })
        assertTrue(shifted.occurrences.any {
            it.direction == PatternDirection.VERTICAL && it.pattern == "147" && it.step == 2
        })
    }

    @Test fun keepsMarketAndSessionBoundariesSeparate() {
        val rows = listOf(
            row(1, "123", Market.SRIDEVI),
            row(2, "456", Market.SRIDEVI),
            row(3, "789", Market.SRIDEVI),
            row(1, "123", Market.KALYAN).copy(sessionId = "night")
        )
        val reports = ChartPatternGridScanner.scan(rows, ChartPatternSide.OPEN_PANNA, maximumStep = 1)
        assertEquals(2, reports.size)
        assertTrue(reports.single { it.market == Market.SRIDEVI }.occurrences.isNotEmpty())
        assertFalse(reports.single { it.market == Market.KALYAN }.occurrences.any {
            it.direction == PatternDirection.VERTICAL && it.pattern == "147"
        })
    }

    @Test fun rejectsUnreconciledDuplicateDatesAndCountsInvalidRows() {
        val duplicate = row(1, "123")
        try {
            ChartPatternGridScanner.scan(
                listOf(duplicate, duplicate), ChartPatternSide.OPEN_PANNA
            )
            fail("Expected duplicate-date rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("reconcile"))
        }

        val invalid = row(2, "456").copy(jodi = "99")
        val report = ChartPatternGridScanner.scan(
            listOf(row(1, "123"), invalid), ChartPatternSide.OPEN_PANNA
        ).single()
        assertEquals(1, report.acceptedRecords)
        assertEquals(1, report.rejectedRecords)
    }

    @Test fun scansClosePannaWhenRequested() {
        val rows = listOf(
            row(1, "000").copy(closePanna = "123", jodi = "06"),
            row(2, "000").copy(closePanna = "456", jodi = "05"),
            row(3, "000").copy(closePanna = "789", jodi = "04")
        )
        val report = ChartPatternGridScanner.scan(
            rows, ChartPatternSide.CLOSE_PANNA, maximumStep = 1
        ).single()
        assertTrue(report.occurrences.any {
            it.direction == PatternDirection.VERTICAL && it.pattern == "147"
        })
    }

    @Test fun scansJodiColumnsHorizontallyAndVertically() {
        val rows = listOf(
            row(1, "001").copy(closePanna = "002", jodi = "12"),
            row(2, "003").copy(closePanna = "004", jodi = "34"),
            row(3, "005").copy(closePanna = "006", jodi = "56")
        )
        val report = ChartPatternGridScanner.scan(
            rows, ChartPatternSide.JODI, minimumLength = 2, maximumStep = 1
        ).single()
        assertTrue(report.occurrences.any {
            it.direction == PatternDirection.HORIZONTAL && it.pattern == "12"
        })
        assertTrue(report.occurrences.any {
            it.direction == PatternDirection.VERTICAL && it.pattern == "135"
        })
    }

    @Test fun verticalPatternsFollowObservedRowsAcrossCalendarGaps() {
        val report = ChartPatternGridScanner.scan(
            listOf(row(1, "123"), row(4, "456"), row(6, "789")),
            ChartPatternSide.OPEN_PANNA,
            maximumStep = 1
        ).single()
        assertTrue(report.occurrences.any {
            it.direction == PatternDirection.VERTICAL && it.pattern == "147" && it.step == 1
        })
    }

    @Test fun invalidObservationBlocksVerticalAndDiagonalPatternsInsteadOfBeingSkipped() {
        val rows = listOf(
            row(1, "123"),
            row(2, "456").copy(jodi = "99"),
            row(3, "789")
        )
        val report = ChartPatternGridScanner.scan(
            rows, ChartPatternSide.OPEN_PANNA, maximumStep = 1
        ).single()

        assertEquals(2, report.acceptedRecords)
        assertEquals(1, report.rejectedRecords)
        assertFalse(report.occurrences.any {
            it.direction == PatternDirection.VERTICAL && it.pattern == "147"
        })
        assertFalse(report.occurrences.any {
            it.direction == PatternDirection.DIAGONAL_DOWN_RIGHT && it.pattern == "159"
        })
    }

    @Test fun duplicateInvalidDateIsAlsoRejectedForReconciliation() {
        val invalid = row(1, "123").copy(jodi = "99")
        val valid = row(1, "456")
        try {
            ChartPatternGridScanner.scan(
                listOf(invalid, valid), ChartPatternSide.OPEN_PANNA
            )
            fail("Expected duplicate-date rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("reconcile"))
        }
    }
}
