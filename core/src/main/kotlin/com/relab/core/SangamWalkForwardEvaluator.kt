package com.relab.core

import java.time.LocalDate

data class SangamWalkForwardReport(
    val market: Market,
    val sessionId: String,
    val requestedTargets: Int,
    val evaluatedTargets: Int,
    val abstainedTargets: Int,
    val exactPairTop10Hits: Int,
    val exactPairTop100Hits: Int,
    val openFamilyTop10Hits: Int,
    val closeFamilyTop10Hits: Int,
    val jodiPairTop100Hits: Int
) {
    fun rate(hits: Int): Double? =
        if (evaluatedTargets == 0) null else hits.toDouble() / evaluatedTargets
}

/**
 * One-step-ahead Sangam validation. Each target is ranked using strictly earlier
 * rows only; the held-out open/close result is scored before it enters later history.
 * Exact pair, family-sequence and Jodi diagnostics remain separate.
 */
object SangamWalkForwardEvaluator {
    fun evaluate(
        records: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        minimumHistory: Int = 30
    ): List<SangamWalkForwardReport> {
        require(minimumHistory >= 1) { "minimumHistory must be positive" }
        val eligible = records.filter { it.date.isBefore(targetDateExclusive) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
        eligible.groupBy { Triple(it.market, it.sessionId, it.date) }.forEach { (key, rows) ->
            require(rows.size == 1) {
                "Duplicate rows for ${key.first}/${key.second} on ${key.third} must be reconciled before Sangam walk-forward evaluation"
            }
        }

        return eligible.groupBy { it.market to it.sessionId }
            .toSortedMap(compareBy<Pair<Market, String>> { it.first.name }.thenBy { it.second })
            .map { (identity, unsorted) ->
                val rows = unsorted.sortedBy { it.date }
                var evaluated = 0
                var abstained = 0
                var exactTop10 = 0
                var exactTop100 = 0
                var openFamilyTop10 = 0
                var closeFamilyTop10 = 0
                var jodiTop100 = 0

                rows.forEachIndexed { index, target ->
                    val prior = rows.take(index)
                    if (prior.size < minimumHistory) {
                        abstained++
                    } else {
                        val open = ReverseEngineeringModel.rank(
                            prior, identity.first, identity.second, target.date,
                            ReverseEngineeringSide.OPEN, limit = 100, minimumHistory = minimumHistory
                        )
                        val close = ReverseEngineeringModel.rank(
                            prior, identity.first, identity.second, target.date,
                            ReverseEngineeringSide.CLOSE, limit = 100, minimumHistory = minimumHistory
                        )
                        if (open.abstained || close.abstained) {
                            abstained++
                        } else {
                            val audit = SangamOutcomeAuditor.analyze(
                                target.openPanna, target.closePanna,
                                open.candidates, close.candidates, pairLimit = 100
                            )
                            evaluated++
                            if (audit.exactPairRank != null && audit.exactPairRank <= 10) exactTop10++
                            if (audit.exactPairRank != null && audit.exactPairRank <= 100) exactTop100++
                            val actualOpenFamily = target.openPanna.map {
                                ChartMath.family(it.digitToInt()).toString()
                            }.joinToString("")
                            val actualCloseFamily = target.closePanna.map {
                                ChartMath.family(it.digitToInt()).toString()
                            }.joinToString("")
                            if (open.candidates.take(10).any { it.familySequence == actualOpenFamily }) openFamilyTop10++
                            if (close.candidates.take(10).any { it.familySequence == actualCloseFamily }) closeFamilyTop10++
                            if (audit.jodiMatchCount > 0) jodiTop100++
                        }
                    }
                }
                SangamWalkForwardReport(
                    identity.first, identity.second, rows.size, evaluated, abstained,
                    exactTop10, exactTop100, openFamilyTop10, closeFamilyTop10, jodiTop100
                )
            }
    }
}
