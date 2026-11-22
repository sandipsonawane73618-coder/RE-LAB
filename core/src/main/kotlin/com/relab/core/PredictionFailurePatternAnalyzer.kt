package com.relab.core

/** Aggregates deterministic failure evidence across independent outcome audit snapshots. */
data class EvidenceLayerFailureFrequency(
    val layer: String,
    val failedComparisons: Int,
    val totalComparisons: Int,
    val failureRate: Double
)

data class PredictionFailurePatternReport(
    val auditCount: Int,
    val failedAuditCount: Int,
    val layerFrequencies: List<EvidenceLayerFailureFrequency>,
    val mostFrequentWeakLayers: List<String>,
    val sampleWarning: String
)

object PredictionFailurePatternAnalyzer {
    fun analyze(audits: List<PredictionOutcomeAuditReport>): PredictionFailurePatternReport {
        val failedAudits = audits.filterNot { it.allHit }
        val layerTotals = linkedMapOf<String, Pair<Int, Int>>()
        failedAudits.forEach { audit ->
            listOf("OPEN" to audit.open, "CLOSE" to audit.close).forEach { (side, result) ->
                if (!result.hitWithinTopLimit) {
                    result.evidenceComparisons.forEach { comparison ->
                        val old = layerTotals[comparison.layer] ?: (0 to 0)
                        layerTotals[comparison.layer] = (old.first + if (comparison.weakerThanLeader) 1 else 0) to (old.second + 1)
                    }
                }
            }
        }
        val frequencies = layerTotals.map { (layer, counts) ->
            EvidenceLayerFailureFrequency(layer, counts.first, counts.second,
                if (counts.second == 0) 0.0 else counts.first.toDouble() / counts.second)
        }.sortedWith(compareByDescending<EvidenceLayerFailureFrequency> { it.failureRate }
            .thenByDescending { it.failedComparisons }.thenBy { it.layer })
        val weakLayers = frequencies.filter { it.failedComparisons > 0 }.map { it.layer }
        val warning = when {
            failedAudits.isEmpty() -> "No failed audits in this sample; failure-layer analysis is not applicable."
            failedAudits.size < 10 -> "Small sample (${failedAudits.size} failed audits); frequencies are descriptive only."
            else -> "Observational correlations only; this does not establish causation or automatically change model weights."
        }
        return PredictionFailurePatternReport(audits.size, failedAudits.size, frequencies, weakLayers, warning)
    }
}
