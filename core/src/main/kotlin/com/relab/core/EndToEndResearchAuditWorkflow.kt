package com.relab.core

import java.time.LocalDate

/**
 * Joined audit result: existing scanner + pattern evidence + AI-proposal assessment,
 * augmented with chronological multi-model walk-forward measurements.
 *
 * All model measurements are descriptive historical coverage/hit rates, not
 * calibrated probabilities or guarantees of future performance.
 */
data class EndToEndResearchAuditReport(
    val research: ResearchToBacktestReport,
    val multiModelWalkForward: List<MultiModelWalkForwardReport>,
    val minimumModelHistory: Int
) {
    fun compactSummary(): String = buildString {
        append(research.compactSummary())
        appendLine()
        appendLine("MULTI-MODEL WALK-FORWARD AUDIT")
        appendLine("Minimum earlier history: $minimumModelHistory")
        appendLine("Market/session/channel evaluations: ${multiModelWalkForward.size}")
        multiModelWalkForward.forEach { report ->
            fun metric(value: Double?): String =
                value?.let { String.format(java.util.Locale.ROOT, "%.3f", it) } ?: "n/a"
            appendLine(
                "${report.market}/${report.sessionId} ${report.channel}" +
                    " | targets=${report.consensus.requestedTargets}" +
                    " | consensus evaluated=${report.consensus.evaluatedTargets}" +
                    " | abstained=${report.consensus.abstainedTargets}" +
                    " | top1=${metric(report.consensus.top1Rate())}" +
                    " | top3=${metric(report.consensus.top3Rate())}" +
                    " | top5=${metric(report.consensus.top5Rate())}"
            )
            report.models.forEach { model ->
                appendLine(
                    "  MODEL ${model.modelId}" +
                        " | evaluated=${model.evaluatedTargets}" +
                        " | abstained=${model.abstainedTargets}" +
                        " | top1=${metric(model.top1Rate())}" +
                        " | top3=${metric(model.top3Rate())}" +
                        " | top5=${metric(model.top5Rate())}"
                )
            }
        }
        appendLine("Walk-forward rates are historical hit/coverage metrics, not probability estimates.")
    }
}

/**
 * End-to-end deterministic orchestration over already reconciled chart records.
 *
 * Input rows must be the canonical/reconciled dataset chosen by the caller. The
 * workflow does not silently merge duplicate dates or promote single-source data
 * to consensus. Each target is evaluated with strictly earlier rows only.
 */
object EndToEndResearchAuditWorkflow {
    /**
     * Deterministic-first entry point: build scanner rankings directly from the
     * reconciled chart records, then run evidence analysis and walk-forward tests.
     * AI proposals are optional enrichment; an empty list is a valid normal run.
     */
    fun runDeterministic(
        records: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        proposals: List<ResearchPatternProposal> = emptyList(),
        minimumEvaluations: Int = 20,
        minimumLift: Double = 0.05,
        minimumScannerHistory: Int = 30,
        minimumModelHistory: Int = 30,
        candidateLimit: Int = 10,
        sourceQuality: ChartBuildReport? = null,
        sourceObservationCount: Int = 0
    ): EndToEndResearchAuditReport {
        require(minimumScannerHistory >= 1) { "Minimum scanner history must be positive" }
        require(candidateLimit in 1..100) { "Candidate limit must be 1..100" }

        val eligibleIdentities = records.asSequence()
            .filter { it.date.isBefore(targetDateExclusive) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
            .map { it.market to it.sessionId }
            .distinct()
            .sortedWith(compareBy<Pair<Market, String>>({ it.first.name }, { it.second }))
            .toList()

        val scannerRankings = eligibleIdentities.flatMap { (market, sessionId) ->
            listOf(ReverseEngineeringSide.OPEN, ReverseEngineeringSide.CLOSE).map { side ->
                ReverseEngineeringModel.rank(
                    records = records,
                    market = market,
                    sessionId = sessionId,
                    targetDate = targetDateExclusive,
                    side = side,
                    limit = candidateLimit,
                    minimumHistory = minimumScannerHistory
                )
            }
        }

        return run(
            records = records,
            scannerRankings = scannerRankings,
            proposals = proposals,
            targetDateExclusive = targetDateExclusive,
            minimumEvaluations = minimumEvaluations,
            minimumLift = minimumLift,
            minimumModelHistory = minimumModelHistory,
            candidateLimit = candidateLimit,
            sourceQuality = sourceQuality,
            sourceObservationCount = sourceObservationCount
        )
    }

    fun run(
        records: List<ChartRecord>,
        scannerRankings: List<ReverseEngineeringRanking>,
        proposals: List<ResearchPatternProposal>,
        targetDateExclusive: LocalDate,
        minimumEvaluations: Int = 20,
        minimumLift: Double = 0.05,
        minimumModelHistory: Int = 30,
        candidateLimit: Int = 10,
        sourceQuality: ChartBuildReport? = null,
        sourceObservationCount: Int = 0
    ): EndToEndResearchAuditReport {
        require(minimumModelHistory >= 1) { "Minimum model history must be positive" }
        require(candidateLimit in 1..100) { "Candidate limit must be 1..100" }

        val research = ResearchToBacktestWorkflow.run(
            records = records,
            scannerRankings = scannerRankings,
            proposals = proposals,
            targetDateExclusive = targetDateExclusive,
            minimumEvaluations = minimumEvaluations,
            minimumLift = minimumLift,
            sourceQuality = sourceQuality,
            sourceObservationCount = sourceObservationCount
        )

        val eligible = records.filter {
            it.date.isBefore(targetDateExclusive) &&
                ChartMath.validate(it) is ValidationResult.Valid
        }
        val grouped = eligible.groupBy { it.market to it.sessionId }
        val walkForward = grouped.entries
            .sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
            .flatMap { (identity, rows) ->
                PredictionChannel.entries.map { channel ->
                    MultiModelWalkForwardEvaluator.evaluate(
                        records = rows,
                        market = identity.first,
                        sessionId = identity.second,
                        channel = channel,
                        targetDateExclusive = targetDateExclusive,
                        minimumHistory = minimumModelHistory,
                        limit = candidateLimit,
                        engine = MultiModelPredictionEngine(minimumHistory = minimumModelHistory)
                    )
                }
            }

        return EndToEndResearchAuditReport(
            research = research,
            multiModelWalkForward = walkForward,
            minimumModelHistory = minimumModelHistory
        )
    }

    /** One row per market/session/channel/model plus its consensus result. */
    fun exportWalkForwardCsv(report: EndToEndResearchAuditReport): String {
        val header = listOf(
            "market", "session", "channel", "model_id", "requested_targets",
            "evaluated_targets", "abstained_targets", "top1_hits", "top1_rate",
            "top3_hits", "top3_rate", "top5_hits", "top5_rate", "minimum_history"
        )
        fun row(
            market: Market,
            session: String,
            channel: PredictionChannel,
            metrics: WalkForwardModelMetrics
        ): List<String> = listOf(
            market.name, session, channel.name, metrics.modelId,
            metrics.requestedTargets.toString(), metrics.evaluatedTargets.toString(),
            metrics.abstainedTargets.toString(), metrics.top1Hits.toString(),
            metrics.top1Rate().csv(), metrics.top3Hits.toString(), metrics.top3Rate().csv(),
            metrics.top5Hits.toString(), metrics.top5Rate().csv(),
            report.minimumModelHistory.toString()
        )

        val rows = report.multiModelWalkForward.flatMap { result ->
            listOf(row(result.market, result.sessionId, result.channel, result.consensus)) +
                result.models.map { row(result.market, result.sessionId, result.channel, it) }
        }
        return (listOf(header) + rows).joinToString("\n") { values ->
            values.joinToString(",") { it.csvEscape() }
        }
    }

    private fun Double?.csv(): String =
        this?.let { String.format(java.util.Locale.ROOT, "%.6f", it) } ?: ""

    private fun String.csvEscape(): String =
        if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + replace("\"", "\"\"") + "\""
        } else this
}
