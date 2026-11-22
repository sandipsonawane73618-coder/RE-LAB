package com.relab.core

import java.io.File
import java.time.LocalDate

/**
 * Runs the production Panna/Half-Sangam/Sangam walk-forward evaluator over an
 * audited published-observation CSV. Targets are evaluated chronologically;
 * the evaluator itself uses only earlier same-market/session rows.
 */
object PannaHalfSangamBacktestCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: input-eligible.csv output-metrics.csv" }
        val input = File(args[0])
        val output = File(args[1])
        require(input.isFile) { "Eligible input CSV does not exist: $input" }

        val imported = SourceCsvImporter.import(input.readText(Charsets.UTF_8))
        require(imported.issues.isEmpty()) {
            "Source-observation CSV has ${imported.issues.size} import issues: " +
                imported.issues.take(10).joinToString { "row ${it.rowNumber}: ${it.message}" }
        }
        val sourceAudit = ReconciledChartBuilder.build(imported.observations)
        // Historical training uses only dates where both publishers agree.
        // Single-source dates and conflicts remain in the audit, never promoted.
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
        val conflictGroups = sourceAudit.conflictGroups.size
        val singleSourceGroups = sourceAudit.singleSourceUnverifiedGroups.size
        val consensusGroups = sourceAudit.records.size
        val malformedObservations = imported.observations.count { it.status == ObservationStatus.MALFORMED }
        val missingObservations = imported.observations.count {
            it.status == ObservationStatus.MISSING_FROM_SOURCE || it.status == ObservationStatus.HOLIDAY_CLOSED
        }
        val publishers = imported.observations.map { it.sourceId }.distinct().sorted()

        val reports = PannaHalfSangamWalkForwardEvaluator.evaluate(
            records = records,
            targetDateExclusive = LocalDate.MAX,
            minimumHistory = 30,
            candidateLimit = 100,
            targetLimit = 365,
            halfSangamPannaLimit = 1000,
            halfSangamOppositeLimit = 1000,
            sangamPannaLimit = 1000
        )
        require(reports.map { it.market }.toSet() == Market.entries.toSet()) {
            "Walk-forward evaluation did not return all nine markets"
        }

        output.parentFile?.mkdirs()
        output.bufferedWriter().use { writer ->
            writer.appendLine(
                "market,session_id,metric,requested_targets,evaluated_targets,abstained_targets," +
                    "top10_hits,top10_rate,top100_hits,top100_rate,candidate_coverage,mrr," +
                    "rank1,rank2_to5,rank6_to10,rank11_to25,rank26_to50,rank51_to100,rank_above100"
            )
            reports.sortedWith(compareBy<PannaHalfSangamWalkForwardReport> { it.market.name }.thenBy { it.sessionId })
                .forEach { report ->
                    appendMetric(writer, report.market, report.sessionId, "OPEN_PANNA",
                        report.openPanna.requestedTargets, report.openPanna.evaluatedTargets,
                        report.openPanna.abstainedTargets, report.openPanna.exactTop10Hits,
                        report.openPanna.exactTop10Rate(), report.openPanna.exactTop100Hits,
                        report.openPanna.exactTop100Rate(), report.openPanna.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "CLOSE_PANNA",
                        report.closePanna.requestedTargets, report.closePanna.evaluatedTargets,
                        report.closePanna.abstainedTargets, report.closePanna.exactTop10Hits,
                        report.closePanna.exactTop10Rate(), report.closePanna.exactTop100Hits,
                        report.closePanna.exactTop100Rate(), report.closePanna.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_PANNA_HYBRID_MODEL_TOP10_EXACT_FREQUENCY_TOP90",
                        report.openPannaHybridTop10PlusExactFrequencyTop90.requestedTargets, report.openPannaHybridTop10PlusExactFrequencyTop90.evaluatedTargets,
                        report.openPannaHybridTop10PlusExactFrequencyTop90.abstainedTargets, report.openPannaHybridTop10PlusExactFrequencyTop90.exactTop10Hits,
                        report.openPannaHybridTop10PlusExactFrequencyTop90.exactTop10Rate(), report.openPannaHybridTop10PlusExactFrequencyTop90.exactTop100Hits,
                        report.openPannaHybridTop10PlusExactFrequencyTop90.exactTop100Rate(), report.openPannaHybridTop10PlusExactFrequencyTop90.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "CLOSE_PANNA_HYBRID_MODEL_TOP10_EXACT_FREQUENCY_TOP90",
                        report.closePannaHybridTop10PlusExactFrequencyTop90.requestedTargets, report.closePannaHybridTop10PlusExactFrequencyTop90.evaluatedTargets,
                        report.closePannaHybridTop10PlusExactFrequencyTop90.abstainedTargets, report.closePannaHybridTop10PlusExactFrequencyTop90.exactTop10Hits,
                        report.closePannaHybridTop10PlusExactFrequencyTop90.exactTop10Rate(), report.closePannaHybridTop10PlusExactFrequencyTop90.exactTop100Hits,
                        report.closePannaHybridTop10PlusExactFrequencyTop90.exactTop100Rate(), report.closePannaHybridTop10PlusExactFrequencyTop90.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_PANNA_CLOSE_ANK_TOP100_BASELINE",
                        report.openPannaCloseAnkTop100PannaBaseline.requestedTargets, report.openPannaCloseAnkTop100PannaBaseline.evaluatedTargets,
                        report.openPannaCloseAnkTop100PannaBaseline.abstainedTargets, report.openPannaCloseAnkTop100PannaBaseline.exactTop10Hits,
                        report.openPannaCloseAnkTop100PannaBaseline.exactTop10Rate(), report.openPannaCloseAnkTop100PannaBaseline.exactTop100Hits,
                        report.openPannaCloseAnkTop100PannaBaseline.exactTop100Rate(), report.openPannaCloseAnkTop100PannaBaseline.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_PANNA_CLOSE_ANK_TOP100_HYBRID",
                        report.openPannaCloseAnkTop100PannaHybrid.requestedTargets, report.openPannaCloseAnkTop100PannaHybrid.evaluatedTargets,
                        report.openPannaCloseAnkTop100PannaHybrid.abstainedTargets, report.openPannaCloseAnkTop100PannaHybrid.exactTop10Hits,
                        report.openPannaCloseAnkTop100PannaHybrid.exactTop10Rate(), report.openPannaCloseAnkTop100PannaHybrid.exactTop100Hits,
                        report.openPannaCloseAnkTop100PannaHybrid.exactTop100Rate(), report.openPannaCloseAnkTop100PannaHybrid.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_ANK_CLOSE_PANNA_TOP100_BASELINE",
                        report.openAnkClosePannaTop100PannaBaseline.requestedTargets, report.openAnkClosePannaTop100PannaBaseline.evaluatedTargets,
                        report.openAnkClosePannaTop100PannaBaseline.abstainedTargets, report.openAnkClosePannaTop100PannaBaseline.exactTop10Hits,
                        report.openAnkClosePannaTop100PannaBaseline.exactTop10Rate(), report.openAnkClosePannaTop100PannaBaseline.exactTop100Hits,
                        report.openAnkClosePannaTop100PannaBaseline.exactTop100Rate(), report.openAnkClosePannaTop100PannaBaseline.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_ANK_CLOSE_PANNA_TOP100_HYBRID",
                        report.openAnkClosePannaTop100PannaHybrid.requestedTargets, report.openAnkClosePannaTop100PannaHybrid.evaluatedTargets,
                        report.openAnkClosePannaTop100PannaHybrid.abstainedTargets, report.openAnkClosePannaTop100PannaHybrid.exactTop10Hits,
                        report.openAnkClosePannaTop100PannaHybrid.exactTop10Rate(), report.openAnkClosePannaTop100PannaHybrid.exactTop100Hits,
                        report.openAnkClosePannaTop100PannaHybrid.exactTop100Rate(), report.openAnkClosePannaTop100PannaHybrid.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "SANGAM_TOP100X100_BASELINE",
                        report.sangamTop100x100Baseline.requestedTargets, report.sangamTop100x100Baseline.evaluatedTargets,
                        report.sangamTop100x100Baseline.abstainedTargets, report.sangamTop100x100Baseline.exactTop10Hits,
                        report.sangamTop100x100Baseline.exactTop10Rate(), report.sangamTop100x100Baseline.exactTop100Hits,
                        report.sangamTop100x100Baseline.exactTop100Rate(), report.sangamTop100x100Baseline.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "SANGAM_TOP100X100_HYBRID",
                        report.sangamTop100x100Hybrid.requestedTargets, report.sangamTop100x100Hybrid.evaluatedTargets,
                        report.sangamTop100x100Hybrid.abstainedTargets, report.sangamTop100x100Hybrid.exactTop10Hits,
                        report.sangamTop100x100Hybrid.exactTop10Rate(), report.sangamTop100x100Hybrid.exactTop100Hits,
                        report.sangamTop100x100Hybrid.exactTop100Rate(), report.sangamTop100x100Hybrid.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_PANNA_CLOSE_ANK_PANNA1000_ANK1000",
                        report.openPannaCloseAnk.requestedTargets, report.openPannaCloseAnk.evaluatedTargets,
                        report.openPannaCloseAnk.abstainedTargets, report.openPannaCloseAnk.exactTop10Hits,
                        report.openPannaCloseAnk.exactTop10Rate(), report.openPannaCloseAnk.exactTop100Hits,
                        report.openPannaCloseAnk.exactTop100Rate(), report.openPannaCloseAnk.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_PANNA_CLOSE_ANK_CONDITIONED_ON_OPEN_PANNA",
                        report.openPannaCloseAnkConditioned.requestedTargets, report.openPannaCloseAnkConditioned.evaluatedTargets,
                        report.openPannaCloseAnkConditioned.abstainedTargets, report.openPannaCloseAnkConditioned.exactTop10Hits,
                        report.openPannaCloseAnkConditioned.exactTop10Rate(), report.openPannaCloseAnkConditioned.exactTop100Hits,
                        report.openPannaCloseAnkConditioned.exactTop100Rate(), report.openPannaCloseAnkConditioned.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_ANK_CLOSE_PANNA_PANNA1000_ANK1000",
                        report.openAnkClosePanna.requestedTargets, report.openAnkClosePanna.evaluatedTargets,
                        report.openAnkClosePanna.abstainedTargets, report.openAnkClosePanna.exactTop10Hits,
                        report.openAnkClosePanna.exactTop10Rate(), report.openAnkClosePanna.exactTop100Hits,
                        report.openAnkClosePanna.exactTop100Rate(), report.openAnkClosePanna.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_ANK_CLOSE_PANNA_CONDITIONED_ON_OPEN_ANK",
                        report.openAnkClosePannaConditioned.requestedTargets, report.openAnkClosePannaConditioned.evaluatedTargets,
                        report.openAnkClosePannaConditioned.abstainedTargets, report.openAnkClosePannaConditioned.exactTop10Hits,
                        report.openAnkClosePannaConditioned.exactTop10Rate(), report.openAnkClosePannaConditioned.exactTop100Hits,
                        report.openAnkClosePannaConditioned.exactTop100Rate(), report.openAnkClosePannaConditioned.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_PANNA_CLOSE_ANK_EXACT_FREQUENCY_BASELINE",
                        report.openPannaCloseAnkExactFrequencyBaseline.requestedTargets, report.openPannaCloseAnkExactFrequencyBaseline.evaluatedTargets,
                        report.openPannaCloseAnkExactFrequencyBaseline.abstainedTargets, report.openPannaCloseAnkExactFrequencyBaseline.exactTop10Hits,
                        report.openPannaCloseAnkExactFrequencyBaseline.exactTop10Rate(), report.openPannaCloseAnkExactFrequencyBaseline.exactTop100Hits,
                        report.openPannaCloseAnkExactFrequencyBaseline.exactTop100Rate(), report.openPannaCloseAnkExactFrequencyBaseline.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_ANK_CLOSE_PANNA_EXACT_FREQUENCY_BASELINE",
                        report.openAnkClosePannaExactFrequencyBaseline.requestedTargets, report.openAnkClosePannaExactFrequencyBaseline.evaluatedTargets,
                        report.openAnkClosePannaExactFrequencyBaseline.abstainedTargets, report.openAnkClosePannaExactFrequencyBaseline.exactTop10Hits,
                        report.openAnkClosePannaExactFrequencyBaseline.exactTop10Rate(), report.openAnkClosePannaExactFrequencyBaseline.exactTop100Hits,
                        report.openAnkClosePannaExactFrequencyBaseline.exactTop100Rate(), report.openAnkClosePannaExactFrequencyBaseline.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "SANGAM_EXACT_FREQUENCY_BASELINE",
                        report.sangamExactFrequencyBaseline.requestedTargets, report.sangamExactFrequencyBaseline.evaluatedTargets,
                        report.sangamExactFrequencyBaseline.abstainedTargets, report.sangamExactFrequencyBaseline.exactTop10Hits,
                        report.sangamExactFrequencyBaseline.exactTop10Rate(), report.sangamExactFrequencyBaseline.exactTop100Hits,
                        report.sangamExactFrequencyBaseline.exactTop100Rate(), report.sangamExactFrequencyBaseline.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "SANGAM_CLOSE_PANNA_CONDITIONED_ON_OPEN_PANNA",
                        report.sangamClosePannaConditionedOnOpenPanna.requestedTargets, report.sangamClosePannaConditionedOnOpenPanna.evaluatedTargets,
                        report.sangamClosePannaConditionedOnOpenPanna.abstainedTargets, report.sangamClosePannaConditionedOnOpenPanna.exactTop10Hits,
                        report.sangamClosePannaConditionedOnOpenPanna.exactTop10Rate(), report.sangamClosePannaConditionedOnOpenPanna.exactTop100Hits,
                        report.sangamClosePannaConditionedOnOpenPanna.exactTop100Rate(), report.sangamClosePannaConditionedOnOpenPanna.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "SANGAM_CLOSE_PANNA_EXACT_FREQUENCY_BASELINE",
                        report.sangamClosePannaExactFrequencyBaseline.requestedTargets, report.sangamClosePannaExactFrequencyBaseline.evaluatedTargets,
                        report.sangamClosePannaExactFrequencyBaseline.abstainedTargets, report.sangamClosePannaExactFrequencyBaseline.exactTop10Hits,
                        report.sangamClosePannaExactFrequencyBaseline.exactTop10Rate(), report.sangamClosePannaExactFrequencyBaseline.exactTop100Hits,
                        report.sangamClosePannaExactFrequencyBaseline.exactTop100Rate(), report.sangamClosePannaExactFrequencyBaseline.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "SANGAM_PANNA1000",
                        report.sangam.requestedTargets, report.sangam.evaluatedTargets,
                        report.sangam.abstainedTargets, report.sangam.exactTop10Hits,
                        report.sangam.exactTop10Rate(), report.sangam.exactTop100Hits,
                        report.sangam.exactTop100Rate(), report.sangam.exactRankDiagnostics)
                }
        }

        val auditFile = output.resolveSibling(output.nameWithoutExtension + ".audit.txt")
        auditFile.parentFile?.mkdirs()
        auditFile.writeText(
            buildString {
                appendLine("HISTORICAL PRELOAD BACKTEST SOURCE AUDIT")
                appendLine("input=${input.absolutePath}")
                appendLine("source_observations=${imported.observations.size}")
                appendLine("import_issues=${imported.issues.size}")
                appendLine("published_observations=${imported.observations.count { it.status == ObservationStatus.PUBLISHED }}")
                appendLine("malformed_observations=$malformedObservations")
                appendLine("missing_or_closed_observations=$missingObservations")
                appendLine("single_source_unverified_groups_excluded=$singleSourceGroups")
                appendLine("independent_consensus_groups=$consensusGroups")
                appendLine("conflict_groups_excluded=$conflictGroups")
                appendLine("consensus_records_used=${records.size}")
                appendLine("publishers=${publishers.joinToString(",")}")
                appendLine("source_quality=TWO_PUBLISHER_CONSENSUS")
                appendLine("conflict_policy=EXCLUDE; no winner selected")
                appendLine("ordering=CHRONOLOGICAL; each target uses earlier same-market/session rows only")
            },
            Charsets.UTF_8
        )
        println("source_observations=${imported.observations.size}; records_used=${records.size}; conflicts_excluded=$conflictGroups; source_quality=TWO_PUBLISHER_CONSENSUS; markets=${marketsPresent.size}; reports=${reports.size}; target_window=365_per_market_session; output=${output.absolutePath}; audit=${auditFile.absolutePath}")
    }

    private fun appendMetric(
        writer: java.io.BufferedWriter,
        market: Market,
        sessionId: String,
        metric: String,
        requested: Int,
        evaluated: Int,
        abstained: Int,
        top10Hits: Int,
        top10Rate: Double?,
        top100Hits: Int,
        top100Rate: Double?,
        ranks: ExactRankDiagnostics
    ) {
        fun number(value: Double?): String = value?.toString().orEmpty()
        writer.appendLine(
            listOf(
                market.name, sessionId, metric, requested, evaluated, abstained,
                top10Hits, number(top10Rate), top100Hits, number(top100Rate),
                number(ranks.candidatePoolCoverage()), number(ranks.meanReciprocalRank()),
                ranks.rank1, ranks.rank2To5, ranks.rank6To10, ranks.rank11To25,
                ranks.rank26To50, ranks.rank51To100, ranks.rankAbove100
            ).joinToString(",")
        )
    }


}
