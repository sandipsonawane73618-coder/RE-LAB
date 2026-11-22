package com.relab.core

import java.time.LocalDate
import java.util.Locale
import kotlin.math.sqrt

/**
 * A second, chronological holdout audit for AI-discovered hypotheses.
 *
 * The first 70% of each proposal's own market/session history is used only
 * to learn outcomes after the proposed sequence. The final 30% is a sealed
 * chronological holdout: predictions are scored before each outcome is added.
 * No AI verdict, scanner score, or all-history backtest status is trusted here.
 */
data class IndependentProposalVerification(
    val proposal: ResearchPatternProposal,
    val trainingRows: Int,
    val holdoutRows: Int,
    val holdoutStart: LocalDate?,
    val holdoutEnd: LocalDate?,
    val supportOccurrences: Int,
    val evaluatedOccurrences: Int,
    val abstainedOccurrences: Int,
    val hits: Int,
    val baselineHits: Int,
    val hitRate: Double?,
    val baselineHitRate: Double?,
    val status: ResearchVerificationStatus,
    val reason: String? = null
)

data class IndependentVerificationReport(
    val targetDateExclusive: LocalDate,
    val trainingFraction: Double,
    val minimumEvaluations: Int,
    val results: List<IndependentProposalVerification>
) {
    fun compactSummary(): String = buildString {
        appendLine("INDEPENDENT CHRONOLOGICAL HOLDOUT VERIFICATION")
        appendLine("Cutoff (exclusive): $targetDateExclusive | training fraction: " +
            String.format(Locale.ROOT, "%.2f", trainingFraction) +
            " | minimum holdout evaluations: $minimumEvaluations")
        appendLine("This audit uses a separate 70/30 chronological split; it does not reuse AI judgments.")
        appendLine("Proposals: ${results.size} | VERIFIED: ${results.count { it.status == ResearchVerificationStatus.VERIFIED }} | UNCERTAIN: ${results.count { it.status == ResearchVerificationStatus.UNCERTAIN }} | REJECTED: ${results.count { it.status == ResearchVerificationStatus.REJECTED }}")
        results.forEach { result ->
            val rate = result.hitRate?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "n/a"
            val baseline = result.baselineHitRate?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "n/a"
            appendLine("${result.status} | ${result.proposal.id} | ${result.proposal.market}/${result.proposal.sessionId} | ${result.proposal.channel}/${result.proposal.outcomeMode} | train=${result.trainingRows} | holdout=${result.holdoutRows} | support=${result.supportOccurrences} | evaluated=${result.evaluatedOccurrences} | abstained=${result.abstainedOccurrences} | hit=$rate | baseline=$baseline" +
                (result.reason?.let { " | reason=$it" } ?: ""))
        }
        appendLine("Holdout rates are historical measurements, not calibrated probabilities or guarantees.")
    }
}

/** Independent holdout evaluator; deliberately separate from ProposedPatternBacktester. */
object IndependentResearchVerifier {
    fun verify(
        records: List<ChartRecord>,
        proposals: List<ResearchPatternProposal>,
        targetDateExclusive: LocalDate,
        minimumEvaluations: Int = 20,
        trainingFraction: Double = 0.70
    ): IndependentVerificationReport {
        require(minimumEvaluations >= 1) { "Minimum evaluations must be positive" }
        require(trainingFraction in 0.50..0.90) { "Training fraction must be between 0.50 and 0.90" }
        val duplicateIds = proposals.groupBy { it.id }.filterValues { it.size > 1 }.keys
        val results = proposals.map { proposal ->
            verifyOne(records, proposal, targetDateExclusive, minimumEvaluations, trainingFraction,
                proposal.id in duplicateIds)
        }
        return IndependentVerificationReport(targetDateExclusive, trainingFraction, minimumEvaluations, results)
    }

    private fun verifyOne(
        records: List<ChartRecord>,
        proposal: ResearchPatternProposal,
        cutoff: LocalDate,
        minimumEvaluations: Int,
        trainingFraction: Double,
        duplicateId: Boolean
    ): IndependentProposalVerification {
        fun rejected(reason: String) = IndependentProposalVerification(
            proposal, 0, 0, null, null, 0, 0, 0, 0, 0, null, null,
            ResearchVerificationStatus.REJECTED, reason
        )
        if (proposal.id.isBlank()) return rejected("Blank proposal id")
        if (duplicateId) return rejected("Duplicate proposal id")
        if (proposal.sessionId.isBlank()) return rejected("Blank session id")

        val tokens = proposal.pattern.split(">")
        val width = if (proposal.channel == PatternEvidenceChannel.JODI) 2 else 3
        if (tokens.size !in 1..20 || tokens.any { token ->
                token.length != width || token.any { !it.isDigit() }
            }) return rejected("Invalid pattern shape for ${proposal.channel}")

        val scoped = records.filter {
            it.market == proposal.market && it.sessionId == proposal.sessionId && it.date.isBefore(cutoff)
        }.sortedBy { it.date }
        if (scoped.map { it.date }.distinct().size != scoped.size) {
            return rejected("Duplicate market/session dates; reconcile source rows first")
        }
        if (scoped.any { ChartMath.validate(it) !is ValidationResult.Valid }) {
            return rejected("Invalid row in scoped history; holdout cannot bridge a data-quality barrier")
        }
        val split = (scoped.size * trainingFraction).toInt()
        if (split <= tokens.size || split >= scoped.size) {
            return IndependentProposalVerification(
                proposal, split, scoped.size - split, scoped.getOrNull(split)?.date, scoped.lastOrNull()?.date,
                0, 0, 0, 0, 0, null, null, ResearchVerificationStatus.UNCERTAIN,
                "Insufficient rows for a chronological train/holdout split"
            )
        }

        fun rawValue(row: ChartRecord): String = when (proposal.channel) {
            PatternEvidenceChannel.OPEN_PANNA -> row.openPanna
            PatternEvidenceChannel.CLOSE_PANNA -> row.closePanna
            PatternEvidenceChannel.JODI -> row.jodi
        }
        fun normalize(value: String): String = when (proposal.outcomeMode) {
            PatternOutcomeMode.EXACT -> value
            PatternOutcomeMode.FAMILY_MOVEMENT -> FamilyMovementMath.key(value)
        }
        val values = scoped.map { normalize(rawValue(it)) }
        val expected = tokens.map(::normalize)
        fun matchesAt(targetIndex: Int): Boolean {
            if (targetIndex < expected.size) return false
            for (offset in expected.indices) {
                if (values[targetIndex - expected.size + offset] != expected[offset]) return false
            }
            return true
        }

        // Train only on pattern occurrences whose following outcome is also in training.
        val learnedOutcomes = mutableListOf<String>()
        for (targetIndex in expected.size until split) {
            if (matchesAt(targetIndex)) learnedOutcomes += values[targetIndex]
        }
        if (learnedOutcomes.isEmpty()) {
            return IndependentProposalVerification(
                proposal, split, scoped.size - split, scoped[split].date, scoped.last().date,
                0, 0, 0, 0, 0, null, null, ResearchVerificationStatus.UNCERTAIN,
                "Pattern has no completed training outcome before holdout"
            )
        }

        var support = 0
        var evaluated = 0
        var hits = 0
        var baselineHits = 0
        for (targetIndex in split until scoped.size) {
            if (!matchesAt(targetIndex)) continue
            support++
            val predicted = mode(learnedOutcomes)
            val baseline = mode(values.subList(0, targetIndex))
            val actual = values[targetIndex]
            evaluated++
            if (predicted == actual) hits++
            if (baseline == actual) baselineHits++
            // Reveal this holdout outcome only after scoring it.
            learnedOutcomes += actual
        }
        val abstained = support - evaluated
        val hitRate = evaluated.takeIf { it > 0 }?.let { hits.toDouble() / it }
        val baselineRate = evaluated.takeIf { it > 0 }?.let { baselineHits.toDouble() / it }
        val status = when {
            evaluated < minimumEvaluations -> ResearchVerificationStatus.UNCERTAIN
            wilson(hits, evaluated).first > wilson(baselineHits, evaluated).second ->
                ResearchVerificationStatus.VERIFIED
            wilson(hits, evaluated).second < wilson(baselineHits, evaluated).first ->
                ResearchVerificationStatus.REJECTED
            else -> ResearchVerificationStatus.UNCERTAIN
        }
        return IndependentProposalVerification(
            proposal, split, scoped.size - split, scoped[split].date, scoped.last().date,
            support, evaluated, abstained, hits, baselineHits, hitRate, baselineRate, status,
            if (evaluated < minimumEvaluations) "Insufficient independent holdout evaluations" else null
        )
    }

    private fun mode(values: List<String>): String = values.groupingBy { it }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .first().key

    private fun wilson(successes: Int, total: Int): Pair<Double, Double> {
        val z = 1.959963984540054
        val n = total.toDouble()
        val p = successes / n
        val denominator = 1.0 + z * z / n
        val center = (p + z * z / (2.0 * n)) / denominator
        val margin = z * sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n)) / denominator
        return (center - margin).coerceAtLeast(0.0) to (center + margin).coerceAtMost(1.0)
    }
}
