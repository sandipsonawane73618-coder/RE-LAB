package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ChartRecordValidatorTest {
    private fun record(open: String, jodi: String, close: String) = ChartRecord(
        Market.SRIDEVI, LocalDate.of(2026, 11, 17), open, jodi, close, "test-source"
    )

    @Test fun validatesAllOneHundredOpenCloseAnkPairs() {
        for (openAnk in 0..9) {
            for (closeAnk in 0..9) {
                val open = pannaForAnk(openAnk)
                val close = pannaForAnk(closeAnk)
                val jodi = "$openAnk$closeAnk"
                assertTrue(
                    "Expected valid pair $openAnk$closeAnk",
                    ChartRecordValidator.validate(record(open, jodi, close)) is ValidationResult.Valid
                )
            }
        }
    }

    @Test fun preservesLeadingZeroJodiAndPannaValues() {
        assertTrue(ChartRecordValidator.validate(record("005", "55", "014")) is ValidationResult.Valid)
        assertTrue(ChartRecordValidator.validate(record("001", "19", "009")) is ValidationResult.Valid)
    }

    @Test fun identifiesLikelySideSwapButDoesNotRewriteInput() {
        val input = record("368", "07", "578")
        val result = ChartRecordValidator.validate(input) as ValidationResult.Invalid
        assertTrue(result.reasons.single().contains("Possible Open/Close Panna swap"))
        assertEquals("368", input.openPanna)
        assertEquals("578", input.closePanna)
        assertEquals("07", input.jodi)
    }

    @Test fun reportsMalformedFieldsIndividually() {
        val issues = ChartRecordValidator.digitIssues(record("12x", "7", "４５６"))
        assertEquals(
            setOf(ChartDigitField.OPEN_PANNA, ChartDigitField.JODI, ChartDigitField.CLOSE_PANNA),
            issues.map { it.field }.toSet()
        )
    }

    @Test fun rejectsUnicodeNumeralsRatherThanSilentlyAcceptingThem() {
        val result = ChartRecordValidator.validate(record("１２３", "45", "456"))
        assertTrue(result is ValidationResult.Invalid)
    }

    private fun pannaForAnk(ank: Int): String {
        for (a in 0..9) for (b in 0..9) {
            val c = (ank - a - b).mod(10)
            return "$a$b$c"
        }
        error("No panna for ank $ank")
    }
}
