package com.relab.app

import android.net.Uri
import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
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
import java.time.Instant
import java.security.MessageDigest
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
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

private data class PatternEvidenceDashboardSet(
    val market: com.relab.core.Market,
    val sessionId: String,
    val rankedEvidence: List<com.relab.core.RankedPatternEvidence>
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
    val crossMarketLeadLagEvidence: List<com.relab.core.CrossMarketLeadLagEvidence>,
    val crossMarketLeadLagBacktests: List<com.relab.core.CrossMarketWalkForwardReport>,
    val candidateSets: List<Triple<com.relab.core.CandidateRanking, com.relab.core.CandidateRanking, List<com.relab.core.JodiCandidate>>>,
    val reverseModelSets: List<ReverseModelDashboardSet>,
    val patternEvidenceSets: List<PatternEvidenceDashboardSet>,
    val reverseModelArtifacts: Map<String, String>,
    val sourceTiming: List<com.relab.core.SourceTimingSummary>,
    val sourceIndependence: List<com.relab.core.SourceIndependenceReport>
)

private data class DashboardAnalysisCacheEntry(
    val cacheKey: String,
    val snapshot: DashboardAnalysisSnapshot,
    val coverageReports: List<com.relab.core.CoverageReport>,
    val marketCacheKeys: Map<String, String>? = null,
    val coverageComplete: Boolean = false
)

private object DashboardAnalysisCache {
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, object : com.google.gson.TypeAdapter<LocalDate>() {
            override fun write(out: com.google.gson.stream.JsonWriter, value: LocalDate) {
                out.value(value.toString())
            }
            override fun read(input: com.google.gson.stream.JsonReader): LocalDate =
                LocalDate.parse(input.nextString())
        }.nullSafe())
        .registerTypeAdapter(Instant::class.java, object : com.google.gson.TypeAdapter<Instant>() {
            override fun write(out: com.google.gson.stream.JsonWriter, value: Instant) {
                out.value(value.toString())
            }
            override fun read(input: com.google.gson.stream.JsonReader): Instant =
                Instant.parse(input.nextString())
        }.nullSafe())
        .create()

    fun key(
        observations: List<com.relab.core.SourceObservation>,
        targetDate: LocalDate,
        ranges: Map<String, Pair<LocalDate, LocalDate>>
    ): String {
        val stableRows = SourceCsvExporter.export(observations)
        val rangeText = ranges.toSortedMap().entries.joinToString("|") {
            "${it.key}=${it.value.first},${it.value.second}"
        }
        val input = "dashboard-analysis-v1|$targetDate|$rangeText|$stableRows"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun read(context: android.content.Context, expectedKey: String): DashboardAnalysisCacheEntry? =
        readLatest(context)?.takeIf { it.cacheKey == expectedKey }

    fun readLatest(context: android.content.Context): DashboardAnalysisCacheEntry? {
        val file = File(context.filesDir, "dashboard-analysis-cache-v1.json")
        val atomicFile = android.util.AtomicFile(file)
        return try {
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use {
                gson.fromJson(it, DashboardAnalysisCacheEntry::class.java)
            }
        } catch (_: java.io.FileNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun marketKeys(
        observations: List<com.relab.core.SourceObservation>,
        targetDate: LocalDate
    ): Map<String, String> = observations
        .filter { it.market in com.relab.core.ReverseEngineeringModel.enabledMarkets }
        .groupBy { it.market to it.sessionId }
        .map { (identity, rows) ->
            val identityKey = "${identity.first.name}|${identity.second}"
            val input = "dashboard-market-analysis-v1|$targetDate|$identityKey|${SourceCsvExporter.export(rows)}"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            identityKey to digest
        }
        .toMap()

    fun write(context: android.content.Context, entry: DashboardAnalysisCacheEntry) {
        val atomicFile = android.util.AtomicFile(
            File(context.filesDir, "dashboard-analysis-cache-v1.json")
        )
        var output: java.io.FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(gson.toJson(entry).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            output?.let(atomicFile::failWrite)
            throw error
        }
    }
}

private fun buildDashboardAnalysisSnapshot(
    observations: List<com.relab.core.SourceObservation>,
    targetDate: LocalDate = LocalDate.now(),
    previousEntry: DashboardAnalysisCacheEntry? = null,
    marketCacheKeys: Map<String, String> = DashboardAnalysisCache.marketKeys(observations, targetDate)
): DashboardAnalysisSnapshot {
    val activeObservations = observations.filter {
        it.market in com.relab.core.ReverseEngineeringModel.enabledMarkets
    }
    val groups = com.relab.core.SourceReconciliation.reconcile(activeObservations)
    val repeatedSourceRecords = com.relab.core.SourceObservationMerger.auditRepeatedSourceRecords(activeObservations)
    val repeatedPublishedResults = com.relab.core.SourceObservationMerger.auditRepeatedPublishedResultsAcrossDates(activeObservations)
    val chartBuild = com.relab.core.ReconciledChartBuilder.build(activeObservations)
    val researchRecords = com.relab.core.ReconciledChartBuilder.buildExploratoryRecords(activeObservations)
    val patternAnalyses = researchRecords.groupBy { it.market to it.sessionId }
        .entries.sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
        .mapNotNull { (identity, marketRecords) ->
            val identityKey = "${identity.first.name}|${identity.second}"
            val reusable = previousEntry?.takeIf {
                it.marketCacheKeys?.get(identityKey) == marketCacheKeys[identityKey]
            }?.snapshot?.patternAnalyses?.firstOrNull {
                it.market == identity.first && it.sessionId == identity.second
            }
            reusable ?: com.relab.core.PatternAnalysis.analyze(marketRecords).singleOrNull()
        }
    val intelligenceReports = researchRecords.groupBy { it.market to it.sessionId }
        .entries.sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
        .mapNotNull { (identity, marketRecords) ->
            val identityKey = "${identity.first.name}|${identity.second}"
            val reusable = previousEntry?.takeIf {
                it.marketCacheKeys?.get(identityKey) == marketCacheKeys[identityKey]
            }?.snapshot?.intelligenceReports?.firstOrNull {
                it.market == identity.first && it.sessionId == identity.second
            }
            reusable ?: com.relab.core.MarketIntelligence.analyze(marketRecords).singleOrNull()
        }
    val crossMarketReports = com.relab.core.CrossMarketReverseEngineering.analyze(researchRecords)
    val crossMarketLeadLagEvidence = com.relab.core.ReverseEngineeringModel.analyzeCrossMarketLeadLag(
        researchRecords, targetDate, maxSourceAgeDays = 7
    )
    val crossMarketLeadLagBacktests = com.relab.core.ReverseEngineeringModel.backtestCrossMarketLeadLag(
        researchRecords, targetDate, maxSourceAgeDays = 7, minimumSignalOccurrences = 5
    )
    val marketSessions = researchRecords.map { it.market to it.sessionId }.distinct()
        .sortedWith(compareBy({ it.first.name }, { it.second }))
    val candidateSets = marketSessions.map { (market, sessionId) ->
        val identityKey = "${market.name}|$sessionId"
        val reusable = previousEntry?.takeIf {
            it.marketCacheKeys?.get(identityKey) == marketCacheKeys[identityKey]
        }?.snapshot?.candidateSets?.firstOrNull {
            it.first.market == market && it.first.sessionId == sessionId
        }
        reusable ?: run {
            val marketRecords = researchRecords.filter { it.market == market && it.sessionId == sessionId }
            val marketObservations = activeObservations.filter { it.market == market && it.sessionId == sessionId }
            val open = com.relab.core.CandidateRankingEngine.rank(
                marketObservations, market, sessionId, targetDate, com.relab.core.PannaSide.OPEN,
                limit = 10, minimumHistory = 5, allowSingleSourceExploration = true,
                prebuiltRecords = marketRecords
            )
            val close = com.relab.core.CandidateRankingEngine.rank(
                marketObservations, market, sessionId, targetDate, com.relab.core.PannaSide.CLOSE,
                limit = 10, minimumHistory = 5, allowSingleSourceExploration = true,
                prebuiltRecords = marketRecords
            )
            Triple(open, close, com.relab.core.CandidateRankingEngine.deriveJodiCandidates(open.candidates, close.candidates, 10))
        }
    }

    val reverseModelSets = marketSessions.map { (market, sessionId) ->
        val identityKey = "${market.name}|$sessionId"
        val reusable = previousEntry?.takeIf {
            it.marketCacheKeys?.get(identityKey) == marketCacheKeys[identityKey]
        }?.snapshot?.reverseModelSets?.firstOrNull {
            it.market == market && it.sessionId == sessionId
        }
        reusable ?: run {
            val marketRecords = researchRecords.filter { it.market == market && it.sessionId == sessionId }
            val open = com.relab.core.ReverseEngineeringModel.rank(
                marketRecords, market, sessionId, targetDate, com.relab.core.ReverseEngineeringSide.OPEN,
                limit = 100, minimumHistory = 30
            )
            val close = com.relab.core.ReverseEngineeringModel.rank(
                marketRecords, market, sessionId, targetDate, com.relab.core.ReverseEngineeringSide.CLOSE,
                limit = 10, minimumHistory = 30
            )
            ReverseModelDashboardSet(
                market, sessionId, open, close,
                com.relab.core.ReverseEngineeringModel.deriveJodiCandidates(open.candidates, close.candidates, 10)
            )
        }
    }

    val patternEvidenceSets = marketSessions.map { (market, sessionId) ->
        val identityKey = "${market.name}|$sessionId"
        val reusable = previousEntry?.takeIf {
            it.marketCacheKeys?.get(identityKey) == marketCacheKeys[identityKey]
        }?.snapshot?.patternEvidenceSets?.firstOrNull {
            it.market == market && it.sessionId == sessionId
        }
        reusable ?: PatternEvidenceDashboardSet(
            market, sessionId,
            com.relab.core.ReverseEngineeringModel.rankPatternEvidence(
                researchRecords.filter { it.market == market && it.sessionId == sessionId },
                market, sessionId, targetDate,
                windowLengths = 2..5, minimumEvaluations = 5, recentOccurrenceWindow = 20
            ).take(5)
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
        crossMarketReports, crossMarketLeadLagEvidence, crossMarketLeadLagBacktests, candidateSets, reverseModelSets, patternEvidenceSets, reverseModelArtifacts,
        com.relab.core.SourceTimingAnalytics.summarize(activeObservations),
        com.relab.core.SourceIndependenceAudit.summarize(activeObservations)
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
    val scope = rememberCoroutineScope()
    var showResearchTools by remember { mutableStateOf(false) }
    var rawCsv by remember { mutableStateOf(initialCsv) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedDateText by remember { mutableStateOf(LocalDate.now().toString()) }
    var dateError by remember { mutableStateOf<String?>(null) }
    var observations by remember { mutableStateOf(initialObservations) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var chartUrl by remember { mutableStateOf("") }
    var linkBusy by remember { mutableStateOf(false) }

    // Canonical chart fetching is explicit. Startup must never perform nine network requests.
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
    var analysisCompletedFor by remember { mutableStateOf<List<com.relab.core.SourceObservation>?>(null) }
    var coverageReports by remember { mutableStateOf<List<com.relab.core.CoverageReport>>(emptyList()) }
    var coverageLoading by remember { mutableStateOf(true) }
    var coverageError by remember { mutableStateOf<String?>(null) }
    var coverageCompletedFor by remember { mutableStateOf<String?>(null) }
    // Only compose one heavy report group at a time after the background analysis completes.
    // The previous eager Column/verticalScroll rendered every market/session card in one frame.
    var expandedDashboardSection by remember { mutableStateOf<String?>(null) }

    // Reuse a durable analysis snapshot when the exact dataset/date/settings are unchanged.
    // On a cache miss, compute off the UI thread and persist the completed result below.
    LaunchedEffect(observations, selectedDate, coverageRangeOverrides) {
        val rows = observations
        val ranges = coverageRangeOverrides
        val cacheKey = withContext(Dispatchers.Default) {
            DashboardAnalysisCache.key(rows, selectedDate, ranges)
        }
        val cached = withContext(Dispatchers.IO) {
            DashboardAnalysisCache.read(context, cacheKey)
        }
        if (cached != null) {
            analysisSnapshot = cached.snapshot
            coverageReports = cached.coverageReports
            analysisError = null
            analysisCompletedFor = rows
            coverageCompletedFor = if (cached.coverageComplete) cacheKey else null
            coverageError = null
            coverageLoading = !cached.coverageComplete
            return@LaunchedEffect
        }

        analysisSnapshot = null
        analysisError = null
        analysisCompletedFor = null
        coverageCompletedFor = null
        coverageLoading = true
        coverageError = null
        try {
            val previousEntry = withContext(Dispatchers.IO) {
                DashboardAnalysisCache.readLatest(context)
            }
            val marketKeys = withContext(Dispatchers.Default) {
                DashboardAnalysisCache.marketKeys(rows, selectedDate)
            }
            val computed = withContext(Dispatchers.Default) {
                buildDashboardAnalysisSnapshot(rows, selectedDate, previousEntry, marketKeys)
            }
            // Persist the expensive market-analysis snapshot before the separate
            // coverage audit starts. If Android closes the app during coverage,
            // the next launch restores this snapshot instead of rebuilding markets.
            withContext(Dispatchers.IO) {
                DashboardAnalysisCache.write(
                    context,
                    DashboardAnalysisCacheEntry(
                        cacheKey, computed, emptyList(), marketKeys, coverageComplete = false
                    )
                )
            }
            analysisSnapshot = computed
            analysisCompletedFor = rows
        } catch (cancelled: CancellationException) {
            // LaunchedEffect is expected to cancel when its inputs change or the screen leaves.
            // Cancellation is lifecycle control, not an analysis failure; never surface it as an error.
            throw cancelled
        } catch (error: Exception) {
            analysisError = error.message ?: "Unknown analysis error"
            analysisCompletedFor = rows
        }
    }

    // Run coverage only after the main analysis snapshot finishes. Running both
    // full-history pipelines at once doubled peak memory on cold launches.
    LaunchedEffect(observations, selectedDate, coverageRangeOverrides, analysisCompletedFor, analysisSnapshot, analysisError) {
        val rowsForCache = observations
        val rangesForCache = coverageRangeOverrides
        val cacheKey = withContext(Dispatchers.Default) {
            DashboardAnalysisCache.key(rowsForCache, selectedDate, rangesForCache)
        }
        if (coverageCompletedFor == cacheKey) return@LaunchedEffect
        if (analysisCompletedFor != observations) {
            coverageLoading = true
            return@LaunchedEffect
        }
        if (analysisSnapshot == null) {
            coverageLoading = false
            coverageReports = emptyList()
            coverageError = analysisError
            return@LaunchedEffect
        }
        coverageLoading = true
        coverageError = null
        try {
            val rows = observations.filter {
                it.market in com.relab.core.ReverseEngineeringModel.enabledMarkets
            }
            val ranges = coverageRangeOverrides
            val computed = withContext(Dispatchers.Default) {
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
            coverageReports = computed
            val completedSnapshot = analysisSnapshot ?: return@LaunchedEffect
            withContext(Dispatchers.IO) {
                DashboardAnalysisCache.write(
                    context,
                    DashboardAnalysisCacheEntry(
                        cacheKey, completedSnapshot, computed,
                        DashboardAnalysisCache.marketKeys(rowsForCache, selectedDate),
                        coverageComplete = true
                    )
                )
            }
            coverageCompletedFor = cacheKey
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            coverageReports = emptyList()
            coverageError = error.message ?: "Unknown coverage audit error"
        } finally {
            // Only the still-active effect owns this state; a cancelled effect must not
            // overwrite the loading state of a newer analysis generation.
            if (currentCoroutineContext().isActive) coverageLoading = false
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
    val crossMarketLeadLagEvidence = snapshot?.crossMarketLeadLagEvidence.orEmpty()
    val crossMarketLeadLagBacktests = snapshot?.crossMarketLeadLagBacktests.orEmpty()
    val candidateSets = snapshot?.candidateSets.orEmpty()
    val reverseModelSets = snapshot?.reverseModelSets.orEmpty()
    val patternEvidenceSets = snapshot?.patternEvidenceSets.orEmpty()
    val baseReverseModelArtifacts = snapshot?.reverseModelArtifacts.orEmpty()
    val sourceTiming = snapshot?.sourceTiming.orEmpty()
    val sourceIndependence = snapshot?.sourceIndependence.orEmpty()

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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reverseBacktestError = error.message ?: "Unknown walk-forward validation error"
        } finally {
            if (currentCoroutineContext().isActive) {
                reverseBacktestBusyKey = null
                reverseBacktestRequest = null
            }
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

    val startupLoading = linkBusy || (snapshot == null && analysisError == null) || coverageLoading
    val startupStatus = when {
        linkBusy -> "Refreshing all 9 markets…"
        snapshot == null && analysisError == null -> "Building analysis in background…"
        coverageLoading -> "Completing coverage audit in background…"
        else -> "Analysis ready"
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("RE-LAB", style = MaterialTheme.typography.headlineLarge)
                Text("Market Predictions", style = MaterialTheme.typography.titleMedium)
                if (startupLoading) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                        Text(startupStatus, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (analysisError != null) {
                    Text("Analysis error: $analysisError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    enabled = !linkBusy,
                    onClick = { scope.launch { importAllCanonicalCharts(onSaveLinkObservations, { observations = it }, { importMessage = it }, { linkBusy = it }) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (linkBusy) "Fetching 9 markets…" else "Fetch / Refresh All 9 Markets") }
                Text("Selected date", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(onClick = { selectedDate = selectedDate.minusDays(1); selectedDateText = selectedDate.toString(); dateError = null }) { Text("‹") }
                    OutlinedTextField(value = selectedDateText, onValueChange = { value ->
                        selectedDateText = value
                        try { selectedDate = LocalDate.parse(value.trim()); dateError = null } catch (_: Exception) { dateError = "Use YYYY-MM-DD" }
                    }, label = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { selectedDate = selectedDate.plusDays(1); selectedDateText = selectedDate.toString(); dateError = null }) { Text("›") }
                }
                dateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                if (reverseModelSets.isEmpty()) {
                    Text("Predictions will appear when enough verified history is available.")
                } else {
                    reverseModelSets.forEach { set ->
                        // Result visibility must not depend on multi-source consensus.
                        // A valid result from one publisher is still published (unverified),
                        // while conflicts must be shown for review rather than labelled pending.
                        val resultGroup = groups.firstOrNull {
                            it.market == set.market && it.sessionId == set.sessionId && it.date == selectedDate
                        }
                        MarketPredictionSheet(set, resultGroup, selectedDate)
                    }
                }
                OutlinedButton(
                    onClick = { showResearchTools = !showResearchTools },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (showResearchTools) "Hide Research & Data Tools" else "Research & Data Tools") }
                if (showResearchTools) {
                Button(
                    onClick = {
                        scope.launch {
                            val contextSummary = withContext(Dispatchers.Default) {
                                // Keep the scanner evidence sent to AI on the same
                                // multi-source consensus basis used by independent backtesting.
                                val consensusRecords = com.relab.core.ReconciledChartBuilder.build(activeObservations).records
                                val aiScannerRankings = consensusRecords
                                    .filter { it.date.isBefore(selectedDate) }
                                    .map { it.market to it.sessionId }
                                    .distinct()
                                    .flatMap { (market, sessionId) ->
                                        listOf(com.relab.core.ReverseEngineeringSide.OPEN, com.relab.core.ReverseEngineeringSide.CLOSE).map { side ->
                                            com.relab.core.ReverseEngineeringModel.rank(
                                                consensusRecords, market, sessionId, selectedDate, side,
                                                limit = 5, minimumHistory = 30
                                            )
                                        }
                                    }
                                com.relab.core.AiResearchContextBuilder.build(
                                    observations = activeObservations,
                                    exploratoryRecords = researchRecords,
                                    targetDateExclusive = selectedDate,
                                    maxRecordsPerMarketSession = 6,
                                    maxObservationsPerMarketSession = 6,
                                    maxChars = 20_000,
                                    scannerRankings = aiScannerRankings
                                )
                            }
                            context.startActivity(
                                Intent(context, AiResearchActivity::class.java)
                                    .putExtra(AiResearchActivity.EXTRA_RESEARCH_CONTEXT, contextSummary)
                                    .putExtra(AiResearchActivity.EXTRA_TARGET_DATE, selectedDate.toString())
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open Two-AI Research Lab") }
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
                            scope.launch { importChartLink(chartUrl, detected, onSaveLinkObservations, { observations = it }, { importMessage = it }, { linkBusy = it }) }
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
                            Text("No saved chart history. Import a CSV/bundle or use Fetch / Refresh All 9 Markets when you want to download charts.")
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
                OutlinedButton(onClick = { expandedDashboardSection = if (expandedDashboardSection == "patterns") null else "patterns" }) {
                    Text(if (expandedDashboardSection == "patterns") "Hide pattern research" else "View pattern research")
                }
                if (expandedDashboardSection == "patterns") {
                Text("Enabled markets: Sridevi · Sridevi Night · Kalyan · Main Bazar · Time Bazar · Milan Day · Milan Night · Rajdhani Day · Rajdhani Night.", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Analysis input: ${researchRecords.size} valid historical rows (${chartBuild.records.size} consensus + ${chartBuild.singleSourceUnverifiedGroups.size} single-source groups) · Conflicts excluded: ${chartBuild.conflictGroups.size} · Pending/missing excluded: ${chartBuild.noPublishedResultGroups.size} · Invalid excluded: ${chartBuild.invalidGroups.size}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (patternAnalyses.isEmpty()) {
                    Text(if (snapshot == null) "Analysing historical patterns…" else "No valid published rows available for pattern analysis yet. Check the imported chart rows and source format.")
                } else {
                    patternAnalyses.forEach { analysis -> PatternResearchCard(analysis) }
                }
                }
                Text("Cross-Market Reverse Engineering · nine enabled markets", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { expandedDashboardSection = if (expandedDashboardSection == "cross") null else "cross" }) {
                    Text(if (expandedDashboardSection == "cross") "Hide cross-market research" else "View cross-market research")
                }
                if (expandedDashboardSection == "cross") {
                Text(
                    "Compares matching dates and sessions across all nine enabled markets. Historical co-occurrence only; not a prediction or causal claim.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (crossMarketReports.isEmpty()) {
                    Text(if (snapshot == null) "Analysing same-date cross-market relationships…" else "Cross-market comparison will appear when at least two enabled markets have valid records for the same date and session.")
                } else {
                    crossMarketReports.forEach { CrossMarketResearchCard(it) }
                }
                Text("Cross-Market Lead/Lag Evidence · prior published results only", style = MaterialTheme.typography.titleMedium)
                Text(
                    "For each target result, uses only the latest source-market row published on an earlier chart date (maximum age 7 days). Pattern hit-rate is compared with the target market's prior global-frequency baseline. Same-day values and future rows are excluded. This is cross-market association from one canonical publisher, not independent-source confirmation or a causal claim.",
                    style = MaterialTheme.typography.bodySmall
                )
                val qualifiedLeadLag = crossMarketLeadLagEvidence
                    .filter { it.evaluatedOccurrences >= 5 && it.lift != null }
                    .groupBy { it.targetMarket }
                if (qualifiedLeadLag.isEmpty()) {
                    Text(if (snapshot == null) "Checking earlier-date cross-market signals…" else "No repeated cross-market signal has enough scored observations yet; the engine abstains rather than promoting weak evidence.")
                } else {
                    qualifiedLeadLag.toSortedMap(compareBy { it.name }).forEach { (targetMarket, evidenceRows) ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Target: $targetMarket", style = MaterialTheme.typography.titleSmall)
                                evidenceRows.sortedWith(
                                    compareByDescending<com.relab.core.CrossMarketLeadLagEvidence> { it.lift }
                                        .thenByDescending { it.evaluatedOccurrences }
                                        .thenBy { it.sourceMarket.name }
                                ).take(5).forEach { item ->
                                    Text(
                                        "${item.sourceMarket} → ${item.targetMarket} · ${item.channel} · signal ${item.sourceSignal}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "Hit ${item.hitRate?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"} vs baseline ${item.baselineHitRate?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"} · lift ${item.lift?.let { "%+.1f%%".format(it * 100.0) } ?: "n/a"} · n=${item.evaluatedOccurrences}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "Latest source ${item.latestSourceDate} → target ${item.latestTargetDate} · source age ${item.latestSourceAgeDays} days · distinct source IDs ${item.distinctSourceCount}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Cross-Market Lead/Lag · chronological walk-forward validation", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Out-of-sample: each target row is predicted before it is added to history. Requires at least 5 earlier matching signals; baseline uses all earlier target outcomes. 95% Wilson intervals show sampling uncertainty, not causal certainty.",
                    style = MaterialTheme.typography.bodySmall
                )
                val qualifiedLeadLagBacktests = crossMarketLeadLagBacktests
                    .filter { it.evaluatedTargets >= 5 && it.lift != null }
                    .sortedWith(
                        compareByDescending<com.relab.core.CrossMarketWalkForwardReport> { it.lift }
                            .thenByDescending { it.evaluatedTargets }
                            .thenBy { it.targetMarket.name }
                            .thenBy { it.sourceMarket.name }
                    )
                if (qualifiedLeadLagBacktests.isEmpty()) {
                    Text(if (snapshot == null) "Running chronological lead/lag validation…" else "No lead/lag rule has 5 or more out-of-sample evaluations yet; no rule is promoted.")
                } else {
                    qualifiedLeadLagBacktests.take(12).forEach { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${item.sourceMarket} → ${item.targetMarket} · ${item.channel}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Walk-forward ${item.hitRate?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"} vs baseline ${item.baselineHitRate?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"} · lift ${item.lift?.let { "%+.1f%%".format(it * 100.0) } ?: "n/a"} · n=${item.evaluatedTargets}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "95% Wilson CI ${item.confidence95Lower?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"}–${item.confidence95Upper?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"} · abstained ${item.abstainedTargets}/${item.requestedTargets} · source IDs ${item.distinctSourceCount}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                }
                Text("Market Intelligence · market-specific behaviour", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { expandedDashboardSection = if (expandedDashboardSection == "intelligence") null else "intelligence" }) {
                    Text(if (expandedDashboardSection == "intelligence") "Hide market intelligence" else "View market intelligence")
                }
                if (expandedDashboardSection == "intelligence") {
                Text(
                    "Each market is analysed independently. Recent-vs-older distribution changes are descriptive alerts, not future-result predictions.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (intelligenceReports.isEmpty()) {
                    Text(if (snapshot == null) "Calculating market regime and distribution shifts…" else "Market intelligence will appear after valid published chart rows are available.")
                } else {
                    intelligenceReports.forEach { MarketIntelligenceCard(it) }
                }
                }
                Text("Reverse Engineering Model · evidence-ranked research", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { expandedDashboardSection = if (expandedDashboardSection == "reverse") null else "reverse" }) {
                    Text(if (expandedDashboardSection == "reverse") "Hide reverse-engineering details" else "View reverse-engineering details")
                }
                if (expandedDashboardSection == "reverse") {
                Text(
                    "Exploratory model uses valid consensus + single-source rows (single-source remains unverified); conflicts, pending and malformed rows are excluded. Combines exact panna, digit-family DNA (1/6, 2/7, 3/8, 4/9, 5/0), Ank, digit positions, SP/DP/TP, reverse panna, weekday, transitions and lagged cross-market conditions. Requires 30 earlier valid rows.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (snapshot == null) {
                    Text(analysisError?.let { "Research analysis error: $it" } ?: "Analysing nine-market history and running chronological backtests off the UI thread…")
                } else if (reverseModelSets.isEmpty()) {
                    Text("No valid history is available for the nine enabled markets yet.")
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
                    Text("Pattern Evidence Database · chronological validation", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Exact 2–5 row sequences ranked by walk-forward lift over the same-channel global-frequency baseline. Recency and rolling hit-rate stability adjust ranking; insufficient samples are never promoted as proven signals.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    patternEvidenceSets.forEach { evidenceSet ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${evidenceSet.market.name} · ${evidenceSet.sessionId}", style = MaterialTheme.typography.titleSmall)
                                if (evidenceSet.rankedEvidence.isEmpty()) {
                                    Text("Not enough valid chart rows for pattern evidence.")
                                } else {
                                    evidenceSet.rankedEvidence.forEach { ranked ->
                                        val item = ranked.evidence
                                        val lift = item.baselineLift?.let { "%+.1f%%".format(it * 100.0) } ?: "n/a"
                                        val hit = item.hitRate?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"
                                        val baseline = item.baselineHitRate?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"
                                        Text(
                                            "${item.channel} · ${item.windowLength} rows · ${item.pattern}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "${ranked.assessment} · hit $hit vs baseline $baseline · lift $lift · n=${item.evaluatedOccurrences} · age=${item.ageInChartRows} rows",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "Recent ${item.recentHitRate?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"} · weighted ${item.recencyWeightedHitRate?.let { "%.1f%%".format(it * 100.0) } ?: "n/a"} · rolling σ ${item.rollingHitRateStdDev?.let { "%.3f".format(it) } ?: "n/a"} · rank ${"%.2f".format(ranked.rankScore)}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
                Text("Next draw candidate research · legacy frequency baseline", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { expandedDashboardSection = if (expandedDashboardSection == "candidates") null else "candidates" }) {
                    Text(if (expandedDashboardSection == "candidates") "Hide candidate research" else "View candidate research")
                }
                if (expandedDashboardSection == "candidates") {
                if (candidateSets.isEmpty()) {
                    Text(if (snapshot == null) "Preparing legacy baseline candidates…" else "No usable published history yet. Candidates require at least 5 earlier valid rows; single-source results are exploratory and remain unverified.")
                } else {
                    candidateSets.forEach { (open, close, jodi) ->
                        CandidateResearchCard(open, close, jodi)
                    }
                }
                }
                Text("Historical coverage audit", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { expandedDashboardSection = if (expandedDashboardSection == "coverage") null else "coverage" }) {
                    Text(if (expandedDashboardSection == "coverage") "Hide coverage audit" else "View coverage audit")
                }
                if (expandedDashboardSection == "coverage") {
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
                }
                Text("Independent publisher cross-check", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { expandedDashboardSection = if (expandedDashboardSection == "publishers") null else "publishers" }) {
                    Text(if (expandedDashboardSection == "publishers") "Hide publisher audit" else "View publisher audit")
                }
                if (expandedDashboardSection == "publishers") {
                if (sourceIndependence.isEmpty()) {
                    Text(if (snapshot == null) "Auditing publisher independence…" else "No published source observations available for publisher comparison.")
                } else {
                    sourceIndependence.forEach { SourceIndependenceCard(it) }
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
                OutlinedButton(onClick = { expandedDashboardSection = if (expandedDashboardSection == "recent") null else "recent" }) {
                    Text(if (expandedDashboardSection == "recent") "Hide recent observations" else "View recent observations")
                }
                if (expandedDashboardSection == "recent") {
                if (groups.isEmpty()) {
                    Text("No source records loaded.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.asReversed().take(30).forEach { group -> SourceGroupCard(group) }
                    }
                }
                }
                Text("Offline SQLite · Source provenance preserved · Conflicts never auto-resolved", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}


private data class PredictionDisplayCell(
    val title: String,
    val value: String,
    val score: Double,
    val passed: Boolean?
)

@Composable
private fun MarketPredictionSheet(
    set: ReverseModelDashboardSet,
    resultGroup: com.relab.core.ReconciliationGroup?,
    selectedDate: LocalDate
) {
    val open = set.open.candidates.take(10)
    val close = set.close.candidates.take(10)
    val openPannas = open.map { it.panna }
    val closePannas = close.map { it.panna }
    val openAnks = open.groupBy { it.ank }.map { (ank, rows) -> ank to rows.maxOf { it.score } }
        .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
        .take(10).map { it.first.toString() }
    val jodis = set.jodi.take(10).map { it.jodi }
    val sangams = open.flatMap { o -> close.map { c -> (o.panna + "-" + c.panna) to (o.score * c.score / 100.0) } }
        .sortedByDescending { it.second }.take(10).map { it.first }
    val halfSangams = open.flatMap { o -> close.map { c -> (o.panna + "-" + c.ank) to (o.score * c.score / 100.0) } }
        .sortedByDescending { it.second }.distinctBy { it.first }.take(10).map { it.first }
    val marketName = when (set.market) {
        com.relab.core.Market.SRIDEVI -> "Sridevi Day"
        com.relab.core.Market.SRIDEVI_NIGHT -> "Sridevi Night"
        com.relab.core.Market.KALYAN -> "Kalyan"
        com.relab.core.Market.MAIN_BAZAR -> "Main Bazar"
        com.relab.core.Market.TIME_BAZAR -> "Time Bazar"
        else -> set.market.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
    }
    val publishedValues = resultGroup?.observations
        ?.filter { it.status == com.relab.core.ObservationStatus.PUBLISHED }
        ?.mapNotNull { it.normalizedResult }
        ?.distinct()
        ?.sorted()
        .orEmpty()
    val hasPublishedResult = publishedValues.isNotEmpty()
    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("$marketName · ${set.sessionId}", style = MaterialTheme.typography.titleSmall)
            if (hasPublishedResult) {
                val conflict = publishedValues.size > 1
                val resultLabel = when {
                    conflict -> "RESULT CONFLICT · REVIEW SOURCES"
                    resultGroup?.state == com.relab.core.ReconciliationState.CONSENSUS -> "RESULT PUBLISHED · VERIFIED"
                    else -> "RESULT PUBLISHED · SINGLE SOURCE"
                }
                Text("$resultLabel · $selectedDate", color = if (conflict) MaterialTheme.colorScheme.error else Color(0xFF1B5E20), style = MaterialTheme.typography.labelMedium)
                publishedValues.forEach { value ->
                    val parts = value.split('-')
                    if (parts.size == 3) {
                        PredictionTableRow("Open Panna", parts[0], !conflict)
                        PredictionTableRow("Jodi", parts[1], !conflict)
                        PredictionTableRow("Close Panna", parts[2], !conflict)
                    } else {
                        PredictionTableRow("Published value", value, !conflict)
                    }
                }
            } else {
                Text("PREDICTION · ${set.open.targetDate} · Result pending", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                PredictionTableRow("Open Ank", openAnks.joinToString(" · "), false, true)
                PredictionTableRow("Panna", openPannas.take(6).joinToString(" · "), false, true)
                PredictionTableRow("Close Panna", closePannas.take(6).joinToString(" · "), false, true)
                PredictionTableRow("Jodi", jodis.take(6).joinToString(" · "), false, true)
                PredictionTableRow("Sangam", sangams.take(6).joinToString(" · "), false, true)
                PredictionTableRow("Half Sangam", halfSangams.take(6).joinToString(" · "), false, true)
                if (set.open.abstained || set.close.abstained) Text("Insufficient history for one or more groups.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PredictionTableRow(label: String, value: String, isResult: Boolean, isPrediction: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(88.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = if (isResult) Color(0xFF1B5E20) else if (isPrediction) Color(0xFFB71C1C) else MaterialTheme.colorScheme.onSurface)
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
private fun SourceIndependenceCard(report: com.relab.core.SourceIndependenceReport) {
    fun percent(value: Double?): String =
        value?.let { "${"%.1f".format(it * 100.0)}%" } ?: "—"

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${report.market} · ${report.sessionId}", style = MaterialTheme.typography.titleSmall)
            Text(
                "Independent publishers: ${report.independentPublisherCount} · ${report.publisherIds.joinToString().ifBlank { "None" }}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Published dates: ${report.publishedDates} · multi-publisher: ${report.multiPublisherDates} · consensus: ${report.consensusDates} · single-publisher: ${report.singlePublisherDates} · conflicts: ${report.conflictDates}",
                style = MaterialTheme.typography.bodySmall
            )
            if (report.pairAgreements.isEmpty()) {
                Text("No independent publisher pair overlaps yet; this market has no cross-publisher agreement measurement.", style = MaterialTheme.typography.labelSmall)
            } else {
                report.pairAgreements.forEach { pair ->
                    Text(
                        "${pair.firstPublisherId} ↔ ${pair.secondPublisherId}: agree ${pair.agreedDates}, conflict ${pair.conflictingDates}, ambiguous ${pair.ambiguousDates} / shared ${pair.sharedPublishedDates} · agreement ${percent(pair.agreementRate)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                "Pair agreement excludes dates where either publisher has multiple distinct values. Pages/snapshots from one publisher count once.",
                style = MaterialTheme.typography.labelSmall
            )
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

/** Fetches all nine enabled markets in order and persists each successful page immediately. */
private suspend fun importAllCanonicalCharts(
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
        "https://sattamatkajodi.com/time-bazar-panel-chart.php",
        "https://sattamatkajodi.com/record/milan-day-penal-chart.php",
        "https://sattamatkajodi.com/record/milan-night-penal-chart.php",
        "https://sattamatkajodi.com/record/rajdhani-day-penal-chart.php",
        "https://sattamatkajodi.com/record/rajdhani-night-penal-chart.php"
    )
    val outcomes = mutableListOf<String>()
    var latestSaved: List<com.relab.core.SourceObservation>? = null
    var importedCount = 0
    updateBusy(true)
    updateMessage("Fetching historical charts for all 9 enabled markets…")
    try {
        for (sourceUrl in sources) {
            currentCoroutineContext().ensureActive()
            val market = com.relab.core.Market.fromSourceUrl(sourceUrl)
            if (market == null) {
                outcomes += "Unknown market URL: $sourceUrl"
                continue
            }

            val report = try {
                withContext(Dispatchers.IO) {
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
                        require(type.contains("text/html") || type.contains("application/xhtml+xml") || type.isBlank()) {
                            "Unexpected content type: $type"
                        }
                        val bytes = connection.inputStream.use { readBoundedBytes(it, 8_000_000L) }
                        com.relab.core.HtmlChartImporter.import(
                            bytes.toString(Charsets.UTF_8), connection.url.toString(),
                            market, "main", java.time.Instant.now()
                        )
                    } finally {
                        connection?.disconnect()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                outcomes += "${market.name}: FAILED — ${error.message ?: "unknown error"}"
                continue
            }

            if (report.observations.isEmpty()) {
                outcomes += "${market.name}: 0 rows, ${report.issues.size} issues, ${report.skippedRows} skipped"
                continue
            }

            try {
                // Persist each market as soon as it succeeds. If Android destroys this
                // Activity mid-fetch, already completed markets survive the next launch.
                latestSaved = withContext(Dispatchers.IO) { save(report.observations) }
                importedCount += report.observations.size
                outcomes += "${market.name}: ${report.observations.size} rows, ${report.issues.size} issues, ${report.skippedRows} skipped"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                outcomes += "${market.name}: SAVE FAILED — ${error.message ?: "unknown error"}"
            }
        }

        latestSaved?.let(updateObservations)
        if (importedCount == 0) {
            updateMessage(
                "No chart rows fetched. Check internet/source availability, then tap Fetch / Refresh All 9 Markets. " +
                    outcomes.joinToString(" | ")
            )
        } else {
            updateMessage(
                "Automatic 9-market fetch complete. Imported $importedCount observations; saved total " +
                    "${latestSaved?.size ?: 0}. " + outcomes.joinToString(" | ")
            )
        }
    } catch (cancelled: CancellationException) {
        // Compose cancels this job when the owning Activity leaves composition.
        // Per-market commits above remain durable and the next launch resumes missing markets.
        throw cancelled
    } catch (error: Exception) {
        updateMessage("Chart fetch failed: ${error.message ?: "Unknown error"}")
    } finally {
        updateBusy(false)
    }
}

private suspend fun importChartLink(
    rawUrl: String,
    market: com.relab.core.Market,
    save: (List<com.relab.core.SourceObservation>) -> List<com.relab.core.SourceObservation>,
    updateObservations: (List<com.relab.core.SourceObservation>) -> Unit,
    updateMessage: (String) -> Unit,
    updateBusy: (Boolean) -> Unit
) {
    val cleanUrl = rawUrl.trim()
    updateBusy(true)
    updateMessage("Fetching chart page…")
    try {
        val report = withContext(Dispatchers.IO) {
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
                com.relab.core.HtmlChartImporter.import(
                    bytes.toString(Charsets.UTF_8), connection.url.toString(),
                    market, "main", java.time.Instant.now()
                )
            } finally {
                connection.disconnect()
            }
        }

        if (report.observations.isEmpty()) {
            val detail = report.issues.firstOrNull()
                ?: "No dated chart-result rows found. This page may be JavaScript-rendered or use an unsupported layout."
            updateMessage("No records imported: $detail Skipped rows: ${report.skippedRows}.")
        } else {
            val merged = withContext(Dispatchers.IO) { save(report.observations) }
            updateObservations(merged)
            updateMessage(
                "${market.name}: fetched ${report.observations.size} rows; total saved observations ${merged.size}. " +
                    "Malformed rows ${report.issues.size}; skipped rows ${report.skippedRows}. Source: ${java.net.URI(cleanUrl).host}."
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        updateMessage("Link import failed: ${error.message ?: "Unknown error"}")
    } finally {
        updateBusy(false)
    }
}

@Composable
private fun StartupLoadingScreen(status: String, detail: String) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text("RE-LAB", style = MaterialTheme.typography.headlineLarge)
                Text("Reverse Engineering Research Lab", style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(14.dp))
                CircularProgressIndicator(strokeWidth = 5.dp)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(10.dp))
                Text(status, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
                Text("The workspace opens after startup data and analysis are ready.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
