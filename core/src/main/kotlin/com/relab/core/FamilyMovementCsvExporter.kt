package com.relab.core

import java.time.LocalDate

/** Auditable CSV export for the family-movement walk-forward experiment. */
object FamilyMovementCsvExporter {
    fun export(
        records: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        minimumHistory: Int = 30
    ): String = buildString {
        appendLine(
            "target_date_exclusive,minimum_history,market,session_id,channel,requested_targets,evaluated_targets,abstained_targets," +
                "family_top1_hits,family_top1_rate,family_top3_hits,family_top3_rate,family_top5_hits,family_top5_rate,family_top10_hits,family_top10_rate," +
                "exact_baseline_top1_hits,exact_baseline_top1_rate,exact_baseline_top3_hits,exact_baseline_top3_rate," +
                "exact_baseline_top5_hits,exact_baseline_top5_rate,exact_baseline_top10_hits,exact_baseline_top10_rate"
        )
        FamilyMovementWalkForwardEvaluator.evaluate(records, targetDateExclusive, minimumHistory).forEach { report ->
            fun rate(hits: Int): String = report.hitRate(hits)?.let {
                "%.6f".format(java.util.Locale.ROOT, it)
            } ?: ""
            val fields = listOf(
                targetDateExclusive.toString(), minimumHistory.toString(), report.market.name,
                report.sessionId, report.channel.name, report.requestedTargets.toString(),
                report.evaluatedTargets.toString(), report.abstainedTargets.toString(),
                report.top1Hits.toString(), rate(report.top1Hits),
                report.top3Hits.toString(), rate(report.top3Hits),
                report.top5Hits.toString(), rate(report.top5Hits),
                report.top10Hits.toString(), rate(report.top10Hits),
                report.exactFrequencyBaselineTop1Hits.toString(), rate(report.exactFrequencyBaselineTop1Hits),
                report.exactFrequencyBaselineTop3Hits.toString(), rate(report.exactFrequencyBaselineTop3Hits),
                report.exactFrequencyBaselineTop5Hits.toString(), rate(report.exactFrequencyBaselineTop5Hits),
                report.exactFrequencyBaselineTop10Hits.toString(), rate(report.exactFrequencyBaselineTop10Hits)
            )
            appendLine(fields.joinToString(",") { csv(it) })
        }
    }

    private fun csv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
}
