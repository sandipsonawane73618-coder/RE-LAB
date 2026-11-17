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
}
