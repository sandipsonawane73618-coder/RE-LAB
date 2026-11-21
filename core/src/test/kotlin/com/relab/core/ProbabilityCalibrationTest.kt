package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class ProbabilityCalibrationTest {
    @Test
    fun computesBrierLogLossAndCalibrationForPerfectPredictions() {
        val report = ProbabilityCalibration.evaluate(
            probabilities = listOf(0.0, 0.0, 1.0, 1.0),
            outcomes = listOf(0, 0, 1, 1),
            binCount = 2
        )
        assertEquals(4, report.samples)
        assertEquals(0.0, report.brierScore, 1e-12)
        assertEquals(0.0, report.expectedCalibrationError, 1e-12)
        assertTrue(report.logLoss >= 0.0 && report.logLoss.isFinite())
    }

    @Test
    fun detectsMisCalibrationAndKeepsBinsWeighted() {
        val report = ProbabilityCalibration.evaluate(
            probabilities = listOf(0.8, 0.8, 0.2, 0.2),
            outcomes = listOf(0, 0, 1, 1),
            binCount = 2
        )
        assertEquals(0.64, report.brierScore, 1e-12)
        assertEquals(0.6, report.expectedCalibrationError, 1e-12)
        assertEquals(4, report.bins.sumOf { it.sampleCount })
    }

    @Test
    fun probabilityOneAndZeroAreHandledWithoutInfiniteLogLoss() {
        val report = ProbabilityCalibration.evaluate(
            probabilities = listOf(0.0, 1.0),
            outcomes = listOf(1, 0),
            binCount = 2
        )
        assertTrue(report.logLoss.isFinite())
        assertTrue(report.logLoss > 0.0)
    }

    @Test
    fun rejectsInvalidDataAndParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            ProbabilityCalibration.evaluate(emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbabilityCalibration.evaluate(listOf(0.5), listOf(1, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbabilityCalibration.evaluate(listOf(Double.NaN), listOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbabilityCalibration.evaluate(listOf(1.1), listOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbabilityCalibration.evaluate(listOf(0.5), listOf(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProbabilityCalibration.evaluate(listOf(0.5), listOf(0), binCount = 0)
        }
    }
}
