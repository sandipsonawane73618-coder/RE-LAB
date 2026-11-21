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
    val patternOnlyHits: Int,
    val baselineOnlyHits: Int,
    val pairedExactPValue: Double?,
    val hitRate: Double?,
    val baselineHitRate: Double?,
    val lift: Double?,
    val wilsonLowerBound: Double?,
    val wilsonUpperBound: Double?,
    val baselineWilsonLowerBound: Double?,
    val baselineWilsonUpperBound: Double?,
    val status: ResearchVerificationStatus,
    val rejectionReason: String? = null
)

private fun ProposedPatternBacktest.diagnosticCategory(minimumEvaluations: Int): String = when {
    rejectionReason != null -> "INVALID_PROPOSAL"
    status == ResearchVerificationStatus.REJECTED -> "STATISTICAL_REJECTION"
    status == ResearchVerificationStatus.UNCERTAIN && evaluatedOccurrences < minimumEvaluations -> "LOW_SAMPLE_UNCERTAIN"
    status == ResearchVerificationStatus.UNCERTAIN -> "INCONCLUSIVE"
    else -> "HISTORICALLY_VERIFIED"
}

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
        var patternOnlyHits = 0
        var baselineOnlyHits = 0
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
                        val patternCorrect = prediction == actual
                        val baselineCorrect = baselinePrediction == actual
                        if (patternCorrect) hits++
                        if (baselineCorrect) baselineHits++
                        if (patternCorrect && !baselineCorrect) patternOnlyHits++
                        if (!patternCorrect && baselineCorrect) baselineOnlyHits++
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
        val status = if (evaluated < minimumEvaluations) {
            ResearchVerificationStatus.UNCERTAIN
        } else {
            val patternInterval = requireNotNull(bounds)
            val baselineInterval = requireNotNull(baselineBounds)
            when {
                patternInterval.first > baselineInterval.second + minimumLift -> ResearchVerificationStatus.VERIFIED
                patternInterval.second < baselineInterval.first -> ResearchVerificationStatus.REJECTED
                else -> ResearchVerificationStatus.UNCERTAIN
            }
        }
        return ProposedPatternBacktest(
            proposal = proposal,
            supportOccurrences = support,
            evaluatedOccurrences = evaluated,
            abstainedOccurrences = support - evaluated,
            hits = hits,
            misses = misses,
            baselineHits = baselineHits,
            patternOnlyHits = patternOnlyHits,
            baselineOnlyHits = baselineOnlyHits,
            pairedExactPValue = pairedExactBinomialPValue(patternOnlyHits, baselineOnlyHits),
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

    /** Two-sided exact binomial test on discordant paired predictions. */
    internal fun pairedExactBinomialPValue(patternWins: Int, baselineWins: Int): Double? {
        require(patternWins >= 0 && baselineWins >= 0) { "Paired win counts must be non-negative" }
        val discordant = patternWins.toLong() + baselineWins.toLong()
        if (discordant == 0L) return null
        val smaller = minOf(patternWins, baselineWins).toLong()
        // Sum the lower binomial tail in log space: O(1) memory and no
        // factorial/combination overflow for large discordant sample counts.
        val logTwo = kotlin.math.ln(2.0)
        var logChoose = 0.0
        var logTailNumerator = Double.NEGATIVE_INFINITY
        for (k in 0L..smaller) {
            if (k > 0L) {
                logChoose += kotlin.math.ln((discordant - k + 1).toDouble()) - kotlin.math.ln(k.toDouble())
            }
            val term = logChoose
            logTailNumerator = if (logTailNumerator == Double.NEGATIVE_INFINITY) term
            else {
                val high = maxOf(logTailNumerator, term)
                high + kotlin.math.ln(kotlin.math.exp(logTailNumerator - high) + kotlin.math.exp(term - high))
            }
        }
        val logTail = logTailNumerator - discordant.toDouble() * logTwo
        return (2.0 * kotlin.math.exp(logTail)).coerceIn(0.0, 1.0)
    }

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
    val datasetRecordsProvided: Int = 0,
    val validConsensusRowsBeforeCutoff: Int = 0,
    val excludedOnOrAfterCutoff: Int = 0,
    val invalidRowsBeforeCutoff: Int = 0,
    val coveredMarketSessions: Int = 0,
    val minimumEvaluationsRequired: Int = 20,
    val exactPatternEvidence: List<PatternEvidenceRecord>,
    val familyPatternEvidence: List<PatternEvidenceRecord>,
    val rankedExactPatternEvidence: List<RankedPatternEvidence>,
    val rankedFamilyPatternEvidence: List<RankedPatternEvidence>,
    val assessments: List<ProposedPatternBacktest>
) {
    fun compactSummary(): String = buildString {
        fun rate(value: Double?) = value?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "n/a"
        appendLine("RESEARCH-TO-BACKTEST REPORT")
        appendLine("Target cutoff (exclusive): $targetDateExclusive")
        appendLine("DATASET AUDIT: provided=$datasetRecordsProvided | valid consensus rows before cutoff=$validConsensusRowsBeforeCutoff | excluded on/after cutoff=$excludedOnOrAfterCutoff | invalid before cutoff=$invalidRowsBeforeCutoff | covered market/sessions=$coveredMarketSessions")
        appendLine("Scanner rankings: ${scannerRankings.size}")
        scannerRankings.take(12).forEach { ranking ->
            val candidates = ranking.candidates.take(3).joinToString("; ") { it.panna + " (" + rate(it.score) + ")" }
            appendLine("  SCANNER " + ranking.market + "/" + ranking.sessionId + " " + ranking.side +
                " | target=" + ranking.targetDate + " | training=" + ranking.trainingRecords +
                " | abstained=" + ranking.abstained + " | top=" + candidates.ifBlank { "none" })
        }
        appendLine("Exact pattern evidence rows: ${exactPatternEvidence.size}")
        appendLine("Family-movement evidence rows: " + familyPatternEvidence.size)
        appendLine("Exact evidence ranking: OUTPERFORMS=" + rankedExactPatternEvidence.count { it.assessment == PatternEvidenceAssessment.OUTPERFORMS_BASELINE } + " | BELOW=" + rankedExactPatternEvidence.count { it.assessment == PatternEvidenceAssessment.BELOW_BASELINE } + " | INCONCLUSIVE=" + rankedExactPatternEvidence.count { it.assessment == PatternEvidenceAssessment.INCONCLUSIVE } + " | INSUFFICIENT=" + rankedExactPatternEvidence.count { it.assessment == PatternEvidenceAssessment.INSUFFICIENT_EVIDENCE })
        rankedExactPatternEvidence.take(5).forEach { appendLine("  " + it.evidence.market + "/" + it.evidence.sessionId + " " + it.evidence.channel + " " + it.evidence.pattern + " | " + it.assessment + " | score=" + rate(it.rankScore) + " | n=" + it.evidence.evaluatedOccurrences) }
        appendLine("Family evidence ranking: OUTPERFORMS=" + rankedFamilyPatternEvidence.count { it.assessment == PatternEvidenceAssessment.OUTPERFORMS_BASELINE } + " | BELOW=" + rankedFamilyPatternEvidence.count { it.assessment == PatternEvidenceAssessment.BELOW_BASELINE } + " | INCONCLUSIVE=" + rankedFamilyPatternEvidence.count { it.assessment == PatternEvidenceAssessment.INCONCLUSIVE } + " | INSUFFICIENT=" + rankedFamilyPatternEvidence.count { it.assessment == PatternEvidenceAssessment.INSUFFICIENT_EVIDENCE })
        rankedFamilyPatternEvidence.take(5).forEach { appendLine("  " + it.evidence.market + "/" + it.evidence.sessionId + " " + it.evidence.channel + " " + it.evidence.pattern + " | " + it.assessment + " | score=" + rate(it.rankScore) + " | n=" + it.evidence.evaluatedOccurrences) }
        appendLine("AI proposals tested: " + assessments.size)
        if (assessments.isEmpty()) appendLine("No executable AI proposals were supplied; scanner and Pattern Evidence DB results remain available for review.")
        ResearchVerificationStatus.entries.forEach { status ->
            appendLine("$status: ${assessments.count { it.status == status }}")
        }
        appendLine("ASSESSMENT DIAGNOSTICS: invalid proposals=${assessments.count { it.rejectionReason != null }} | statistical rejections=${assessments.count { it.status == ResearchVerificationStatus.REJECTED && it.rejectionReason == null }} | low-sample uncertain=${assessments.count { it.status == ResearchVerificationStatus.UNCERTAIN && it.evaluatedOccurrences < minimumEvaluationsRequired }} | other uncertain=${assessments.count { it.status == ResearchVerificationStatus.UNCERTAIN && it.evaluatedOccurrences >= minimumEvaluationsRequired }}")
        appendLine("DATA QUALITY: invalid pre-cutoff rows=$invalidRowsBeforeCutoff | post-cutoff rows excluded=$excludedOnOrAfterCutoff")
        assessments.sortedWith(compareBy<ProposedPatternBacktest> { it.status.ordinal }
            .thenByDescending { it.wilsonLowerBound ?: -1.0 }
            .thenBy { it.proposal.id }).forEach { result ->
            val proposal = result.proposal
            val diagnostic = result.diagnosticCategory(minimumEvaluationsRequired)
            appendLine("${result.status} | ${diagnostic} | ${proposal.id} | ${proposal.market}/${proposal.sessionId} | ${proposal.channel} | ${proposal.pattern} | n=${result.evaluatedOccurrences} | hit=${rate(result.hitRate)} | baseline=${rate(result.baselineHitRate)} | Wilson-LB=${rate(result.wilsonLowerBound)} | Baseline-Wilson-UB=${rate(result.baselineWilsonUpperBound)} | pairedWins=${result.patternOnlyHits} | baselineOnlyWins=${result.baselineOnlyHits} | pairedExactP=${rate(result.pairedExactPValue)}" + (result.rejectionReason?.let { " | REASON=$it" } ?: ""))
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
        require(scannerRankings.all { it.targetDate == targetDateExclusive }) {
            "Scanner rankings must use the same exclusive target date as the backtest"
        }
        val beforeCutoff = records.filter { it.date.isBefore(targetDateExclusive) }
        val eligible = beforeCutoff.filter { ChartMath.validate(it) is ValidationResult.Valid }
        val availableSessions = eligible.map { it.market to it.sessionId }.toSet()
        require(scannerRankings.all { (it.market to it.sessionId) in availableSessions }) {
            "Scanner rankings must reference a market/session present in eligible consensus history"
        }
        require(scannerRankings.all { ranking ->
            ranking.trainingRecords <= eligible.count { it.market == ranking.market && it.sessionId == ranking.sessionId }
        }) {
            "Scanner training record count cannot exceed eligible consensus rows for its market/session"
        }
        val coveredMarketSessions = eligible.map { it.market to it.sessionId }.distinct().size
        val exactEvidence = PatternEvidenceDatabase.analyze(
            eligible, targetDateExclusive, outcomeMode = PatternOutcomeMode.EXACT
        )
        val familyEvidence = PatternEvidenceDatabase.analyze(
            eligible, targetDateExclusive, outcomeMode = PatternOutcomeMode.FAMILY_MOVEMENT
        )
        val rankedExact = PatternEvidenceRanking.rank(exactEvidence)
        val rankedFamily = PatternEvidenceRanking.rank(familyEvidence)
        val assessments = proposals.map { proposal ->
            try {
                ProposedPatternBacktester.evaluate(
                    eligible, proposal, targetDateExclusive, minimumEvaluations, minimumLift
                )
            } catch (error: IllegalArgumentException) {
                ProposedPatternBacktest(
                    proposal = proposal,
                    supportOccurrences = 0,
                    evaluatedOccurrences = 0,
                    abstainedOccurrences = 0,
                    hits = 0,
                    misses = 0,
                    baselineHits = 0,
                    patternOnlyHits = 0,
                    baselineOnlyHits = 0,
                    pairedExactPValue = null,
                    hitRate = null,
                    baselineHitRate = null,
                    lift = null,
                    wilsonLowerBound = null,
                    wilsonUpperBound = null,
                    baselineWilsonLowerBound = null,
                    baselineWilsonUpperBound = null,
                    status = ResearchVerificationStatus.REJECTED,
                    rejectionReason = error.message ?: "Invalid proposal"
                )
            }
        }.sortedWith(compareBy<ProposedPatternBacktest> { it.status.ordinal }
            .thenByDescending { it.wilsonLowerBound ?: -1.0 }
            .thenBy { it.proposal.id })
        return ResearchToBacktestReport(
            targetDateExclusive, scannerRankings.toList(),
            datasetRecordsProvided = records.size,
            validConsensusRowsBeforeCutoff = eligible.size,
            excludedOnOrAfterCutoff = records.size - beforeCutoff.size,
            invalidRowsBeforeCutoff = beforeCutoff.size - eligible.size,
            coveredMarketSessions = coveredMarketSessions,
            minimumEvaluationsRequired = minimumEvaluations,
            exactPatternEvidence = exactEvidence,
            familyPatternEvidence = familyEvidence,
            rankedExactPatternEvidence = rankedExact,
            rankedFamilyPatternEvidence = rankedFamily,
            assessments = assessments
        )
    }
}

object ResearchToBacktestCsvExporter {
    fun export(report: ResearchToBacktestReport): String {
        val header = listOf(
            "proposal_id", "source", "market", "session", "channel", "pattern", "outcome_mode",
            "support_occurrences", "evaluated_occurrences", "abstained_occurrences", "hits", "misses",
            "baseline_hits", "pattern_only_hits", "baseline_only_hits", "paired_exact_p_value", "hit_rate", "baseline_hit_rate", "lift", "wilson_lower_95",
            "wilson_upper_95", "baseline_wilson_lower_95", "baseline_wilson_upper_95", "verification_status", "diagnostic_category", "rejection_reason", "rationale"
        )
        val rows = report.assessments.map { result ->
            val p = result.proposal
            listOf(
                p.id, p.source, p.market.name, p.sessionId, p.channel.name, p.pattern, p.outcomeMode.name,
                result.supportOccurrences.toString(), result.evaluatedOccurrences.toString(),
                result.abstainedOccurrences.toString(), result.hits.toString(), result.misses.toString(),
                result.baselineHits.toString(), result.patternOnlyHits.toString(), result.baselineOnlyHits.toString(), result.pairedExactPValue.csvRate(), result.hitRate.csvRate(), result.baselineHitRate.csvRate(),
                result.lift.csvRate(), result.wilsonLowerBound.csvRate(), result.wilsonUpperBound.csvRate(),
                result.baselineWilsonLowerBound.csvRate(), result.baselineWilsonUpperBound.csvRate(),
                result.status.name, result.diagnosticCategory(report.minimumEvaluationsRequired), result.rejectionReason.orEmpty(), p.rationale
            )
        }
        return (listOf(header) + rows).joinToString("\n") { row -> row.joinToString(",") { it.csvEscape() } }
    }

    fun exportEvidence(report: ResearchToBacktestReport): String {
        val header = listOf(
            "market", "session", "channel", "outcome_mode", "pattern", "occurrences",
            "evaluated_occurrences", "hits", "misses", "hit_rate", "baseline_hit_rate",
            "lift", "rank_score", "pattern_wilson_lower_95", "pattern_wilson_upper_95",
            "baseline_wilson_lower_95", "baseline_wilson_upper_95", "assessment"
        )
        val ranked = report.rankedExactPatternEvidence.map { PatternOutcomeMode.EXACT to it } +
            report.rankedFamilyPatternEvidence.map { PatternOutcomeMode.FAMILY_MOVEMENT to it }
        val rows = ranked.map { (mode, item) ->
            val evidence = item.evidence
            listOf(
                evidence.market.name, evidence.sessionId, evidence.channel.name, mode.name,
                evidence.pattern, evidence.occurrences.toString(), evidence.evaluatedOccurrences.toString(),
                evidence.hits.toString(), evidence.misses.toString(), evidence.hitRate.csvRate(),
                evidence.baselineHitRate.csvRate(), evidence.baselineLift.csvRate(),
                item.rankScore.csvRate(), item.hitRateLower95.csvRate(), item.hitRateUpper95.csvRate(),
                item.baselineLower95.csvRate(), item.baselineUpper95.csvRate(), item.assessment.name
            )
        }
        return (listOf(header) + rows).joinToString("\n") { row -> row.joinToString(",") { it.csvEscape() } }
    }

    private fun Double?.csvRate(): String = this?.let { String.format(Locale.ROOT, "%.6f", it) } ?: ""
    private fun String.csvEscape(): String = if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + replace("\"", "\"\"") + "\""
    } else this
}
