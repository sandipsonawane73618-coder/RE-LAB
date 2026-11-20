package com.relab.app

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.relab.core.Market
import com.relab.core.ObservationStatus
import com.relab.core.ReverseEngineeringModel
import com.relab.core.SourceCsvExporter
import com.relab.core.SourceObservation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

/**
 * Reproduces the reported launcher failure correctly: the target app process is
 * force-stopped between launches, while the instrumentation process remains alive.
 * A persistent five-market fixture avoids network-dependent startup in this test.
 */
@RunWith(AndroidJUnit4::class)
class StartupRelaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext

    @Test
    fun launcherSurvivesThreeColdProcessRelaunchesWithSavedHistory() {
        seedSavedHistory()

        repeat(3) { launchNumber ->
            forceStopTargetProcess()

            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                val launchIntent = Intent(targetContext, SplashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                instrumentation.startActivitySync(launchIntent)

                val launchedMain = instrumentation.waitForMonitorWithTimeout(monitor, 20_000)
                assertNotNull("MainActivity did not open on launch #${launchNumber + 1}", launchedMain)
                val main = launchedMain as MainActivity

                // Give Compose, SQLite loading and the analysis pipeline time to run.
                SystemClock.sleep(2_000)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertFalse("MainActivity unexpectedly finished", main.isFinishing)
                    assertFalse("MainActivity was destroyed during startup", main.isDestroyed)
                }

                val processId = shell("pidof ${targetContext.packageName}")
                assertTrue(
                    "App process died after launch #${launchNumber + 1}; pidof returned: $processId",
                    processId.isNotBlank()
                )
            } finally {
                instrumentation.removeMonitor(monitor)
                forceStopTargetProcess()
            }
        }
    }

    private fun seedSavedHistory() {
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

        LocalObservationDatabase(targetContext).use { database ->
            database.replaceDataset(SourceCsvExporter.export(fixture), fixture)
        }
    }

    private fun forceStopTargetProcess() {
        shell("am force-stop ${targetContext.packageName}")
        SystemClock.sleep(300)
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
            .trim()
    }
}
