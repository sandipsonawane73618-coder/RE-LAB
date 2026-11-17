package com.relab.core

import java.time.Instant
import java.time.LocalDate

enum class ObservationStatus {
    PUBLISHED,
    PENDING,
    HOLIDAY_CLOSED,
    MISSING_FROM_SOURCE,
    MALFORMED
}

data class SourceObservation(
    val market: Market,
    val sessionId: String,
    val date: LocalDate,
    val sourceId: String,
    val sourceRecordId: String? = null,
    val sourceUrl: String? = null,
    val retrievedAt: Instant,
    val publishedAt: Instant? = null,
    val rawText: String,
    /** Normalized raw result, e.g. 278-74-389; never use this to overwrite rawText. */
    val normalizedResult: String? = null,
    val status: ObservationStatus
) {
    init {
        require(sessionId.isNotBlank()) { "Session ID is required" }
        require(sourceId.isNotBlank()) { "Source ID is required" }
        require(status != ObservationStatus.PUBLISHED || !normalizedResult.isNullOrBlank()) {
            "Published observations require a normalized result"
        }
    }
}

enum class ReconciliationState {
    CONSENSUS,
    CONFLICT,
    NO_PUBLISHED_RESULT
}

data class ReconciliationGroup(
    val market: Market,
    val sessionId: String,
    val date: LocalDate,
    val observations: List<SourceObservation>,
    val state: ReconciliationState,
    /** Set only when every published source agrees on exactly one normalized result. */
    val agreedResult: String?
)

object SourceReconciliation {
    fun reconcile(observations: List<SourceObservation>): List<ReconciliationGroup> {
        return observations
            .groupBy { Triple(it.market, it.sessionId, it.date) }
            .map { (key, group) ->
                val publishedValues = group
                    .filter { it.status == ObservationStatus.PUBLISHED }
                    .mapNotNull { it.normalizedResult }
                    .distinct()
                    .sorted()
                val state = when {
                    publishedValues.size > 1 -> ReconciliationState.CONFLICT
                    publishedValues.size == 1 -> ReconciliationState.CONSENSUS
                    else -> ReconciliationState.NO_PUBLISHED_RESULT
                }
                ReconciliationGroup(
                    market = key.first,
                    sessionId = key.second,
                    date = key.third,
                    observations = group.sortedWith(compareBy<SourceObservation> { it.sourceId }.thenBy { it.sourceRecordId ?: "" }),
                    state = state,
                    agreedResult = if (state == ReconciliationState.CONSENSUS) publishedValues.single() else null
                )
            }
            .sortedWith(compareBy<ReconciliationGroup> { it.market.name }
                .thenBy { it.sessionId }.thenBy { it.date })
    }
}
