package com.relab.core

import java.time.LocalDate
import java.util.Locale
import kotlin.math.sqrt

enum class ResearchVerificationStatus { VERIFIED, UNCERTAIN, REJECTED }

data class ResearchPatternProposal(
    val id: String,
    val market: Market,
    val sessionId: String,
    val channel: PatternEvidenceChannel,
    val pattern: String,
    val outcomeMode: PatternOutcomeMode = PatternOutcomeMode.EXACT,
    val source: String = "AI",
    val rationale: String = ""
)

data class ProposedPatternBacktest(
    val proposal: ResearchPatternProposal,
    val supportOccurrences: Int,
    val evaluatedOccurrences: Int,
    val abstainedOccurrences: Int,
    val hits: Int,
    val misses: Int,
    val baselineHits: Int,
    val hitRate: Double?,
    val baselineHitRate: Double?,
    val lift: Double?,
    val wilsonLowerBound: Double?,
    val wilsonUpperBound: Double?,
    val baselineWilsonLowerBound: Double?,
    val baselineWilsonUpperBound: Double?,
    val status: ResearchVerificationStatus
)

/**
 * Executes the same one-step-ahead pattern rule on chronological rows.
 * A target is scored before its outcome is added to either pattern or baseline history.
 */
object ProposedPatternBacktester {
    fun evaluate(
        records: List<ChartRecord>,
        proposal: ResearchPatternProposal,
        targetDateExclusive: LocalDate,
        minimumEvaluations: Int = 20,
        minimumLift: Double = 0.05
    ): ProposedPatternBacktest {
        require(proposal.id.isNotBlank()) { "Proposal id is required" }
        require(proposal.sessionId.isNotBlank()) { "Session id is required" }
        require(minimumEvaluations >= 1) { "Minimum evaluations must be positive" }
        require(minimumLift in 0.0..1.0) { "Minimum lift must be between 0 and 1" }

        val tokens = proposal.pattern.split(">")
        require(tokens.size in 1..20 && tokens.all { it.isNotBlank() }) {
            "Pattern must contain 1 to 20 non-empty values separated by >"
        }
        val expectedLength = if (proposal.channel == PatternEvidenceChannel.JODI) 2 else 3
        require(tokens.all { it.length == expectedLength && it.all(Char::isDigit) }) {
            "Pattern values must each contain exactly $expectedLength digits for ${proposal.channel}"
        }

        fun normalize(value: String): String = when (proposal.outcomeMode) {
            PatternOutcomeMode.EXACT -> value
            PatternOutcomeMode.FAMILY_MOVEMENT -> FamilyMovementMath.key(value)
        }
        val normalizedPattern = tokens.joinToString(">") { normalize(it) }
        val rows = records.asSequence()
            .filter { it.market == proposal.market && it.sessionId == proposal.sessionId }
            .filter { it.date.isBefore(targetDateExclusive) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
            .sortedBy { it.date }
            .toList()
        require(rows.map { it.date }.distinct().size == rows.size) {
            "Duplicate dates for ${proposal.market}/${proposal.sessionId} must be reconciled before backtesting"
        }

        fun value(row: ChartRecord): String = when (proposal.channel) {
            PatternEvidenceChannel.OPEN_PANNA -> row.openPanna
            PatternEvidenceChannel.CLOSE_PANNA -> row.closePanna
            PatternEvidenceChannel.JODI -> row.jodi
        }

        val history = mutableListOf<String>()
        val matchedOutcomes = mutableListOf<String>()
        var support = 0
        var evaluated = 0
        var hits = 0
        var baselineHits = 0
        rows.forEachIndexed { index, row ->
            val actual = normalize(value(row))
            if (index >= tokens.size) {
                val precedingPattern = history.takeLast(tokens.size).joinToString(">")
                if (precedingPattern == normalizedPattern) {
                    support++
                    if (matchedOutcomes.isNotEmpty() && history.isNotEmpty()) {
                        val prediction = mode(matchedOutcomes)
                        val baselinePrediction = mode(history)
                        evaluated++
                        if (prediction == actual) hits++
                        if (baselinePrediction == actual) baselineHits++
                    }
                }
            }
            // The held-out outcome is added only after it has been scored.
            history += actual
            if (index >= tokens.size && history.size > tokens.size) {
                val priorPattern = history.subList(history.size - tokens.size - 1, history.size - 1)
                    .joinToString(">")
                if (priorPattern == normalizedPattern) matchedOutcomes += actual
            }
        }
        val misses = evaluated - hits
        val hitRate = evaluated.takeIf { it > 0 }?.let { hits.toDouble() / it }
        val baselineRate = evaluated.takeIf { it > 0 }?.let { baselineHits.toDouble() / it }
        val bounds = hitRate?.let { wilson(hits, evaluated) }
        val baselineBounds = baselineRate?.let { wilson(baselineHits, evaluated) }
        val status = when {
            evaluated < minimumEvaluations -> ResearchVerificationStatus.UNCERTAIN
            bounds!!.first > baselineBounds!!.second + minimumLift -> ResearchVerificationStatus.VERIFIED
            bounds.second < baselineBounds.first -> ResearchVerificationStatus.REJECTED
            else -> ResearchVerificationStatus.UNCERTAIN
        }
        return ProposedPatternBacktest(
            proposal = proposal,
            supportOccurrences = support,
            evaluatedOccurrences = evaluated,
            abstainedOccurrences = support - evaluated,
            hits = hits,
            misses = misses,
            baselineHits = baselineHits,
            hitRate = hitRate,
            baselineHitRate = baselineRate,
            lift = if (hitRate != null && baselineRate != null) hitRate - baselineRate else null,
            wilsonLowerBound = bounds?.first,
            wilsonUpperBound = bounds?.second,
            baselineWilsonLowerBound = baselineBounds?.first,
            baselineWilsonUpperBound = baselineBounds?.second,
            status = status
        )
    }

    private fun mode(values: List<String>): String = values.groupingBy { it }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .first().key

    /** Two-sided 95% Wilson interval for a binomial proportion. */
    private fun wilson(successes: Int, trials: Int): Pair<Double, Double> {
        val z = 1.96
        val n = trials.toDouble()
        val p = successes.toDouble() / n
        val denominator = 1.0 + z * z / n
        val center = (p + z * z / (2.0 * n)) / denominator
        val margin = z * sqrt((p * (1.0 - p) / n) + (z * z / (4.0 * n * n))) / denominator
        return (center - margin).coerceIn(0.0, 1.0) to (center + margin).coerceIn(0.0, 1.0)
    }
}

data class ResearchToBacktestReport(
    val targetDateExclusive: LocalDate,
    val scannerRankings: List<ReverseEngineeringRanking>,
    val exactPatternEvidence: List<PatternEvidenceRecord>,
    val familyPatternEvidence: List<PatternEvidenceRecord>,
    val assessments: List<ProposedPatternBacktest>
) {
    fun compactSummary(): String = buildString {
        appendLine("RESEARCH-TO-BACKTEST REPORT")
        appendLine("Target cutoff (exclusive): $targetDateExclusive")
        appendLine("Scanner rankings: ${scannerRankings.size}")
        appendLine("Exact pattern evidence rows: ${exactPatternEvidence.size}")
        appendLine("Family-movement evidence rows: ${familyPatternEvidence.size}")
        appendLine("AI proposals tested: ${assessments.size}")
        ResearchVerificationStatus.entries.forEach { status ->
            appendLine("$status: ${assessments.count { it.status == status }}")
        }
        assessments.sortedWith(compareBy<ProposedPatternBacktest> { it.status.ordinal }
            .thenByDescending { it.wilsonLowerBound ?: -1.0 }
            .thenBy { it.proposal.id }).forEach { result ->
            val proposal = result.proposal
            fun rate(value: Double?) = value?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "n/a"
            appendLine("${result.status} | ${proposal.id} | ${proposal.market}/${proposal.sessionId} | ${proposal.channel} | ${proposal.pattern} | n=${result.evaluatedOccurrences} | hit=${rate(result.hitRate)} | baseline=${rate(result.baselineHitRate)} | Wilson-LB=${rate(result.wilsonLowerBound)} | Baseline-Wilson-UB=${rate(result.baselineWilsonUpperBound)}")
        }
        appendLine("Verification is historical evidence only, not a guarantee or calibrated future probability.")
    }
}

/** Connects scanner output, exact/family pattern evidence and AI proposals in one auditable run. */
object ResearchToBacktestWorkflow {
    fun run(
        records: List<ChartRecord>,
        scannerRankings: List<ReverseEngineeringRanking>,
        proposals: List<ResearchPatternProposal>,
        targetDateExclusive: LocalDate,
        minimumEvaluations: Int = 20,
        minimumLift: Double = 0.05
    ): ResearchToBacktestReport {
        val eligible = records.filter { it.date.isBefore(targetDateExclusive) }
        val exactEvidence = PatternEvidenceDatabase.analyze(
            eligible, targetDateExclusive, outcomeMode = PatternOutcomeMode.EXACT
        )
        val familyEvidence = PatternEvidenceDatabase.analyze(
            eligible, targetDateExclusive, outcomeMode = PatternOutcomeMode.FAMILY_MOVEMENT
        )
        val assessments = proposals.map { proposal ->
            ProposedPatternBacktester.evaluate(
                eligible, proposal, targetDateExclusive, minimumEvaluations, minimumLift
            )
        }.sortedWith(compareBy<ProposedPatternBacktest> { it.status.ordinal }
            .thenByDescending { it.wilsonLowerBound ?: -1.0 }
            .thenBy { it.proposal.id })
        return ResearchToBacktestReport(
            targetDateExclusive, scannerRankings.toList(), exactEvidence, familyEvidence, assessments
        )
    }
}

object ResearchToBacktestCsvExporter {
    fun export(report: ResearchToBacktestReport): String {
        val header = listOf(
            "proposal_id", "source", "market", "session", "channel", "pattern", "outcome_mode",
            "support_occurrences", "evaluated_occurrences", "abstained_occurrences", "hits", "misses",
            "baseline_hits", "hit_rate", "baseline_hit_rate", "lift", "wilson_lower_95",
            "wilson_upper_95", "baseline_wilson_lower_95", "baseline_wilson_upper_95", "verification_status", "rationale"
        )
        val rows = report.assessments.map { result ->
            val p = result.proposal
            listOf(
                p.id, p.source, p.market.name, p.sessionId, p.channel.name, p.pattern, p.outcomeMode.name,
                result.supportOccurrences.toString(), result.evaluatedOccurrences.toString(),
                result.abstainedOccurrences.toString(), result.hits.toString(), result.misses.toString(),
                result.baselineHits.toString(), result.hitRate.csvRate(), result.baselineHitRate.csvRate(),
                result.lift.csvRate(), result.wilsonLowerBound.csvRate(), result.wilsonUpperBound.csvRate(),
                result.baselineWilsonLowerBound.csvRate(), result.baselineWilsonUpperBound.csvRate(),
                result.status.name, p.rationale
            )
        }
        return (listOf(header) + rows).joinToString("\n") { row -> row.joinToString(",") { it.csvEscape() } }
    }

    private fun Double?.csvRate(): String = this?.let { String.format(Locale.ROOT, "%.6f", it) } ?: ""
    private fun String.csvEscape(): String = if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + replace("\"", "\"\"") + "\""
    } else this
}
