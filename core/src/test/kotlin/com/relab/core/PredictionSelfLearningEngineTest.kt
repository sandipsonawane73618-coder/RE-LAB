package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PredictionSelfLearningEngineTest {
    private val created = Instant.parse("2026-01-01T10:00:00Z")

    private fun candidate(panna: String, score: Double) = ReverseEngineeringCandidate(
        panna, panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString(""),
        ChartMath.pannaType(panna), panna.sumOf { it.digitToInt() } % 10, score,
        0.1, 0.2, 0.3, 0.4, 0.5, 0.6, null, null, null, null
    )

    private fun snapshot(market: Market, session: String) = PredictionSnapshot(
        market, session, LocalDate.of(2026, 1, 2), created,
        LocalDate.of(2026, 1, 1), "test-v1", """{"frozen":true}"""
    )

    private fun audit(market: Market, session: String): FrozenSangamOutcomeAudit {
        val snap = snapshot(market, session)
        val outcome = PredictionSnapshotOutcome(
            snap.snapshotKey, "111-36-222",
            Instant.parse("2026-01-02T12:00:00Z"), "PUBLISHED"
        )
        return FrozenSangamOutcomeAuditor.analyze(
            snap, outcome,
            openCandidates = listOf(candidate("222", 90.0), candidate("345", 70.0)),
            closeCandidates = listOf(candidate("111", 90.0), candidate("456", 70.0)),
            pairLimit = 100,
            jodiCandidates = listOf(ReverseEngineeringJodiCandidate("81", 90.0, 1))
        )
    }

    @Test
    fun learnsReciprocalSideSwapAndJodiFamilyMovementWithoutCallingThemExactHits() {
        val report = PredictionSelfLearningEngine.analyze(
            listOf(audit(Market.SRIDEVI, "main"))
        ).single()

        assertEquals(1, report.auditedOutcomes)
        assertEquals(0, report.openExactHits)
        assertEquals(0, report.closeExactHits)
        assertEquals(1, report.reciprocalOpenCloseSwapHints)
        assertEquals(0, report.jodiExactHits)
        assertEquals(1, report.jodiFamilyMovementOnlyHits)
        assertEquals("OBSERVE_ONLY", report.sampleStatus)
    }

    @Test
    fun keepsOpenCloseAndJodiFamilyMovementInTheirOwnChannels() {
        val snap = snapshot(Market.SRIDEVI, "main")
        val outcome = PredictionSnapshotOutcome(
            snap.snapshotKey, "111-36-222",
            Instant.parse("2026-01-02T12:00:00Z"), "PUBLISHED"
        )
        val frozenAudit = FrozenSangamOutcomeAuditor.analyze(
            snap, outcome,
            openCandidates = listOf(candidate("666", 90.0)),
            closeCandidates = listOf(candidate("777", 90.0)),
            pairLimit = 100,
            jodiCandidates = listOf(ReverseEngineeringJodiCandidate("81", 90.0, 1))
        )
        val feedback = PredictionSelfLearningEngine.analyze(listOf(frozenAudit)).single()

        assertEquals(0, feedback.openExactHits)
        assertEquals(1, feedback.openFamilyMovementHits)
        assertEquals(0, feedback.closeExactHits)
        assertEquals(1, feedback.closeFamilyMovementHits)
        assertEquals(0, feedback.jodiExactHits)
        assertEquals(1, feedback.jodiFamilyMovementOnlyHits)
        assertEquals(0, feedback.reciprocalOpenCloseSwapHints)
    }

    @Test
    fun keepsFeedbackSeparateByMarketAndSession() {
        val reports = PredictionSelfLearningEngine.analyze(
            listOf(
                audit(Market.SRIDEVI, "day"),
                audit(Market.KALYAN, "night"),
                audit(Market.SRIDEVI, "day")
            )
        )

        assertEquals(2, reports.size)
        assertEquals(2, reports.first { it.market == Market.SRIDEVI }.auditedOutcomes)
        assertEquals(1, reports.first { it.market == Market.KALYAN }.auditedOutcomes)
    }

    @Test
    fun emptyFeedbackDoesNotInventLearningSignals() {
        assertTrue(PredictionSelfLearningEngine.analyze(emptyList()).isEmpty())
    }
}
