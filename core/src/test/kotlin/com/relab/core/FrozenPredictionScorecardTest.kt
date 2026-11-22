package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FrozenPredictionScorecardTest {
    private val created = Instant.parse("2026-01-01T10:00:00Z")

    private fun snapshot(date: Int) = PredictionSnapshot(
        market = Market.SRIDEVI,
        sessionId = "main",
        targetDate = LocalDate.of(2026, 1, date),
        createdAt = created,
        historyThroughDate = LocalDate.of(2026, 1, 1),
        modelVersion = "scorecard-v1",
        payloadJson = """{"frozen":"$date"}"""
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

    private fun outcome(snapshot: PredictionSnapshot) = PredictionSnapshotOutcome(
        snapshotKey = snapshot.snapshotKey,
        actualResult = "111-36-222",
        publishedAt = Instant.parse("2026-01-02T12:00:00Z"),
        sourceStatus = "PUBLISHED"
    )

    private fun candidates() = listOf(candidate("111", 90.0), candidate("612", 80.0), candidate("129", 70.0)) to
        listOf(candidate("222", 90.0), candidate("722", 80.0), candidate("229", 70.0))

    private fun audit(snapshot: PredictionSnapshot): FrozenSangamOutcomeAudit {
        val (open, close) = candidates()
        return FrozenSangamOutcomeAuditor.analyze(snapshot, outcome(snapshot), open, close)
    }

    private fun pannaHalfAudit(snapshot: PredictionSnapshot): FrozenPannaHalfSangamAudit {
        val (open, close) = candidates()
        return FrozenPannaHalfSangamAuditor.analyze(snapshot, outcome(snapshot), open, close)
    }

    @Test
    fun aggregatesExactFamilyNearHalfSangamAndPendingSeparately() {
        val saved = listOf(snapshot(2), snapshot(3))
        val report = FrozenPredictionScorecardBuilder.build(
            saved, listOf(audit(saved.first())), listOf(pannaHalfAudit(saved.first()))
        ).single()

        assertEquals(2, report.savedSnapshots)
        assertEquals(1, report.auditedOutcomes)
        assertEquals(1, report.pendingOutcomes)
        assertEquals(1, report.openExactTop10)
        assertEquals(1, report.openExactTop100)
        assertEquals(1, report.closeExactTop10)
        assertEquals(1, report.sangamExactTop10)
        assertEquals(1, report.sangamExactTop100)
        assertEquals(1, report.openFamilyTop10)
        assertEquals(1, report.closeFamilyTop10)
        assertEquals(1, report.openPositionalNearTop10)
        assertEquals(1, report.closePositionalNearTop10)
        assertEquals(1, report.pannaHalfAuditedOutcomes)
        assertEquals(1, report.openPannaCloseAnkTop10)
        assertEquals(1, report.openAnkClosePannaTop10)
        assertEquals(1.0, report.rate(report.openExactTop10)!!, 0.0)
        assertEquals(1.0, report.halfSangamRate(report.openPannaCloseAnkTop10)!!, 0.0)
        val pendingOnly = FrozenPredictionScorecardBuilder.build(listOf(snapshot(4)), emptyList()).single()
        assertNull(pendingOnly.rate(0))
        assertNull(pendingOnly.halfSangamRate(0))
    }

    @Test
    fun rejectsAuditThatDoesNotBelongToProvidedFrozenSnapshot() {
        try {
            FrozenPredictionScorecardBuilder.build(listOf(snapshot(2)), listOf(audit(snapshot(3))))
            fail("Audit for an unknown snapshot must be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("does not belong"))
        }
    }

    @Test
    fun rejectsDuplicateSnapshotAndAuditKeys() {
        val frozen = snapshot(2)
        try {
            FrozenPredictionScorecardBuilder.build(listOf(frozen, frozen), emptyList())
            fail("Duplicate snapshots must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate frozen snapshot"))
        }
        try {
            val item = audit(frozen)
            FrozenPredictionScorecardBuilder.build(listOf(frozen), listOf(item, item))
            fail("Duplicate audits must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate frozen outcome"))
        }
    }

    @Test
    fun rejectsPannaHalfAuditForUnknownSnapshot() {
        try {
            FrozenPredictionScorecardBuilder.build(
                listOf(snapshot(2)), emptyList(), listOf(pannaHalfAudit(snapshot(3)))
            )
            fail("Panna/Half-Sangam audit for an unknown snapshot must be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("does not belong"))
        }
    }
}
