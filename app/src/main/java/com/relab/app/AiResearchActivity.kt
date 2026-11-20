package com.relab.app
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import com.relab.core.ReverseEngineeringModel
import com.relab.core.ReverseEngineeringSide
import com.relab.core.ResearchToBacktestWorkflow

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
    val loopEngine = remember { AiResearchLoopEngine() }
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
    LaunchedEffect(Unit) {
        history = withContext(Dispatchers.IO) { store.latest() }
        pendingSession = withContext(Dispatchers.IO) { store.latestResumable() }
        pendingLoop = withContext(Dispatchers.IO) { store.latestResumableLoop() }
        keyStatus = "Gemini: " + (if (vault.hasKey(AiKeyVault.GEMINI)) "saved securely" else "not configured")
        keyStatus += " · Groq: " + (if (vault.hasKey(AiKeyVault.GROQ)) "saved securely" else "not configured")
    }
    fun launchResearch(session: AiResearchSession) {
        scope.launch {
            busy = true; result = null; status = "Resuming from last saved stage…"
            try {
                val keys = withContext(Dispatchers.IO) { vault.read(AiKeyVault.GEMINI) to vault.read(AiKeyVault.GROQ) }
                val completed = withContext(Dispatchers.IO) {
                    engine.runCrossReview(keys.first.orEmpty(), keys.second.orEmpty(), session.question, session.evidence,
                        session.priorMemory, session.checkpoint()) { stage, output -> store.saveStage(session.id, stage, output) }
                }
                withContext(Dispatchers.IO) { store.markCompleted(session.id, session.question, completed) }
                result = completed; history = withContext(Dispatchers.IO) { store.latest() }
                pendingSession = withContext(Dispatchers.IO) { store.latestResumable() }
                status = "Cross-review complete. New note saved as UNVERIFIED."
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { store.markInterrupted(session.id, e.message ?: "Network or provider error") }
                pendingSession = withContext(Dispatchers.IO) { store.latestResumable() }
                status = "Research paused safely. Completed stages are saved; tap Resume research to continue."
            } finally { busy = false }
        }
    }
    fun launchAutoLoop(session: AiResearchLoopSession) {
        scope.launch {
            busy = true; result = null; loopResult = null
            status = "Auto Research Loop running — Researcher → Auditor → improvement…"
            try {
                val keys = withContext(Dispatchers.IO) { vault.read(AiKeyVault.GEMINI) to vault.read(AiKeyVault.GROQ) }
                val completed = withContext(Dispatchers.IO) {
                    loopEngine.run(
                        keys.first.orEmpty(), keys.second.orEmpty(), session.question, session.evidence,
                        session.priorMemory, session.maxRounds, session.checkpoint()
                    ) { round, stage, output -> store.saveLoopStage(session.id, round, stage, output) }
                }
                withContext(Dispatchers.IO) { store.markLoopCompleted(session.id, completed) }
                loopResult = completed
                history = withContext(Dispatchers.IO) { store.latest() }
                pendingLoop = withContext(Dispatchers.IO) { store.latestResumableLoop() }
                status = "Auto Research Loop complete. Results remain UNVERIFIED until independently tested."
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { store.markLoopInterrupted(session.id, e.message ?: "Network or provider error") }
                pendingLoop = withContext(Dispatchers.IO) { store.latestResumableLoop() }
                status = "Auto loop paused safely. Completed round stages are saved; tap Resume auto loop to continue."
            } finally { busy = false }
        }
    }
    fun launchBacktest(synthesis: String) {
        scope.launch {
            busy = true
            backtestReport = null
            status = "Extracting structured AI proposals and running chronological backtests…"
            try {
                val parsed = AiPatternProposalParser.parse(synthesis)
                require(parsed.proposals.isNotEmpty()) {
                    parsed.issues.joinToString("; ").ifBlank { "AI supplied no executable pattern proposals" }
                }
                val report = withContext(Dispatchers.IO) {
                    val observations = LocalObservationDatabase(context).loadObservations()
                    val records = com.relab.core.ReconciledChartBuilder.buildExploratoryRecords(observations)
                    val sessions = records.filter { it.date.isBefore(targetDate) }
                        .map { it.market to it.sessionId }.distinct()
                    val scannerRankings = sessions.flatMap { (market, session) ->
                        ReverseEngineeringSide.entries.map { side ->
                            ReverseEngineeringModel.rank(records, market, session, targetDate, side, limit = 10, minimumHistory = 30)
                        }
                    }
                    ResearchToBacktestWorkflow.run(records, scannerRankings, parsed.proposals, targetDate)
                }
                backtestReport = report.compactSummary() + if (parsed.issues.isEmpty()) "" else "\n\nProposal parser notes:\n" + parsed.issues.joinToString("\n")
                status = "Backtest completed. Only statistical evidence can change a proposal's verification status."
            } catch (error: Exception) {
                backtestReport = "Backtest not completed: \${error.message ?: "unknown error"}"
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
                            Text("Saved auto loop: " + session.state + " · " + session.rounds.count { it.checkpoint.finalSynthesis.isNotBlank() } +
                                "/" + session.maxRounds + " rounds fully checkpointed", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = !busy, onClick = { launchAutoLoop(session) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Resume auto loop from saved stage")
                            }
                        }
                        loopResult?.let { Text(it.report()) }
                        if (loopResult != null || result != null) {
                            Button(enabled = !busy, onClick = {
                                val synthesis = loopResult?.finalSynthesis?.takeIf { it.isNotBlank() }
                                    ?: result?.finalSynthesis.orEmpty()
                                launchBacktest(synthesis)
                            }, modifier = Modifier.fillMaxWidth()) { Text("Backtest AI-proposed patterns") }
                        }
                        backtestReport?.let {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Chronological verification · cutoff \${targetDate} (exclusive)", style = MaterialTheme.typography.titleMedium)
                                    Text(it)
                                }
                            }
                        }
                        if (busy) Text("Each completed AI stage is saved locally. If the app closes or network drops, reopen and resume.")
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
