package com.relab.core

import java.time.temporal.ChronoUnit

/** Deterministic descriptive analysis. Inputs are validated and grouped by market. */
data class PatternCount(
    val pattern: String,
    val count: Int,
    val denominator: Int,
    val percentage: Double,
    val meetsMinimumSupport: Boolean
)

data class RepeatGap(
    val pattern: String,
    val gapsInDays: List<Long>
)

data class WalkForwardRow(
    val date: java.time.LocalDate,
    val actualOpenFamilySequence: String,
    val globalModePrediction: String,
    val weekdayModePrediction: String,
    val previousStatePrediction: String
)

data class WalkForwardMetric(
    val correct: Int,
    val evaluated: Int
) {
    val hitRate: Double
        get() = if (evaluated == 0) 0.0 else correct.toDouble() / evaluated
}

data class MarketAnalysis(
    val market: Market,
    val acceptedRecords: Int,
    val rejectedRecords: Int,
    val openExactPanna: List<PatternCount>,
    val closeExactPanna: List<PatternCount>,
    val openFamilySequences: List<PatternCount>,
    val closeFamilySequences: List<PatternCount>,
    val openWeekdayCounts: Map<java.time.DayOfWeek, Int>,
    val closeWeekdayCounts: Map<java.time.DayOfWeek, Int>,
    /** Matrix rows are previous family, columns are next family. */
    val openFamilyDigitTransitions: List<List<Int>>,
    /** Sparse 125-state transition map: "previousSequence>nextSequence" -> count. */
    val openPannaFamilyTransitions: Map<String, Int>,
    /** Key is "openFamilySequence>closeFamilySequence". */
    val openToCloseFamilyRelations: List<PatternCount>,
    val openRepeatGaps: List<RepeatGap>,
    val walkForwardRows: List<WalkForwardRow>,
    val globalModeBaseline: WalkForwardMetric,
    val weekdayModeBaseline: WalkForwardMetric,
    val previousStateBaseline: WalkForwardMetric
)

object PatternAnalysis {
    fun analyze(records: List<ChartRecord>, minimumSupport: Int = 1): List<MarketAnalysis> {
        require(minimumSupport >= 1) { "minimumSupport must be at least 1" }
        return Market.entries.mapNotNull { market ->
            val marketRecords = records.filter { it.market == market }
            if (marketRecords.isEmpty()) return@mapNotNull null
            analyzeMarket(market, marketRecords, minimumSupport)
        }
    }

    private fun analyzeMarket(
        market: Market,
        records: List<ChartRecord>,
        minimumSupport: Int
    ): MarketAnalysis {
        val validated = records.mapNotNull { record ->
            (ChartMath.validate(record) as? ValidationResult.Valid)?.let { record to it.features }
        }.sortedBy { it.first.date }

        // A single resolved record per market/date is required before chronological analysis.
        require(validated.map { it.first.date }.distinct().size == validated.size) {
            "Duplicate dates for $market: reconcile source observations before analysis"
        }

        val accepted = validated.map { it.first }
        val features = validated.map { it.second }
        val openSeq = features.map { it.openFamilySequence.joinToString("") }
        val closeSeq = features.map { it.closeFamilySequence.joinToString("") }

        fun counts(values: List<String>): List<PatternCount> {
            val grouped = values.groupingBy { it }.eachCount()
            return grouped.map { (pattern, count) ->
                PatternCount(pattern, count, values.size, count * 100.0 / values.size, count >= minimumSupport)
            }.sortedWith(compareByDescending<PatternCount> { it.count }.thenBy { it.pattern })
        }

        val digitTransitions = Array(5) { IntArray(5) }
        for (i in 1 until features.size) {
            for (position in 0..2) {
                digitTransitions[features[i - 1].openFamilySequence[position]][features[i].openFamilySequence[position]]++
            }
        }

        val sequenceTransitions = linkedMapOf<String, Int>()
        for (i in 1 until openSeq.size) {
            val key = "${openSeq[i - 1]}>${openSeq[i]}"
            sequenceTransitions[key] = (sequenceTransitions[key] ?: 0) + 1
        }

        val relations = openSeq.indices.map { "${openSeq[it]}>${closeSeq[it]}" }
        val lastSeen = mutableMapOf<String, java.time.LocalDate>()
        val gaps = mutableMapOf<String, MutableList<Long>>()
        accepted.indices.forEach { index ->
            val pattern = openSeq[index]
            lastSeen[pattern]?.let { previous ->
                gaps.getOrPut(pattern) { mutableListOf() }.add(ChronoUnit.DAYS.between(previous, accepted[index].date))
            }
            lastSeen[pattern] = accepted[index].date
        }

        val weekdayOpen = accepted.groupingBy { it.date.dayOfWeek }.eachCount()
        val weekdayClose = weekdayOpen.toMap()

        val rows = mutableListOf<WalkForwardRow>()
        for (i in 1 until accepted.size) {
            val history = openSeq.take(i)
            val historyWeekday = openSeq.indices.take(i)
                .filter { accepted[it].date.dayOfWeek == accepted[i].date.dayOfWeek }
                .map { openSeq[it] }
            val globalMode = mode(history)
            val weekdayMode = if (historyWeekday.isEmpty()) globalMode else mode(historyWeekday)
            rows += WalkForwardRow(
                date = accepted[i].date,
                actualOpenFamilySequence = openSeq[i],
                globalModePrediction = globalMode,
                weekdayModePrediction = weekdayMode,
                previousStatePrediction = openSeq[i - 1]
            )
        }

        fun metric(selector: (WalkForwardRow) -> String) =
            WalkForwardMetric(rows.count { selector(it) == it.actualOpenFamilySequence }, rows.size)

        return MarketAnalysis(
            market = market,
            acceptedRecords = accepted.size,
            rejectedRecords = records.size - accepted.size,
            openExactPanna = counts(accepted.map { it.openPanna }),
            closeExactPanna = counts(accepted.map { it.closePanna }),
            openFamilySequences = counts(openSeq).map { it.copy(meetsMinimumSupport = it.count >= minimumSupport) },
            closeFamilySequences = counts(closeSeq).map { it.copy(meetsMinimumSupport = it.count >= minimumSupport) },
            openWeekdayCounts = weekdayOpen,
            closeWeekdayCounts = weekdayClose,
            openFamilyDigitTransitions = digitTransitions.map { it.toList() },
            openPannaFamilyTransitions = sequenceTransitions.toSortedMap(),
            openToCloseFamilyRelations = counts(relations),
            openRepeatGaps = gaps.toSortedMap().map { (pattern, values) -> RepeatGap(pattern, values.toList()) },
            walkForwardRows = rows,
            globalModeBaseline = metric { it.globalModePrediction },
            weekdayModeBaseline = metric { it.weekdayModePrediction },
            previousStateBaseline = metric { it.previousStatePrediction }
        )
    }

    private fun mode(values: List<String>): String =
        values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key
}
