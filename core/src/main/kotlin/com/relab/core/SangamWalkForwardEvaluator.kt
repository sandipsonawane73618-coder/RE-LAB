package com.relab.core

import java.time.LocalDate

/** Chronological thirds within one market/session history; descriptive, not predictive confidence. */
data class WalkForwardPeriodReport(
    val period: Int,
    val firstTargetDate: LocalDate?,
    val lastTargetDate: LocalDate?,
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
    val jodiPairTop100Hits: Int,
    val chronologicalPeriods: List<WalkForwardPeriodReport> = emptyList()
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
                val periodEvaluated = IntArray(3)
                val periodAbstained = IntArray(3)
                val periodExactTop10 = IntArray(3)
                val periodExactTop100 = IntArray(3)
                val periodOpenFamily = IntArray(3)
                val periodCloseFamily = IntArray(3)
                val periodJodi = IntArray(3)

                rows.forEachIndexed { index, target ->
                    val period = ((index.toLong() * 3L) / rows.size).toInt().coerceAtMost(2)
                    val prior = rows.take(index)
                    if (prior.size < minimumHistory) {
                        abstained++
                        periodAbstained[period]++
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
                            periodAbstained[period]++
                        } else {
                            val audit = SangamOutcomeAuditor.analyze(
                                target.openPanna, target.closePanna,
                                open.candidates, close.candidates, pairLimit = 100
                            )
                            evaluated++
                            periodEvaluated[period]++
                            if (audit.exactPairRank != null && audit.exactPairRank <= 10) {
                                exactTop10++
                                periodExactTop10[period]++
                            }
                            if (audit.exactPairRank != null && audit.exactPairRank <= 100) {
                                exactTop100++
                                periodExactTop100[period]++
                            }
                            val actualOpenFamily = target.openPanna.map {
                                ChartMath.family(it.digitToInt()).toString()
                            }.joinToString("")
                            val actualCloseFamily = target.closePanna.map {
                                ChartMath.family(it.digitToInt()).toString()
                            }.joinToString("")
                            if (open.candidates.take(10).any { it.familySequence == actualOpenFamily }) {
                                openFamilyTop10++
                                periodOpenFamily[period]++
                            }
                            if (close.candidates.take(10).any { it.familySequence == actualCloseFamily }) {
                                closeFamilyTop10++
                                periodCloseFamily[period]++
                            }
                            if (audit.jodiMatchCount > 0) {
                                jodiTop100++
                                periodJodi[period]++
                            }
                        }
                    }
                }
                val periods = (0..2).map { period ->
                    val start = (period.toLong() * rows.size / 3L).toInt()
                    val endExclusive = ((period + 1L) * rows.size / 3L).toInt().coerceAtMost(rows.size)
                    val periodRows = rows.subList(start, endExclusive)
                    WalkForwardPeriodReport(
                        period = period + 1,
                        firstTargetDate = periodRows.firstOrNull()?.date,
                        lastTargetDate = periodRows.lastOrNull()?.date,
                        requestedTargets = periodRows.size,
                        evaluatedTargets = periodEvaluated[period],
                        abstainedTargets = periodAbstained[period],
                        exactPairTop10Hits = periodExactTop10[period],
                        exactPairTop100Hits = periodExactTop100[period],
                        openFamilyTop10Hits = periodOpenFamily[period],
                        closeFamilyTop10Hits = periodCloseFamily[period],
                        jodiPairTop100Hits = periodJodi[period]
                    )
                }
                SangamWalkForwardReport(
                    identity.first, identity.second, rows.size, evaluated, abstained,
                    exactTop10, exactTop100, openFamilyTop10, closeFamilyTop10, jodiTop100,
                    chronologicalPeriods = periods
                )
            }
    }
}
