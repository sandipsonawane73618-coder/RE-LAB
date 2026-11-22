package com.relab.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WalkForwardProbabilityCalibratorTest {
    private val market = Market.KALYAN
    private val channel = PatternEvidenceChannel.OPEN_PANNA
    private val mode = PatternOutcomeMode.EXACT

    private fun forecast(
        id: String,
        target: String,
        probability: Double = 0.75,
        outcome: Int? = 1,
        published: String? = null,
        market: Market = this.market,
        session: String = "main",
        channel: PatternEvidenceChannel = this.channel,
        mode: PatternOutcomeMode = this.mode,
        history: String = LocalDate.parse(target).minusDays(1).toString(),
        created: String = LocalDate.parse(target).minusDays(1).toString()
    ) = CalibrationForecast(
        id, market, session, channel, mode, LocalDate.parse(target),
        Instant.parse("${created}T08:00:00Z"), LocalDate.parse(history), probability,
        outcome, published?.let(Instant::parse)
    )

    @Test
    fun calibratesHeldOutForecastOnlyFromEarlierPublishedSameScopeResults() {
        val training = (1..20).map { day ->
            val date = LocalDate.of(2026, 1, day)
            val target = date.toString()
            val published = date.plusDays(1).toString() + "T08:00:00Z"
            forecast("train-$day", target, 0.75, if (day <= 10) 1 else 0, published)
        }
        val heldOut = forecast(
            "test-1", "2026-02-01", 0.75, 1, "2026-02-02T08:00:00Z",
            created = "2026-01-31"
        )
        val report = WalkForwardProbabilityCalibrator.evaluate(
            training + heldOut, market, "main", channel, mode,
            LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-02"),
            binCount = 4, minimumCalibrationSamples = 20
        )
        assertEquals(1, report.evaluatedForecasts)
        assertEquals(1, report.calibratedForecasts)
        assertEquals(0.5, report.forecasts.single().calibratedProbability!!, 1e-12)
        assertEquals(20, report.forecasts.single().calibrationSupport)
        assertNotNull(report.calibratedMetrics)
    }

    @Test
    fun excludesSameDateFutureAndUnpublishedEvidenceAndOtherScopes() {
        val prior = (1..20).map { day ->
            val date = LocalDate.of(2026, 1, day)
            forecast(
                "prior-$day", date.toString(), 0.75, 1,
                date.plusDays(1).toString() + "T08:00:00Z"
            )
        }
        val target = forecast(
            "target", "2026-02-01", 0.75, 1, "2026-02-02T08:00:00Z",
            created = "2026-01-31"
        )
        val latePublished = forecast(
            "late", "2026-01-25", 0.75, 1, "2026-02-01T09:00:00Z",
            created = "2026-01-24"
        )
        val otherMarket = forecast(
            "other-market", "2026-01-20", 0.75, 1, "2026-01-21T08:00:00Z",
            market = Market.MILAN_DAY
        )
        val report = WalkForwardProbabilityCalibrator.evaluate(
            prior + latePublished + otherMarket + target, market, "main", channel, mode,
            LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-02"),
            binCount = 4, minimumCalibrationSamples = 20
        )
        assertEquals(0, report.forecasts.single().calibrationSupport)
        assertTrue(report.forecasts.single().abstained)
        assertEquals(0, report.calibratedForecasts)
        assertNull(report.calibratedMetrics)
    }

    @Test
    fun abstainsWhenCalibrationSupportIsInsufficient() {
        val prior = (1..5).map { day ->
            val date = LocalDate.of(2026, 1, day)
            forecast("small-$day", date.toString(), 0.65,
                1, date.plusDays(1).toString() + "T08:00:00Z")
        }
        val target = forecast(
            "target-small", "2026-02-01", 0.65, 0,
            "2026-02-02T08:00:00Z", created = "2026-01-31"
        )
        val report = WalkForwardProbabilityCalibrator.evaluate(
            prior + target, market, "main", channel, mode,
            LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-02"),
            binCount = 4, minimumCalibrationSamples = 6
        )
        assertEquals(5, report.forecasts.single().calibrationSupport)
        assertEquals(1, report.abstentions)
        assertNull(report.calibratedMetrics)
    }

    @Test
    fun rejectsForecastsWithFutureHistoryOrInvalidOutcomePublication() {
        assertThrows(IllegalArgumentException::class.java) {
            forecast("future-history", "2026-02-01", history = "2026-02-01")
        }
        assertThrows(IllegalArgumentException::class.java) {
            forecast("early-result", "2026-02-01", outcome = 1, published = "2026-02-01T08:00:00Z")
        }
        assertThrows(IllegalArgumentException::class.java) {
            forecast("invalid-outcome", "2026-02-01", outcome = 2, published = "2026-02-02T08:00:00Z")
        }
    }

    @Test
    fun rejectsDuplicateForecastIdsAndInvalidEvaluationWindow() {
        val one = forecast("duplicate", "2026-01-01", 0.5, 0, "2026-01-02T08:00:00Z")
        assertThrows(IllegalArgumentException::class.java) {
            WalkForwardProbabilityCalibrator.evaluate(
                listOf(one, one), market, "main", channel, mode,
                LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-02")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalkForwardProbabilityCalibrator.evaluate(
                emptyList(), market, "main", channel, mode,
                LocalDate.parse("2026-02-02"), LocalDate.parse("2026-02-01")
            )
        }
    }
}
