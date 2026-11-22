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
    val openPannaCloseAnkConditioned: HalfSangamWalkForwardMetrics,
    val openAnkClosePanna: HalfSangamWalkForwardMetrics,
    val openAnkClosePannaConditioned: HalfSangamWalkForwardMetrics,
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
        candidateLimit: Int = 100,
        targetLimit: Int = Int.MAX_VALUE,
        halfSangamPannaLimit: Int = candidateLimit,
        halfSangamOppositeLimit: Int = candidateLimit,
        sangamPannaLimit: Int = candidateLimit
    ): List<PannaHalfSangamWalkForwardReport> {
        require(minimumHistory >= 1) { "minimumHistory must be positive" }
        require(candidateLimit in 10..100) { "candidateLimit must be between 10 and 100" }
        require(targetLimit >= 1) { "targetLimit must be positive" }
        require(halfSangamPannaLimit in 10..1000) { "halfSangamPannaLimit must be between 10 and 1000" }
        require(halfSangamOppositeLimit in 10..1000) { "halfSangamOppositeLimit must be between 10 and 1000" }
        require(sangamPannaLimit in 10..1000) { "sangamPannaLimit must be between 10 and 1000" }

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
                val conditionedHalfExactRanks = mutableListOf<Int?>()
                val sangamExactRanks = mutableListOf<Int?>()
                val halfEvaluated = IntArray(2)
                val halfAbstained = IntArray(2)
                val halfTop10 = IntArray(2)
                val halfTop100 = IntArray(2)
                var conditionedHalfEvaluated = 0
                var conditionedHalfAbstained = 0
                var conditionedHalfTop10 = 0
                var conditionedHalfTop100 = 0
                val conditionedReverseHalfExactRanks = mutableListOf<Int?>()
                var conditionedReverseHalfEvaluated = 0
                var conditionedReverseHalfAbstained = 0
                var conditionedReverseHalfTop10 = 0
                var conditionedReverseHalfTop100 = 0
                var sangamEvaluated = 0
                var sangamAbstained = 0
                var sangamTop10 = 0
                var sangamTop100 = 0

                val requestedTargets = minOf(rows.size, targetLimit)
                val firstTargetIndex = rows.size - requestedTargets
                rows.forEachIndexed { index, target ->
                    // Retain the complete earlier history for each selected target,
                    // but avoid expensive ranking on older rows outside the OOS window.
                    if (index >= firstTargetIndex) {
                        val prior = rows.subList(0, index)
                        if (prior.size < minimumHistory) {
                            openAbstained++
                            closeAbstained++
                            halfAbstained.indices.forEach { halfAbstained[it]++ }
                            conditionedHalfAbstained++
                            conditionedReverseHalfAbstained++
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
                        // In the OPEN_PANNA_CLOSE_ANK direction, the open result is
                        // already known before the close result. Condition only this
                        // close-side research ranking on that observed open Panna.
                        val closeGivenOpen = ReverseEngineeringModel.rank(
                            prior, identity.first, identity.second, target.date,
                            ReverseEngineeringSide.CLOSE, knownOpenPanna = target.openPanna,
                            limit = 1000, minimumHistory = minimumHistory
                        )
                        val closeGivenOpenAnk = ReverseEngineeringModel.rank(
                            prior, identity.first, identity.second, target.date,
                            ReverseEngineeringSide.CLOSE, limit = 1000,
                            minimumHistory = minimumHistory,
                            knownOpenAnk = ank(target.openPanna)
                        )
                        if (open.abstained || close.abstained || closeGivenOpen.abstained || closeGivenOpenAnk.abstained) {
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

                            val halfOpenPannaWindow = open.candidates.take(halfSangamPannaLimit)
                            val halfClosePannaWindow = close.candidates.take(halfSangamPannaLimit)
                            val halfOpenAnkEvidence = open.candidates.take(halfSangamOppositeLimit)
                            val halfCloseAnkEvidence = close.candidates.take(halfSangamOppositeLimit)
                            val actualOpenAnk = ank(target.openPanna)
                            val actualCloseAnk = ank(target.closePanna)
                            val openToCloseRank = exactHalfSangamRank(
                                halfOpenPannaWindow, halfCloseAnkEvidence,
                                target.openPanna, actualCloseAnk
                            )
                            val conditionedCloseAnkEvidence =
                                closeGivenOpen.candidates.take(halfSangamOppositeLimit)
                            val openToCloseConditionedRank = exactHalfSangamRank(
                                halfOpenPannaWindow, conditionedCloseAnkEvidence,
                                target.openPanna, actualCloseAnk
                            )
                            conditionedHalfEvaluated++
                            conditionedHalfExactRanks += openToCloseConditionedRank
                            if (openToCloseConditionedRank != null && openToCloseConditionedRank <= 10) conditionedHalfTop10++
                            if (openToCloseConditionedRank != null && openToCloseConditionedRank <= 100) conditionedHalfTop100++
                            val closeToOpenRank = exactHalfSangamRank(
                                halfClosePannaWindow, halfOpenAnkEvidence,
                                target.closePanna, actualOpenAnk
                            )
                            val conditionedClosePannaWindow =
                                closeGivenOpenAnk.candidates.take(halfSangamPannaLimit)
                            val closeToOpenConditionedRank = exactHalfSangamRank(
                                conditionedClosePannaWindow, halfOpenAnkEvidence,
                                target.closePanna, actualOpenAnk
                            )
                            conditionedReverseHalfEvaluated++
                            conditionedReverseHalfExactRanks += closeToOpenConditionedRank
                            if (closeToOpenConditionedRank != null && closeToOpenConditionedRank <= 10) conditionedReverseHalfTop10++
                            if (closeToOpenConditionedRank != null && closeToOpenConditionedRank <= 100) conditionedReverseHalfTop100++
                            listOf(openToCloseRank, closeToOpenRank).forEachIndexed { direction, rank ->
                                halfEvaluated[direction]++
                                halfExactRanks[direction] += rank
                                if (rank != null && rank <= 10) halfTop10[direction]++
                                if (rank != null && rank <= 100) halfTop100[direction]++
                            }

                            val sangamOpenWindow = open.candidates.take(sangamPannaLimit)
                            val sangamCloseWindow = close.candidates.take(sangamPannaLimit)
                            val sangamRank = SangamOutcomeAuditor.exactPairRank(
                                target.openPanna, target.closePanna,
                                sangamOpenWindow, sangamCloseWindow
                            )
                            sangamEvaluated++
                            sangamExactRanks += sangamRank
                            if (sangamRank != null && sangamRank <= 10) sangamTop10++
                            if (sangamRank != null && sangamRank <= 100) sangamTop100++
                        }
                    }
                    }
                }

                fun pannaMetrics(
                    evaluated: Int, abstained: Int, top10: Int, top100: Int,
                    family10: Int, family100: Int, near10: Int, near100: Int, ranks: List<Int?>
                ) = PannaSideWalkForwardMetrics(
                    requestedTargets, evaluated, abstained, top10, top100,
                    family10, family100, near10, near100, exactRankDiagnostics(ranks)
                )
                fun halfMetrics(direction: Int) = HalfSangamWalkForwardMetrics(
                    requestedTargets, halfEvaluated[direction], halfAbstained[direction],
                    halfTop10[direction], halfTop100[direction], exactRankDiagnostics(halfExactRanks[direction])
                )
                PannaHalfSangamWalkForwardReport(
                    market = identity.first,
                    sessionId = identity.second,
                    firstTargetDate = rows.getOrNull(firstTargetIndex)?.date,
                    lastTargetDate = rows.lastOrNull()?.date,
                    openPanna = pannaMetrics(openEvaluated, openAbstained, openTop10, openTop100, openFamily10, openFamily100, openNear10, openNear100, openExactRanks),
                    closePanna = pannaMetrics(closeEvaluated, closeAbstained, closeTop10, closeTop100, closeFamily10, closeFamily100, closeNear10, closeNear100, closeExactRanks),
                    openPannaCloseAnk = halfMetrics(0),
                    openPannaCloseAnkConditioned = HalfSangamWalkForwardMetrics(
                        requestedTargets, conditionedHalfEvaluated, conditionedHalfAbstained,
                        conditionedHalfTop10, conditionedHalfTop100,
                        exactRankDiagnostics(conditionedHalfExactRanks)
                    ),
                    openAnkClosePanna = halfMetrics(1),
                    openAnkClosePannaConditioned = HalfSangamWalkForwardMetrics(
                        requestedTargets, conditionedReverseHalfEvaluated, conditionedReverseHalfAbstained,
                        conditionedReverseHalfTop10, conditionedReverseHalfTop100,
                        exactRankDiagnostics(conditionedReverseHalfExactRanks)
                    ),
                    sangam = HalfSangamWalkForwardMetrics(requestedTargets, sangamEvaluated, sangamAbstained, sangamTop10, sangamTop100, exactRankDiagnostics(sangamExactRanks)),
                    minimumHistory = minimumHistory,
                    candidateLimit = candidateLimit
                )
            }
    }

    /**
     * Exact rank for (Panna, opposite-side Ank) using the same score and tie
     * ordering as the materialized candidate list, but without sorting up to
     * 10,000 pair objects for every historical target.
     */
    internal fun exactHalfSangamRank(
        pannaCandidates: List<ReverseEngineeringCandidate>,
        oppositeSideCandidates: List<ReverseEngineeringCandidate>,
        actualPanna: String,
        actualAnk: Int
    ): Int? {
        val actualPannaCandidate = pannaCandidates.firstOrNull { it.panna == actualPanna } ?: return null
        val ankScores = oppositeSideCandidates.groupBy { ank(it.panna) }
            .mapValues { (_, candidates) -> candidates.sumOf { it.score } }
        val actualAnkScore = ankScores[actualAnk] ?: return null
        val maxAnkScore = ankScores.values.maxOrNull()?.coerceAtLeast(1e-12) ?: 1.0
        val actualScore = actualPannaCandidate.score * actualAnkScore / maxAnkScore
        val sortedAnks = ankScores.entries.sortedWith(
            compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key }
        )
        var rank = 1L
        pannaCandidates.forEach { panna ->
            var low = 0
            var high = sortedAnks.size
            while (low < high) {
                val middle = (low + high) ushr 1
                val score = panna.score * sortedAnks[middle].value / maxAnkScore
                if (score > actualScore) low = middle + 1 else high = middle
            }
            rank += low.toLong()
            var index = low
            while (index < sortedAnks.size) {
                val ankValue = sortedAnks[index].key
                val score = panna.score * sortedAnks[index].value / maxAnkScore
                if (score != actualScore) break
                if (panna.panna < actualPanna ||
                    (panna.panna == actualPanna && ankValue < actualAnk)
                ) rank++
                index++
            }
        }
        return rank.toInt()
    }

    private fun positionalMatch(candidate: String, actual: String): Boolean =
        (0..2).count { candidate[it] == actual[it] } >= 2

    private fun ank(panna: String): Int = panna.sumOf { it.digitToInt() } % 10

    private fun family(panna: String): String =
        panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
}
