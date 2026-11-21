package com.relab.app
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import com.relab.core.ReverseEngineeringModel
import com.relab.core.ReverseEngineeringSide
import com.relab.core.EndToEndResearchAuditWorkflow
import com.relab.core.ResearchToBacktestCsvExporter

class AiResearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent.getStringExtra(EXTRA_RESEARCH_CONTEXT).orEmpty()
        val targetDate = intent.getStringExtra(EXTRA_TARGET_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        setContent { AiResearchScreen(this, initial, targetDate) { finish() } }
    }
    companion object {
        const val EXTRA_RESEARCH_CONTEXT = "relab.ai.RESEARCH_CONTEXT"
        const val EXTRA_TARGET_DATE = "relab.ai.TARGET_DATE"
    }
}

@Composable
private fun AiResearchScreen(context: Context, initialContext: String, targetDate: LocalDate, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val vault = remember { AiKeyVault(context) }
    val store = remember { AiResearchMemoryStore(context) }
    val engine = remember { AiResearchEngine() }
    var geminiKey by remember { mutableStateOf("") }
    var groqKey by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("Find reproducible logic patterns, challenge assumptions, and propose leakage-free tests. Treat predictions as experimental.") }
    var evidence by remember { mutableStateOf(initialContext) }
    var keyStatus by remember { mutableStateOf("Checking encrypted credentials…") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<AiResearchResult?>(null) }
    var history by remember { mutableStateOf<List<AiResearchEntry>>(emptyList()) }
    var pendingSession by remember { mutableStateOf<AiResearchSession?>(null) }
    var pendingLoop by remember { mutableStateOf<AiResearchLoopSession?>(null) }
    var loopResult by remember { mutableStateOf<AiResearchLoopResult?>(null) }
    var backtestReport by remember { mutableStateOf<String?>(null) }
    var backtestCsv by remember { mutableStateOf<String?>(null) }
    var walkForwardCsv by remember { mutableStateOf<String?>(null) }
    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val csv = backtestCsv.orEmpty()
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(csv.toByteArray(Charsets.UTF_8))
                        } ?: error("Could not open the selected destination")
                    }
                    status = "Backtest CSV exported successfully."
                } catch (error: Exception) {
                    status = "CSV export failed: " + (error.message ?: "unknown error")
                }
            }
        }
    }
    val walkForwardCsvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val csv = walkForwardCsv.orEmpty()
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(csv.toByteArray(Charsets.UTF_8))
                        } ?: error("Could not open the selected destination")
                    }
                    status = "Multi-model walk-forward CSV exported successfully."
                } catch (error: Exception) {
                    status = "Walk-forward CSV export failed: " + (error.message ?: "unknown error")
                }
            }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) status = "Notifications are disabled. Android may show background activity only in its active-apps panel."
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        history = withContext(Dispatchers.IO) { store.latest() }
        pendingSession = withContext(Dispatchers.IO) { store.latestResumable() }
        pendingLoop = withContext(Dispatchers.IO) { store.latestResumableLoop() }
        keyStatus = "Gemini: " + (if (vault.hasKey(AiKeyVault.GEMINI)) "saved securely" else "not configured")
        keyStatus += " · Groq: " + (if (vault.hasKey(AiKeyVault.GROQ)) "saved securely" else "not configured")

        var previousWorkState: androidx.work.WorkInfo.State? = null
        while (true) {
            val workState = withContext(Dispatchers.IO) {
                androidx.work.WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(AiResearchLoopWorker.UNIQUE_WORK_NAME)
                    .get().firstOrNull()?.state
            }
            busy = workState == androidx.work.WorkInfo.State.ENQUEUED ||
                workState == androidx.work.WorkInfo.State.RUNNING ||
                workState == androidx.work.WorkInfo.State.BLOCKED
            if (workState != previousWorkState) {
                when (workState) {
                    androidx.work.WorkInfo.State.ENQUEUED -> status = "Background research queued. You can leave the app."
                    androidx.work.WorkInfo.State.RUNNING -> status = "Background research is running. You can close this screen; progress is saved."
                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        history = withContext(Dispatchers.IO) { store.latest() }
                        pendingSession = withContext(Dispatchers.IO) { store.latestResumable() }
                        pendingLoop = withContext(Dispatchers.IO) { store.latestResumableLoop() }
                        // Do not hydrate the full multi-stage AI transcript into Compose state here.
                        // A three-round loop can produce a very large report; rendering it in one Text
                        // tree after completion can freeze the UI and trigger an Android ANR.
                        // Keep the full result persisted in SQLite; the bounded history preview and
                        // explicit backtest action remain available without eagerly composing it.
                        result = null
                        loopResult = null
                        status = "Background AI research completed. Full results are saved; use the backtest action below. Results remain UNVERIFIED until backtested."
                    }
                    androidx.work.WorkInfo.State.FAILED -> {
                        pendingSession = withContext(Dispatchers.IO) { store.latestResumable() }
                        pendingLoop = withContext(Dispatchers.IO) { store.latestResumableLoop() }
                        val savedError = pendingSession?.lastError ?: pendingLoop?.lastError
                        status = savedError?.let { "Background research paused: " + it }
                            ?: "Background research failed. Saved checkpoints are preserved."
                    }
                    androidx.work.WorkInfo.State.CANCELLED -> {
                        pendingSession = withContext(Dispatchers.IO) { store.latestResumable() }
                        pendingLoop = withContext(Dispatchers.IO) { store.latestResumableLoop() }
                        status = "Background research cancelled. Saved checkpoints are preserved."
                    }
                    else -> Unit
                }
                previousWorkState = workState
            }
            delay(1500)
        }
    }
    fun launchResearch(session: AiResearchSession) {
        result = null
        busy = true
        status = "Queueing persistent background cross-review. The app can be closed while it runs."
        try {
            AiResearchBackgroundWork.enqueueSession(context, session.id)
        } catch (error: Exception) {
            busy = false
            status = "Could not queue background cross-review: " + (error.message ?: "unknown error")
        }
    }
    fun launchAutoLoop(session: AiResearchLoopSession) {
        result = null
        loopResult = null
        busy = true
        status = "Queueing persistent background research. The app can be closed while it runs."
        try {
            AiResearchBackgroundWork.enqueueLoop(context, session.id)
        } catch (error: Exception) {
            busy = false
            status = "Could not queue background research: " + (error.message ?: "unknown error")
        }
    }
    fun launchBacktest(synthesis: String) {
        scope.launch {
            busy = true
            backtestReport = null
            backtestCsv = null
            walkForwardCsv = null
            status = "Extracting AI proposals and running the complete chronological research audit…"
            try {
                val parsed = AiPatternProposalParser.parse(synthesis)
                val report = withContext(Dispatchers.IO) {
                    val observations = LocalObservationDatabase(context).loadObservations()
                    // Verification uses only multi-source consensus rows. Exploratory/single-source
                    // records may inform AI hypotheses but cannot independently verify them.
                    val records = com.relab.core.ReconciledChartBuilder.build(observations).records
                    val sessions = records.filter { it.date.isBefore(targetDate) }
                        .map { it.market to it.sessionId }.distinct()
                    val scannerRankings = sessions.flatMap { (market, session) ->
                        ReverseEngineeringSide.entries.map { side ->
                            ReverseEngineeringModel.rank(records, market, session, targetDate, side, limit = 10, minimumHistory = 30)
                        }
                    }
                    EndToEndResearchAuditWorkflow.run(
                        records = records,
                        scannerRankings = scannerRankings,
                        proposals = parsed.proposals,
                        targetDateExclusive = targetDate,
                        minimumModelHistory = 30,
                        candidateLimit = 10
                    )
                }
                backtestReport = report.compactSummary() + if (parsed.issues.isEmpty()) "" else "\n\nProposal parser notes:\n" + parsed.issues.joinToString("\n")
                backtestCsv = ResearchToBacktestCsvExporter.export(report.research)
                walkForwardCsv = EndToEndResearchAuditWorkflow.exportWalkForwardCsv(report)
                status = if (parsed.proposals.isEmpty()) {
                    "Research report completed; AI supplied no executable patterns. Scanner and Pattern Evidence DB results are still available. " +
                        parsed.issues.joinToString("; ")
                } else {
                    "Backtest completed. Only statistical evidence can change a proposal's verification status."
                }
            } catch (error: Exception) {
                backtestReport = "Backtest not completed: ${error.message ?: "unknown error"}"
                status = "No proposal was marked verified."
            } finally { busy = false }
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Text("Two-AI Research Lab", style = MaterialTheme.typography.titleLarge)
                }
                Text("Gemini proposes logic. Groq independently challenges it. Gemini reconciles both reviews.")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("API credentials", style = MaterialTheme.typography.titleMedium)
                        Text("Keys are encrypted with Android Keystore AES-GCM and are not embedded in source code or written to logs.")
                        OutlinedTextField(geminiKey, { geminiKey = it }, label = { Text("Gemini API key") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(groqKey, { groqKey = it }, label = { Text("Groq API key") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && (geminiKey.isNotBlank() || groqKey.isNotBlank()), onClick = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        if (geminiKey.isNotBlank()) vault.save(AiKeyVault.GEMINI, geminiKey)
                                        if (groqKey.isNotBlank()) vault.save(AiKeyVault.GROQ, groqKey)
                                    }
                                    geminiKey = ""; groqKey = ""
                                    keyStatus = "Gemini: " + (if (vault.hasKey(AiKeyVault.GEMINI)) "saved securely" else "not configured")
                                    keyStatus += " · Groq: " + (if (vault.hasKey(AiKeyVault.GROQ)) "saved securely" else "not configured")
                                    status = "Credentials encrypted and saved on this device."
                                } catch (e: Exception) { status = "Credential save failed: " + (e.message ?: "unknown error") }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save keys securely") }
                        Text(keyStatus, style = MaterialTheme.typography.bodySmall)
                        Text("Create keys: aistudio.google.com/apikey · console.groq.com/keys", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Research task", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(question, { question = it }, label = { Text("Research question") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(evidence, { evidence = it }, label = { Text("Evidence / dataset context") }, minLines = 5, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && question.isNotBlank(), onClick = {
                            scope.launch {
                                try {
                                    val prior = history.take(3).joinToString("\n\n") {
                                        "Earlier note (UNVERIFIED): " + it.question.take(250) + "\n" + it.finalSynthesis.take(800)
                                    }
                                    val session = withContext(Dispatchers.IO) { store.createSession(question, evidence, prior) }
                                    pendingSession = session
                                    launchResearch(session)
                                } catch (e: Exception) { status = "Could not start research: " + (e.message ?: "unknown error") }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Run 2-AI cross-review") }
                        pendingSession?.let { session ->
                            Text("Saved research session: " + session.state + " · " +
                                (if (session.finalSynthesis.isNotBlank()) "final synthesis saved" else if (session.groqReview.isNotBlank()) "Groq review saved" else if (session.geminiAnalysis.isNotBlank()) "Gemini analysis saved" else "waiting for first stage"),
                                style = MaterialTheme.typography.bodySmall)
                            if (session.geminiAnalysis.isBlank() && session.groqReview.isBlank() && session.finalSynthesis.isBlank()) {
                                Text("No stage checkpoint exists yet. Resume will retry the first Gemini stage.", style = MaterialTheme.typography.bodySmall)
                            }
                            session.lastError?.takeIf { it.isNotBlank() }?.let {
                                Text("Last error: " + it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(enabled = !busy, onClick = { launchResearch(session) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Resume research from saved stage")
                            }
                        }
                        Button(enabled = !busy && question.isNotBlank() && evidence.isNotBlank(), onClick = {
                            scope.launch {
                                try {
                                    val prior = history.take(3).joinToString("\n\n") {
                                        "Earlier note (UNVERIFIED): " + it.question.take(250) + "\n" + it.finalSynthesis.take(800)
                                    }
                                    val session = withContext(Dispatchers.IO) {
                                        store.createLoopSession(question, evidence, prior, AiResearchLoopEngine.DEFAULT_MAX_ROUNDS)
                                    }
                                    pendingLoop = session
                                    launchAutoLoop(session)
                                } catch (e: Exception) { status = "Could not start auto loop: " + (e.message ?: "unknown error") }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Start Auto Research Loop · 3 rounds") }
                        pendingLoop?.let { session ->
                            Text("Saved auto loop: " + session.state + " · " + session.rounds.count {
                                it.checkpoint.geminiAnalysis.isNotBlank() && it.checkpoint.groqReview.isNotBlank() &&
                                    it.checkpoint.finalSynthesis.isNotBlank()
                            } + "/" + session.maxRounds + " rounds fully checkpointed", style = MaterialTheme.typography.bodySmall)
                            session.rounds.firstOrNull { it.checkpoint.finalSynthesis.isBlank() }?.let { round ->
                                val stage = when {
                                    round.checkpoint.geminiAnalysis.isBlank() -> "Gemini pending"
                                    round.checkpoint.groqReview.isBlank() -> "Groq review pending"
                                    else -> "Final synthesis pending"
                                }
                                Text("Resume point: Round " + round.roundNumber + " · " + stage, style = MaterialTheme.typography.bodySmall)
                            }
                            session.lastError?.takeIf { it.isNotBlank() }?.let {
                                Text("Last error: " + it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(enabled = !busy, onClick = { launchAutoLoop(session) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Resume auto loop from saved stage")
                            }
                        }
                        loopResult?.let { Text(it.report()) }
                        val savedSynthesis = loopResult?.finalSynthesis?.takeIf { it.isNotBlank() }
                            ?: result?.finalSynthesis?.takeIf { it.isNotBlank() }
                            ?: history.firstOrNull()?.finalSynthesis.orEmpty()
                        if (savedSynthesis.isNotBlank()) {
                            Button(enabled = !busy, onClick = { launchBacktest(savedSynthesis) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Backtest latest saved AI research")
                            }
                        }
                        backtestReport?.let {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Chronological verification · cutoff ${targetDate} (exclusive)", style = MaterialTheme.typography.titleMedium)
                                    Text(it)
                                    if (!backtestCsv.isNullOrBlank()) {
                                        OutlinedButton(
                                            enabled = !busy,
                                            onClick = { csvExportLauncher.launch("relab-research-verification-" + targetDate + ".csv") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Export research verification CSV") }
                                    }
                                    if (!walkForwardCsv.isNullOrBlank()) {
                                        OutlinedButton(
                                            enabled = !busy,
                                            onClick = { walkForwardCsvExportLauncher.launch("relab-multi-model-walk-forward-" + targetDate + ".csv") },
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("Export multi-model walk-forward CSV") }
                                    }
                                }
                            }
                        }
                        if (busy) Text("Background worker is active. You may leave or close the app; saved stages and the foreground notification track progress.")
                        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                result?.let {
                    ResultCard("1 · Gemini logic analysis", it.geminiAnalysis)
                    ResultCard("2 · Groq independent review", it.groqReview)
                    ResultCard("3 · Gemini synthesis", it.finalSynthesis)
                }
                if (history.isNotEmpty()) {
                    Text("Research memory · latest " + history.size, style = MaterialTheme.typography.titleMedium)
                    history.forEach { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(entry.createdAt, style = MaterialTheme.typography.labelSmall)
                                Text(entry.question, style = MaterialTheme.typography.titleSmall)
                                Text("UNVERIFIED · Prior AI note is context, not ground truth.", style = MaterialTheme.typography.bodySmall)
                                Text(entry.finalSynthesis.take(1200))
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun ResultCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body)
        }
    }
}