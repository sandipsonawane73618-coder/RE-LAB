package com.relab.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.relab.core.ObservationStatus
import com.relab.core.CoverageReport
import com.relab.core.CoverageState
import com.relab.core.DuplicateObservationGroup
import com.relab.core.DuplicateObservationState
import com.relab.core.HistoricalCoverageAudit
import com.relab.core.ReconciliationState
import com.relab.core.MarketAnalysis
import com.relab.core.PatternAnalysis
import com.relab.core.ReconciledChartBuilder
import com.relab.core.SourceCsvImporter
import com.relab.core.SourceCsvExporter
import com.relab.core.SourceObservationMerger
import com.relab.core.SourceReconciliation
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = LocalObservationDatabase(this)
        val preferences = getSharedPreferences("relab_local", MODE_PRIVATE)

        // One-time migration from the first CSV-only build. Keep the old preference
        // until the database transaction succeeds so a failed migration is recoverable.
        val legacyCsv = preferences.getString("source_csv", null)
        if (database.loadOriginalCsv() == null && !legacyCsv.isNullOrBlank()) {
            val legacyReport = SourceCsvImporter.import(legacyCsv)
            if (legacyReport.observations.isNotEmpty()) {
                database.replaceDataset(legacyCsv, legacyReport.observations)
                preferences.edit().remove("source_csv").apply()
            }
        }

        setContent {
            RelabHome(
                initialCsv = database.loadOriginalCsv(),
                initialObservations = database.loadObservations(),
                onSaveCsv = { csv, observations ->
                    require(observations.isNotEmpty()) { "No valid source observations to save" }
                    database.appendDataset(csv, observations)
                },
                onClearCsv = {
                    database.clearDataset()
                    preferences.edit().remove("source_csv").apply()
                }
            )
        }
    }
}

@Composable
private fun RelabHome(initialCsv: String?, initialObservations: List<com.relab.core.SourceObservation>, onSaveCsv: (String, List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>, onClearCsv: () -> Unit) {
    val context = LocalContext.current
    var rawCsv by remember { mutableStateOf(initialCsv) }
    var observations by remember { mutableStateOf(initialObservations) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = SourceCsvExporter.export(observations)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                    ?: error("Backup file could not be written")
                importMessage = "Exported ${observations.size} source observations with provenance and raw text."
            } catch (error: Exception) {
                importMessage = "Export error: ${error.message ?: "Unknown error"}"
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Selected file could not be opened")
                val parsed = SourceCsvImporter.import(csv)
                if (parsed.observations.isEmpty()) {
                    importMessage = "Import failed: no valid source observations. " + (parsed.issues.firstOrNull()?.message ?: "")
                } else {
                    val previousCount = observations.size
                    val merged = onSaveCsv(csv, parsed.observations)
                    val addedCount = (merged.size - previousCount).coerceAtLeast(0)
                    observations = merged
                    rawCsv = csv
                    importMessage = "Added ${addedCount} new observations. Total: ${merged.size}. Exact re-imports are skipped; source conflicts are retained. Row issues: ${parsed.issues.size}."
                }
            } catch (error: Exception) {
                importMessage = "CSV read/import error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    val report = remember(rawCsv) { rawCsv?.let { SourceCsvImporter.import(it) } }
    val groups = remember(observations) { SourceReconciliation.reconcile(observations) }
    val conflicts = groups.count { it.state == ReconciliationState.CONFLICT }
    val repeatedSourceRecords = remember(observations) {
        SourceObservationMerger.auditRepeatedSourceRecords(observations)
    }
    val published = observations.count { it.status == ObservationStatus.PUBLISHED }
    val pending = observations.count { it.status == ObservationStatus.PENDING }
    val malformed = observations.count { it.status == ObservationStatus.MALFORMED }
    val chartBuild = remember(observations) { ReconciledChartBuilder.build(observations) }
    val patternAnalyses = remember(chartBuild.records) { PatternAnalysis.analyze(chartBuild.records) }
    var coverageRangeOverrides by remember { mutableStateOf<Map<String, Pair<LocalDate, LocalDate>>>(emptyMap()) }
    val coverageReports = remember(observations, coverageRangeOverrides) {
        observations.groupBy { it.market to it.sessionId }
            .mapNotNull { (key, rows) ->
                val dates = rows.map { it.date }
                if (dates.isEmpty()) null else HistoricalCoverageAudit.analyze(
                    market = key.first,
                    sessionId = key.second,
                    startDate = coverageRangeOverrides["${key.first.name}:${key.second}"]?.first ?: dates.minOrNull()!!,
                    endDate = coverageRangeOverrides["${key.first.name}:${key.second}"]?.second ?: dates.maxOrNull()!!,
                    observations = rows
                )
            }
            .sortedWith(compareBy<CoverageReport> { it.market.name }.thenBy { it.sessionId })
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("RE-LAB", style = MaterialTheme.typography.headlineLarge)
                Text("Sridevi + Kalyan · Offline chart research", style = MaterialTheme.typography.titleMedium)
                Text("Source-first research. Every imported source observation is stored locally.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/csv")) }) { Text("Import CSV") }
                    OutlinedButton(
                        onClick = { exportPicker.launch("relab-source-backup.csv") },
                        enabled = observations.isNotEmpty()
                    ) { Text("Export backup") }
                    OutlinedButton(onClick = {
                        rawCsv = null
                        observations = emptyList()
                        onClearCsv()
                        importMessage = "Local database cleared."
                    }, enabled = observations.isNotEmpty()) { Text("Clear") }
                }
                importMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Local dataset", style = MaterialTheme.typography.titleLarge)
                        Text("Source observations: ${observations.size}")
                        Text("Published: $published   Pending: $pending")
                        Text("Malformed rows: $malformed   Conflicting dates: $conflicts")
                        Text("Reconciled date groups: ${groups.size}")
                        if (report == null) Text("No CSV imported yet. Import a source CSV to begin historical research.")
                        if (report != null && report.issues.isNotEmpty()) {
                            Text("Import row issues: ${report.issues.size}")
                            report.issues.take(5).forEach { issue ->
                                Text("Row ${issue.rowNumber}: ${issue.message}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text("Pattern research · consensus records only", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Analysis input: ${chartBuild.records.size} multi-source consensus records · Single-source unverified: ${chartBuild.singleSourceUnverifiedGroups.size} · Conflicts excluded: ${chartBuild.conflictGroups.size} · Pending/missing excluded: ${chartBuild.noPublishedResultGroups.size} · Invalid excluded: ${chartBuild.invalidGroups.size}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (patternAnalyses.isEmpty()) {
                    Text("Import and reconcile published source records to enable pattern analysis.")
                } else {
                    patternAnalyses.forEach { analysis -> PatternResearchCard(analysis) }
                }
                Text("Historical coverage audit", style = MaterialTheme.typography.titleLarge)
                if (coverageReports.isEmpty()) {
                    Text("Coverage audit will appear after source data is imported.")
                } else {
                    coverageReports.forEach { coverage ->
                        CoverageReportCard(coverage) { start, end ->
                            coverageRangeOverrides = coverageRangeOverrides + ("${coverage.market.name}:${coverage.sessionId}" to (start to end))
                        }
                    }
                }
                Text("Repeated source-record audit", style = MaterialTheme.typography.titleLarge)
                if (repeatedSourceRecords.isEmpty()) {
                    Text("No repeated source-record IDs found. Records without a source record ID are not guessed or grouped.")
                } else {
                    DuplicateObservationAuditCard(repeatedSourceRecords)
                }
                Text("Recent source observations", style = MaterialTheme.typography.titleLarge)
                if (groups.isEmpty()) {
                    Text("No source records loaded.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.asReversed().take(30).forEach { group -> SourceGroupCard(group) }
                    }
                }
                Text("Offline SQLite · Source provenance preserved · Conflicts never auto-resolved", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}


@Composable
private fun CoverageReportCard(report: CoverageReport, onRangeApply: (LocalDate, LocalDate) -> Unit) {
    var startText by remember(report.market, report.sessionId, report.startDate) { mutableStateOf(report.startDate.toString()) }
    var endText by remember(report.market, report.sessionId, report.endDate) { mutableStateOf(report.endDate.toString()) }
    var rangeError by remember(report.market, report.sessionId) { mutableStateOf<String?>(null) }
    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("${report.market} · ${report.sessionId}", style = MaterialTheme.typography.titleMedium)
            Text("Coverage date range (YYYY-MM-DD)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = startText, onValueChange = { startText = it }, label = { Text("Start date") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = endText, onValueChange = { endText = it }, label = { Text("End date") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Button(onClick = {
                try {
                    val start = LocalDate.parse(startText.trim())
                    val end = LocalDate.parse(endText.trim())
                    require(!end.isBefore(start)) { "End date must be on or after start date" }
                    onRangeApply(start, end)
                    rangeError = null
                } catch (_: Exception) {
                    rangeError = "Enter valid dates in YYYY-MM-DD format; end date must not be earlier."
                }
            }) { Text("Apply date range") }
            rangeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("Range: ${report.startDate} → ${report.endDate}")
            Text("Expected trading days: ${report.expectedTradingDays}")
            Text("Published: ${report.publishedDays} · Multi-source consensus: ${report.consensusDays} · Single-source unverified: ${report.singleSourceDays} · Conflicts: ${report.conflictDays}")
            Text("Pending: ${report.pendingDays} · Holidays: ${report.holidayDays}")
            Text("Source missing: ${report.sourceMissingDays} · Malformed only: ${report.malformedOnlyDays} · No observation: ${report.noObservationDays}")
            Text("Latest coverage records", style = MaterialTheme.typography.titleSmall)
            report.days.takeLast(10).asReversed().forEach { day ->
                val stateLabel = when (day.state) {
                    CoverageState.NO_OBSERVATION -> "NO OBSERVATION"
                    CoverageState.HOLIDAY_REPORTED -> "HOLIDAY REPORTED"
                    CoverageState.PENDING -> "PENDING"
                    CoverageState.SOURCE_MISSING -> "SOURCE MISSING"
                    CoverageState.MALFORMED_ONLY -> "MALFORMED ONLY"
                    CoverageState.SINGLE_SOURCE_UNVERIFIED -> "SINGLE SOURCE · UNVERIFIED"
                    CoverageState.CONSENSUS -> "MULTI-SOURCE CONSENSUS"
                    CoverageState.CONFLICT -> "CONFLICT"
                }
                val resultLabel = when {
                    day.distinctPublishedResults.size > 1 -> day.distinctPublishedResults.joinToString(" / ")
                    day.distinctPublishedResults.size == 1 -> day.distinctPublishedResults.single()
                    else -> "${day.observationCount} source observations"
                }
                Text("${day.date} · $stateLabel · $resultLabel", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Malformed-only dates are separate from missing observations. No observation is not assumed to be a holiday; source conflicts remain unresolved.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}


@Composable
private fun DuplicateObservationAuditCard(groups: List<DuplicateObservationGroup>) {
    val changed = groups.count { it.state == DuplicateObservationState.SOURCE_RECORD_CHANGED }
    val unchanged = groups.size - changed
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Groups: ${groups.size} · Same content: $unchanged · Content changed: $changed")
            groups.asReversed().take(20).forEach { group ->
                val stateLabel = when (group.state) {
                    DuplicateObservationState.REPEATED_SAME_CONTENT -> "REPEATED · SAME CONTENT"
                    DuplicateObservationState.SOURCE_RECORD_CHANGED -> "SOURCE RECORD CHANGED"
                }
                val snapshots = group.observations.joinToString(" → ") { observation ->
                    "${observation.retrievedAt} [${observation.status}: ${observation.normalizedResult ?: observation.rawText}]"
                }
                Text("${group.market} · ${group.sessionId} · ${group.date} · ${group.sourceId} · ${group.sourceRecordId}", style = MaterialTheme.typography.titleSmall)
                Text("$stateLabel · ${group.observations.size} snapshots", style = MaterialTheme.typography.bodySmall)
                Text(snapshots, style = MaterialTheme.typography.bodySmall)
            }
            Text("Audit only: no source rows are deleted, merged, or silently corrected.", style = MaterialTheme.typography.labelSmall)
        }
    }
}


@Composable
private fun PatternResearchCard(analysis: MarketAnalysis) {
    val openTop = analysis.openFamilySequences.take(3).joinToString { "${it.pattern} (${it.count})" }.ifBlank { "Not enough records" }
    val closeTop = analysis.closeFamilySequences.take(3).joinToString { "${it.pattern} (${it.count})" }.ifBlank { "Not enough records" }
    val openPairs = analysis.openDigitPairPositionCounts.mapIndexed { index, counts ->
        val top = counts.take(2).joinToString { "${it.pattern} (${it.count})" }
        "P${index + 1}: ${top.ifBlank { "—" }}"
    }.joinToString(" · ")
    val closePairs = analysis.closeDigitPairPositionCounts.mapIndexed { index, counts ->
        val top = counts.take(2).joinToString { "${it.pattern} (${it.count})" }
        "P${index + 1}: ${top.ifBlank { "—" }}"
    }.joinToString(" · ")
    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("${analysis.market} · ${analysis.sessionId}", style = MaterialTheme.typography.titleMedium)
            Text("Accepted: ${analysis.acceptedRecords} · Rejected by chart maths: ${analysis.rejectedRecords}")
            Text("Top open family sequences: $openTop", style = MaterialTheme.typography.bodySmall)
            Text("Top close family sequences: $closeTop", style = MaterialTheme.typography.bodySmall)
            Text("Open digit pairs: $openPairs", style = MaterialTheme.typography.bodySmall)
            Text("Close digit pairs: $closePairs", style = MaterialTheme.typography.bodySmall)
            Text(
                "SP/DP/TP open: ${analysis.openPannaTypeCounts} · close: ${analysis.closePannaTypeCounts}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Repeat-gap samples · open: ${analysis.openRepeatGaps.sumOf { it.gapsInDays.size }} · close: ${analysis.closeRepeatGaps.sumOf { it.gapsInDays.size }}")
            Text(
                "Walk-forward baselines (open family): global ${analysis.globalModeBaseline.correct}/${analysis.globalModeBaseline.evaluated}, weekday ${analysis.weekdayModeBaseline.correct}/${analysis.weekdayModeBaseline.evaluated}, previous-state ${analysis.previousStateBaseline.correct}/${analysis.previousStateBaseline.evaluated}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Descriptive history only; walk-forward scores are baselines, not predictions.", style = MaterialTheme.typography.labelSmall)
        }
    }
}


@Composable
private fun SourceGroupCard(group: com.relab.core.ReconciliationGroup) {
    val resultText = when (group.state) {
        ReconciliationState.CONSENSUS -> "MULTI-SOURCE CONSENSUS · ${group.agreedResult}"
        ReconciliationState.SINGLE_SOURCE_UNVERIFIED -> "SINGLE SOURCE · UNVERIFIED · " +
            group.observations.filter { it.status == ObservationStatus.PUBLISHED }
                .mapNotNull { it.normalizedResult }.distinct().joinToString()
        ReconciliationState.CONFLICT -> "CONFLICT · " + group.observations
            .filter { it.status == ObservationStatus.PUBLISHED }
            .mapNotNull { it.normalizedResult }.distinct().sorted().joinToString(" / ")
        ReconciliationState.NO_PUBLISHED_RESULT -> "NO PUBLISHED RESULT · ${group.observations.size} source observations"
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${group.market} · ${group.sessionId} · ${group.date}", style = MaterialTheme.typography.titleSmall)
            Text(resultText, style = MaterialTheme.typography.bodyMedium)
            Text(group.observations.joinToString { "${it.sourceId}: ${it.status}" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}
