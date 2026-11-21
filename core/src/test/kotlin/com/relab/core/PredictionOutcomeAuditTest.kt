package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class PredictionOutcomeAuditTest {
    private fun candidate(panna: String, score: Double) = ReverseEngineeringCandidate(
        panna = panna,
        familySequence = panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString(""),
        pannaType = ChartMath.pannaType(panna),
        ank = panna.sumOf { it.digitToInt() } % 10,
        score = score,
        exactPannaEvidence = 0.01,
        familyEvidence = 0.02,
        ankEvidence = 0.03,
        positionEvidence = 0.04,
        pannaTypeEvidence = 0.05,
        reversePannaEvidence = 0.06,
        weekdayEvidence = null,
        previousTransitionEvidence = null,
        openCloseConditionalEvidence = null,
        crossMarketConditionalEvidence = null
    )

    @Test
    fun reportsHitAndFailureWithActualRanksAndScores() {
        val report = PredictionOutcomeAudit.analyze(
            actualOpenPanna = "123",
            actualClosePanna = "789",
            actualJodi = "45",
            openCandidates = listOf(candidate("123", 91.0), candidate("456", 80.0)),
            closeCandidates = listOf(candidate("111", 92.0), candidate("789", 70.0)),
            jodiCandidates = listOf(
                ReverseEngineeringJodiCandidate("12", 90.0, 2),
                ReverseEngineeringJodiCandidate("45", 75.0, 1)
            ),
            topLimit = 1
        )

        assertTrue(report.open.hitWithinTopLimit)
        assertEquals(1, report.open.actualRank)
        assertEquals(91.0, report.open.actualScore)
        assertFalse(report.close.hitWithinTopLimit)
        assertEquals(2, report.close.actualRank)
        assertFalse(report.jodi.hitWithinTopLimit)
        assertEquals(2, report.jodi.actualRank)
        assertEquals(listOf("CLOSE", "JODI"), report.failedSides)
        assertTrue(report.compactAnalysis().contains("Actual 789 ranked #2"))
    }

    @Test
    fun comparesActualCandidateEvidenceLayersAgainstPredictionLeader() {
        val actual = candidate("123", 80.0).copy(
            exactPannaEvidence = 0.01,
            familyEvidence = 0.08,
            ankEvidence = 0.03,
            positionEvidence = 0.02,
            weekdayEvidence = null
        )
        val leader = candidate("456", 95.0).copy(
            exactPannaEvidence = 0.05,
            familyEvidence = 0.04,
            ankEvidence = 0.03,
            positionEvidence = 0.06,
            weekdayEvidence = 0.07
        )
        val report = PredictionOutcomeAudit.analyze(
            actualOpenPanna = "123",
            actualClosePanna = "456",
            actualJodi = "12",
            openCandidates = listOf(leader, actual),
            closeCandidates = listOf(leader),
            jodiCandidates = listOf(ReverseEngineeringJodiCandidate("12", 90.0, 1)),
            topLimit = 1
        )

        assertEquals(2, report.open.actualRank)
        assertTrue(report.open.weakerEvidenceLayers.contains("Exact panna recurrence"))
        assertTrue(report.open.weakerEvidenceLayers.contains("Digit-position distribution"))
        assertFalse(report.open.evidenceComparisons.any { it.layer == "Target weekday" })
        assertTrue(report.compactAnalysis().contains("evidence weaker than leader"))
        assertTrue(report.compactAnalysis().contains("not causal proof"))
    }

    @Test
    fun reportsActualOutsideCandidateWindowWithoutInventingRank() {
        val report = PredictionOutcomeAudit.analyze(
            actualOpenPanna = "999",
            actualClosePanna = "888",
            actualJodi = "77",
            openCandidates = listOf(candidate("123", 80.0)),
            closeCandidates = emptyList(),
            jodiCandidates = emptyList()
        )

        assertEquals(null, report.open.actualRank)
        assertEquals(null, report.open.actualScore)
        assertFalse(report.open.hitWithinTopLimit)
        assertEquals(null, report.close.topCandidate)
        assertEquals(null, report.jodi.topCandidate)
        assertTrue(report.compactAnalysis().contains("outside the supplied candidate window"))
    }

    @Test
    fun rejectsMalformedActualResultsAndInvalidTopLimit() {
        val open = listOf(candidate("123", 80.0))
        val close = listOf(candidate("456", 70.0))
        val jodi = listOf(ReverseEngineeringJodiCandidate("12", 60.0, 1))
        try {
            PredictionOutcomeAudit.analyze("12", "456", "12", open, close, jodi)
            fail("Malformed actual result must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            PredictionOutcomeAudit.analyze("123", "456", "12", open, close, jodi, topLimit = 0)
            fail("Invalid top limit must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
