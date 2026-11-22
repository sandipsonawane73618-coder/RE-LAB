package com.relab.core

import java.time.LocalDate

/**
 * Chronological audit for Panna and both directional Half-Sangam forms.
 *
 * Half-Sangam conventions:
 * - OPEN_PANNA_CLOSE_ANK: open-side Panna paired with close-side Ank.
 * - OPEN_ANK_CLOSE_PANNA: open-side Ank paired with close-side Panna.
 *
 * Candidate scores are relative ranking indices, never calibrated probabilities.
 * Each target is evaluated using strictly earlier rows from the same market/session.
 */
enum class HalfSangamDirection {
    OPEN_PANNA_CLOSE_ANK,
    OPEN_ANK_CLOSE_PANNA
}

data class ExactRankDiagnostics(
    val evaluatedTargets: Int,
    val presentInCandidatePool: Int,
    val absentFromCandidatePool: Int,
    val rank1: Int,
    val rank2To5: Int,
    val rank6To10: Int,
    val rank11To25: Int,
    val rank26To50: Int,
    val rank51To100: Int,
    val rankAbove100: Int,
    val reciprocalRankSum: Double
) {
    fun candidatePoolCoverage(): Double? =
        if (evaluatedTargets == 0) null else presentInCandidatePool.toDouble() / evaluatedTargets

    fun meanReciprocalRank(): Double? =
        if (evaluatedTargets == 0) null else reciprocalRankSum / evaluatedTargets
}

private fun exactRankDiagnostics(ranks: List<Int?>): ExactRankDiagnostics {
    val present = ranks.filterNotNull()
    return ExactRankDiagnostics(
        evaluatedTargets = ranks.size,
        presentInCandidatePool = present.size,
        absentFromCandidatePool = ranks.size - present.size,
        rank1 = present.count { it == 1 },
        rank2To5 = present.count { it in 2..5 },
        rank6To10 = present.count { it in 6..10 },
        rank11To25 = present.count { it in 11..25 },
        rank26To50 = present.count { it in 26..50 },
        rank51To100 = present.count { it in 51..100 },
        rankAbove100 = present.count { it > 100 },
        reciprocalRankSum = present.sumOf { 1.0 / it }
    )
}

data class PannaSideWalkForwardMetrics(
    val requestedTargets: Int,
    val evaluatedTargets: Int,
    val abstainedTargets: Int,
    val exactTop10Hits: Int,
    val exactTop100Hits: Int,
    val familyTop10Hits: Int,
    val familyTop100Hits: Int,
    val positionalNearTop10Hits: Int,
    val positionalNearTop100Hits: Int,
    val exactRankDiagnostics: ExactRankDiagnostics
) {
    fun exactTop10Rate(): Double? = if (evaluatedTargets == 0) null else exactTop10Hits.toDouble() / evaluatedTargets
    fun exactTop100Rate(): Double? = if (evaluatedTargets == 0) null else exactTop100Hits.toDouble() / evaluatedTargets
}

data class HalfSangamWalkForwardMetrics(
    val requestedTargets: Int,
    val evaluatedTargets: Int,
    val abstainedTargets: Int,
    val exactTop10Hits: Int,
    val exactTop100Hits: Int,
    val exactRankDiagnostics: ExactRankDiagnostics
) {
    fun exactTop10Rate(): Double? = if (evaluatedTargets == 0) null else exactTop10Hits.toDouble() / evaluatedTargets
    fun exactTop100Rate(): Double? = if (evaluatedTargets == 0) null else exactTop100Hits.toDouble() / evaluatedTargets
}

data class PannaHalfSangamWalkForwardReport(
    val market: Market,
    val sessionId: String,
    val firstTargetDate: LocalDate?,
    val lastTargetDate: LocalDate?,
    val openPanna: PannaSideWalkForwardMetrics,
    val closePanna: PannaSideWalkForwardMetrics,
    val openPannaCloseAnk: HalfSangamWalkForwardMetrics,
    val openAnkClosePanna: HalfSangamWalkForwardMetrics,
    val sangam: HalfSangamWalkForwardMetrics,
    val minimumHistory: Int,
    val candidateLimit: Int
) {
    val requestedTargets: Int get() = openPanna.requestedTargets
    val evaluatedTargets: Int get() = openPanna.evaluatedTargets
    val abstainedTargets: Int get() = openPanna.abstainedTargets
}

private data class HalfSangamCandidate(
    val panna: String,
    val ank: Int,
    val score: Double
)

/**
 * Adds missing Panna and Half-Sangam walk-forward coverage without changing the
 * production candidate weights or Open Ank ranking. Results remain market/session scoped.
 */
object PannaHalfSangamWalkForwardEvaluator {
    fun evaluate(
        records: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        minimumHistory: Int = 30,
        candidateLimit: Int = 100
    ): List<PannaHalfSangamWalkForwardReport> {
        require(minimumHistory >= 1) { "minimumHistory must be positive" }
        require(candidateLimit in 10..100) { "candidateLimit must be between 10 and 100" }

        val eligible = records.filter { it.date.isBefore(targetDateExclusive) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
        eligible.groupBy { Triple(it.market, it.sessionId, it.date) }.forEach { (key, rows) ->
            require(rows.size == 1) {
                "Duplicate rows for ${key.first}/${key.second} on ${key.third} must be reconciled before Panna/Half-Sangam walk-forward evaluation"
            }
        }

        return eligible.groupBy { it.market to it.sessionId }
            .toSortedMap(compareBy<Pair<Market, String>> { it.first.name }.thenBy { it.second })
            .map { (identity, unsorted) ->
                val rows = unsorted.sortedBy { it.date }
                var openEvaluated = 0
                var closeEvaluated = 0
                var openAbstained = 0
                var closeAbstained = 0
                var openTop10 = 0
                var openTop100 = 0
                var closeTop10 = 0
                var closeTop100 = 0
                var openFamily10 = 0
                var openFamily100 = 0
                var closeFamily10 = 0
                var closeFamily100 = 0
                var openNear10 = 0
                var openNear100 = 0
                var closeNear10 = 0
                var closeNear100 = 0
                val openExactRanks = mutableListOf<Int?>()
                val closeExactRanks = mutableListOf<Int?>()
                val halfExactRanks = listOf(mutableListOf<Int?>(), mutableListOf<Int?>())
                val sangamExactRanks = mutableListOf<Int?>()
                val halfEvaluated = IntArray(2)
                val halfAbstained = IntArray(2)
                val halfTop10 = IntArray(2)
                val halfTop100 = IntArray(2)
                var sangamEvaluated = 0
                var sangamAbstained = 0
                var sangamTop10 = 0
                var sangamTop100 = 0

                rows.forEachIndexed { index, target ->
                    val prior = rows.take(index)
                    if (prior.size < minimumHistory) {
                        openAbstained++
                        closeAbstained++
                        halfAbstained.indices.forEach { halfAbstained[it]++ }
                        sangamAbstained++
                    } else {
                        val open = ReverseEngineeringModel.rank(
                            prior, identity.first, identity.second, target.date,
                            ReverseEngineeringSide.OPEN, limit = 1000,
                            minimumHistory = minimumHistory
                        )
                        val close = ReverseEngineeringModel.rank(
                            prior, identity.first, identity.second, target.date,
                            ReverseEngineeringSide.CLOSE, limit = 1000,
                            minimumHistory = minimumHistory
                        )
                        if (open.abstained || close.abstained) {
                            openAbstained++
                            closeAbstained++
                            halfAbstained.indices.forEach { halfAbstained[it]++ }
                            sangamAbstained++
                        } else {
                            openEvaluated++
                            closeEvaluated++
                            val openRank = open.candidates.indexOfFirst { it.panna == target.openPanna }
                                .takeIf { it >= 0 }?.plus(1)
                            val closeRank = close.candidates.indexOfFirst { it.panna == target.closePanna }
                                .takeIf { it >= 0 }?.plus(1)
                            openExactRanks += openRank
                            closeExactRanks += closeRank
                            if (openRank != null && openRank <= 10) openTop10++
                            if (openRank != null && openRank <= 100) openTop100++
                            if (closeRank != null && closeRank <= 10) closeTop10++
                            if (closeRank != null && closeRank <= 100) closeTop100++

                            val openFamily = family(target.openPanna)
                            val closeFamily = family(target.closePanna)
                            if (open.candidates.take(10).any { it.familySequence == openFamily }) openFamily10++
                            if (open.candidates.take(100).any { it.familySequence == openFamily }) openFamily100++
                            if (close.candidates.take(10).any { it.familySequence == closeFamily }) closeFamily10++
                            if (close.candidates.take(100).any { it.familySequence == closeFamily }) closeFamily100++
                            if (open.candidates.take(10).any { positionalMatch(it.panna, target.openPanna) }) openNear10++
                            if (open.candidates.take(100).any { positionalMatch(it.panna, target.openPanna) }) openNear100++
                            if (close.candidates.take(10).any { positionalMatch(it.panna, target.closePanna) }) closeNear10++
                            if (close.candidates.take(100).any { positionalMatch(it.panna, target.closePanna) }) closeNear100++

                            val openWindow = open.candidates.take(candidateLimit)
                            val closeWindow = close.candidates.take(candidateLimit)
                            val openToClose = rankHalfSangam(openWindow, closeWindow)
                            val closeToOpen = rankHalfSangam(closeWindow, openWindow)
                            val actualOpenAnk = ank(target.openPanna)
                            val actualCloseAnk = ank(target.closePanna)
                            val halfTargets = listOf(
                                target.openPanna to actualCloseAnk,
                                target.closePanna to actualOpenAnk
                            )
                            listOf(openToClose, closeToOpen).forEachIndexed { direction, candidates ->
                                halfEvaluated[direction]++
                                val actual = halfTargets[direction]
                                val rank = candidates.indexOfFirst {
                                    it.panna == actual.first && it.ank == actual.second
                                }.takeIf { it >= 0 }?.plus(1)
                                halfExactRanks[direction] += rank
                                if (rank != null && rank <= 10) halfTop10[direction]++
                                if (rank != null && rank <= 100) halfTop100[direction]++
                            }

                            val sangam = SangamOutcomeAuditor.analyze(
                                target.openPanna, target.closePanna,
                                openWindow, closeWindow, pairLimit = 100
                            )
                            sangamEvaluated++
                            sangamExactRanks += sangam.exactPairRank
                            if (sangam.exactPairRank != null && sangam.exactPairRank <= 10) sangamTop10++
                            if (sangam.exactPairRank != null && sangam.exactPairRank <= 100) sangamTop100++
                        }
                    }
                }

                fun pannaMetrics(
                    evaluated: Int, abstained: Int, top10: Int, top100: Int,
                    family10: Int, family100: Int, near10: Int, near100: Int, ranks: List<Int?>
                ) = PannaSideWalkForwardMetrics(
                    rows.size, evaluated, abstained, top10, top100,
                    family10, family100, near10, near100, exactRankDiagnostics(ranks)
                )
                fun halfMetrics(direction: Int) = HalfSangamWalkForwardMetrics(
                    rows.size, halfEvaluated[direction], halfAbstained[direction],
                    halfTop10[direction], halfTop100[direction], exactRankDiagnostics(halfExactRanks[direction])
                )
                PannaHalfSangamWalkForwardReport(
                    market = identity.first,
                    sessionId = identity.second,
                    firstTargetDate = rows.firstOrNull()?.date,
                    lastTargetDate = rows.lastOrNull()?.date,
                    openPanna = pannaMetrics(openEvaluated, openAbstained, openTop10, openTop100, openFamily10, openFamily100, openNear10, openNear100, openExactRanks),
                    closePanna = pannaMetrics(closeEvaluated, closeAbstained, closeTop10, closeTop100, closeFamily10, closeFamily100, closeNear10, closeNear100, closeExactRanks),
                    openPannaCloseAnk = halfMetrics(0),
                    openAnkClosePanna = halfMetrics(1),
                    sangam = HalfSangamWalkForwardMetrics(rows.size, sangamEvaluated, sangamAbstained, sangamTop10, sangamTop100, exactRankDiagnostics(sangamExactRanks)),
                    minimumHistory = minimumHistory,
                    candidateLimit = candidateLimit
                )
            }
    }

    private fun rankHalfSangam(
        pannaCandidates: List<ReverseEngineeringCandidate>,
        oppositeSideCandidates: List<ReverseEngineeringCandidate>
    ): List<HalfSangamCandidate> {
        val ankScores = oppositeSideCandidates.groupBy { ank(it.panna) }
            .mapValues { (_, candidates) -> candidates.sumOf { it.score } }
        val maxAnkScore = ankScores.values.maxOrNull()?.coerceAtLeast(1e-12) ?: 1.0
        return pannaCandidates.flatMap { panna ->
            ankScores.map { (ank, score) ->
                HalfSangamCandidate(panna.panna, ank, panna.score * score / maxAnkScore)
            }
        }.sortedWith(
            compareByDescending<HalfSangamCandidate> { it.score }
                .thenBy { it.panna }
                .thenBy { it.ank }
        )
    }

    private fun positionalMatch(candidate: String, actual: String): Boolean =
        (0..2).count { candidate[it] == actual[it] } >= 2

    private fun ank(panna: String): Int = panna.sumOf { it.digitToInt() } % 10

    private fun family(panna: String): String =
        panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
}
