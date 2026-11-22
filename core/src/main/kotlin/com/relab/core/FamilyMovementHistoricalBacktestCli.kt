package com.relab.core

import java.io.File
import java.time.LocalDate

/** Runs the canonical family-equivalence walk-forward audit per market/session/channel. */
object FamilyMovementHistoricalBacktestCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: source-observations.csv output-family-metrics.csv" }
        val input = File(args[0])
        val output = File(args[1])
        require(input.isFile) { "Source-observation CSV does not exist: $input" }

        val imported = SourceCsvImporter.import(input.readText(Charsets.UTF_8))
        require(imported.issues.isEmpty()) {
            "Source-observation CSV has ${imported.issues.size} import issues: " +
                imported.issues.take(10).joinToString { "row ${it.rowNumber}: ${it.message}" }
        }
        val sourceAudit = ReconciledChartBuilder.build(imported.observations)
        val records = sourceAudit.records
        require(records.isNotEmpty()) { "No two-publisher consensus chart records are available" }
        val marketsPresent = records.map { it.market }.toSet()
        require(marketsPresent == Market.entries.toSet()) {
            "Expected consensus history for all nine markets; found: ${marketsPresent.map { it.name }.sorted()}"
        }
        records.groupBy { Triple(it.market, it.sessionId, it.date) }.forEach { (key, rows) ->
            require(rows.size == 1) {
                "Unreconciled duplicate date for ${key.first}/${key.second} on ${key.third}"
            }
        }

        val latestDate = records.maxOf { it.date }
        val cutoffExclusive = latestDate.plusDays(1)
        val reports = FamilyMovementWalkForwardEvaluator.evaluate(
            records = records,
            targetDateExclusive = cutoffExclusive,
            minimumHistory = 30
        )
        require(reports.size == Market.entries.size * FamilyMovementChannel.entries.size) {
            "Expected three family channels for each of the nine markets"
        }
        output.parentFile?.mkdirs()
        output.writeText(FamilyMovementCsvExporter.export(records, cutoffExclusive, minimumHistory = 30), Charsets.UTF_8)
        val audit = output.resolveSibling(output.nameWithoutExtension + ".audit.txt")
        audit.writeText(buildString {
            appendLine("FAMILY MOVEMENT CHRONOLOGICAL WALK-FORWARD AUDIT")
            appendLine("source_observations=${imported.observations.size}")
            appendLine("consensus_records_used=${records.size}")
            appendLine("conflict_groups_excluded=${sourceAudit.conflictGroups.size}")
            appendLine("single_source_unverified_groups_excluded=${sourceAudit.singleSourceUnverifiedGroups.size}")
            appendLine("publishers=${imported.observations.map { it.sourceId }.distinct().sorted().joinToString(",")}")
            appendLine("markets=${marketsPresent.map { it.name }.sorted().joinToString(",")}")
            appendLine("channels=${FamilyMovementChannel.entries.joinToString(",")}")
            appendLine("target_date_exclusive=$cutoffExclusive")
            appendLine("minimum_history=30")
            appendLine("ordering=CHRONOLOGICAL; target scored before it enters same-market/session history")
            appendLine("family_definition=FamilyMovementMath.key; +5 digit-family substitution and reversal")
            appendLine("interpretation=family-equivalence hit rates are diagnostic, not exact hits or calibrated probabilities")
            reports.forEach { report ->
                appendLine(
                    "market=${report.market.name};session=${report.sessionId};channel=${report.channel};" +
                        "requested=${report.requestedTargets};evaluated=${report.evaluatedTargets};" +
                        "abstained=${report.abstainedTargets};familyTop10=${report.top10Hits};" +
                        "exactBaselineTop10=${report.exactFrequencyBaselineTop10Hits}"
                )
            }
        }, Charsets.UTF_8)
        println("markets=${marketsPresent.size}; reports=${reports.size}; records=${records.size}; output=${output.absolutePath}")
    }
}
