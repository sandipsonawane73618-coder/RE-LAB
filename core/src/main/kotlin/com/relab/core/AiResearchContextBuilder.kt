package com.relab.core

import java.time.LocalDate

/**
 * Builds a balanced, bounded evidence pack for external AI review.
 * Raw observations and exploratory derived records are kept explicitly separate.
 * Only rows strictly before targetDateExclusive are included.
 */
object AiResearchContextBuilder {
    fun build(
        observations: List<SourceObservation>,
        exploratoryRecords: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        maxRecordsPerMarketSession: Int = 6,
        maxObservationsPerMarketSession: Int = 6,
        maxChars: Int = 20_000,
        scannerRankings: List<ReverseEngineeringRanking> = emptyList()
    ): String {
        require(maxRecordsPerMarketSession >= 0)
        require(maxObservationsPerMarketSession >= 0)
        require(maxChars >= 1)
        val eligibleObservations = observations.filter {
            it.market in ReverseEngineeringModel.enabledMarkets && it.date.isBefore(targetDateExclusive)
        }
        val eligibleRecords = exploratoryRecords.filter {
            it.market in ReverseEngineeringModel.enabledMarkets && it.date.isBefore(targetDateExclusive)
        }
        val observationGroups = eligibleObservations.groupBy { it.market to it.sessionId }
        val recordGroups = eligibleRecords.groupBy { it.market to it.sessionId }
        val eligibleRankings = scannerRankings.filter { it.market in ReverseEngineeringModel.enabledMarkets && it.targetDate == targetDateExclusive }.groupBy { it.market to it.sessionId }
        val output = StringBuilder()
        var currentLimit = maxChars
        fun line(value: String) {
            val remaining = currentLimit - output.length
            if (remaining <= 0) return
            if (value.length + 1 <= remaining) {
                output.append(value).append('\n')
            } else {
                output.append(value.take((remaining - 1).coerceAtLeast(0))).append('\n')
            }
        }
        line("RE-LAB ACTUAL CHART EVIDENCE")
        line("Target date exclusive: $targetDateExclusive")
        line("Scope: all nine enabled markets; rows on/after target date excluded.")
        line("IMPORTANT: raw source observations below are not independent confirmations. Derived records are exploratory and may include single-source/conflicting groups; inspect provenance before treating as truth.")
        line("Eligible source observations: ${eligibleObservations.size}; eligible exploratory records: ${eligibleRecords.size}.")
        line("SCANNER RANKINGS: relative evidence indices only; NOT calibrated probabilities. Rankings use history strictly before target date.")
        val truncationSuffix = "\n[Evidence pack reached configured character limit; per-market character budgets preserve coverage across markets.]"
        val marketBudget = ((maxChars - output.length - truncationSuffix.length) / ReverseEngineeringModel.enabledMarkets.size).coerceAtLeast(1)
        for (market in ReverseEngineeringModel.enabledMarkets.sortedBy { it.name }) {
            val marketStart = output.length
            val marketLimit = minOf(maxChars, marketStart + marketBudget)
            currentLimit = marketLimit
            if (output.length >= marketLimit) break
            val sessions = (observationGroups.keys + recordGroups.keys + eligibleRankings.keys)
                .filter { it.first == market }
                .map { it.second }.distinct().sorted()
            line("")
            line("=== MARKET: ${market.name} ===")
            if (sessions.isEmpty()) {
                line("No eligible imported rows for this market.")
                continue
            }
            for (session in sessions) {
                if (output.length >= marketLimit) break
                val sourceRows = observationGroups[market to session].orEmpty().sortedByDescending { it.date }
                val records = recordGroups[market to session].orEmpty().sortedByDescending { it.date }
                line("SESSION: $session | source observations=${sourceRows.size} | exploratory records=${records.size}")
                val statusCounts = sourceRows.groupingBy { it.status.name }.eachCount().toSortedMap()
                line("Source status counts: " + if (statusCounts.isEmpty()) "none" else statusCounts.entries.joinToString(", ") { "${it.key}=${it.value}" })
                line("CHART RECORDS (date | open panna | jodi | close panna | source id):")
                records.take(maxRecordsPerMarketSession).sortedBy { it.date }.forEach {
                    line("${it.date} | ${it.openPanna} | ${it.jodi} | ${it.closePanna} | ${it.sourceId} | EVIDENCE_REF=[${market.name}/$session/${it.date}/${it.sourceId}]")
                }
                line("RAW SOURCE OBSERVATIONS (date | source id | status | raw text | normalized result | retrieved at):")
                sourceRows.take(maxObservationsPerMarketSession).sortedBy { it.date }.forEach {
                    val raw = it.rawText.replace('\n', ' ').replace('\r', ' ').take(100)
                    line("${it.date} | ${it.sourceId} | ${it.status.name} | $raw | ${it.normalizedResult ?: "—"} | ${it.retrievedAt} | EVIDENCE_REF=[${market.name}/$session/${it.date}/${it.sourceId}]")
                }

                eligibleRankings[market to session].orEmpty().sortedBy { it.side.name }.forEach { ranking ->
                    line("SCANNER ${ranking.side} | target=${ranking.targetDate} | training rows=${ranking.trainingRecords} | min history=${ranking.minimumHistoryRequired} | abstained=${ranking.abstained} | SCANNER_REF=[SCANNER/${market.name}/$session/${ranking.side}/${ranking.targetDate}]")
                    line("Active evidence layers: ${ranking.activeEvidenceLayers.joinToString(", ").ifBlank { "none" }}")
                    line("Scanner note: ${ranking.note}")
                    if (ranking.abstained) {
                        line("No candidates: model abstained rather than ranking insufficient history.")
                    } else {
                        line("TOP PANNA CANDIDATES (panna | family | relative index | exact | family | ank | position | type | reverse | weekday | transition | open-close | cross-market):")
                        ranking.candidates.take(3).forEach { candidate ->
                            fun metric(value: Double?): String = value?.let { "%.4f".format(java.util.Locale.ROOT, it) } ?: "n/a"
                            line("${candidate.panna} | ${candidate.familySequence} | ${metric(candidate.score)} | ${metric(candidate.exactPannaEvidence)} | ${metric(candidate.familyEvidence)} | ${metric(candidate.ankEvidence)} | ${metric(candidate.positionEvidence)} | ${metric(candidate.pannaTypeEvidence)} | ${metric(candidate.reversePannaEvidence)} | ${metric(candidate.weekdayEvidence)} | ${metric(candidate.previousTransitionEvidence)} | ${metric(candidate.openCloseConditionalEvidence)} | ${metric(candidate.crossMarketConditionalEvidence)}")
                        }
                        line("TOP FAMILY SEQUENCES (family | relative index | historical count | weekday | previous transition | open-close | cross-market):")
                        ranking.familyCandidates.take(3).forEach { candidate ->
                            line("${candidate.familySequence} | ${"%.3f".format(java.util.Locale.ROOT, candidate.score)} | ${candidate.historicalCount} | ${candidate.weekdayCount ?: "n/a"} | ${candidate.previousTransitionCount ?: "n/a"} | ${candidate.openCloseConditionalCount ?: "n/a"} | ${candidate.crossMarketConditionalCount ?: "n/a"}")
                        }
                    }
                }
            }
        }
        if (output.length >= maxChars - truncationSuffix.length) {
            return output.substring(0, (maxChars - truncationSuffix.length).coerceAtLeast(0)) + truncationSuffix
        }
        return output.toString().trimEnd()
    }
}
