package com.relab.core

import java.time.LocalDate

/**
 * Produces point-in-time regime snapshots. A snapshot for date D is calculated
 * exclusively from valid records strictly before D; records on D and later are
 * never visible to the detector. This makes regime labels safe to join to
 * chronological backtests without future leakage.
 */
data class MarketRegimeSnapshot(
    val market: Market,
    val sessionId: String,
    val targetDate: LocalDate,
    val historyRecords: Int,
    val report: MarketIntelligenceReport
)

object MarketRegimeWalkForwardEvaluator {
    fun evaluate(
        records: List<ChartRecord>,
        targetDateExclusive: LocalDate,
        minimumRecords: Int = 10
    ): List<MarketRegimeSnapshot> {
        require(minimumRecords >= 2) { "minimumRecords must be at least 2" }

        val eligible = records.asSequence()
            .filter { it.date.isBefore(targetDateExclusive) }
            .filter { ChartMath.validate(it) is ValidationResult.Valid }
            .toList()

        eligible.groupBy { Triple(it.market, it.sessionId, it.date) }.forEach { (key, rows) ->
            require(rows.size == 1) {
                "Duplicate rows for ${key.first}/${key.second} on ${key.third} must be reconciled before regime evaluation"
            }
        }

        val snapshots = mutableListOf<MarketRegimeSnapshot>()
        eligible.groupBy { it.market to it.sessionId }
            .toSortedMap(compareBy<Pair<Market, String>> { it.first.name }.thenBy { it.second })
            .forEach { (identity, unsorted) ->
                val rows = unsorted.sortedBy { it.date }
                rows.forEachIndexed { index, target ->
                    // Strictly earlier rows only. The current target is excluded.
                    val history = rows.subList(0, index)
                    val report = MarketIntelligence.analyze(history, minimumRecords)
                        .firstOrNull { it.market == identity.first && it.sessionId == identity.second }
                        ?: return@forEachIndexed
                    snapshots += MarketRegimeSnapshot(
                        market = identity.first,
                        sessionId = identity.second,
                        targetDate = target.date,
                        historyRecords = report.validRecords,
                        report = report
                    )
                }
            }

        return snapshots.sortedWith(
            compareBy<MarketRegimeSnapshot> { it.targetDate }
                .thenBy { it.market.name }
                .thenBy { it.sessionId }
        )
    }
}
