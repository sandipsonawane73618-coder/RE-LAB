package com.relab.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.relab.core.ObservationStatus
import com.relab.core.CoverageReport
import com.relab.core.CoverageState
import com.relab.core.CoverageCsvExporter
import com.relab.core.ResearchBundleExporter
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
import com.relab.core.SourceTimingAnalytics
import com.relab.core.SourceReconciliation
import java.time.LocalDate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_BUNDLE_ARCHIVE_BYTES = 100_000_000L

private fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray {
    require(maxBytes >= 0) { "Maximum byte count must not be negative" }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Selected file exceeds size limit: " + maxBytes + " bytes" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = LocalObservationDatabase(this)
        val preferences = getSharedPreferences("relab_local", MODE_PRIVATE)

        val initialCoverageRanges = preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(COVERAGE_RANGE_PREFIX) || value !is String) return@mapNotNull null
            val parts = value.split('|')
            if (parts.size != 2) return@mapNotNull null
            try {
                key.removePrefix(COVERAGE_RANGE_PREFIX) to (LocalDate.parse(parts[0]) to LocalDate.parse(parts[1]))
            } catch (_: Exception) {
                null
            }
        }.toMap()

        setContent {
            var initialData by remember {
                mutableStateOf<Pair<String?, List<com.relab.core.SourceObservation>>?>(null)
            }
            LaunchedEffect(Unit) {
                initialData = withContext(Dispatchers.IO) {
                    // One-time migration from the first CSV-only build. Keep the old
                    // preference until the database transaction succeeds.
                    val legacyCsv = preferences.getString("source_csv", null)
                    if (database.loadOriginalCsv() == null && !legacyCsv.isNullOrBlank()) {
                        val legacyReport = SourceCsvImporter.import(legacyCsv)
                        if (legacyReport.observations.isNotEmpty()) {
                            database.replaceDataset(legacyCsv, legacyReport.observations)
                            preferences.edit().remove("source_csv").apply()
                        }
                    }
                    database.loadOriginalCsv() to database.loadObservations()
                }
            }

            val loaded = initialData
            if (loaded == null) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Loading saved chart history…", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else {
                RelabHome(
                    initialCoverageRanges = initialCoverageRanges,
                    onSaveCoverageRange = { key, start, end ->
                        preferences.edit().putString(COVERAGE_RANGE_PREFIX + key, "$start|$end").apply()
                    },
                    initialCsv = loaded.first,
                    initialObservations = loaded.second,
                    onSaveCsv = { csv, observations ->
                        require(observations.isNotEmpty()) { "No valid source observations to save" }
                        database.appendDataset(csv, observations)
                    },
                    onSaveLinkObservations = { imported ->
                        require(imported.isNotEmpty()) { "No chart rows were extracted from this link" }
                        database.appendDataset(SourceCsvExporter.export(imported), imported)
                    },
                    onClearCsv = {
                        database.clearDataset()
                        val rangeKeys = preferences.all.keys.filter { it.startsWith(COVERAGE_RANGE_PREFIX) }
                        preferences.edit().apply {
                            remove("source_csv")
                            rangeKeys.forEach(::remove)
                        }.apply()
                    }
                )
            }
        }
    }

    companion object {
        private const val COVERAGE_RANGE_PREFIX = "coverage_range:"
    }
}

private data class ReverseModelDashboardSet(
    val market: com.relab.core.Market,
    val sessionId: String,
    val open: com.relab.core.ReverseEngineeringRanking,
    val close: com.relab.core.ReverseEngineeringRanking,
    val jodi: List<com.relab.core.ReverseEngineeringJodiCandidate>
)

private data class DashboardAnalysisSnapshot(
    val activeObservations: List<com.relab.core.SourceObservation>,
    val groups: List<com.relab.core.ReconciliationGroup>,
    val repeatedSourceRecords: List<com.relab.core.DuplicateObservationGroup>,
    val repeatedPublishedResults: List<com.relab.core.RepeatedPublishedResultGroup>,
    val chartBuild: com.relab.core.ChartBuildReport,
    val researchRecords: List<com.relab.core.ChartRecord>,
    val patternAnalyses: List<com.relab.core.MarketAnalysis>,
    val intelligenceReports: List<com.relab.core.MarketIntelligenceReport>,
    val crossMarketReports: List<com.relab.core.CrossMarketPairReport>,
    val candidateSets: List<Triple<com.relab.core.CandidateRanking, com.relab.core.CandidateRanking, List<com.relab.core.JodiCandidate>>>,
    val reverseModelSets: List<ReverseModelDashboardSet>,
    val reverseModelArtifacts: Map<String, String>,
    val sourceTiming: List<com.relab.core.SourceTimingSummary>
)

private fun buildDashboardAnalysisSnapshot(
    observations: List<com.relab.core.SourceObservation>
): DashboardAnalysisSnapshot {
    val activeObservations = observations.filter {
        it.market in com.relab.core.ReverseEngineeringModel.enabledMarkets
    }
    val groups = com.relab.core.SourceReconciliation.reconcile(activeObservations)
    val repeatedSourceRecords = com.relab.core.SourceObservationMerger.auditRepeatedSourceRecords(activeObservations)
    val repeatedPublishedResults = com.relab.core.SourceObservationMerger.auditRepeatedPublishedResultsAcrossDates(activeObservations)
    val chartBuild = com.relab.core.ReconciledChartBuilder.build(activeObservations)
    val researchRecords = com.relab.core.ReconciledChartBuilder.buildExploratoryRecords(activeObservations)
    val patternAnalyses = com.relab.core.PatternAnalysis.analyze(researchRecords)
    val intelligenceReports = com.relab.core.MarketIntelligence.analyze(researchRecords)
    val crossMarketReports = com.relab.core.CrossMarketReverseEngineering.analyze(researchRecords)
    val marketSessions = researchRecords.map { it.market to it.sessionId }.distinct()
        .sortedWith(compareBy({ it.first.name }, { it.second }))
    val targetDate = LocalDate.now()

    val candidateSets = marketSessions.map { (market, sessionId) ->
        val open = com.relab.core.CandidateRankingEngine.rank(
            activeObservations, market, sessionId, targetDate, com.relab.core.PannaSide.OPEN,
            limit = 10, minimumHistory = 5, allowSingleSourceExploration = true,
            prebuiltRecords = researchRecords
        )
        val close = com.relab.core.CandidateRankingEngine.rank(
            activeObservations, market, sessionId, targetDate, com.relab.core.PannaSide.CLOSE,
            limit = 10, minimumHistory = 5, allowSingleSourceExploration = true,
            prebuiltRecords = researchRecords
        )
        Triple(open, close, com.relab.core.CandidateRankingEngine.deriveJodiCandidates(open.candidates, close.candidates, 10))
    }

    val reverseModelSets = marketSessions.map { (market, sessionId) ->
        val open = com.relab.core.ReverseEngineeringModel.rank(
            researchRecords, market, sessionId, targetDate, com.relab.core.ReverseEngineeringSide.OPEN,
            limit = 10, minimumHistory = 30
        )
        val close = com.relab.core.ReverseEngineeringModel.rank(
            researchRecords, market, sessionId, targetDate, com.relab.core.ReverseEngineeringSide.CLOSE,
            limit = 10, minimumHistory = 30
        )
        ReverseModelDashboardSet(
            market, sessionId, open, close,
            com.relab.core.ReverseEngineeringModel.deriveJodiCandidates(open.candidates, close.candidates, 10)
        )
    }

    val reverseModelArtifacts = com.relab.core.ReverseEngineeringCsvExporter.exportAll(
        reverseModelSets.flatMap { listOf(it.open, it.close) },
        emptyList(),
        reverseModelSets.map {
            com.relab.core.ReverseEngineeringJodiReport(it.market, it.sessionId, targetDate, it.jodi)
        }
    )

    return DashboardAnalysisSnapshot(
        activeObservations, groups, repeatedSourceRecords, repeatedPublishedResults,
        chartBuild, researchRecords, patternAnalyses, intelligenceReports,
        crossMarketReports, candidateSets, reverseModelSets, reverseModelArtifacts,
        com.relab.core.SourceTimingAnalytics.summarize(activeObservations)
    )
}

@Composable
private fun RelabHome(
    initialCsv: String?,
    initialObservations: List<com.relab.core.SourceObservation>,
    initialCoverageRanges: Map<String, Pair<LocalDate, LocalDate>>,
    onSaveCoverageRange: (String, LocalDate, LocalDate) -> Unit,
    onSaveCsv: (String, List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    onSaveLinkObservations: (List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    onClearCsv: () -> Unit
) {
    val context = LocalContext.current
    var rawCsv by remember { mutableStateOf(initialCsv) }
    var observations by remember { mutableStateOf(initialObservations) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var chartUrl by remember { mutableStateOf("") }
    var linkBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (initialObservations.isEmpty() && observations.isEmpty() && !linkBusy) {
            importAllCanonicalCharts(onSaveLinkObservations, { observations = it }, { importMessage = it }, { linkBusy = it })
        }
    }
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

    var report by remember { mutableStateOf<com.relab.core.CsvImportReport?>(null) }
    LaunchedEffect(rawCsv) {
        val csv = rawCsv
        report = if (csv.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            SourceCsvImporter.import(csv)
        }
    }
    var coverageRangeOverrides by remember { mutableStateOf(initialCoverageRanges) }
    var analysisSnapshot by remember { mutableStateOf<DashboardAnalysisSnapshot?>(null) }
    var analysisError by remember { mutableStateOf<String?>(null) }

    // Reconciliation, pattern scans, candidate generation and chronological backtests
    // operate on thousands of rows. Keep the complete analysis off the Compose/UI thread.
    LaunchedEffect(observations) {
        analysisSnapshot = null
        analysisError = null
        val rows = observations
        try {
            val computed = withContext(Dispatchers.Default) {
                buildDashboardAnalysisSnapshot(rows)
            }
            analysisSnapshot = computed
        } catch (error: Exception) {
            analysisError = error.message ?: "Unknown analysis error"
        }
    }

    var coverageReports by remember { mutableStateOf<List<com.relab.core.CoverageReport>>(emptyList()) }
    LaunchedEffect(observations, coverageRangeOverrides) {
        val rows = observations.filter {
            it.market in com.relab.core.ReverseEngineeringModel.enabledMarkets
        }
        val ranges = coverageRangeOverrides
        coverageReports = withContext(Dispatchers.Default) {
            rows.groupBy { it.market to it.sessionId }
                .mapNotNull { (key, marketRows) ->
                    val dates = marketRows.map { it.date }
                    if (dates.isEmpty()) null else com.relab.core.HistoricalCoverageAudit.analyze(
                        market = key.first,
                        sessionId = key.second,
                        startDate = ranges["${key.first.name}:${key.second}"]?.first ?: dates.minOrNull()!!,
                        endDate = ranges["${key.first.name}:${key.second}"]?.second ?: dates.maxOrNull()!!,
                        observations = marketRows
                    )
                }
                .sortedWith(compareBy<com.relab.core.CoverageReport> { it.market.name }.thenBy { it.sessionId })
        }
    }

    val snapshot = analysisSnapshot
    val activeObservations = snapshot?.activeObservations
        ?: observations.filter { it.market in com.relab.core.ReverseEngineeringModel.enabledMarkets }
    val groups = snapshot?.groups.orEmpty()
    val conflicts = groups.count { it.state == ReconciliationState.CONFLICT }
    val repeatedSourceRecords = snapshot?.repeatedSourceRecords.orEmpty()
    val repeatedPublishedResults = snapshot?.repeatedPublishedResults.orEmpty()
    val published = activeObservations.count { it.status == ObservationStatus.PUBLISHED }
    val pending = activeObservations.count { it.status == ObservationStatus.PENDING }
    val malformed = activeObservations.count { it.status == ObservationStatus.MALFORMED }
    val chartBuild = snapshot?.chartBuild ?: com.relab.core.ChartBuildReport(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
    )
    val researchRecords = snapshot?.researchRecords.orEmpty()
    val patternAnalyses = snapshot?.patternAnalyses.orEmpty()
    val intelligenceReports = snapshot?.intelligenceReports.orEmpty()
    val crossMarketReports = snapshot?.crossMarketReports.orEmpty()
    val candidateSets = snapshot?.candidateSets.orEmpty()
    val reverseModelSets = snapshot?.reverseModelSets.orEmpty()
    val baseReverseModelArtifacts = snapshot?.reverseModelArtifacts.orEmpty()
    val sourceTiming = snapshot?.sourceTiming.orEmpty()

    var reverseBacktestResults by remember {
        mutableStateOf<Map<String, Pair<com.relab.core.ReverseEngineeringBacktestReport, com.relab.core.ReverseEngineeringBacktestReport>>>(emptyMap())
    }
    var reverseBacktestArtifacts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reverseBacktestRequest by remember { mutableStateOf<ReverseModelDashboardSet?>(null) }
    var reverseBacktestBusyKey by remember { mutableStateOf<String?>(null) }
    var reverseBacktestError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(observations) {
        reverseBacktestResults = emptyMap()
        reverseBacktestArtifacts = emptyMap()
        reverseBacktestError = null
        reverseBacktestRequest = null
    }

    LaunchedEffect(reverseBacktestRequest, researchRecords) {
        val request = reverseBacktestRequest ?: return@LaunchedEffect
        val key = "${request.market.name}|${request.sessionId}"
        reverseBacktestBusyKey = key
        reverseBacktestError = null
        try {
            val reports = withContext(Dispatchers.Default) {
                val open = com.relab.core.ReverseEngineeringModel.backtest(
                    researchRecords, request.market, request.sessionId,
                    com.relab.core.ReverseEngineeringSide.OPEN,
                    targetLimit = 20, minimumHistory = 30, candidateLimit = 10
                )
                val close = com.relab.core.ReverseEngineeringModel.backtest(
                    researchRecords, request.market, request.sessionId,
                    com.relab.core.ReverseEngineeringSide.CLOSE,
                    targetLimit = 20, minimumHistory = 30, candidateLimit = 10
                )
                open to close
            }
            reverseBacktestResults = reverseBacktestResults + (key to reports)
            reverseBacktestArtifacts = reverseBacktestArtifacts +
                com.relab.core.ReverseEngineeringCsvExporter.exportAll(
                    emptyList(), listOf(reports.first, reports.second), emptyList()
                )
        } catch (error: Exception) {
            reverseBacktestError = error.message ?: "Unknown walk-forward validation error"
        } finally {
            reverseBacktestBusyKey = null
            reverseBacktestRequest = null
        }
    }

    val reverseModelArtifacts = baseReverseModelArtifacts + reverseBacktestArtifacts

    val researchExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = com.relab.core.DerivedChartCsvExporter.export(chartBuild.records)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                    ?: error("Research export file could not be written")
                importMessage = "Exported ${chartBuild.records.size} validated consensus chart records with derived features."
            } catch (error: Exception) {
                importMessage = "Research export error: ${error.message ?: "Unknown error"}"
            }
        }
    }
    val walkForwardExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = com.relab.core.WalkForwardCsvExporter.export(patternAnalyses)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                    ?: error("Walk-forward file could not be written")
                val exportedRows = patternAnalyses.sumOf { it.walkForwardRows.size }
                importMessage = "Exported $exportedRows chronological walk-forward rows across ${patternAnalyses.size} market/session analyses."
            } catch (error: Exception) {
                importMessage = "Walk-forward export error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    val manifestExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            try {
                val manifest = com.relab.core.ResearchManifestExporter.export(
                    observations, chartBuild.records, patternAnalyses, java.time.Instant.now()
                )
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(manifest) }
                    ?: error("Research manifest file could not be written")
                importMessage = "Exported reproducibility manifest with SHA-256 source-ledger checksum and dataset coverage metadata."
            } catch (error: Exception) {
                importMessage = "Manifest export error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    val timingExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = SourceTimingAnalytics.exportCsv(activeObservations)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                    ?: error("Source timing file could not be written")
                importMessage = "Exported publication-to-capture timing for ${sourceTiming.size} publisher/session groups. Missing publication times remain unestimated."
            } catch (error: Exception) {
                importMessage = "Source timing export error: ${error.message ?: "Unknown error"}"
            }
        }    }

    val coverageExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = CoverageCsvExporter.export(coverageReports)
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                    ?: error("Coverage audit file could not be written")
                val rows = coverageReports.sumOf { it.days.size }
                importMessage = "Exported $rows expected trading-day coverage rows across ${coverageReports.size} market/session reports."
            } catch (error: Exception) {
                importMessage = "Coverage export error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    val bundleExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) {
            try {
                val bundle = ResearchBundleExporter.export(
                    observations, chartBuild.records, patternAnalyses, coverageReports, java.time.Instant.now(),
                    reverseModelArtifacts = reverseModelArtifacts
                )
                context.contentResolver.openOutputStream(uri)?.use { it.write(bundle) }
                    ?: error("Research bundle file could not be written")
                importMessage = "Exported complete research bundle: ${observations.size} source observations, ${chartBuild.records.size} consensus records and SHA-256 integrity index."
            } catch (error: Exception) {
                importMessage = "Research bundle export error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    val bundleValidationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use {
                    readBoundedBytes(it, MAX_BUNDLE_ARCHIVE_BYTES)
                } ?: error("Selected bundle could not be opened")
                val validation = com.relab.core.ResearchBundleValidator.validate(bytes)
                if (!validation.valid) {
                    importMessage = "Bundle rejected: ${validation.errors.joinToString("; ")}"
                } else {
                    val sourceCsv = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                        var content: String? = null
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.name == "source-observations.csv") {
                                content = readBoundedBytes(zip, MAX_BUNDLE_ARCHIVE_BYTES).toString(Charsets.UTF_8)
                                break
                            }
                            zip.closeEntry()
                        }
                        content
                    } ?: error("Verified bundle has no source-observations.csv")
                    val parsed = SourceCsvImporter.import(sourceCsv)
                    if (parsed.observations.isEmpty()) {
                        importMessage = "Bundle verified, but no valid source observations were available to import."
                    } else {
                        val previousCount = observations.size
                        val merged = onSaveCsv(sourceCsv, parsed.observations)
                        val addedCount = (merged.size - previousCount).coerceAtLeast(0)
                        observations = merged
                        rawCsv = sourceCsv
                        importMessage = "Verified bundle imported: ${validation.verifiedFiles} artifact checksums passed; added ${addedCount} observations. Total: ${merged.size}. Existing records and conflicts were retained. Row issues: ${parsed.issues.size}."
                    }
                }
            } catch (error: Exception) {
                importMessage = "Bundle validation error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("RE-LAB", style = MaterialTheme.typography.headlineLarge)
                Text("5 markets · Offline chart research", style = MaterialTheme.typography.titleMedium)
                Button(
                    enabled = !linkBusy,
                    onClick = { importAllCanonicalCharts(onSaveLinkObservations, { observations = it }, { importMessage = it }, { linkBusy = it }) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (linkBusy) "Fetching 5 markets…" else "Fetch / Refresh All 5 Markets") }
                Text("Paste any supported chart URL. RE-LAB detects the market from its exact source path.")
                OutlinedTextField(
                    value = chartUrl,
                    onValueChange = { chartUrl = it },
                    label = { Text("Paste chart website link") },
                    placeholder = { Text("https://sattamatkajodi.com/record/kalyan-penal-chart.php") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = chartUrl.isNotBlank() && !linkBusy,
                    onClick = {
                        val detected = com.relab.core.Market.fromSourceUrl(chartUrl.trim())
                        if (detected == null) {
                            importMessage = "Unsupported chart URL. Supported markets: Sridevi, Sridevi Night, Kalyan, Time Bazar, Main Bazar."
                        } else {
                            importChartLink(chartUrl, detected, onSaveLinkObservations, { observations = it }, { importMessage = it }, { linkBusy = it })
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (linkBusy) "Fetching..." else "Auto-detect market & fetch") }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/csv")) }) {
                            Text("Import CSV")
                        }
                        OutlinedButton(
                            onClick = { bundleValidationPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
                        ) { Text("Import verified bundle") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { bundleExportPicker.launch("relab-complete-research-bundle.zip") },
                            enabled = observations.isNotEmpty()
                        ) { Text("Export complete bundle") }
                        OutlinedButton(
                            onClick = { exportPicker.launch("relab-source-backup.csv") },
                            enabled = observations.isNotEmpty()
                        ) { Text("Export backup") }
                    }
                    OutlinedButton(onClick = {
                        rawCsv = null
                        observations = emptyList()
                        onClearCsv()
                        importMessage = "Local database cleared."
                    }, enabled = observations.isNotEmpty()) { Text("Clear local dataset") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { researchExportPicker.launch("relab-derived-consensus-research.csv") },
                        enabled = chartBuild.records.isNotEmpty()
                    ) { Text("Export research CSV") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { walkForwardExportPicker.launch("relab-walk-forward-audit.csv") },
                        enabled = patternAnalyses.any { it.walkForwardRows.isNotEmpty() }
                    ) { Text("Export walk-forward CSV") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { manifestExportPicker.launch("relab-research-manifest.json") },
                        enabled = observations.isNotEmpty()
                    ) { Text("Export research manifest") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { coverageExportPicker.launch("relab-historical-coverage-audit.csv") },
                        enabled = coverageReports.isNotEmpty()
                    ) { Text("Export coverage audit CSV") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { timingExportPicker.launch("relab-source-publication-timing.csv") },
                        enabled = sourceTiming.isNotEmpty()
                    ) { Text("Export source timing CSV") }
                }
                importMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val currentReport = report
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Local dataset", style = MaterialTheme.typography.titleLarge)
                        Text("Enabled-market observations: ${activeObservations.size} · ignored legacy-market rows: ${(observations.size - activeObservations.size).coerceAtLeast(0)}")
                        Text("Published: $published   Pending: $pending")
                        Text("Malformed rows: $malformed   Conflicting dates: $conflicts")
                        Text("Reconciled date groups: ${groups.size}")
                        if (currentReport == null) {
                            Text("No CSV backup is loaded. Historical charts are fetched automatically when the local dataset is empty; manual import is optional.")
                        }
                        if (currentReport != null && currentReport.issues.isNotEmpty()) {
                            Text("Import row issues: ${currentReport.issues.size}")
                            currentReport.issues.take(5).forEach { issue ->
                                Text("Row ${issue.rowNumber}: ${issue.message}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text("Pattern research · exploratory + verified", style = MaterialTheme.typography.titleLarge)
                Text("Enabled markets: Sridevi · Sridevi Night · Kalyan · Main Bazar · Time Bazar. Other legacy market rows are excluded from analysis.", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Analysis input: ${researchRecords.size} valid historical rows (${chartBuild.records.size} consensus + ${chartBuild.singleSourceUnverifiedGroups.size} single-source groups) · Conflicts excluded: ${chartBuild.conflictGroups.size} · Pending/missing excluded: ${chartBuild.noPublishedResultGroups.size} · Invalid excluded: ${chartBuild.invalidGroups.size}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (patternAnalyses.isEmpty()) {
                    Text(if (snapshot == null) "Analysing historical patterns…" else "No valid published rows available for pattern analysis yet. Check the imported chart rows and source format.")
                } else {
                    patternAnalyses.forEach { analysis -> PatternResearchCard(analysis) }
                }
                Text("Cross-Market Reverse Engineering · five enabled markets", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Compares matching dates and sessions across Sridevi, Sridevi Night, Kalyan, Main Bazar and Time Bazar. Historical co-occurrence only; not a prediction or causal claim.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (crossMarketReports.isEmpty()) {
                    Text(if (snapshot == null) "Analysing same-date cross-market relationships…" else "Cross-market comparison will appear when at least two enabled markets have valid records for the same date and session.")
                } else {
                    crossMarketReports.forEach { CrossMarketResearchCard(it) }
                }
                Text("Market Intelligence · market-specific behaviour", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Each market is analysed independently. Recent-vs-older distribution changes are descriptive alerts, not future-result predictions.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (intelligenceReports.isEmpty()) {
                    Text(if (snapshot == null) "Calculating market regime and distribution shifts…" else "Market intelligence will appear after valid published chart rows are available.")
                } else {
                    intelligenceReports.forEach { MarketIntelligenceCard(it) }
                }
                Text("Reverse Engineering Model · evidence-ranked research", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Exploratory model uses valid consensus + single-source rows (single-source remains unverified); conflicts, pending and malformed rows are excluded. Combines exact panna, digit-family DNA (1/6, 2/7, 3/8, 4/9, 5/0), Ank, digit positions, SP/DP/TP, reverse panna, weekday, transitions and lagged cross-market conditions. Requires 30 earlier valid rows.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (snapshot == null) {
                    Text(analysisError?.let { "Research analysis error: $it" } ?: "Analysing five-market history and running chronological backtests off the UI thread…")
                } else if (reverseModelSets.isEmpty()) {
                    Text("No valid history is available for the five enabled markets yet.")
                } else {
                    reverseModelSets.forEach { set ->
                        val backtestKey = "${set.market.name}|${set.sessionId}"
                        ReverseEngineeringModelCard(
                            set = set,
                            backtests = reverseBacktestResults[backtestKey],
                            backtestBusy = reverseBacktestBusyKey == backtestKey,
                            backtestError = reverseBacktestError.takeIf { reverseBacktestBusyKey == null && reverseBacktestResults[backtestKey] == null },
                            onRunBacktest = { reverseBacktestRequest = set }
                        )
                    }
                }
                Text("Next draw candidate research · legacy frequency baseline", style = MaterialTheme.typography.titleLarge)
                if (candidateSets.isEmpty()) {
                    Text(if (snapshot == null) "Preparing legacy baseline candidates…" else "No usable published history yet. Candidates require at least 5 earlier valid rows; single-source results are exploratory and remain unverified.")
                } else {
                    candidateSets.forEach { (open, close, jodi) ->
                        CandidateResearchCard(open, close, jodi)
                    }
                }
                Text("Historical coverage audit", style = MaterialTheme.typography.titleLarge)
                if (coverageReports.isEmpty()) {
                    Text(if (snapshot == null) "Calculating historical coverage…" else "Coverage audit will appear after source data is imported.")
                } else {
                    coverageReports.forEach { coverage ->
                        CoverageReportCard(coverage) { start, end ->
                            val key = "${coverage.market.name}:${coverage.sessionId}"
                            coverageRangeOverrides = coverageRangeOverrides + (key to (start to end))
                            onSaveCoverageRange(key, start, end)
                        }
                    }
                }
                Text("Repeated source-record audit", style = MaterialTheme.typography.titleLarge)
                if (repeatedSourceRecords.isEmpty()) {
                    Text(if (snapshot == null) "Checking repeated source-record IDs…" else "No repeated source-record IDs found. Records without a source record ID are not guessed or grouped.")
                } else {
                    DuplicateObservationAuditCard(repeatedSourceRecords)
                }
                Text("Repeated result across dates · stale archive review", style = MaterialTheme.typography.titleLarge)
                if (repeatedPublishedResults.isEmpty()) {
                    Text(if (snapshot == null) "Checking repeated published values…" else "No identical published result repeated across 3 or more distinct dates by the same source.")
                } else {
                    RepeatedPublishedResultAuditCard(repeatedPublishedResults)
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
private fun RepeatedPublishedResultAuditCard(groups: List<com.relab.core.RepeatedPublishedResultGroup>) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Review flags: ${groups.size}")
            groups.take(20).forEach { group ->
                val dates = group.observations.map { it.date }.distinct().joinToString()
                Text("${group.market} · ${group.sessionId} · ${group.sourceId}", style = MaterialTheme.typography.titleSmall)
                Text("${group.result} repeated on ${group.distinctDates} dates", style = MaterialTheme.typography.bodySmall)
                Text("Dates: $dates", style = MaterialTheme.typography.bodySmall)
            }
            Text("Review signal only—not proof of stale data. Original observations remain preserved.", style = MaterialTheme.typography.labelSmall)
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
            Text("Walk-forward hit rate · Open family", style = MaterialTheme.typography.titleSmall)
            Text(
                "Global ${"%.1f".format(analysis.globalModeBaseline.hitRate * 100)}% · Weekday ${"%.1f".format(analysis.weekdayModeBaseline.hitRate * 100)}% · Previous ${"%.1f".format(analysis.previousStateBaseline.hitRate * 100)}%  (n=${analysis.globalModeBaseline.evaluated})",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Walk-forward hit rate · Close family", style = MaterialTheme.typography.titleSmall)
            Text(
                "Global ${"%.1f".format(analysis.closeGlobalModeBaseline.hitRate * 100)}% · Weekday ${"%.1f".format(analysis.closeWeekdayModeBaseline.hitRate * 100)}% · Previous ${"%.1f".format(analysis.closePreviousStateBaseline.hitRate * 100)}%  (n=${analysis.closeGlobalModeBaseline.evaluated})",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Descriptive history only; walk-forward scores are baselines, not predictions.", style = MaterialTheme.typography.labelSmall)
        }
    }
}


@Composable
private fun MarketIntelligenceCard(report: com.relab.core.MarketIntelligenceReport) {
    val regimeLabel = when (report.regime) {
        com.relab.core.MarketRegime.INSUFFICIENT_HISTORY -> "GREY · INSUFFICIENT HISTORY"
        com.relab.core.MarketRegime.STABLE -> "GREEN · BROADLY STABLE"
        com.relab.core.MarketRegime.WATCH -> "AMBER · WATCH FOR CHANGE"
        com.relab.core.MarketRegime.SHIFT_DETECTED -> "RED · DISTRIBUTION SHIFT"
    }
    val regimeColor = when (report.regime) {
        com.relab.core.MarketRegime.INSUFFICIENT_HISTORY -> MaterialTheme.colorScheme.onSurfaceVariant
        com.relab.core.MarketRegime.STABLE -> MaterialTheme.colorScheme.primary
        com.relab.core.MarketRegime.WATCH -> MaterialTheme.colorScheme.tertiary
        com.relab.core.MarketRegime.SHIFT_DETECTED -> MaterialTheme.colorScheme.error
    }
    fun transitionText(items: List<com.relab.core.TransitionEvidence>): String =
        items.take(3).joinToString(" · ") { "${it.transition} (${it.count}, ${"%.1f".format(it.percentage)}%)" }
            .ifBlank { "Not enough transitions" }
    fun weekdayText(items: Map<java.time.DayOfWeek, Int>): String =
        items.entries.sortedByDescending { it.value }.take(3)
            .joinToString(" · ") { "${it.key.name.lowercase().replaceFirstChar(Char::uppercase)}: ${it.value}" }
            .ifBlank { "No weekday evidence" }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${report.market} · ${report.sessionId}", style = MaterialTheme.typography.titleMedium)
            Text("Records: ${report.validRecords} · ${regimeLabel}", color = regimeColor, style = MaterialTheme.typography.titleSmall)
            Text(report.explanation, style = MaterialTheme.typography.bodySmall)
            Text(
                "Recent-vs-prior distribution difference: ${report.distributionShift?.let { "${"%.1f".format(it * 100)}%" } ?: "Not evaluated"}" +
                    if (report.comparisonWindowSize > 0) " · Window: ${report.comparisonWindowSize} rows" else "",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Open transitions: ${transitionText(report.topOpenTransitions)}", style = MaterialTheme.typography.bodySmall)
            Text("Close transitions: ${transitionText(report.topCloseTransitions)}", style = MaterialTheme.typography.bodySmall)
            Text("Open weekday counts: ${weekdayText(report.openWeekdayCounts)}", style = MaterialTheme.typography.bodySmall)
            Text("Close weekday counts: ${weekdayText(report.closeWeekdayCounts)}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Family-sequence entropy · Open ${"%.2f".format(report.openFamilyEntropyBits)} bits · Close ${"%.2f".format(report.closeFamilyEntropyBits)} bits",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Shift flag compares two equal recent/older windows; it is a descriptive distribution test, not a prediction or proof of a market mechanism.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ReverseEngineeringModelCard(
    set: ReverseModelDashboardSet,
    backtests: Pair<com.relab.core.ReverseEngineeringBacktestReport, com.relab.core.ReverseEngineeringBacktestReport>?,
    backtestBusy: Boolean,
    backtestError: String?,
    onRunBacktest: () -> Unit
) {
    fun pct(value: Double): String = "${"%.1f".format(value * 100.0)}%"
    fun evidence(value: Double?): String = value?.let { "${"%.3f".format(it)}" } ?: "—"

    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("${set.market} · ${set.sessionId}", style = MaterialTheme.typography.titleMedium)
            Text("Open-side reverse model", style = MaterialTheme.typography.titleSmall)
            if (set.open.abstained) {
                Text("ABSTAIN: ${set.open.note}")
            } else {
                Text("Training rows: ${set.open.trainingRecords} · Active layers: ${set.open.activeEvidenceLayers.joinToString()}", style = MaterialTheme.typography.bodySmall)
                set.open.candidates.forEachIndexed { index, candidate ->
                    Text("${index + 1}. ${candidate.panna} · family ${candidate.familySequence} · ank ${candidate.ank} · ${candidate.pannaType} · evidence ${"%.1f".format(candidate.score)}/100")
                    Text("Exact ${evidence(candidate.exactPannaEvidence)} · Family ${evidence(candidate.familyEvidence)} · Ank ${evidence(candidate.ankEvidence)} · Position ${evidence(candidate.positionEvidence)} · Type ${evidence(candidate.pannaTypeEvidence)} · Reverse ${evidence(candidate.reversePannaEvidence)} · Weekday ${evidence(candidate.weekdayEvidence)} · Transition ${evidence(candidate.previousTransitionEvidence)} · Cross-market ${evidence(candidate.crossMarketConditionalEvidence)}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Top family sequences (separate 125-state ranking)", style = MaterialTheme.typography.labelLarge)
                set.open.familyCandidates.take(5).forEachIndexed { index, family ->
                    Text("${index + 1}. ${family.familySequence} · evidence ${"%.1f".format(family.score)}/100 · history ${family.historicalCount} · weekday ${family.weekdayCount ?: 0} · transition ${family.previousTransitionCount ?: 0}")
                }
            }

            Text("Close-side reverse model", style = MaterialTheme.typography.titleSmall)
            if (set.close.abstained) {
                Text("ABSTAIN: ${set.close.note}")
            } else {
                Text("Training rows: ${set.close.trainingRecords} · Active layers: ${set.close.activeEvidenceLayers.joinToString()}", style = MaterialTheme.typography.bodySmall)
                set.close.candidates.forEachIndexed { index, candidate ->
                    Text("${index + 1}. ${candidate.panna} · family ${candidate.familySequence} · ank ${candidate.ank} · ${candidate.pannaType} · evidence ${"%.1f".format(candidate.score)}/100")
                    Text("Exact ${evidence(candidate.exactPannaEvidence)} · Family ${evidence(candidate.familyEvidence)} · Ank ${evidence(candidate.ankEvidence)} · Position ${evidence(candidate.positionEvidence)} · Type ${evidence(candidate.pannaTypeEvidence)} · Reverse ${evidence(candidate.reversePannaEvidence)} · Weekday ${evidence(candidate.weekdayEvidence)} · Transition ${evidence(candidate.previousTransitionEvidence)} · Cross-market ${evidence(candidate.crossMarketConditionalEvidence)}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Top family sequences (separate 125-state ranking)", style = MaterialTheme.typography.labelLarge)
                set.close.familyCandidates.take(5).forEachIndexed { index, family ->
                    Text("${index + 1}. ${family.familySequence} · evidence ${"%.1f".format(family.score)}/100 · history ${family.historicalCount} · weekday ${family.weekdayCount ?: 0} · transition ${family.previousTransitionCount ?: 0}")
                }
            }

            Text("Derived Jodi candidates", style = MaterialTheme.typography.titleSmall)
            if (set.jodi.isEmpty()) Text("Jodi candidates require both open and close model rankings.")
            else set.jodi.forEachIndexed { index, candidate ->
                Text("${index + 1}. ${candidate.jodi} · relative score ${"%.1f".format(candidate.score)} · supporting pairs ${candidate.supportingPannaPairs}")
            }

            Text("Chronological walk-forward validation", style = MaterialTheme.typography.titleSmall)
            if (backtests == null) {
                Text("This heavier validation is kept off startup so the saved history and research screens can load first.")
                Button(onClick = onRunBacktest, enabled = !backtestBusy) {
                    Text(if (backtestBusy) "Running walk-forward validation…" else "Run 20-target walk-forward validation")
                }
                backtestError?.let {
                    Text("Walk-forward error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val openBacktest = backtests.first
                val closeBacktest = backtests.second
                Text("Open: n=${openBacktest.evaluatedTargets}, abstained=${openBacktest.abstainedTargets}/${openBacktest.requestedTargets} · exact top-1 ${openBacktest.top1ExactHits} (${pct(openBacktest.top1ExactRate)}) · exact top-10 ${openBacktest.top10ExactHits} (${pct(openBacktest.top10ExactRate)}) · ank top-1 ${openBacktest.top1AnkHits} (${pct(openBacktest.top1AnkRate)}) · ank top-10 ${openBacktest.top10AnkHits} (${pct(openBacktest.top10AnkRate)}) · family top-1 ${openBacktest.top1FamilyHits} (${pct(openBacktest.top1FamilyRate)}) · family top-10 ${openBacktest.top10FamilyHits} (${pct(openBacktest.top10FamilyRate)}) · prior-mode ${openBacktest.priorModeFamilyHits} (${pct(openBacktest.priorModeFamilyRate)})", style = MaterialTheme.typography.bodySmall)
                Text("Close: n=${closeBacktest.evaluatedTargets}, abstained=${closeBacktest.abstainedTargets}/${closeBacktest.requestedTargets} · exact top-1 ${closeBacktest.top1ExactHits} (${pct(closeBacktest.top1ExactRate)}) · exact top-10 ${closeBacktest.top10ExactHits} (${pct(closeBacktest.top10ExactRate)}) · ank top-1 ${closeBacktest.top1AnkHits} (${pct(closeBacktest.top1AnkRate)}) · ank top-10 ${closeBacktest.top10AnkHits} (${pct(closeBacktest.top10AnkRate)}) · family top-1 ${closeBacktest.top1FamilyHits} (${pct(closeBacktest.top1FamilyRate)}) · family top-10 ${closeBacktest.top10FamilyHits} (${pct(closeBacktest.top10FamilyRate)}) · prior-mode ${closeBacktest.priorModeFamilyHits} (${pct(closeBacktest.priorModeFamilyRate)})", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Hit rates are historical out-of-sample measurements, not future guarantees. Relative evidence scores are not probabilities. Source verification status is shown in the reconciliation audit.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun CrossMarketResearchCard(report: com.relab.core.CrossMarketPairReport) {
    fun pct(count: Int): String =
        if (report.sharedDates == 0) "—" else "${"%.1f".format(count * 100.0 / report.sharedDates)}%"

    val openLinks = report.topOpenRelations.joinToString { "${it.fromFamilySequence}→${it.toFamilySequence} (${it.count})" }
    val closeLinks = report.topCloseRelations.joinToString { "${it.fromFamilySequence}→${it.toFamilySequence} (${it.count})" }
    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("${report.firstMarket} ↔ ${report.secondMarket}", style = MaterialTheme.typography.titleMedium)
            Text("Same-date/session comparisons: ${report.sharedDates}", style = MaterialTheme.typography.bodySmall)
            Text("Open family match: ${report.openFamilyMatches} (${pct(report.openFamilyMatches)}) · Close family match: ${report.closeFamilyMatches} (${pct(report.closeFamilyMatches)})")
            Text("Open Ank match: ${report.openAnkMatches} · Close Ank match: ${report.closeAnkMatches} · Exact Jodi match: ${report.jodiMatches} (${pct(report.jodiMatches)})")
            if (openLinks.isNotBlank()) Text("Top open-family relations: $openLinks", style = MaterialTheme.typography.bodySmall)
            if (closeLinks.isNotBlank()) Text("Top close-family relations: $closeLinks", style = MaterialTheme.typography.bodySmall)
            Text("Descriptive co-occurrence only. Shared dates do not establish causation or future prediction.", style = MaterialTheme.typography.labelSmall)
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

@Composable
private fun CandidateResearchCard(
    open: com.relab.core.CandidateRanking,
    close: com.relab.core.CandidateRanking,
    jodi: List<com.relab.core.JodiCandidate>
) {
    fun pct(count: Int, total: Int): String =
        if (total <= 0) "—" else "${"%.1f".format(count * 100.0 / total)}%"

    @Composable
    fun candidateRows(title: String, ranking: com.relab.core.CandidateRanking) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        ranking.candidates.take(5).forEachIndexed { index, candidate ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${index + 1}. ${candidate.panna} · ${candidate.pannaType}", style = MaterialTheme.typography.bodyMedium)
                Text("Exact ${pct(candidate.exactHistoricalCount, ranking.trainingRecords)}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Family ${pct(candidate.familyHistoricalCount, ranking.trainingRecords)} · weekday ${candidate.weekdayFamilyCount} · transition ${candidate.previousFamilyTransitionCount}",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text("${open.market} · ${open.sessionId}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Earlier records: ${open.trainingRecords} · Target: ${open.targetDate}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Exploratory single-source research · not independently verified",
                style = MaterialTheme.typography.labelSmall
            )
            if (open.abstained || close.abstained) {
                Text(open.note)
                Text(close.note)
            } else {
                candidateRows("Open Panna · historical frequency", open)
                candidateRows("Close Panna · historical frequency", close)
                Text("Derived Jodi · relative evidence rank", style = MaterialTheme.typography.titleSmall)
                jodi.take(5).forEachIndexed { index, candidate ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${index + 1}. ${candidate.jodi}", style = MaterialTheme.typography.bodyMedium)
                        Text("Index ${"%.1f".format(candidate.score)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "Exact % = exact panna count ÷ earlier records. Family % uses digit-family matches. Jodi index is not a probability.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** Fetches and stores history for the five markets enabled in this app. */
private fun importAllCanonicalCharts(
    save: (List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    updateObservations: (List<com.relab.core.SourceObservation>) -> Unit,
    updateMessage: (String) -> Unit,
    updateBusy: (Boolean) -> Unit
) {
    val sources = listOf(
        "https://sattamatkajodi.com/record/sridevi-satta-penal-chart.php",
        "https://sattamatkajodi.com/record/sridevi-night-satta-penal-chart.php",
        "https://sattamatkajodi.com/record/kalyan-penal-chart.php",
        "https://sattamatkajodi.com/main-bazar-panel-chart.php",
        "https://sattamatkajodi.com/time-bazar-panel-chart.php"
    )
    val mainHandler = Handler(Looper.getMainLooper())
    updateBusy(true)
    updateMessage("Fetching historical charts for all 5 enabled markets…")
    Thread {
        val imported = mutableListOf<com.relab.core.SourceObservation>()
        val outcomes = mutableListOf<String>()
        sources.forEach { sourceUrl ->
            val market = com.relab.core.Market.fromSourceUrl(sourceUrl)
            if (market == null) {
                outcomes += "Unknown market URL: $sourceUrl"
            } else {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL(sourceUrl).openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15000
                    connection.readTimeout = 25000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "RE-LAB-Android/0.1")
                    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    val code = connection.responseCode
                    require(code in 200..299) { "HTTP $code" }
                    val type = connection.contentType.orEmpty().lowercase()
                    require(type.contains("text/html") || type.contains("application/xhtml+xml") || type.isBlank()) { "Unexpected content type: $type" }
                    val bytes = connection.inputStream.use { readBoundedBytes(it, 8_000_000L) }
                    val report = com.relab.core.HtmlChartImporter.import(bytes.toString(Charsets.UTF_8), connection.url.toString(), market, "main", java.time.Instant.now())
                    imported += report.observations
                    outcomes += "${market.name}: ${report.observations.size} rows, ${report.issues.size} issues, ${report.skippedRows} skipped"
                } catch (error: Exception) {
                    outcomes += "${market.name}: FAILED — ${error.message ?: "unknown error"}"
                } finally {
                    connection?.disconnect()
                }
            }
        }
        // SQLite merge + bulk insert can involve thousands of observations. Keep it
        // off the main thread; only publish the completed immutable snapshot to Compose.
        val saveResult = if (imported.isEmpty()) null else runCatching { save(imported) }
        mainHandler.post {
            try {
                if (imported.isEmpty()) {
                    updateMessage("No chart rows fetched. Check internet/source availability, then tap Fetch / Refresh All 5 Markets. " + outcomes.joinToString(" | "))
                } else {
                    val merged = saveResult?.getOrThrow()
                        ?: error("Chart rows were fetched but local save did not complete")
                    updateObservations(merged)
                    updateMessage("Automatic 5-market fetch complete. Imported ${imported.size} observations; saved total ${merged.size}. " + outcomes.joinToString(" | "))
                }
            } catch (error: Exception) {
                updateMessage("Fetched charts but local save failed: ${error.message ?: "unknown error"}")
            } finally {
                updateBusy(false)
            }
        }
    }.start()
}

private fun importChartLink(
    rawUrl: String,
    market: com.relab.core.Market,
    save: (List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    updateObservations: (List<com.relab.core.SourceObservation>) -> Unit,
    updateMessage: (String) -> Unit,
    updateBusy: (Boolean) -> Unit
) {
    val cleanUrl = rawUrl.trim()
    val mainHandler = Handler(Looper.getMainLooper())
    updateBusy(true)
    updateMessage("Fetching chart page…")
    Thread {
        try {
            val uri = java.net.URI(cleanUrl)
            require(uri.scheme == "https" || uri.scheme == "http") { "Enter a valid HTTP or HTTPS link." }
            require(!uri.host.isNullOrBlank()) { "The link must include a website host." }
            val connection = URL(cleanUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "RE-LAB-Android/0.1")
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            try {
                val code = connection.responseCode
                require(code in 200..299) { "Website returned HTTP $code." }
                val type = connection.contentType.orEmpty().lowercase()
                require(type.contains("text/html") || type.contains("application/xhtml+xml") || type.isBlank()) {
                    "This link did not return an HTML chart page ($type)."
                }
                val bytes = connection.inputStream.use { readBoundedBytes(it, 8_000_000L) }
                val html = bytes.toString(Charsets.UTF_8)
                val report = com.relab.core.HtmlChartImporter.import(
                    html, connection.url.toString(), market, "main", java.time.Instant.now()
                )
                // Persist large chart imports on this worker thread, not the UI thread.
                val saveResult = if (report.observations.isEmpty()) null else runCatching {
                    save(report.observations)
                }
                mainHandler.post {
                    try {
                        if (report.observations.isEmpty()) {
                            val detail = report.issues.firstOrNull()
                                ?: "No dated chart-result rows found. This page may be JavaScript-rendered or use an unsupported layout."
                            updateMessage("No records imported: $detail Skipped rows: ${report.skippedRows}.")
                        } else {
                            val merged = saveResult?.getOrThrow()
                                ?: error("Chart rows were fetched but local save did not complete")
                            updateObservations(merged)
                            updateMessage(
                                "${market.name}: fetched ${report.observations.size} rows; total saved observations ${merged.size}. " +
                                    "Malformed rows ${report.issues.size}; skipped rows ${report.skippedRows}. Source: ${uri.host}."
                            )
                        }
                    } catch (error: Exception) {
                        updateMessage("Save failed: ${error.message ?: "Unknown error"}")
                    } finally {
                        updateBusy(false)
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            mainHandler.post {
                updateMessage("Link import failed: ${error.message ?: "Unknown error"}")
                updateBusy(false)
            }
        }
    }.start()
}
