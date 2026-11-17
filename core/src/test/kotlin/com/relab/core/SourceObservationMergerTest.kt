package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SourceObservationMergerTest {
    private fun observation(source: String, result: String, retrievedAt: String = "2026-11-17T10:00:00Z") =
        SourceObservation(
            market = Market.SRIDEVI,
            sessionId = "main",
            date = LocalDate.of(2026, 11, 17),
            sourceId = source,
            retrievedAt = Instant.parse(retrievedAt),
            rawText = result,
            normalizedResult = result,
            status = ObservationStatus.PUBLISHED
        )

    @Test fun appendsNewSourcesAndPreservesConflicts() {
        val first = observation("source-a", "115-73-229")
        val conflict = observation("source-b", "566-73-148")
        val result = SourceObservationMerger.append(listOf(first), listOf(conflict))

        assertEquals(1, result.addedCount)
        assertEquals(0, result.alreadyPresentCount)
        assertEquals(listOf(first, conflict), result.observations)
        assertEquals(
            ReconciliationState.CONFLICT,
            SourceReconciliation.reconcile(result.observations).single().state
        )
    }

    @Test fun exactReimportIsIdempotentButDuplicatesInsideFirstBatchRemain() {
        val duplicateA = observation("archive", "115-73-229")
        val duplicateB = duplicateA.copy(rawText = "115-73-229")
        val firstImport = SourceObservationMerger.append(emptyList(), listOf(duplicateA, duplicateB))
        assertEquals(2, firstImport.addedCount)
        assertEquals(2, firstImport.observations.size)

        val secondImport = SourceObservationMerger.append(firstImport.observations, listOf(duplicateA, duplicateB))
        assertEquals(0, secondImport.addedCount)
        assertEquals(2, secondImport.alreadyPresentCount)
        assertEquals(2, secondImport.observations.size)
    }

    @Test fun sameResultFromDifferentRetrievalTimesRemainsSeparateEvidence() {
        val earlier = observation("archive", "115-73-229")
        val later = observation("archive", "115-73-229", "2026-11-17T11:00:00Z")
        val result = SourceObservationMerger.append(listOf(earlier), listOf(later))

        assertEquals(1, result.addedCount)
        assertEquals(2, result.observations.size)
    }

    @Test fun repeatedSourceRecordWithSameContentIsAuditedWithoutDeletingSnapshots() {
        val first = observation("archive", "115-73-229").copy(sourceRecordId = "2026-11-17")
        val later = first.copy(retrievedAt = Instant.parse("2026-11-17T11:00:00Z"))
        val audit = SourceObservationMerger.auditRepeatedSourceRecords(listOf(first, later))

        assertEquals(1, audit.size)
        assertEquals(DuplicateObservationState.REPEATED_SAME_CONTENT, audit.single().state)
        assertEquals(2, audit.single().observations.size)
    }

    @Test fun changedValueForSameSourceRecordIsFlaggedAndBothValuesRemain() {
        val first = observation("archive", "115-73-229").copy(sourceRecordId = "2026-11-17")
        val later = first.copy(
            retrievedAt = Instant.parse("2026-11-17T11:00:00Z"),
            rawText = "577-99-667",
            normalizedResult = "577-99-667"
        )
        val audit = SourceObservationMerger.auditRepeatedSourceRecords(listOf(first, later))

        assertEquals(1, audit.size)
        assertEquals(DuplicateObservationState.SOURCE_RECORD_CHANGED, audit.single().state)
        assertEquals(listOf("115-73-229", "577-99-667"), audit.single().observations.mapNotNull { it.normalizedResult })
    }

    @Test fun observationsWithoutPublisherRecordIdentityAreNotGuessedAsDuplicates() {
        val first = observation("archive", "115-73-229")
        val later = first.copy(retrievedAt = Instant.parse("2026-11-17T11:00:00Z"))
        assertTrue(SourceObservationMerger.auditRepeatedSourceRecords(listOf(first, later)).isEmpty())
    }
}
