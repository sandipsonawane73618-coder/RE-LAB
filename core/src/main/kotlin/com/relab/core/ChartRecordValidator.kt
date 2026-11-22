package com.relab.core

enum class ChartDigitField { OPEN_PANNA, JODI, CLOSE_PANNA }

data class ChartDigitIssue(
    val field: ChartDigitField,
    val message: String
)

object ChartRecordValidator {
    /**
     * Strict chart-format validation. Digits are ASCII 0-9 so imported Unicode numerals
     * cannot silently normalize differently across publishers or runtimes.
     */
    fun validate(record: ChartRecord): ValidationResult {
        val issues = digitIssues(record)
        if (issues.isNotEmpty()) return ValidationResult.Invalid(issues.map { it.message })
        return ChartMath.validate(record)
    }

    fun digitIssues(record: ChartRecord): List<ChartDigitIssue> = buildList {
        if (!isThreeAsciiDigits(record.openPanna)) {
            add(ChartDigitIssue(ChartDigitField.OPEN_PANNA, "Open Panna must contain exactly 3 ASCII digits (0-9)"))
        }
        if (!isTwoAsciiDigits(record.jodi)) {
            add(ChartDigitIssue(ChartDigitField.JODI, "Jodi must contain exactly 2 ASCII digits (0-9)"))
        }
        if (!isThreeAsciiDigits(record.closePanna)) {
            add(ChartDigitIssue(ChartDigitField.CLOSE_PANNA, "Close Panna must contain exactly 3 ASCII digits (0-9)"))
        }
    }

    private fun isThreeAsciiDigits(value: String): Boolean =
        value.length == 3 && value.all { it in '0'..'9' }

    private fun isTwoAsciiDigits(value: String): Boolean =
        value.length == 2 && value.all { it in '0'..'9' }
}
