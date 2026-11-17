package com.relab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RelabHome() }
    }
}

@Composable
private fun RelabHome() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("RE-LAB", style = MaterialTheme.typography.headlineLarge)
                Text("Offline chart research", style = MaterialTheme.typography.titleMedium)
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Project foundation", style = MaterialTheme.typography.titleLarge)
                        Text("Sridevi + Kalyan")
                        Text("Historical source reconciliation, Panna/Jodi validation and descriptive analysis.")
                        Text("No imported chart data yet — source audit comes before data loading.")
                    }
                }
                Text("Local-first • Provenance-preserving • Walk-forward research")
            }
        }
    }
}
