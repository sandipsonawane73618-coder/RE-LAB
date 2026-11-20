package com.relab.core

/** Portable CSV exports for the auditable reverse-engineering model. */
data class ReverseEngineeringJodiReport(
    val market: Market,
    val sessionId: String,
    val targetDate: java.time.LocalDate,
    val candidates: List<ReverseEngineeringJodiCandidate>
)

object ReverseEngineeringCsvExporter {
    fun exportAll(
        rankings: List<ReverseEngineeringRanking>,
        backtests: List<ReverseEngineeringBacktestReport>,
        jodiReports: List<ReverseEngineeringJodiReport>
    ): Map<String, String> = linkedMapOf(
        "reverse-model-panna-rankings.csv" to exportPannaRankings(rankings),
        "reverse-model-family-rankings.csv" to exportFamilyRankings(rankings),
        "reverse-model-jodi-rankings.csv" to exportJodiRankings(jodiReports),
        "reverse-model-walk-forward.csv" to exportBacktests(backtests)
    )

    fun exportPannaRankings(rankings: List<ReverseEngineeringRanking>): String {
        val header = listOf(
            "market", "session_id", "target_date", "side", "rank", "panna", "family_sequence",
            "ank", "panna_type", "relative_evidence_score", "exact_panna_evidence",
            "family_evidence", "ank_evidence", "position_evidence", "panna_type_evidence",
            "reverse_panna_evidence", "weekday_evidence", "previous_transition_evidence",
            "open_close_conditional_evidence", "cross_market_conditional_evidence",
            "training_records", "minimum_history", "abstained", "active_layers", "note"
        )
        val rows = mutableListOf(header)
        rankings.forEach { report ->
            if (report.candidates.isEmpty()) {
                rows += listOf(
                    report.market.name, report.sessionId, report.targetDate.toString(), report.side.name,
                    "0", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                    report.trainingRecords.toString(), report.minimumHistoryRequired.toString(),
                    report.abstained.toString(), report.activeEvidenceLayers.joinToString("|"), report.note
                )
            } else report.candidates.forEachIndexed { index, candidate ->
                rows += listOf(
                    report.market.name, report.sessionId, report.targetDate.toString(), report.side.name,
                    (index + 1).toString(), candidate.panna, candidate.familySequence, candidate.ank.toString(),
                    candidate.pannaType.name, candidate.score.decimal(), candidate.exactPannaEvidence.decimal(),
                    candidate.familyEvidence.decimal(), candidate.ankEvidence.decimal(),
                    candidate.positionEvidence.decimal(), candidate.pannaTypeEvidence.decimal(),
                    candidate.reversePannaEvidence.decimal(), candidate.weekdayEvidence.csvDecimal(),
                    candidate.previousTransitionEvidence.csvDecimal(),
                    candidate.openCloseConditionalEvidence.csvDecimal(),
                    candidate.crossMarketConditionalEvidence.csvDecimal(),
                    report.trainingRecords.toString(), report.minimumHistoryRequired.toString(),
                    report.abstained.toString(), report.activeEvidenceLayers.joinToString("|"), report.note
                )
            }
        }
        return csv(rows)
    }

    fun exportFamilyRankings(rankings: List<ReverseEngineeringRanking>): String {
        val rows = mutableListOf(listOf(
            "market", "session_id", "target_date", "side", "rank", "family_sequence",
            "relative_evidence_score", "historical_count", "weekday_count",
            "previous_transition_count", "open_close_conditional_count",
            "cross_market_conditional_count", "training_records", "abstained"
        ))
        rankings.forEach { report ->
            report.familyCandidates.forEachIndexed { index, candidate ->
                rows += listOf(
                    report.market.name, report.sessionId, report.targetDate.toString(), report.side.name,
                    (index + 1).toString(), candidate.familySequence, candidate.score.decimal(),
                    candidate.historicalCount.toString(), candidate.weekdayCount.csvInt(),
                    candidate.previousTransitionCount.csvInt(), candidate.openCloseConditionalCount.csvInt(),
                    candidate.crossMarketConditionalCount.csvInt(), report.trainingRecords.toString(),
                    report.abstained.toString()
                )
            }
        }
        return csv(rows)
    }

    fun exportJodiRankings(reports: List<ReverseEngineeringJodiReport>): String {
        val rows = mutableListOf(listOf(
            "market", "session_id", "target_date", "rank", "jodi", "relative_pair_score", "supporting_panna_pairs"
        ))
        reports.forEach { report ->
            report.candidates.forEachIndexed { index, candidate ->
                rows += listOf(
                    report.market.name, report.sessionId, report.targetDate.toString(),
                    (index + 1).toString(), candidate.jodi, candidate.score.decimal(),
                    candidate.supportingPannaPairs.toString()
                )
            }
        }
        return csv(rows)
    }

    fun exportBacktests(reports: List<ReverseEngineeringBacktestReport>): String {
        val rows = mutableListOf(listOf(
            "market", "session_id", "side", "requested_targets", "evaluated_targets", "abstained_targets",
            "exact_top1_hits", "exact_top1_rate", "exact_top10_hits", "exact_top10_rate",
            "ank_top1_hits", "ank_top1_rate", "ank_top10_hits", "ank_top10_rate",
            "family_top1_hits", "family_top1_rate", "family_top10_hits", "family_top10_rate",
            "prior_mode_family_hits", "prior_mode_family_rate",
            "global_exact_top1_hits", "global_exact_top1_rate",
            "global_exact_top10_hits", "global_exact_top10_rate",
            "weekday_exact_top1_hits", "weekday_exact_top1_rate",
            "weekday_exact_top10_hits", "weekday_exact_top10_rate",
            "previous_transition_family_hits", "previous_transition_family_rate"
        ))
        reports.forEach { report ->
            rows += listOf(
                report.market.name, report.sessionId, report.side.name, report.requestedTargets.toString(),
                report.evaluatedTargets.toString(), report.abstainedTargets.toString(),
                report.top1ExactHits.toString(), report.top1ExactRate.decimal(),
                report.top10ExactHits.toString(), report.top10ExactRate.decimal(),
                report.top1AnkHits.toString(), report.top1AnkRate.decimal(),
                report.top10AnkHits.toString(), report.top10AnkRate.decimal(),
                report.top1FamilyHits.toString(), report.top1FamilyRate.decimal(),
                report.top10FamilyHits.toString(), report.top10FamilyRate.decimal(),
                report.priorModeFamilyHits.toString(), report.priorModeFamilyRate.decimal(),
                report.globalExactTop1Hits.toString(), report.globalExactTop1Rate.decimal(),
                report.globalExactTop10Hits.toString(), report.globalExactTop10Rate.decimal(),
                report.weekdayExactTop1Hits.toString(), report.weekdayExactTop1Rate.decimal(),
                report.weekdayExactTop10Hits.toString(), report.weekdayExactTop10Rate.decimal(),
                report.previousTransitionFamilyHits.toString(), report.previousTransitionFamilyRate.decimal()
            )
        }
        return csv(rows)
    }

    private fun csv(rows: List<List<String>>): String =
        rows.joinToString("\n", postfix = "\n") { row -> row.joinToString(",") { it.csvEscape() } }

    private fun String.csvEscape(): String =
        if (any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + replace("\"", "\"\"") + "\""
        else this

    private fun Double.decimal(): String = "%.8f".format(java.util.Locale.ROOT, this)
    private fun Double?.csvDecimal(): String = this?.decimal().orEmpty()
    private fun Int?.csvInt(): String = this?.toString().orEmpty()
}
