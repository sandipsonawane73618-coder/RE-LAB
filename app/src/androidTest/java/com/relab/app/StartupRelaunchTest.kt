package com.relab.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.relab.core.ObservationStatus
import com.relab.core.ReverseEngineeringModel
import com.relab.core.SourceCsvExporter
import com.relab.core.SourceObservation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

/**
 * Verifies the real launcher route with persisted five-market history.
 * The CI workflow force-stops the app between separate instrumentation sessions,
 * so the next pass starts from a genuinely fresh Android app process.
 */
@RunWith(AndroidJUnit4::class)
class StartupRelaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext

    @Test
    fun launcherOpensMainActivityWithPersistedHistory() {
        seedHistoryOnlyWhenDatabaseIsEmpty()

        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            val launchIntent = Intent(targetContext, SplashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            instrumentation.startActivitySync(launchIntent)

            val launchedMain = instrumentation.waitForMonitorWithTimeout(monitor, 20_000)
            assertNotNull("SplashActivity did not launch MainActivity", launchedMain)
            val main = launchedMain as MainActivity

            // Exercise saved-history loading, Compose startup and background analysis.
            SystemClock.sleep(2_000)
            instrumentation.runOnMainSync {
                assertFalse("MainActivity unexpectedly finished during startup", main.isFinishing)
                assertFalse("MainActivity was destroyed during startup", main.isDestroyed)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun seedHistoryOnlyWhenDatabaseIsEmpty() {
        val database = LocalObservationDatabase(targetContext)
        try {
            if (database.loadObservations().isNotEmpty()) return

            val start = LocalDate.now().minusDays(420)
            val retrievedAt = Instant.parse("2026-01-01T00:00:00Z")
            val fixture = ReverseEngineeringModel.enabledMarkets.flatMap { market ->
                generateSequence(start) { it.plusDays(1) }
                    .filter { it.dayOfWeek in market.activeDays }
                    .take(120)
                    .mapIndexed { index, date ->
                        SourceObservation(
                            market = market,
                            sessionId = "main",
                            date = date,
                            sourceId = "cold-launch-instrumentation",
                            sourceRecordId = "${market.name}-$index",
                            sourceUrl = "instrumentation://saved-history",
                            retrievedAt = retrievedAt.plusSeconds(index.toLong()),
                            rawText = "123-65-456",
                            normalizedResult = "123-65-456",
                            status = ObservationStatus.PUBLISHED
                        )
                    }.toList()
            }
            database.replaceDataset(SourceCsvExporter.export(fixture), fixture)
        } finally {
            database.close()
        }
    }
}
