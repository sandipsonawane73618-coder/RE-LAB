package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class RandomnessChallengeTest {
    @Test
    fun alignedPredictionsBeatShuffledOutcomes() {
        val actuals = (0 until 60).map { (it % 3).toString() }
        val result = RandomnessChallenge.evaluate(
            predictions = actuals,
            actuals = actuals,
            permutations = 2_000,
            seed = 42L
        )
        assertEquals(60, result.observations)
        assertEquals(60, result.observedHits)
        assertTrue(result.expectedRandomHits < 30.0)
        assertTrue(result.pValue <= 0.01)
    }

    @Test
    fun permutationResultIsReproducibleForSameSeed() {
        val predictions = (0 until 40).map { (it % 4).toString() }
        val actuals = (0 until 40).map { ((it * 3 + 1) % 4).toString() }
        val first = RandomnessChallenge.evaluate(predictions, actuals, 500, 99L)
        val second = RandomnessChallenge.evaluate(predictions, actuals, 500, 99L)
        assertEquals(first, second)
    }

    @Test
    fun shuffledNullPreservesObservedOutcomeDistribution() {
        val predictions = List(40) { "0" }
        val actuals = List(10) { "0" } + List(30) { "1" }
        val result = RandomnessChallenge.evaluate(predictions, actuals, 1_000, 7L)
        assertEquals(10, result.observedHits)
        assertEquals(10.0, result.expectedRandomHits, 0.8)
        assertTrue(result.pValue > 0.05)
    }

    @Test
    fun rejectsMismatchedInputsAndInvalidPermutationCount() {
        assertThrows(IllegalArgumentException::class.java) {
            RandomnessChallenge.evaluate(listOf("1"), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            RandomnessChallenge.evaluate(listOf("1"), listOf("1"), permutations = 99)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RandomnessChallenge.evaluate(listOf(" "), listOf("1"))
        }
    }

    @Test
    fun benjaminiHochbergAdjustsAndPreservesOriginalKeys() {
        val adjusted = FalseDiscoveryRate.benjaminiHochberg(
            linkedMapOf("a" to 0.01, "b" to 0.04, "c" to 0.03, "not-tested" to null)
        )
        assertEquals(setOf("a", "b", "c"), adjusted.keys)
        assertEquals(0.03, adjusted.getValue("a"), 1e-12)
        assertEquals(0.04, adjusted.getValue("b"), 1e-12)
        assertEquals(0.045, adjusted.getValue("c"), 1e-12)
        assertTrue(adjusted.values.all { it in 0.0..1.0 })
    }

    @Test
    fun falseDiscoveryCorrectionRejectsInvalidPValues() {
        assertThrows(IllegalArgumentException::class.java) {
            FalseDiscoveryRate.benjaminiHochberg(mapOf("bad" to 1.2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FalseDiscoveryRate.benjaminiHochberg(mapOf("bad" to Double.NaN))
        }
    }

    @Test
    fun emptyAndNullOnlyFamiliesProduceNoAdjustedValues() {
        assertTrue(FalseDiscoveryRate.benjaminiHochberg(emptyMap()).isEmpty())
        assertTrue(FalseDiscoveryRate.benjaminiHochberg(mapOf("x" to null)).isEmpty())
    }
}
