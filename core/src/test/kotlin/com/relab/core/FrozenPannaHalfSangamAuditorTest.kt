package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FrozenPannaHalfSangamAuditorTest {
    private val frozen = PredictionSnapshot(
        market = Market.SRIDEVI, sessionId = "main",
        targetDate = LocalDate.of(2026, 1, 2),
        createdAt = Instant.parse("2026-01-01T10:00:00Z"),
        historyThroughDate = LocalDate.of(2026, 1, 1),
        modelVersion = "test-v1", payloadJson = """{"frozen":true}"""
    )
    private fun candidate(panna: String, score: Double) = ReverseEngineeringCandidate(
        panna, panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString(""),
        ChartMath.pannaType(panna), panna.sumOf { it.digitToInt() } % 10, score,
        score, 0.0, 0.0, 0.0, 0.0, 0.0, null, null, null, null
    )
    private fun outcome(result: String = "111-36-222", key: String = frozen.snapshotKey) =
        PredictionSnapshotOutcome(key, result, Instant.parse("2026-01-02T12:00:00Z"), "PUBLISHED")

    @Test fun ranksPannaAndBothHalfSangamDirectionsFromFrozenCandidateLists() {
        val open = listOf(candidate("111", 100.0), candidate("123", 50.0))
        val close = listOf(candidate("222", 100.0), candidate("234", 50.0))
        val report = FrozenPannaHalfSangamAuditor.analyze(frozen, outcome(), open, close)
        assertEquals(1, report.openPannaExactRank)
        assertEquals(1, report.closePannaExactRank)
        assertEquals(1, report.openPannaCloseAnkExactRank)
        assertEquals(1, report.openAnkClosePannaExactRank)
        assertTrue(report.openPannaExactTop10)
        assertTrue(report.openPannaCloseAnkExactTop100)
    }

    @Test fun reportsMissesAsNullRanksNotHits() {
        val report = FrozenPannaHalfSangamAuditor.analyze(
            frozen, outcome(),
            listOf(candidate("345", 100.0)), listOf(candidate("456", 100.0))
        )
        assertNull(report.openPannaExactRank)
        assertNull(report.closePannaExactRank)
        assertNull(report.openPannaCloseAnkExactRank)
        assertNull(report.openAnkClosePannaExactRank)
        assertFalse(report.openPannaExactTop10)
    }

    @Test fun rejectsMismatchedSnapshotAndMalformedOutcome() {
        try {
            FrozenPannaHalfSangamAuditor.analyze(frozen, outcome(key = "other"), emptyList(), emptyList())
            fail("Snapshot key mismatch should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("key"))
        }
        try {
            FrozenPannaHalfSangamAuditor.analyze(frozen, outcome("bad"), emptyList(), emptyList())
            fail("Malformed result should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("format"))
        }
    }
}
