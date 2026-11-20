package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FamilyMovementWalkForwardEvaluatorTest {
    private fun row(day: Int, jodi: String, open: String, close: String) = ChartRecord(
        market = Market.SRIDEVI,
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        openPanna = open,
        jodi = jodi,
        closePanna = close,
        sourceId = "source-$day",
        sessionId = "main"
    )

    @Test fun familyAndReverseVariantsAreRecognizedInChronologicalJodiTest() {
        val records = listOf(
            row(1, "17", "001", "025"),
            row(2, "62", "006", "016"),
            row(3, "71", "007", "025"),
            row(4, "26", "002", "016")
        )
        val report = FamilyMovementWalkForwardEvaluator.evaluate(
            records, LocalDate.of(2026, 2, 1), minimumHistory = 3
        ).single { it.channel == FamilyMovementChannel.JODI }
        assertEquals(4, report.requestedTargets)
        assertEquals(1, report.evaluatedTargets)
        assertEquals(3, report.abstainedTargets)
        assertEquals(1, report.top1Hits)
        assertEquals(1, report.top3Hits)
        assertEquals(1, report.top10Hits)
    }

    @Test fun targetAndFutureRowsAreExcludedBeforeEvaluation() {
        val records = listOf(
            row(1, "17", "001", "025"),
            row(2, "62", "006", "016"),
            row(3, "71", "007", "025"),
            row(40, "26", "002", "016")
        )
        val report = FamilyMovementWalkForwardEvaluator.evaluate(
            records, LocalDate.of(2026, 2, 1), minimumHistory = 2
        ).single { it.channel == FamilyMovementChannel.JODI }
        assertEquals(3, report.requestedTargets)
        assertEquals(1, report.evaluatedTargets)
    }

    @Test fun duplicateMarketSessionDateIsRejected() {
        val first = row(1, "17", "001", "025")
        try {
            FamilyMovementWalkForwardEvaluator.evaluate(
                listOf(first, first.copy(sourceId = "duplicate")),
                LocalDate.of(2026, 2, 1), minimumHistory = 1
            )
            fail("Duplicate date must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate rows"))
        }
    }

    @Test fun reportsEveryMarketChannelAndAbstainsWithoutHistory() {
        val reports = FamilyMovementWalkForwardEvaluator.evaluate(
            listOf(row(1, "17", "001", "025")),
            LocalDate.of(2026, 2, 1), minimumHistory = 30
        )
        assertEquals(3, reports.size)
        assertTrue(reports.all { it.evaluatedTargets == 0 && it.abstainedTargets == 1 })
        assertTrue(reports.all { it.hitRate(it.top1Hits) == null })
    }
}
