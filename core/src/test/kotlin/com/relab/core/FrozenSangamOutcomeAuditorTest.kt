package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FrozenSangamOutcomeAuditorTest {
    private val created = Instant.parse("2026-01-01T10:00:00Z")

    private fun snapshot() = PredictionSnapshot(
        market = Market.SRIDEVI,
        sessionId = "main",
        targetDate = LocalDate.of(2026, 1, 2),
        createdAt = created,
        historyThroughDate = LocalDate.of(2026, 1, 1),
        modelVersion = "test-v1",
        payloadJson = """{"frozen":true}"""
    )

    private fun candidate(panna: String, score: Double) = ReverseEngineeringCandidate(
        panna = panna,
        familySequence = panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString(""),
        pannaType = ChartMath.pannaType(panna),
        ank = panna.sumOf { it.digitToInt() } % 10,
        score = score,
        exactPannaEvidence = score,
        familyEvidence = 0.0,
        ankEvidence = 0.0,
        positionEvidence = 0.0,
        pannaTypeEvidence = 0.0,
        reversePannaEvidence = 0.0,
        weekdayEvidence = null,
        previousTransitionEvidence = null,
        openCloseConditionalEvidence = null,
        crossMarketConditionalEvidence = null
    )

    private fun outcome(value: String = "111-36-222", key: String = snapshot().snapshotKey) =
        PredictionSnapshotOutcome(
            snapshotKey = key,
            actualResult = value,
            publishedAt = Instant.parse("2026-01-02T12:00:00Z"),
            sourceStatus = "PUBLISHED"
        )

    @Test
    fun auditsPublishedResultAgainstFrozenCandidatesAndKeepsExactSeparate() {
        val report = FrozenSangamOutcomeAuditor.analyze(
            snapshot(), outcome(),
            listOf(candidate("111", 90.0), candidate("123", 80.0)),
            listOf(candidate("222", 90.0), candidate("234", 80.0))
        )
        assertEquals(snapshot().snapshotKey, report.snapshotKey)
        assertEquals("test-v1", report.modelVersion)
        assertEquals("111", report.audit.actualOpenPanna)
        assertEquals("222", report.audit.actualClosePanna)
        assertTrue(report.audit.exactPairHit)
        assertEquals(1, report.audit.exactPairRank)
        assertEquals(1, report.openPannaAudit.exactRank)
        assertEquals(1, report.closePannaAudit.exactRank)
        assertTrue(report.openPannaAudit.nearestCandidates.first().exactHit)
        assertTrue(report.closePannaAudit.nearestCandidates.first().exactHit)
    }

    @Test
    fun rejectsOutcomeForDifferentSnapshot() {
        try {
            FrozenSangamOutcomeAuditor.analyze(
                snapshot(), outcome(key = "KALYAN|main|2026-01-02"),
                listOf(candidate("111", 90.0)), listOf(candidate("222", 90.0))
            )
            fail("Mismatched snapshot keys must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not match"))
        }
    }

    @Test
    fun rejectsOutcomePublishedBeforePredictionWasFrozen() {
        val early = outcome().copy(publishedAt = Instant.parse("2026-01-01T09:00:00Z"))
        try {
            FrozenSangamOutcomeAuditor.analyze(
                snapshot(), early,
                listOf(candidate("111", 90.0)), listOf(candidate("222", 90.0))
            )
            fail("Outcome cannot predate prediction")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("precede"))
        }
    }

    @Test
    fun rejectsJodiInconsistentWithPublishedPannas() {
        try {
            FrozenSangamOutcomeAuditor.analyze(
                snapshot(), outcome(value = "111-99-222"),
                listOf(candidate("111", 90.0)), listOf(candidate("222", 90.0))
            )
            fail("Jodi must reconcile with panna sums")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not match"))
        }
    }

    @Test
    fun rejectsMalformedPublishedResult() {
        try {
            FrozenSangamOutcomeAuditor.analyze(
                snapshot(), outcome(value = "11-36-222"),
                listOf(candidate("111", 90.0)), listOf(candidate("222", 90.0))
            )
            fail("Malformed result must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("format"))
        }
    }
}
