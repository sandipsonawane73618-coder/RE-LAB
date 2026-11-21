package com.relab.core

import java.time.LocalDate

/**
 * Real-chart chronological validation restricted to multi-publisher consensus rows.
 * Single-source and conflicting rows are reported as excluded, never silently scored.
 */
data class ConsensusWalkForwardValidationReport(
    val targetDateExclusive: LocalDate,
    val minimumHistory: Int,
    val consensusRecordCount: Int,
    val conflictGroupCount: Int,
    val singleSourceUnverifiedGroupCount: Int,
    val noPublishedResultGroupCount: Int,
    val invalidGroupCount: Int,
    val marketSessionReports: List<SangamWalkForwardReport>
) {
    val requestedTargets: Int get() = marketSessionReports.sumOf { it.requestedTargets }
    val evaluatedTargets: Int get() = marketSessionReports.sumOf { it.evaluatedTargets }
    val abstainedTargets: Int get() = marketSessionReports.sumOf { it.abstainedTargets }
    val exactPairTop10Hits: Int get() = marketSessionReports.sumOf { it.exactPairTop10Hits }
    val exactPairTop100Hits: Int get() = marketSessionReports.sumOf { it.exactPairTop100Hits }
    val openFamilyTop10Hits: Int get() = marketSessionReports.sumOf { it.openFamilyTop10Hits }
    val closeFamilyTop10Hits: Int get() = marketSessionReports.sumOf { it.closeFamilyTop10Hits }
    val jodiDiagnosticMatches: Int get() = marketSessionReports.sumOf { it.jodiPairTop100Hits }

    fun rate(hits: Int): Double? =
        if (evaluatedTargets == 0) null else hits.toDouble() / evaluatedTargets
}

object ConsensusWalkForwardValidator {
    fun evaluate(
        observations: List<SourceObservation>,
        targetDateExclusive: LocalDate,
        minimumHistory: Int = 30
    ): ConsensusWalkForwardValidationReport {
        require(minimumHistory >= 1) { "minimumHistory must be positive" }
        val historical = observations.filter { it.date.isBefore(targetDateExclusive) }
        val reconciliation = SourceReconciliation.reconcile(historical)
        val chartBuild = ReconciledChartBuilder.build(historical)
        val reports = SangamWalkForwardEvaluator.evaluate(
            chartBuild.records,
            targetDateExclusive = targetDateExclusive,
            minimumHistory = minimumHistory
        )
        return ConsensusWalkForwardValidationReport(
            targetDateExclusive = targetDateExclusive,
            minimumHistory = minimumHistory,
            consensusRecordCount = chartBuild.records.size,
            conflictGroupCount = reconciliation.count { it.state == ReconciliationState.CONFLICT },
            singleSourceUnverifiedGroupCount = chartBuild.singleSourceUnverifiedGroups.size,
            noPublishedResultGroupCount = chartBuild.noPublishedResultGroups.size,
            invalidGroupCount = chartBuild.invalidGroups.size,
            marketSessionReports = reports
        )
    }
}
