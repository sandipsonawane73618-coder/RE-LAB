package com.relab.core

import java.io.File
import java.time.LocalDate

/**
 * Runs Open Panna, Close Panna and Jodi multi-model walk-forward evaluation
 * against conflict-free, explicitly single-source historical chart rows.
 */
object MultiModelHistoricalBacktestCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: source-observations.csv output-metrics.csv" }
        val input = File(args[0])
        val output = File(args[1])
        require(input.isFile) { "Source-observation CSV does not exist: $input" }

        val imported = SourceCsvImporter.import(input.readText(Charsets.UTF_8))
        require(imported.issues.isEmpty()) {
            "Source-observation CSV has ${imported.issues.size} import issues: " +
                imported.issues.take(10).joinToString { "row ${it.rowNumber}: ${it.message}" }
        }
        val sourceAudit = ReconciledChartBuilder.build(imported.observations)
        // Only independently matching publisher records enter historical training.
        val records = sourceAudit.records
        require(records.isNotEmpty()) { "No two-publisher consensus chart records are available" }
        val thinMarkets = Market.entries.filter { market ->
            records.count { it.market == market } < 30
        }
        require(thinMarkets.isEmpty()) {
            "At least 30 consensus dates are required per market; insufficient: ${thinMarkets.map { it.name }}"
        }
        val marketsPresent = records.map { it.market }.toSet()
        require(marketsPresent == Market.entries.toSet()) {
            "Expected conflict-free history for all nine markets; found: ${marketsPresent.map { it.name }.sorted()}"
        }
        records.groupBy { Triple(it.market, it.sessionId, it.date) }.forEach { (key, rows) ->
            require(rows.size == 1) {
                "Unreconciled duplicate date for ${key.first}/${key.second} on ${key.third}"
            }
        }

        val latestDate = records.maxOf { it.date }
        val evaluationStart = latestDate.minusDays(364)
        val cutoffExclusive = latestDate.plusDays(1)
        val identities = records.map { it.market to it.sessionId }.distinct()
            .sortedWith(compareBy({ it.first.name }, { it.second }))
        val reports = identities.flatMap { (market, sessionId) ->
            PredictionChannel.entries.map { channel ->
                MultiModelWalkForwardEvaluator.evaluate(
                    records = records,
                    market = market,
                    sessionId = sessionId,
                    channel = channel,
                    targetDateExclusive = cutoffExclusive,
                    minimumHistory = 30,
                    limit = 10,
                    targetStartInclusive = evaluationStart
                )
            }
        }
        require(reports.size == Market.entries.size * PredictionChannel.entries.size) {
            "Expected all nine markets across Open Panna, Close Panna and Jodi"
        }

        output.parentFile?.mkdirs()
        output.bufferedWriter().use { writer ->
            writer.appendLine(
                "market,session_id,channel,model_id,evaluation_start,evaluation_end_exclusive," +
                    "requested_targets,evaluated_targets,abstained_targets,top1_hits,top1_rate," +
                    "top3_hits,top3_rate,top5_hits,top5_rate"
            )
            reports.forEach { report ->
                val all = report.models + report.consensus
                all.forEach { metric ->
                    writer.appendLine(
                        listOf(
                            report.market.name,
                            report.sessionId,
                            report.channel.name,
                            metric.modelId,
                            evaluationStart.toString(),
                            cutoffExclusive.toString(),
                            metric.requestedTargets.toString(),
                            metric.evaluatedTargets.toString(),
                            metric.abstainedTargets.toString(),
                            metric.top1Hits.toString(),
                            metric.top1Rate()?.toString().orEmpty(),
                            metric.top3Hits.toString(),
                            metric.top3Rate()?.toString().orEmpty(),
                            metric.top5Hits.toString(),
                            metric.top5Rate()?.toString().orEmpty()
                        ).joinToString(",")
                    )
                }
            }
        }

        val auditFile = output.resolveSibling(output.nameWithoutExtension + ".audit.txt")
        auditFile.writeText(
            buildString {
                appendLine("MULTI-MODEL HISTORICAL WALK-FORWARD SOURCE AUDIT")
                appendLine("source_observations=${imported.observations.size}")
                appendLine("published_observations=${imported.observations.count { it.status == ObservationStatus.PUBLISHED }}")
                appendLine("malformed_observations=${imported.observations.count { it.status == ObservationStatus.MALFORMED }}")
                appendLine("conflict_groups_excluded=${sourceAudit.conflictGroups.size}")
                appendLine("single_source_unverified_groups_excluded=${sourceAudit.singleSourceUnverifiedGroups.size}")
                appendLine("independent_consensus_groups=${sourceAudit.records.size}")
                appendLine("consensus_records_used=${records.size}")
                appendLine("publishers=${imported.observations.map { it.sourceId }.distinct().sorted().joinToString(",")}")
                appendLine("source_quality=TWO_PUBLISHER_CONSENSUS")
                appendLine("channels=${PredictionChannel.entries.joinToString(",")}")
                appendLine("evaluation_start=$evaluationStart")
                appendLine("evaluation_end_exclusive=$cutoffExclusive")
                appendLine("minimum_history=30")
                appendLine("candidate_limit=10")
                appendLine("ordering=CHRONOLOGICAL; each target uses strictly earlier same-market/session rows")
                appendLine("interpretation=ranking hit-rate diagnostics only; not calibrated probabilities or guaranteed outcomes")
            },
            Charsets.UTF_8
        )
        println(
            "markets=${marketsPresent.size}; channels=${PredictionChannel.entries.size}; " +
                "reports=${reports.size}; records_used=${records.size}; " +
                "evaluation_window=$evaluationStart..${latestDate}; output=${output.absolutePath}"
        )
    }
}
