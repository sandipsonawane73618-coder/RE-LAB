package com.relab.core

import java.time.LocalDate

enum class ReverseEngineeringSide { OPEN, CLOSE }

data class ReverseEngineeringJodiCandidate(val jodi: String, val score: Double, val supportingPannaPairs: Int)

data class ReverseEngineeringCandidate(
    val panna: String,
    val familySequence: String,
    val pannaType: PannaType,
    val ank: Int,
    val score: Double,
    val exactPannaEvidence: Double,
    val familyEvidence: Double,
    val ankEvidence: Double,
    val positionEvidence: Double,
    val pannaTypeEvidence: Double,
    val reversePannaEvidence: Double,
    val weekdayEvidence: Double?,
    val previousTransitionEvidence: Double?,
    val openCloseConditionalEvidence: Double?,
    val crossMarketConditionalEvidence: Double?
)

data class ReverseEngineeringFamilyCandidate(
    val familySequence: String,
    val score: Double,
    val historicalCount: Int,
    val weekdayCount: Int?,
    val previousTransitionCount: Int?,
    val openCloseConditionalCount: Int?,
    val crossMarketConditionalCount: Int?
)

data class ReverseEngineeringRanking(
    val market: Market,
    val sessionId: String,
    val side: ReverseEngineeringSide,
    val targetDate: LocalDate,
    val trainingRecords: Int,
    val minimumHistoryRequired: Int,
    val abstained: Boolean,
    val candidates: List<ReverseEngineeringCandidate>,
    val familyCandidates: List<ReverseEngineeringFamilyCandidate>,
    val activeEvidenceLayers: List<String>,
    val note: String
)

data class ReverseEngineeringBacktestReport(
    val market: Market,
    val sessionId: String,
    val side: ReverseEngineeringSide,
    val requestedTargets: Int,
    val evaluatedTargets: Int,
    val abstainedTargets: Int,
    val top1ExactHits: Int,
    val top10ExactHits: Int,
    val top1AnkHits: Int,
    val top10AnkHits: Int,
    val top1FamilyHits: Int,
    val top10FamilyHits: Int,
    val priorModeFamilyHits: Int
) {
    val top1ExactRate: Double get() = rate(top1ExactHits)
    val top10ExactRate: Double get() = rate(top10ExactHits)
    val top1AnkRate: Double get() = rate(top1AnkHits)
    val top10AnkRate: Double get() = rate(top10AnkHits)
    val top1FamilyRate: Double get() = rate(top1FamilyHits)
    val top10FamilyRate: Double get() = rate(top10FamilyHits)
    val priorModeFamilyRate: Double get() = rate(priorModeFamilyHits)
    private fun rate(value: Int): Double =
        if (evaluatedTargets == 0) 0.0 else value.toDouble() / evaluatedTargets
}

/**
 * Five-market, auditable chart reverse-engineering model.
 * Uses only records strictly earlier than targetDate.
 * Scores are relative evidence indices, never calibrated probabilities.
 */
object ReverseEngineeringModel {
    val enabledMarkets: List<Market> = listOf(
        Market.SRIDEVI, Market.SRIDEVI_NIGHT, Market.KALYAN,
        Market.MAIN_BAZAR, Market.TIME_BAZAR
    )

    private data class CrossMarketSignal(
        val conditionalCounts: Map<String, Int>,
        val denominator: Int
    )

    fun rank(
        records: List<ChartRecord>,
        market: Market,
        sessionId: String,
        targetDate: LocalDate,
        side: ReverseEngineeringSide,
        knownOpenPanna: String? = null,
        limit: Int = 10,
        minimumHistory: Int = 30,
        smoothingAlpha: Double = 1.0
    ): ReverseEngineeringRanking {
        require(market in enabledMarkets) { "Only the five enabled markets can be ranked" }
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(limit in 1..100) { "Candidate limit must be between 1 and 100" }
        require(minimumHistory >= 1) { "Minimum history must be at least 1" }
        require(smoothingAlpha > 0.0 && smoothingAlpha.isFinite()) { "Smoothing alpha must be positive and finite" }
        if (knownOpenPanna != null) require(
            knownOpenPanna.length == 3 && knownOpenPanna.all(Char::isDigit)
        ) { "Known open panna must contain exactly three digits" }

        val validRows = records.asSequence()
            .filter { it.market in enabledMarkets && it.sessionId == sessionId && it.date.isBefore(targetDate) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
            .toList()
        validRows.groupBy { it.market to it.date }.forEach { (identity, rows) ->
            require(rows.map { it.sessionId }.distinct().size == rows.size) {
                "Duplicate dates for ${identity.first} must be reconciled before cross-market model ranking"
            }
        }
        val history = validRows.filter { it.market == market }.sortedBy { it.date }
        require(history.map { it.date }.distinct().size == history.size) {
            "Duplicate dates for $market/$sessionId must be reconciled before model ranking"
        }
        if (history.size < minimumHistory) {
            return ReverseEngineeringRanking(
                market, sessionId, side, targetDate, history.size, minimumHistory,
                true, emptyList(), emptyList(), emptyList(),
                "Insufficient earlier valid history: need $minimumHistory rows; found ${history.size}. Model abstained."
            )
        }

        fun pannaOf(record: ChartRecord): String =
            if (side == ReverseEngineeringSide.OPEN) record.openPanna else record.closePanna
        fun familyOf(panna: String): String =
            panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")

        val historicalPannas = history.map(::pannaOf)
        val historicalFamilies = historicalPannas.map(::familyOf)
        val exactCounts = historicalPannas.groupingBy { it }.eachCount()
        val familyCounts = historicalFamilies.groupingBy { it }.eachCount()
        val ankCounts = historicalPannas.groupingBy { it.sumOf { it.digitToInt() } % 10 }.eachCount()
        val positionCounts = (0..2).map { position ->
            historicalPannas.groupingBy { it[position].digitToInt() }.eachCount()
        }
        val pannaTypeCounts = historicalPannas.groupingBy { ChartMath.pannaType(it) }.eachCount()
        val reversePannaCounts = historicalPannas.map { it.reversed() }.groupingBy { it }.eachCount()
        val weekdayRows = history.indices.filter { history[it].date.dayOfWeek == targetDate.dayOfWeek }
        val weekdayCounts = weekdayRows.map { historicalFamilies[it] }.groupingBy { it }.eachCount()
        val transitionCounts = historicalFamilies.zipWithNext()
            .groupingBy { (before, after) -> "$before>$after" }.eachCount()
        val previousFamily = historicalFamilies.last()

        val openCloseCounts = if (side == ReverseEngineeringSide.CLOSE && knownOpenPanna != null) {
            val knownOpenFamily = familyOf(knownOpenPanna)
            history.map { familyOf(it.openPanna) to familyOf(it.closePanna) }
                .filter { it.first == knownOpenFamily }
                .map { it.second }.groupingBy { it }.eachCount()
        } else emptyMap()

        val crossSignals = ReverseEngineeringModel.enabledMarkets.asSequence()
            .filter { it != market }
            .mapNotNull { sourceMarket ->
                val sourceRows = validRows.filter { it.market == sourceMarket }.associateBy { it.date }
                val sourceLatest = sourceRows.values.maxByOrNull { it.date } ?: return@mapNotNull null
                val sourceLatestPanna = if (side == ReverseEngineeringSide.OPEN) sourceLatest.openPanna else sourceLatest.closePanna
                val sourceFamily = familyOf(sourceLatestPanna)
                val aligned = history.mapNotNull { targetRow ->
                    val sourceRow = sourceRows[targetRow.date] ?: return@mapNotNull null
                    val targetPanna = pannaOf(targetRow)
                    val sourcePanna = if (side == ReverseEngineeringSide.OPEN) sourceRow.openPanna else sourceRow.closePanna
                    familyOf(sourcePanna) to familyOf(targetPanna)
                }
                val conditional = aligned.filter { it.first == sourceFamily }
                    .map { it.second }.groupingBy { it }.eachCount()
                if (conditional.isEmpty()) null else CrossMarketSignal(conditional, conditional.values.sum())
            }.toList()

        val familyCandidates = (0 until 125).map { value ->
            val family = buildString { append(value / 25); append((value / 5) % 5); append(value % 5) }
            val familyProbability = ((familyCounts[family] ?: 0) + smoothingAlpha) /
                (history.size + 125.0 * smoothingAlpha)
            val weekdayProbability = if (weekdayRows.isEmpty()) null else
                ((weekdayCounts[family] ?: 0) + smoothingAlpha) /
                    (weekdayRows.size + 125.0 * smoothingAlpha)
            val transitionProbability = if (history.size < 2) null else
                ((transitionCounts["$previousFamily>$family"] ?: 0) + smoothingAlpha) /
                    (history.size - 1 + 125.0 * smoothingAlpha)
            val openCloseProbability = if (openCloseCounts.isEmpty()) null else
                ((openCloseCounts[family] ?: 0) + smoothingAlpha) /
                    (openCloseCounts.values.sum() + 125.0 * smoothingAlpha)
            val crossProbability = if (crossSignals.isEmpty()) null else
                crossSignals.map { signal ->
                    ((signal.conditionalCounts[family] ?: 0) + smoothingAlpha) /
                        (signal.denominator + 125.0 * smoothingAlpha)
                }.average()
            listOf(family, familyProbability.toString(), weekdayProbability?.toString().orEmpty(),
                transitionProbability?.toString().orEmpty(), openCloseProbability?.toString().orEmpty(),
                crossProbability?.toString().orEmpty())
        }

        fun maxFamilyValue(index: Int): Double =
            familyCandidates.mapNotNull { it[index].toDoubleOrNull() }.maxOrNull()?.coerceAtLeast(1e-12) ?: 1.0
        val familyMaxima = (1..5).map(::maxFamilyValue)
        val rankedFamilies = familyCandidates.map { values ->
            val raw = (1..5).map { index -> values[index].toDoubleOrNull()?.div(familyMaxima[index - 1]) }
            val familyWeights = listOf(0.35, 0.20, 0.20, 0.10, 0.15)
            var score = 0.0
            var total = 0.0
            raw.forEachIndexed { index, value ->
                if (value != null) {
                    score += value * familyWeights[index]
                    total += familyWeights[index]
                }
            }
            val family = values[0]
            ReverseEngineeringFamilyCandidate(
                familySequence = family,
                score = if (total == 0.0) 0.0 else score / total * 100.0,
                historicalCount = familyCounts[family] ?: 0,
                weekdayCount = if (weekdayRows.isEmpty()) null else weekdayCounts[family] ?: 0,
                previousTransitionCount = if (history.size < 2) null else transitionCounts["$previousFamily>$family"] ?: 0,
                openCloseConditionalCount = if (openCloseCounts.isEmpty()) null else openCloseCounts[family] ?: 0,
                crossMarketConditionalCount = if (crossSignals.isEmpty()) null else crossSignals.sumOf { it.conditionalCounts[family] ?: 0 }
            )
        }.sortedWith(
            compareByDescending<ReverseEngineeringFamilyCandidate> { it.score }
                .thenByDescending { it.historicalCount }
                .thenBy { it.familySequence }
        ).take(10)

        val candidates = (0..999).map { value ->
            val panna = value.toString().padStart(3, '0')
            val family = familyOf(panna)
            val exactProbability = ((exactCounts[panna] ?: 0) + smoothingAlpha) /
                (history.size + 1000.0 * smoothingAlpha)
            val familyProbability = ((familyCounts[family] ?: 0) + smoothingAlpha) /
                (history.size + 125.0 * smoothingAlpha)
            val ank = panna.sumOf { it.digitToInt() } % 10
            val ankProbability = ((ankCounts[ank] ?: 0) + smoothingAlpha) /
                (history.size + 10.0 * smoothingAlpha)
            val positionProbability = (0..2).map { position ->
                ((positionCounts[position][panna[position].digitToInt()] ?: 0) + smoothingAlpha) /
                    (history.size + 10.0 * smoothingAlpha)
            }.average()
            val type = ChartMath.pannaType(panna)
            val typeProbability = ((pannaTypeCounts[type] ?: 0) + smoothingAlpha) /
                (history.size + PannaType.entries.size * smoothingAlpha)
            val reverseProbability = ((reversePannaCounts[panna.reversed()] ?: 0) + smoothingAlpha) /
                (history.size + 1000.0 * smoothingAlpha)
            val weekdayProbability = if (weekdayRows.isEmpty()) null else
                ((weekdayCounts[family] ?: 0) + smoothingAlpha) /
                    (weekdayRows.size + 125.0 * smoothingAlpha)
            val transitionProbability = if (history.size < 2) null else
                ((transitionCounts["$previousFamily>$family"] ?: 0) + smoothingAlpha) /
                    (history.size - 1 + 125.0 * smoothingAlpha)
            val openCloseProbability = if (openCloseCounts.isEmpty()) null else
                ((openCloseCounts[family] ?: 0) + smoothingAlpha) /
                    (openCloseCounts.values.sum() + 125.0 * smoothingAlpha)
            val crossProbability = if (crossSignals.isEmpty()) null else
                crossSignals.map { signal ->
                    ((signal.conditionalCounts[family] ?: 0) + smoothingAlpha) /
                        (signal.denominator + 125.0 * smoothingAlpha)
                }.average()
            ReverseEngineeringCandidate(
                panna, family, type, ank, 0.0,
                exactProbability, familyProbability, ankProbability, positionProbability,
                typeProbability, reverseProbability, weekdayProbability,
                transitionProbability, openCloseProbability, crossProbability
            )
        }

        fun maxOf(selector: (ReverseEngineeringCandidate) -> Double?): Double =
            candidates.mapNotNull(selector).maxOrNull()?.coerceAtLeast(1e-12) ?: 1.0
        val maxima = listOf(
            maxOf { it.exactPannaEvidence }, maxOf { it.familyEvidence },
            maxOf { it.ankEvidence }, maxOf { it.positionEvidence },
            maxOf { it.pannaTypeEvidence }, maxOf { it.reversePannaEvidence },
            maxOf { it.weekdayEvidence }, maxOf { it.previousTransitionEvidence },
            maxOf { it.openCloseConditionalEvidence }, maxOf { it.crossMarketConditionalEvidence }
        )
        val weights = listOf(0.08, 0.22, 0.10, 0.12, 0.06, 0.06, 0.10, 0.12, 0.07, 0.07)
        val active = buildList {
            add("Exact panna recurrence")
            add("Digit-family sequence")
            add("Ank distribution")
            add("Digit-position distribution")
            add("SP/DP/TP panna type")
            add("Reverse-panna recurrence")
            if (weekdayRows.isNotEmpty()) add("Target weekday")
            if (history.size >= 2) add("Previous-state transition")
            if (openCloseCounts.isNotEmpty()) add("Known open-to-close conditional")
            if (crossSignals.isNotEmpty()) add("Lagged cross-market conditional")
        }

        val ranked = candidates.map { candidate ->
            val values = listOf(
                candidate.exactPannaEvidence / maxima[0],
                candidate.familyEvidence / maxima[1],
                candidate.ankEvidence / maxima[2],
                candidate.positionEvidence / maxima[3],
                candidate.pannaTypeEvidence / maxima[4],
                candidate.reversePannaEvidence / maxima[5],
                candidate.weekdayEvidence?.div(maxima[6]),
                candidate.previousTransitionEvidence?.div(maxima[7]),
                candidate.openCloseConditionalEvidence?.div(maxima[8]),
                candidate.crossMarketConditionalEvidence?.div(maxima[9])
            )
            var weighted = 0.0
            var totalWeight = 0.0
            values.forEachIndexed { index, value ->
                if (value != null) {
                    weighted += value * weights[index]
                    totalWeight += weights[index]
                }
            }
            candidate.copy(score = if (totalWeight == 0.0) 0.0 else weighted / totalWeight * 100.0)
        }.sortedWith(
            compareByDescending<ReverseEngineeringCandidate> { it.score }
                .thenByDescending { it.familyEvidence }
                .thenByDescending { it.exactPannaEvidence }
                .thenBy { it.panna }
        ).take(limit)

        return ReverseEngineeringRanking(
            market, sessionId, side, targetDate, history.size, minimumHistory,
            false, ranked, rankedFamilies, active,
            "Relative evidence index only; not a calibrated probability or guarantee. Training data ends before $targetDate. Source confidence is governed by the reconciliation ledger."
        )
    }

    fun backtest(
        records: List<ChartRecord>,
        market: Market,
        sessionId: String,
        side: ReverseEngineeringSide,
        targetLimit: Int = 120,
        minimumHistory: Int = 30,
        candidateLimit: Int = 10
    ): ReverseEngineeringBacktestReport {
        require(targetLimit in 1..1000) { "Target limit must be between 1 and 1000" }
        require(market in enabledMarkets) { "Only the five enabled markets can be backtested" }
        val actualRows = records.asSequence()
            .filter { it.market == market && it.sessionId == sessionId }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
            .sortedBy { it.date }.toList()
        require(actualRows.map { it.date }.distinct().size == actualRows.size) {
            "Duplicate dates must be reconciled before walk-forward backtesting"
        }
        val targets = actualRows.takeLast(targetLimit)
        var evaluated = 0
        var abstained = 0
        var top1Exact = 0
        var top10Exact = 0
        var top1Ank = 0
        var top10Ank = 0
        var top1Family = 0
        var top10Family = 0
        var baselineFamily = 0

        targets.forEach { actual ->
            val ranking = rank(records, market, sessionId, actual.date, side,
                limit = candidateLimit, minimumHistory = minimumHistory)
            if (ranking.abstained || ranking.candidates.isEmpty()) {
                abstained++
                return@forEach
            }
            evaluated++
            val actualPanna = pannaOf(actual, side)
            val actualFamily = familyOf(actualPanna)
            val first = ranking.candidates.first()
            if (first.panna == actualPanna) top1Exact++
            if (ranking.candidates.any { it.panna == actualPanna }) top10Exact++
            val actualAnk = actualPanna.sumOf { it.digitToInt() } % 10
            if (first.ank == actualAnk) top1Ank++
            if (ranking.candidates.any { it.ank == actualAnk }) top10Ank++
            if (ranking.familyCandidates.firstOrNull()?.familySequence == actualFamily) top1Family++
            if (ranking.familyCandidates.any { it.familySequence == actualFamily }) top10Family++

            val priorFamilies = actualRows.filter { it.date.isBefore(actual.date) }
                .map { familyOf(pannaOf(it, side)) }
            val modeFamily = priorFamilies.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .firstOrNull()?.key
            if (modeFamily == actualFamily) baselineFamily++
        }
        return ReverseEngineeringBacktestReport(
            market, sessionId, side, targets.size, evaluated, abstained,
            top1Exact, top10Exact, top1Ank, top10Ank, top1Family, top10Family, baselineFamily
        )
    }

    fun deriveJodiCandidates(
        openCandidates: List<ReverseEngineeringCandidate>,
        closeCandidates: List<ReverseEngineeringCandidate>,
        limit: Int = 10
    ): List<ReverseEngineeringJodiCandidate> {
        require(limit in 1..100) { "Candidate limit must be between 1 and 100" }
        val scores = mutableMapOf<String, Double>()
        val support = mutableMapOf<String, Int>()
        openCandidates.forEach { open ->
            closeCandidates.forEach { close ->
                val openAnk = open.panna.sumOf { it.digitToInt() } % 10
                val closeAnk = close.panna.sumOf { it.digitToInt() } % 10
                val jodi = "$openAnk$closeAnk"
                scores[jodi] = (scores[jodi] ?: 0.0) + open.score * close.score / 100.0
                support[jodi] = (support[jodi] ?: 0) + 1
            }
        }
        return scores.map { (jodi, score) ->
            ReverseEngineeringJodiCandidate(jodi, score, support[jodi] ?: 0)
        }.sortedWith(compareByDescending<ReverseEngineeringJodiCandidate> { it.score }.thenBy { it.jodi })
            .take(limit)
    }

    private fun pannaOf(record: ChartRecord, side: ReverseEngineeringSide): String =
        if (side == ReverseEngineeringSide.OPEN) record.openPanna else record.closePanna

    private fun familyOf(panna: String): String =
        panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
}
