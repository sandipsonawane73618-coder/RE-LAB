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

class AiResearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent.getStringExtra(EXTRA_RESEARCH_CONTEXT).orEmpty()
        setContent { AiResearchScreen(this, initial) { finish() } }
    }
    companion object { const val EXTRA_RESEARCH_CONTEXT = "relab.ai.RESEARCH_CONTEXT" }
}

@Composable
private fun AiResearchScreen(context: Context, initialContext: String, onBack: () -> Unit) {
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
    LaunchedEffect(Unit) {
        history = withContext(Dispatchers.IO) { store.latest() }
        keyStatus = "Gemini: " + (if (vault.hasKey(AiKeyVault.GEMINI)) "saved securely" else "not configured")
        keyStatus += " · Groq: " + (if (vault.hasKey(AiKeyVault.GROQ)) "saved securely" else "not configured")
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
                                busy = true; result = null
                                status = "Gemini → Groq independent review → Gemini synthesis…"
                                try {
                                    val keys = withContext(Dispatchers.IO) { vault.read(AiKeyVault.GEMINI) to vault.read(AiKeyVault.GROQ) }
                                    val prior = history.take(3).joinToString("\n\n") {
                                        "Earlier note (UNVERIFIED): " + it.question.take(250) + "\n" + it.finalSynthesis.take(800)
                                    }
                                    val completed = withContext(Dispatchers.IO) {
                                        engine.runCrossReview(keys.first.orEmpty(), keys.second.orEmpty(), question, evidence, prior)
                                            .also { store.save(question, it) }
                                    }
                                    result = completed
                                    history = withContext(Dispatchers.IO) { store.latest() }
                                    status = "Cross-review complete. New note saved as UNVERIFIED."
                                } catch (e: Exception) { status = "Research failed: " + (e.message ?: "unknown error") }
                                finally { busy = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Research running…" else "Run 2-AI cross-review") }
                        if (busy) Text("Three API calls are running. Keep the app open until completion.")
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
