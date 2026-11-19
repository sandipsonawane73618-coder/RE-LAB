package com.relab.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var status by remember { mutableStateOf("Preparing your research workspace…") }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "RE-LAB",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Reverse Engineering Research Lab",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(36.dp))
                    CircularProgressIndicator(
                        strokeWidth = 5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(status, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Checking chart source and loading saved history",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LaunchedEffect(Unit) {
                status = "Connecting to chart source…"
                val sourceReady = withContext(Dispatchers.IO) {
                    var connection: HttpURLConnection? = null
                    try {
                        connection = URL("https://sattamatkajodi.com/record/sridevi-satta-penal-chart.php")
                            .openConnection() as HttpURLConnection
                        connection.connectTimeout = 7000
                        connection.readTimeout = 7000
                        connection.instanceFollowRedirects = true
                        connection.setRequestProperty("User-Agent", "RE-LAB-Android/0.1")
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(16 * 1024)
                            input.read(buffer)
                        }
                        true
                    } catch (_: Exception) {
                        false
                    } finally {
                        connection?.disconnect()
                    }
                }
                status = if (sourceReady) "Chart source reached. Opening research workspace…" 
                         else "Offline/source unavailable. Opening with saved history…"
                delay(900)
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                finish()
            }
        }
    }
}
