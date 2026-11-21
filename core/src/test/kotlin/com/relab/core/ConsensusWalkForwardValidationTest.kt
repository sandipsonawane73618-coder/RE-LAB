package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ConsensusWalkForwardValidationTest {
    private val retrieved = Instant.parse("2026-02-01T12:00:00Z")

    private fun observation(
        day: Int,
        source: String,
        result: String = "111-36-222",
        status: ObservationStatus = ObservationStatus.PUBLISHED
    ) = SourceObservation(
        market = Market.SRIDEVI,
        sessionId = "main",
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        sourceId = source,
        sourceRecordId = "$source-$day",
        retrievedAt = retrieved.plusSeconds(day.toLong()),
        publishedAt = retrieved.plusSeconds(day.toLong()),
        rawText = result,
        normalizedResult = result.takeIf { status == ObservationStatus.PUBLISHED },
        status = status
    )

    @Test
    fun validatesOnlyConsensusRowsAndReportsExcludedEvidence() {
        val rows = buildList {
            (1..5).forEach { day ->
                add(observation(day, "publisher-a"))
                add(observation(day, "publisher-b"))
            }
            add(observation(6, "single-publisher"))
            add(observation(7, "publisher-a", "111-36-222"))
            add(observation(7, "publisher-b", "123-65-456"))
            add(observation(8, "publisher-a", status = ObservationStatus.PENDING))
            add(observation(9, "publisher-a"))
            add(observation(9, "publisher-b"))
        }

        val report = ConsensusWalkForwardValidator.evaluate(
            rows,
            targetDateExclusive = LocalDate.of(2026, 1, 10),
            minimumHistory = 2
        )

        assertEquals(6, report.consensusRecordCount)
        assertEquals(1, report.singleSourceUnverifiedGroupCount)
        assertEquals(1, report.conflictGroupCount)
        assertEquals(1, report.noPublishedResultGroupCount)
        assertEquals(6, report.requestedTargets)
        assertEquals(4, report.evaluatedTargets)
        assertEquals(2, report.abstainedTargets)
        assertEquals(1.0, report.rate(report.exactPairTop10Hits)!!, 0.0)
    }

    @Test
    fun excludesFutureRowsAndKeepsNoHistoryRateUnavailable() {
        val observations = (1..4).flatMap { day ->
            listOf(observation(day, "a"), observation(day, "b"))
        } + listOf(observation(20, "a"), observation(20, "b"))

        val report = ConsensusWalkForwardValidator.evaluate(
            observations,
            targetDateExclusive = LocalDate.of(2026, 1, 5),
            minimumHistory = 10
        )

        assertEquals(4, report.consensusRecordCount)
        assertEquals(4, report.requestedTargets)
        assertEquals(0, report.evaluatedTargets)
        assertEquals(4, report.abstainedTargets)
        assertNull(report.rate(report.exactPairTop10Hits))
    }

    @Test
    fun rejectsInvalidMinimumHistory() {
        try {
            ConsensusWalkForwardValidator.evaluate(emptyList(), LocalDate.of(2026, 2, 1), 0)
            fail("minimumHistory must be positive")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("minimumHistory"))
        }
    }
}
