package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReverseEngineeringCsvExporterTest {
    @Test fun exportsPannaFamilyJodiAndBacktestArtifacts() {
        val records = (0 until 40).map { index ->
            val open = "166"
            val close = "277"
            ChartRecord(
                Market.SRIDEVI, LocalDate.of(2026, 1, 1).plusDays(index.toLong()),
                open, "36", close, "fixture"
            )
        }
        val open = ReverseEngineeringModel.rank(
            records, Market.SRIDEVI, "main", LocalDate.of(2026, 3, 1),
            ReverseEngineeringSide.OPEN, minimumHistory = 30
        )
        val close = ReverseEngineeringModel.rank(
            records, Market.SRIDEVI, "main", LocalDate.of(2026, 3, 1),
            ReverseEngineeringSide.CLOSE, minimumHistory = 30
        )
        val jodi = ReverseEngineeringModel.deriveJodiCandidates(open.candidates, close.candidates)
        val artifacts = ReverseEngineeringCsvExporter.exportAll(
            listOf(open, close),
            listOf(
                ReverseEngineeringModel.backtest(records, Market.SRIDEVI, "main", ReverseEngineeringSide.OPEN, 5, 30),
                ReverseEngineeringModel.backtest(records, Market.SRIDEVI, "main", ReverseEngineeringSide.CLOSE, 5, 30)
            ),
            listOf(ReverseEngineeringJodiReport(Market.SRIDEVI, "main", LocalDate.of(2026, 3, 1), jodi))
        )

        assertEquals(
            setOf(
                "reverse-model-panna-rankings.csv",
                "reverse-model-family-rankings.csv",
                "reverse-model-jodi-rankings.csv",
                "reverse-model-walk-forward.csv"
            ),
            artifacts.keys
        )
        assertTrue(artifacts.getValue("reverse-model-panna-rankings.csv").contains("ank_evidence"))
        assertTrue(artifacts.getValue("reverse-model-panna-rankings.csv").contains("166"))
        assertTrue(artifacts.getValue("reverse-model-family-rankings.csv").contains("family_sequence"))
        assertTrue(artifacts.getValue("reverse-model-jodi-rankings.csv").contains("36"))
        assertTrue(artifacts.getValue("reverse-model-walk-forward.csv").contains("exact_top10_rate"))
        assertTrue(artifacts.getValue("reverse-model-walk-forward.csv").contains("global_exact_top1_rate"))
        assertTrue(artifacts.getValue("reverse-model-walk-forward.csv").contains("weekday_exact_top10_rate"))
        assertTrue(artifacts.getValue("reverse-model-walk-forward.csv").contains("previous_transition_family_rate"))
    }

    @Test fun csvEscapesNotesAndRetainsAbstentionRows() {
        val report = ReverseEngineeringRanking(
            Market.SRIDEVI, "main", ReverseEngineeringSide.OPEN,
            LocalDate.of(2026, 3, 1), 2, 30, true,
            emptyList(), emptyList(), emptyList(),
            "Need more history, then retry"
        )
        val csv = ReverseEngineeringCsvExporter.exportPannaRankings(listOf(report))
        assertTrue(csv.contains("\"Need more history, then retry\""))
        assertTrue(csv.contains(",true,"))
    }
}
