package com.relab.core

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PatternDriftDetectorTest {
    private val market = Market.SRIDEVI
    private val channel = PatternEvidenceChannel.OPEN_PANNA
    private val mode = PatternOutcomeMode.EXACT
    private val asOf = Instant.parse("2026-11-22T00:00:00Z")

    private fun rows(recentHits: Int, totalPerWindow: Int = 20): List<PatternDriftObservation> {
        return (0 until totalPerWindow * 2).map { index ->
            val recent = index >= totalPerWindow
            val hit = if (recent) index - totalPerWindow < recentHits else index < totalPerWindow
            val date = LocalDate.of(2026, 10, 1).plusDays(index.toLong())
            PatternDriftObservation(
                "obs-$index", market, "main", channel, mode, "111>222",
                date, date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), hit
            )
        }
    }

    @Test fun detectsConfirmedDegradationUsingDisjointWindows() {
        val report = PatternDriftDetector.evaluate(
            rows(recentHits = 0), market, "main", channel, mode, "111>222", asOf,
            baselineWindow = 20, recentWindow = 20, minimumWindowSupport = 10
        )
        assertEquals(PatternDriftStatus.DEGRADING, report.status)
        assertEquals(20, report.baselineSupport)
        assertEquals(20, report.recentSupport)
    }

    @Test fun overlappingIntervalsAreNotCalledDrift() {
        val report = PatternDriftDetector.evaluate(
            rows(recentHits = 18), market, "main", channel, mode, "111>222", asOf,
            baselineWindow = 20, recentWindow = 20, minimumWindowSupport = 10
        )
        assertEquals(PatternDriftStatus.NO_CONFIRMED_DRIFT, report.status)
    }

    @Test fun insufficientEvidenceAbstains() {
        val report = PatternDriftDetector.evaluate(
            rows(recentHits = 5).take(12), market, "main", channel, mode, "111>222", asOf,
            baselineWindow = 20, recentWindow = 10, minimumWindowSupport = 10
        )
        assertEquals(PatternDriftStatus.INSUFFICIENT_EVIDENCE, report.status)
    }

    @Test fun excludesUnpublishedFutureAndOtherScopes() {
        val valid = rows(recentHits = 0)
        val future = valid.first().copy(
            observationId = "future", targetDate = LocalDate.of(2026, 11, 22),
            outcomePublishedAt = Instant.parse("2026-11-23T00:00:00Z")
        )
        val other = valid.first().copy(
            observationId = "other", market = Market.KALYAN
        )
        val report = PatternDriftDetector.evaluate(
            valid + future + other, market, "main", channel, mode, "111>222", asOf,
            baselineWindow = 20, recentWindow = 20, minimumWindowSupport = 10
        )
        assertEquals(20, report.baselineSupport)
        assertEquals(20, report.recentSupport)
        assertEquals(PatternDriftStatus.DEGRADING, report.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateObservationIdsInScope() {
        val row = rows(0).first()
        PatternDriftDetector.evaluate(
            listOf(row, row), market, "main", channel, mode, "111>222", asOf
        )
    }
}
