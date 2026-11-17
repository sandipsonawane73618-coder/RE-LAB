package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ChartMathTest {
    private fun record(open: String, jodi: String, close: String) = ChartRecord(
        Market.SRIDEVI, LocalDate.of(2026, 11, 17), open, jodi, close, "test-source"
    )

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

    @Test fun rejectsSundayKalyanRecord() {
        val sunday = ChartRecord(Market.KALYAN, LocalDate.of(2026, 11, 15), "578", "07", "368", "test")
        assertTrue(ChartMath.validate(sunday) is ValidationResult.Invalid)
    }
}
