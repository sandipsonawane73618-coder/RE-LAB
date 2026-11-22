package com.relab.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.relab.core.Market
import com.relab.core.ObservationStatus
import com.relab.core.PredictionSnapshot
import com.relab.core.PredictionSnapshotOutcome
import com.relab.core.ReverseEngineeringModel
import com.relab.core.SourceCsvExporter
import com.relab.core.SourceObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

/**
 * Exercises the real SQLite implementation in the isolated instrumentation-test
 * package, not the installed application's user database.
 */
@RunWith(AndroidJUnit4::class)
class LocalObservationDatabaseInstrumentationTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetIsolatedTestDatabase() {
        testContext.deleteDatabase("relab_local_history.db")
        targetContext.deleteDatabase("relab_local_history.db")
    }

    @Test
    fun packagedTwoPublisherHistoryInstallsIntoSqliteAndIsIdempotent() {
        LocalObservationDatabase(targetContext).use { database ->
            val first = PreloadedLearningBootstrap.installIfAvailable(targetContext, database)
            assertTrue(
                "The tested APK must contain the audited historical CSV and manifest; got $first",
                first is PreloadedLearningBootstrap.Result.Installed
            )
            val installed = first as PreloadedLearningBootstrap.Result.Installed
            assertEquals(9, installed.markets)
            assertTrue("Expected the captured two-publisher history, got ${installed.observations} rows",
                installed.observations >= 50_000)

            val rows = database.loadObservations()
            assertEquals(installed.observations, rows.size)
            assertEquals(9, rows.map { it.market }.toSet().size)
            assertEquals(setOf("sattamatkajodi", "sattamatkame"), rows.map { it.sourceId }.toSet())
            assertEquals(installed.checksum, database.preloadedSeedChecksum())

            // A second bootstrap must not append duplicates or overwrite the seed.
            assertEquals(
                PreloadedLearningBootstrap.Result.AlreadyInstalled,
                PreloadedLearningBootstrap.installIfAvailable(targetContext, database)
            )
            assertEquals(installed.observations, database.loadObservations().size)
        }
    }

    @Test
    fun datasetAndPredictionRecordsSurviveDatabaseReopen() {
        val market = ReverseEngineeringModel.enabledMarkets.first()
        val first = observation(market, LocalDate.parse("2026-01-08"), "source-a", "123-65-456")
        val firstCsv = SourceCsvExporter.export(listOf(first))
        val snapshot = PredictionSnapshot(
            market = market,
            sessionId = "main",
            targetDate = LocalDate.parse("2026-01-10"),
            createdAt = Instant.parse("2026-01-10T08:00:00Z"),
            historyThroughDate = LocalDate.parse("2026-01-09"),
            modelVersion = "instrumentation-regression",
            payloadJson = """{"open":[1,2,3],"jodi":["12"]}"""
        )
        val outcome = PredictionSnapshotOutcome(
            snapshotKey = snapshot.snapshotKey,
            actualResult = "123-65-456",
            publishedAt = Instant.parse("2026-01-11T08:00:00Z"),
            sourceStatus = "PUBLISHED"
        )

        LocalObservationDatabase(testContext).use { database ->
            database.replaceDataset(firstCsv, listOf(first))
            assertEquals(1, database.loadObservations().size)
            assertEquals(firstCsv, database.loadOriginalCsv())

            val second = observation(market, LocalDate.parse("2026-01-09"), "source-b", "456-12-789")
            val merged = database.appendDataset(SourceCsvExporter.export(listOf(second)), listOf(second))
            assertEquals(2, merged.size)
            assertEquals(2, database.loadObservations().size)

            assertTrue(database.savePredictionSnapshot(snapshot))
            assertFalse(
                "A later recomputation must not overwrite the frozen snapshot",
                database.savePredictionSnapshot(snapshot.copy(payloadJson = """{"open":[9]}""",
                    payloadSha256 = PredictionSnapshot.sha256("""{"open":[9]}""")))
            )
            assertTrue(database.savePredictionOutcome(outcome))
            assertFalse("An outcome is first-write-only", database.savePredictionOutcome(outcome.copy(actualResult = "000-00-000")))
        }

        LocalObservationDatabase(testContext).use { reopened ->
            assertEquals(2, reopened.loadObservations().size)
            assertEquals(1, reopened.loadPredictionSnapshots().size)
            assertEquals(snapshot.payloadJson, reopened.loadPredictionSnapshots().single().payloadJson)
            assertEquals(snapshot.payloadSha256, reopened.loadPredictionSnapshots().single().payloadSha256)
            assertEquals(1, reopened.loadPredictionOutcomes().size)
            assertEquals("123-65-456", reopened.loadPredictionOutcomes().single().actualResult)
        }
    }

    @Test
    fun preloadedSeedMergesOnceAndSurvivesDatabaseReopen() {
        val market = ReverseEngineeringModel.enabledMarkets.first()
        val existing = observation(market, LocalDate.parse("2026-01-08"), "live-source", "123-65-456")
        val preloaded = observation(market, LocalDate.parse("2026-01-09"), "preload-source", "456-12-789")
        val csv = SourceCsvExporter.export(listOf(preloaded))
        val checksum = "a".repeat(64)

        LocalObservationDatabase(testContext).use { database ->
            database.replaceDataset(SourceCsvExporter.export(listOf(existing)), listOf(existing))
            database.seedPreloadedDataset(csv, listOf(preloaded), checksum)
            assertEquals(2, database.loadObservations().size)
            assertEquals(checksum, database.preloadedSeedChecksum())
            // A repeat install of the same package is a no-op.
            database.seedPreloadedDataset(csv, listOf(preloaded), checksum)
            assertEquals(2, database.loadObservations().size)
        }

        LocalObservationDatabase(testContext).use { reopened ->
            assertEquals(2, reopened.loadObservations().size)
            assertEquals(checksum, reopened.preloadedSeedChecksum())
        }
    }

    @Test
    fun explicitDatasetClearPreventsAutomaticPreloadReseed() {
        val market = ReverseEngineeringModel.enabledMarkets.first()
        val row = observation(market, LocalDate.parse("2026-01-08"), "preload-source", "123-65-456")
        LocalObservationDatabase(testContext).use { database ->
            database.seedPreloadedDataset(SourceCsvExporter.export(listOf(row)), listOf(row), "b".repeat(64))
            database.clearDataset()
            assertTrue(database.isPreloadedSeedDisabled())
            assertTrue(database.loadObservations().isEmpty())
        }
    }

    @Test
    fun refusesOutcomeWithoutPreviouslyFrozenPrediction() {
        val market = ReverseEngineeringModel.enabledMarkets.first()
        val outcome = PredictionSnapshotOutcome(
            snapshotKey = "${market.name}|main|2026-01-10",
            actualResult = "123-65-456",
            publishedAt = Instant.parse("2026-01-11T08:00:00Z"),
            sourceStatus = "PUBLISHED"
        )
        LocalObservationDatabase(testContext).use { database ->
            assertFalse(database.savePredictionOutcome(outcome))
            assertTrue(database.loadPredictionOutcomes().isEmpty())
        }
    }

    private fun observation(
        market: Market,
        date: LocalDate,
        sourceId: String,
        result: String
    ) = SourceObservation(
        market = market,
        sessionId = "main",
        date = date,
        sourceId = sourceId,
        sourceRecordId = "$sourceId-$date",
        sourceUrl = "instrumentation://$sourceId",
        retrievedAt = Instant.parse("2026-01-12T08:00:00Z"),
        publishedAt = Instant.parse("2026-01-11T08:00:00Z"),
        rawText = result,
        normalizedResult = result,
        status = ObservationStatus.PUBLISHED
    )
}
