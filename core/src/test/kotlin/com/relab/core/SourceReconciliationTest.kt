package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SourceReconciliationTest {
    private fun observation(
        source: String,
        status: ObservationStatus,
        result: String? = null,
        session: String = "main"
    ) = SourceObservation(
        market = Market.SRIDEVI,
        sessionId = session,
        date = LocalDate.of(2026, 11, 17),
        sourceId = source,
        retrievedAt = Instant.parse("2026-11-17T10:00:00Z"),
        rawText = result ?: status.name,
        normalizedResult = result,
        status = status
    )

    @Test fun agreesOnlyWhenAllPublishedValuesMatch() {
        val group = SourceReconciliation.reconcile(
            listOf(
                observation("source-a", ObservationStatus.PUBLISHED, "115-73-229"),
                observation("source-b", ObservationStatus.PUBLISHED, "115-73-229")
            )
        ).single()
        assertEquals(ReconciliationState.CONSENSUS, group.state)
        assertEquals("115-73-229", group.agreedResult)
        assertEquals(2, group.observations.size)
    }

    @Test fun preservesConflictingSourceValuesWithoutChoosingOne() {
        val group = SourceReconciliation.reconcile(
            listOf(
                observation("source-a", ObservationStatus.PUBLISHED, "566-73-148"),
                observation("source-b", ObservationStatus.PUBLISHED, "556-63-148")
            )
        ).single()
        assertEquals(ReconciliationState.CONFLICT, group.state)
        assertNull(group.agreedResult)
        assertEquals(2, group.observations.size)
    }

    @Test fun pendingAndHolidayAreNotTreatedAsNumericResults() {
        val groups = SourceReconciliation.reconcile(
            listOf(
                observation("source-a", ObservationStatus.PENDING),
                observation("source-b", ObservationStatus.HOLIDAY_CLOSED)
            )
        )
        assertEquals(ReconciliationState.NO_PUBLISHED_RESULT, groups.single().state)
        assertNull(groups.single().agreedResult)
    }

    @Test fun differentSessionsNeverMerge() {
        val groups = SourceReconciliation.reconcile(
            listOf(
                observation("source-a", ObservationStatus.PUBLISHED, "115-73-229", "main"),
                observation("source-b", ObservationStatus.PUBLISHED, "469-98-170", "day")
            )
        )
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.state == ReconciliationState.CONSENSUS })
    }
}
