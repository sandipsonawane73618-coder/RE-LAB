package com.relab.core

import java.time.LocalDate

enum class PannaSide { OPEN, CLOSE }

data class PannaCandidate(
    val panna: String,
    val familySequence: String,
    val pannaType: PannaType,
    val score: Double,
    val exactHistoricalCount: Int,
    val familyHistoricalCount: Int,
    val weekdayFamilyCount: Int,
    val previousFamilyTransitionCount: Int,
    /** Count of earlier observations in this digit-family + reversal equivalence class. */
    val familyMovementHistoricalCount: Int = 0,
    val familyMovementClass: String = ""
)

data class JodiCandidate(
    val jodi: String,
    val score: Double,
    val supportingPannaPairs: Int
)

data class CandidateRanking(
    val market: Market,
    val sessionId: String,
    val side: PannaSide,
    val targetDate: LocalDate,
    val trainingRecords: Int,
    val minimumHistoryRequired: Int,
    val abstained: Boolean,
    val candidates: List<PannaCandidate>,
    val note: String
)

/**
 * Transparent, descriptive candidate ranking. Input is source observations, not raw
 * ChartRecords, so reconciliation is enforced here. No future rows or unverified
 * single-source observations enter the ranking.
 */
object CandidateRankingEngine {
    /**
     * Ranks against one explicitly selected canonical chart publisher.
     * Single-publisher rows remain unverified provenance-wise; this method
     * does not relabel them as consensus or merge other publishers.
     */
    fun rankCanonical(
        observations: List<SourceObservation>, market: Market, sessionId: String,
        targetDate: LocalDate, side: PannaSide, limit: Int = 10, minimumHistory: Int = 5
    ): CandidateRanking {
        val canonicalRows = observations.filter { it.market == market && it.sessionId == sessionId }
        require(canonicalRows.map { it.sourceId }.distinct().size <= 1) {
            "Canonical ranking accepts exactly one sourceId per market/session"
        }
        val result = rank(canonicalRows, market, sessionId, targetDate, side, limit, minimumHistory, allowSingleSourceExploration = true)
        val publisher = canonicalRows.firstOrNull()?.sourceId ?: "unknown"
        return result.copy(note = if (result.abstained) result.note else
            "Ranked from prior valid rows of one canonical publisher ($publisher). Single-source provenance is unverified; score is a relative evidence index, not probability or a guarantee.")
    }

    fun rank(
        observations: List<SourceObservation>,
        market: Market,
        sessionId: String,
        targetDate: LocalDate,
        side: PannaSide,
        limit: Int = 10,
        minimumHistory: Int = 5,
        allowSingleSourceExploration: Boolean = false,
        prebuiltRecords: List<ChartRecord>? = null
    ): CandidateRanking {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(limit in 1..100) { "Candidate limit must be between 1 and 100" }
        require(minimumHistory >= 1) { "Minimum history must be at least 1" }

        val sourceRecords = prebuiltRecords ?: if (allowSingleSourceExploration) {
            ReconciledChartBuilder.buildExploratoryRecords(observations)
        } else {
            ReconciledChartBuilder.build(observations).records
        }
        val reconciled = sourceRecords
            .filter { it.market == market && it.sessionId == sessionId && it.date.isBefore(targetDate) }
            .sortedBy { it.date }
        require(reconciled.map { it.date }.distinct().size == reconciled.size) {
            "Duplicate dates must be reconciled before candidate ranking"
        }

        if (reconciled.size < minimumHistory) {
            return CandidateRanking(
                market, sessionId, side, targetDate, reconciled.size, minimumHistory,
                true, emptyList(),
                if (allowSingleSourceExploration) "Insufficient history: need $minimumHistory earlier valid published records; found ${reconciled.size}." else "Insufficient verified history: need $minimumHistory multi-source consensus records; found ${reconciled.size}."
            )
        }

        val pannaOf: (ChartRecord) -> String = if (side == PannaSide.OPEN) ({ it.openPanna }) else ({ it.closePanna })
        val history = reconciled.map(pannaOf)
        val families = history.map(::familySequence)
        val weekdayFamilies = reconciled.indices
            .filter { reconciled[it].date.dayOfWeek == targetDate.dayOfWeek }
            .map { families[it] }
        val previousState = families.last()

        val exactCounts = history.groupingBy { it }.eachCount()
        val familyMovementClasses = history.map(FamilyMovementMath::key)
        val familyMovementCounts = familyMovementClasses.groupingBy { it }.eachCount()
        val familyCounts = families.groupingBy { it }.eachCount()
        val weekdayCounts = weekdayFamilies.groupingBy { it }.eachCount()
        val transitionCounts = families.zipWithNext()
            .groupingBy { (from, to) -> "$from>$to" }.eachCount()
        val maxExact = exactCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val maxFamily = familyCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val maxWeekday = weekdayCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val maxTransition = transitionCounts.values.maxOrNull()?.coerceAtLeast(1) ?: 1

        val ranked = (0..999).map { value ->
            val panna = value.toString().padStart(3, '0')
            val family = familySequence(panna)
            val exact = exactCounts[panna] ?: 0
            val familyCount = familyCounts[family] ?: 0
            val weekday = weekdayCounts[family] ?: 0
            val transition = transitionCounts["$previousState>$family"] ?: 0
            val movementClass = FamilyMovementMath.key(panna)
            val movementCount = familyMovementCounts[movementClass] ?: 0
            // The latest chronological validation selected exact_only. Keep family,
            // weekday and transition counts visible as diagnostics, but do not let
            // unproven secondary signals override exact historical frequency.
            // Jodi is not scored separately because it is mathematically derived.
            val score = exact.toDouble() / maxExact * 100.0
            PannaCandidate(
                panna, family, ChartMath.pannaType(panna), score,
                exact, familyCount, weekday, transition, movementCount, movementClass
            )
        }.sortedWith(
            compareByDescending<PannaCandidate> { it.exactHistoricalCount }
                .thenBy { it.panna }
        ).take(limit)

        return CandidateRanking(
            market, sessionId, side, targetDate, reconciled.size, minimumHistory,
            false, ranked,
            "Ranked by exact historical Panna frequency with deterministic lexical tie-break, matching the validation-selected exact_only baseline. Digit-family/reversal movement support, family, weekday and transition counts are descriptive diagnostics only. Score is a relative evidence index, not probability or a guarantee."
        )
    }

    fun deriveJodiCandidates(
        openCandidates: List<PannaCandidate>,
        closeCandidates: List<PannaCandidate>,
        limit: Int = 10
    ): List<JodiCandidate> {
        require(limit in 1..100) { "Candidate limit must be between 1 and 100" }
        val scores = mutableMapOf<String, Double>()
        val pairs = mutableMapOf<String, Int>()
        openCandidates.forEach { open ->
            closeCandidates.forEach { close ->
                val openAnk = open.panna.sumOf { it.digitToInt() } % 10
                val closeAnk = close.panna.sumOf { it.digitToInt() } % 10
                val jodi = "$openAnk$closeAnk"
                scores[jodi] = (scores[jodi] ?: 0.0) + open.score * close.score / 100.0
                pairs[jodi] = (pairs[jodi] ?: 0) + 1
            }
        }
        return scores.map { (jodi, score) ->
            JodiCandidate(jodi, score, pairs[jodi] ?: 0)
        }.sortedWith(compareByDescending<JodiCandidate> { it.score }.thenBy { it.jodi })
            .take(limit)
    }

    private fun familySequence(panna: String): String =
        panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
}
