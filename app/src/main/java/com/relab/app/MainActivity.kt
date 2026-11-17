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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.relab.core.ObservationStatus
import com.relab.core.ReconciliationState
import com.relab.core.SourceCsvImporter
import com.relab.core.SourceReconciliation

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
                onSaveCsv = { csv ->
                    val parsed = SourceCsvImporter.import(csv)
                    require(parsed.observations.isNotEmpty()) { "No valid source observations to save" }
                    database.replaceDataset(csv, parsed.observations)
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
private fun RelabHome(initialCsv: String?, onSaveCsv: (String) -> Unit, onClearCsv: () -> Unit) {
    val context = LocalContext.current
    var rawCsv by remember { mutableStateOf(initialCsv) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Selected file could not be opened")
                val parsed = SourceCsvImporter.import(csv)
                if (parsed.observations.isEmpty()) {
                    importMessage = "Import failed: no valid source observations. " + (parsed.issues.firstOrNull()?.message ?: "")
                } else {
                    onSaveCsv(csv)
                    rawCsv = csv
                    importMessage = "Saved in offline database: ${parsed.observations.size} observations, ${parsed.issues.size} row issues. Raw source text retained."
                }
            } catch (error: Exception) {
                importMessage = "CSV read/import error: ${error.message ?: "Unknown error"}"
            }
        }
    }

    val report = remember(rawCsv) { rawCsv?.let { SourceCsvImporter.import(it) } }
    val observations = report?.observations.orEmpty()
    val groups = remember(observations) { SourceReconciliation.reconcile(observations) }
    val conflicts = groups.count { it.state == ReconciliationState.CONFLICT }
    val published = observations.count { it.status == ObservationStatus.PUBLISHED }
    val pending = observations.count { it.status == ObservationStatus.PENDING }
    val malformed = observations.count { it.status == ObservationStatus.MALFORMED }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("RE-LAB", style = MaterialTheme.typography.headlineLarge)
                Text("Sridevi + Kalyan · Offline chart research", style = MaterialTheme.typography.titleMedium)
                Text("Source-first research. Every imported source observation is stored locally.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/csv")) }) { Text("Import source CSV") }
                    OutlinedButton(onClick = {
                        rawCsv = null
                        onClearCsv()
                        importMessage = "Local database cleared."
                    }, enabled = rawCsv != null) { Text("Clear data") }
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
                Text("Recent source observations", style = MaterialTheme.typography.titleLarge)
                if (groups.isEmpty()) {
                    Text("No source records loaded.")
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(groups.asReversed().take(100), key = { "${it.market}-${it.sessionId}-${it.date}" }) { group ->
                            Card {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("${group.market} · ${group.sessionId} · ${group.date}", style = MaterialTheme.typography.titleSmall)
                                    val resultText = when (group.state) {
                                        ReconciliationState.CONSENSUS -> "CONSENSUS · ${group.agreedResult}"
                                        ReconciliationState.CONFLICT -> "CONFLICT · " + group.observations.filter { it.status == ObservationStatus.PUBLISHED }.mapNotNull { it.normalizedResult }.distinct().sorted().joinToString(" / ")
                                        ReconciliationState.NO_PUBLISHED_RESULT -> "NO PUBLISHED RESULT · ${group.observations.size} source observations"
                                    }
                                    Text(resultText, style = MaterialTheme.typography.bodyMedium)
                                    Text(group.observations.joinToString { "${it.sourceId}: ${it.status}" }, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                Text("Offline SQLite · Source provenance preserved · Conflicts never auto-resolved", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
