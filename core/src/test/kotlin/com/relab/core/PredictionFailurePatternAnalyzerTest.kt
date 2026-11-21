package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class PredictionFailurePatternAnalyzerTest {
    private fun side(hit: Boolean, comparisons: List<PredictionEvidenceLayerComparison> = emptyList()) =
        PredictionOutcomeSideAudit("actual", "candidate", 50.0, 2, 20.0, hit, 10,
            "fixture audit", comparisons)

    @Test
    fun aggregatesOnlyFailedPannaSideEvidenceAndWarnsSmallSample() {
        val failed = PredictionOutcomeAuditReport(
            side(false, listOf(
                PredictionEvidenceLayerComparison("Digit family", 4, 0.2, 0.7, true),
                PredictionEvidenceLayerComparison("Ank", 1, 0.5, 0.5, false)
            )),
            side(true),
            side(false)
        )
        val hit = PredictionOutcomeAuditReport(side(true), side(true), side(true))
        val report = PredictionFailurePatternAnalyzer.analyze(listOf(failed, hit))

        assertEquals(2, report.auditCount)
        assertEquals(1, report.failedAuditCount)
        assertEquals(listOf("Digit family"), report.mostFrequentWeakLayers)
        assertEquals(1, report.layerFrequencies.first().failedComparisons)
        assertEquals(1, report.layerFrequencies.first().totalComparisons)
        assertEquals(1.0, report.layerFrequencies.first().failureRate, 1e-9)
        assertTrue(report.sampleWarning.contains("Small sample"))
    }

    @Test
    fun emptyAuditSetIsHandledWithoutInventingEvidence() {
        val report = PredictionFailurePatternAnalyzer.analyze(emptyList())
        assertEquals(0, report.auditCount)
        assertEquals(0, report.failedAuditCount)
        assertTrue(report.layerFrequencies.isEmpty())
        assertTrue(report.sampleWarning.contains("No failed audits"))
    }
}
