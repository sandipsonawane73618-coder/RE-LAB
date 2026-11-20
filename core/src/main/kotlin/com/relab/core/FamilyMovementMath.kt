package com.relab.core

/**
 * Digit-family movement equivalence observed in market results.
 *
 * Each digit is paired with the digit five steps away (0<->5, 1<->6, ...).
 * A result may also appear in reversed order. These are candidate equivalences,
 * not claims that any member is more likely than another.
 */
object FamilyMovementMath {
    fun variants(value: String): List<String> {
        require(value.length in 2..3 && value.all(Char::isDigit)) {
            "Family movement values must contain two or three digits"
        }
        val familySwap = value.map { digit ->
            ((digit.digitToInt() + 5) % 10).toString()
        }.joinToString("")
        val reversed = value.reversed()
        val reversedFamilySwap = familySwap.reversed()
        return listOf(value, familySwap, reversed, reversedFamilySwap).distinct()
    }

    /** Stable equivalence-class key, including family substitution and reversal. */
    fun key(value: String): String = variants(value).minOrNull()!!
}
