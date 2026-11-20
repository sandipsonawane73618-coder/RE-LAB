package com.relab.core

import java.time.LocalDate

/** Same-date, same-session cross-market comparisons across all nine chart markets. */
data class CrossMarketRelation(
    val fromFamilySequence: String,
    val toFamilySequence: String,
    val count: Int
)

data class CrossMarketPairReport(
    val firstMarket: Market,
    val secondMarket: Market,
    val sharedDates: Int,
    val openFamilyMatches: Int,
    val closeFamilyMatches: Int,
    val openAnkMatches: Int,
    val closeAnkMatches: Int,
    val jodiMatches: Int,
    val topOpenRelations: List<CrossMarketRelation>,
    val topCloseRelations: List<CrossMarketRelation>
) {
    val openFamilyMatchRate: Double
        get() = if (sharedDates == 0) 0.0 else openFamilyMatches.toDouble() / sharedDates
    val closeFamilyMatchRate: Double
        get() = if (sharedDates == 0) 0.0 else closeFamilyMatches.toDouble() / sharedDates
    val jodiMatchRate: Double
        get() = if (sharedDates == 0) 0.0 else jodiMatches.toDouble() / sharedDates
}

/**
 * Descriptive reverse-engineering across markets. Only matching calendar dates
 * and session IDs are compared. This reports historical co-occurrence, not
 * causation, independence, probability, or a guaranteed future relationship.
 */
object CrossMarketReverseEngineering {
    val enabledMarkets: List<Market> = listOf(
        Market.SRIDEVI,
        Market.SRIDEVI_NIGHT,
        Market.KALYAN,
        Market.MAIN_BAZAR,
        Market.TIME_BAZAR,
        Market.MILAN_DAY,
        Market.MILAN_NIGHT,
        Market.RAJDHANI_DAY,
        Market.RAJDHANI_NIGHT
    )

    private data class DateSession(val date: LocalDate, val sessionId: String)

    fun analyze(records: List<ChartRecord>): List<CrossMarketPairReport> {
        val validInput = records.filter { it.market in enabledMarkets }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
        validInput.groupBy { Triple(it.market, it.sessionId, it.date) }.forEach { (key, rows) ->
            require(rows.size == 1) {
                "Duplicate cross-market rows for ${key.first}/${key.second} on ${key.third} must be reconciled first"
            }
        }
        val validByMarket = enabledMarkets.associateWith { market ->
            records.asSequence()
                .filter { it.market == market }
                .mapNotNull { record ->
                    val features = (ChartMath.validate(record) as? ValidationResult.Valid)?.features
                        ?: return@mapNotNull null
                    (DateSession(record.date, record.sessionId)) to (record to features)
                }
                .toMap()
        }

        val reports = mutableListOf<CrossMarketPairReport>()
        for (firstIndex in enabledMarkets.indices) {
            for (secondIndex in firstIndex + 1 until enabledMarkets.size) {
                val first = enabledMarkets[firstIndex]
                val second = enabledMarkets[secondIndex]
                val firstRows = validByMarket[first].orEmpty()
                val secondRows = validByMarket[second].orEmpty()
                val sharedKeys = firstRows.keys.intersect(secondRows.keys).sortedWith(
                    compareBy<DateSession> { it.date }.thenBy { it.sessionId }
                )
                if (sharedKeys.isEmpty()) continue

                var openMatches = 0
                var closeMatches = 0
                var openAnkMatches = 0
                var closeAnkMatches = 0
                var jodiMatches = 0
                val openRelations = mutableMapOf<String, Int>()
                val closeRelations = mutableMapOf<String, Int>()

                sharedKeys.forEach { key ->
                    val firstFeatures = firstRows.getValue(key).second
                    val secondFeatures = secondRows.getValue(key).second
                    val firstOpen = firstFeatures.openFamilySequence.joinToString("")
                    val secondOpen = secondFeatures.openFamilySequence.joinToString("")
                    val firstClose = firstFeatures.closeFamilySequence.joinToString("")
                    val secondClose = secondFeatures.closeFamilySequence.joinToString("")
                    if (firstOpen == secondOpen) openMatches++
                    if (firstClose == secondClose) closeMatches++
                    if (firstFeatures.openAnk == secondFeatures.openAnk) openAnkMatches++
                    if (firstFeatures.closeAnk == secondFeatures.closeAnk) closeAnkMatches++
                    val firstRecord = firstRows.getValue(key).first
                    val secondRecord = secondRows.getValue(key).first
                    if (firstRecord.jodi == secondRecord.jodi) jodiMatches++
                    val openKey = "$firstOpen>$secondOpen"
                    val closeKey = "$firstClose>$secondClose"
                    openRelations[openKey] = (openRelations[openKey] ?: 0) + 1
                    closeRelations[closeKey] = (closeRelations[closeKey] ?: 0) + 1
                }

                fun topRelations(counts: Map<String, Int>) = counts.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(5)
                    .map { entry ->
                        val parts = entry.key.split('>')
                        CrossMarketRelation(parts[0], parts[1], entry.value)
                    }

                reports += CrossMarketPairReport(
                    firstMarket = first,
                    secondMarket = second,
                    sharedDates = sharedKeys.size,
                    openFamilyMatches = openMatches,
                    closeFamilyMatches = closeMatches,
                    openAnkMatches = openAnkMatches,
                    closeAnkMatches = closeAnkMatches,
                    jodiMatches = jodiMatches,
                    topOpenRelations = topRelations(openRelations),
                    topCloseRelations = topRelations(closeRelations)
                )
            }
        }
        return reports.sortedWith(
            compareByDescending<CrossMarketPairReport> { it.sharedDates }
                .thenBy { it.firstMarket.name }
                .thenBy { it.secondMarket.name }
        )
    }
}
