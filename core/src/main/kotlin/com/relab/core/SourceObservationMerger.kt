package com.relab.core

/**
 * Appends source observations from another import without erasing history.
 *
 * Exact observations already present in the existing dataset are skipped, making
 * re-importing the same CSV idempotent. Duplicates inside a single incoming batch
 * are retained because they may be meaningful repeated source evidence.
 */
data class ObservationMergeResult(
    val observations: List<SourceObservation>,
    val addedCount: Int,
    val alreadyPresentCount: Int
)

/** A non-destructive audit of repeated records published by the same source. */
enum class DuplicateObservationState {
    /** Same source record identity and same captured content across snapshots. */
    REPEATED_SAME_CONTENT,
    /** Same source record identity but captured content changed between snapshots. */
    SOURCE_RECORD_CHANGED
}

data class DuplicateObservationGroup(
    val market: Market,
    val sessionId: String,
    val date: java.time.LocalDate,
    val sourceId: String,
    val sourceRecordId: String,
    val observations: List<SourceObservation>,
    val state: DuplicateObservationState
)

object SourceObservationMerger {
    fun append(
        existing: List<SourceObservation>,
        incoming: List<SourceObservation>
    ): ObservationMergeResult {
        val existingSet = existing.toHashSet()
        val additions = incoming.filterNot { it in existingSet }
        return ObservationMergeResult(
            observations = existing + additions,
            addedCount = additions.size,
            alreadyPresentCount = incoming.size - additions.size
        )
    }

    /**
     * Finds repeated source-record identities without deleting or merging rows.
     * Records without sourceRecordId are intentionally excluded: date alone is
     * not a sufficiently reliable identity for a publisher's archive row.
     */
    fun auditRepeatedSourceRecords(
        observations: List<SourceObservation>
    ): List<DuplicateObservationGroup> {
        return observations
            .filter { !it.sourceRecordId.isNullOrBlank() }
            .groupBy {
                listOf(
                    it.market.name,
                    it.sessionId,
                    it.date.toString(),
                    it.sourceId,
                    it.sourceRecordId!!
                )
            }
            .values
            .filter { it.size > 1 }
            .map { group ->
                val ordered = group.sortedBy { it.retrievedAt }
                val distinctContent = ordered.map {
                    listOf(it.status.name, it.rawText, it.normalizedResult ?: "")
                }.distinct()
                val first = ordered.first()
                DuplicateObservationGroup(
                    market = first.market,
                    sessionId = first.sessionId,
                    date = first.date,
                    sourceId = first.sourceId,
                    sourceRecordId = first.sourceRecordId!!,
                    observations = ordered,
                    state = if (distinctContent.size == 1) {
                        DuplicateObservationState.REPEATED_SAME_CONTENT
                    } else {
                        DuplicateObservationState.SOURCE_RECORD_CHANGED
                    }
                )
            }
            .sortedWith(
                compareBy<DuplicateObservationGroup> { it.market.name }
                    .thenBy { it.sessionId }
                    .thenBy { it.date }
                    .thenBy { it.sourceId }
                    .thenBy { it.sourceRecordId }
            )
    }
}
