package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FamilyMovementCsvExporterTest {
    @Test fun exportsChronologicalFamilyAndExactBaselineMetrics() {
        val records = listOf(
            ChartRecord(Market.SRIDEVI, LocalDate.parse("2026-01-01"), "001", "17", "025", "a"),
            ChartRecord(Market.SRIDEVI, LocalDate.parse("2026-01-02"), "006", "62", "002", "b"),
            ChartRecord(Market.SRIDEVI, LocalDate.parse("2026-01-03"), "007", "71", "001", "c"),
            ChartRecord(Market.SRIDEVI, LocalDate.parse("2026-01-04"), "002", "26", "006", "d")
        )
        val csv = FamilyMovementCsvExporter.export(records, LocalDate.parse("2026-02-01"), 3)
        assertTrue(csv.startsWith("target_date_exclusive,minimum_history,market"))
        assertTrue(csv.contains("2026-02-01,3,SRIDEVI,main,JODI,4,1,3,1,1.000000"))
        assertTrue(csv.contains("OPEN_PANNA"))
        assertTrue(csv.contains("CLOSE_PANNA"))
    }

    @Test fun quotesSessionIdentifiersContainingCsvSpecialCharacters() {
        val record = ChartRecord(
            Market.SRIDEVI, LocalDate.parse("2026-01-01"), "001", "12", "002", "source",
            sessionId = "day, \"main\""
        )
        val csv = FamilyMovementCsvExporter.export(listOf(record), LocalDate.parse("2026-02-01"), 30)
        assertTrue(csv.contains("\"day, \"\"main\"\"\""))
    }
}