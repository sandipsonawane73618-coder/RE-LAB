package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ChartMathTest {
    private fun record(open: String, jodi: String, close: String) = ChartRecord(
        Market.SRIDEVI, LocalDate.of(2026, 11, 17), open, jodi, close, "test-source"
    )

    @Test fun detectsAllNineMarketsFromExactSourcePaths() {
        val base = "https://sattamatkajodi.com"
        assertEquals(Market.SRIDEVI, Market.fromSourceUrl("$base/record/sridevi-satta-penal-chart.php"))
        assertEquals(Market.SRIDEVI_NIGHT, Market.fromSourceUrl("$base/record/sridevi-night-satta-penal-chart.php?month=11"))
        assertEquals(Market.KALYAN, Market.fromSourceUrl("$base/record/kalyan-penal-chart.php"))
        assertEquals(Market.TIME_BAZAR, Market.fromSourceUrl("$base/time-bazar-panel-chart.php"))
        assertEquals(Market.MAIN_BAZAR, Market.fromSourceUrl("$base/main-bazar-panel-chart.php"))
        assertEquals(Market.MILAN_DAY, Market.fromSourceUrl("$base/record/milan-day-penal-chart.php"))
        assertEquals(Market.MILAN_NIGHT, Market.fromSourceUrl("$base/record/milan-night-penal-chart.php"))
        assertEquals(Market.RAJDHANI_DAY, Market.fromSourceUrl("$base/record/rajdhani-day-penal-chart.php"))
        assertEquals(Market.RAJDHANI_NIGHT, Market.fromSourceUrl("$base/record/rajdhani-night-penal-chart.php"))
        assertNull(Market.fromSourceUrl("$base/unknown-chart.php"))
    }

    @Test fun digitFamiliesMatchLockedMapping() {
        assertEquals(listOf(0, 1, 2, 3, 4, 0, 1, 2, 3, 4), (0..9).map(ChartMath::family))
    }

    @Test fun preservesLeadingZeroJodi() {
        val result = ChartMath.validate(record("578", "07", "368"))
        assertTrue(result is ValidationResult.Valid)
    }

    @Test fun rejectsIncorrectJodi() {
        val result = ChartMath.validate(record("578", "08", "368"))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test fun identifiesSpDpTp() {
        assertEquals(PannaType.SP, ChartMath.pannaType("278"))
        assertEquals(PannaType.DP, ChartMath.pannaType("277"))
        assertEquals(PannaType.TP, ChartMath.pannaType("777"))
    }

    @Test fun derivesFamilySequencesAndJodiFamilies() {
        val result = ChartMath.validate(record("578", "07", "368")) as ValidationResult.Valid
        assertEquals(listOf(0, 2, 3), result.features.openFamilySequence)
        assertEquals(listOf(3, 1, 3), result.features.closeFamilySequence)
        assertEquals(0, result.features.openJodiFamily)
        assertEquals(2, result.features.closeJodiFamily)
    }

    @Test fun rejectsMalformedPanna() {
        val result = ChartMath.validate(record("57", "07", "368"))
        assertTrue(result is ValidationResult.Invalid)
    }

    @Test fun currentMarketSchedulesKeepWeekdaysSeparateFromPannaMath() {
        assertFalse(java.time.DayOfWeek.SUNDAY in Market.KALYAN.activeDays)
        assertTrue(java.time.DayOfWeek.SUNDAY in Market.SRIDEVI.activeDays)
        // Mathematical validation remains independent of calendar history.
        val sundayRecord = ChartRecord(Market.KALYAN, LocalDate.of(2026, 11, 15), "578", "07", "368", "test")
        assertTrue(ChartMath.validate(sundayRecord) is ValidationResult.Valid)
    }
}
