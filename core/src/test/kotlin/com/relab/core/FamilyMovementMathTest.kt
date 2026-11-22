package com.relab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FamilyMovementMathTest {
    @Test fun jodiFamilyMovementIncludesSwapAndReverseForms() {
        assertEquals(listOf("17", "62", "71", "26"), FamilyMovementMath.variants("17"))
    }

    @Test fun pannaFamilyMovementAppliesToThreeDigitResultsToo() {
        assertEquals(listOf("123", "678", "321", "876"), FamilyMovementMath.variants("123"))
    }

    @Test fun allMembersShareOneStableMovementKey() {
        val expected = FamilyMovementMath.key("17")
        listOf("17", "62", "71", "26").forEach { value ->
            assertEquals(expected, FamilyMovementMath.key(value))
        }
        assertNotEquals(expected, FamilyMovementMath.key("18"))
    }

    @Test fun leadingZeroesArePreserved() {
        assertEquals(listOf("07", "52", "70", "25"), FamilyMovementMath.variants("07"))
    }

    @Test fun rejectsUnsupportedOrMalformedValues() {
        listOf("", "1", "1234", "1x", " 17").forEach { value ->
            try {
                FamilyMovementMath.variants(value)
                throw AssertionError("Expected invalid value to be rejected: '$value'")
            } catch (_: IllegalArgumentException) {
                // Expected validation failure.
            }
        }
    }
}
