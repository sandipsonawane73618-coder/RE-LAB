package com.relab.app

import android.content.Context
import android.util.Log
import com.relab.core.Market
import com.relab.core.ObservationStatus
import com.relab.core.SourceCsvImporter
import java.security.MessageDigest

/**
 * Installs a build-time, checksum-verified historical dataset exactly once.
 * User observations are merged, never replaced. Live imports continue to append
 * through LocalObservationDatabase after this bootstrap.
 */
object PreloadedLearningBootstrap {
    private const val TAG = "RE-LAB-Preload"
    private const val CSV_ASSET = "preloaded/source-observations.csv"
    private const val MANIFEST_ASSET = "preloaded/manifest.properties"

    sealed interface Result {
        data object NotPackaged : Result
        data object AlreadyInstalled : Result
        data class Installed(val observations: Int, val markets: Int, val checksum: String) : Result
        data class Rejected(val reason: String) : Result
    }

    fun installIfAvailable(context: Context, database: LocalObservationDatabase): Result {
        if (database.isPreloadedSeedDisabled()) return Result.AlreadyInstalled
        val csv = try {
            context.assets.open(CSV_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: java.io.FileNotFoundException) {
            return Result.NotPackaged
        } catch (error: Exception) {
            return Result.Rejected("Could not read packaged chart history: ${error.message}")
        }
        val manifest = try {
            context.assets.open(MANIFEST_ASSET).bufferedReader(Charsets.UTF_8).use { it.readLines() }
                .mapNotNull { line ->
                    val split = line.indexOf('=')
                    if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
                }.toMap()
        } catch (error: Exception) {
            return Result.Rejected("Preloaded manifest is missing or unreadable: ${error.message}")
        }
        if (manifest["format_version"] != "1") return Result.Rejected("Unsupported preloaded package format")
        val checksum = sha256(csv)
        if (manifest["sha256"] != checksum) return Result.Rejected("Preloaded CSV checksum does not match manifest")
        val parsed = SourceCsvImporter.import(csv)
        val rows = parsed.observations
        if (rows.isEmpty()) return Result.Rejected("Preloaded chart history contains no valid observations")
        if (manifest["observation_count"]?.toIntOrNull()?.let { it != rows.size } == true) {
            return Result.Rejected("Preloaded observation count does not match manifest")
        }
        val marketCounts = rows.filter { it.status == ObservationStatus.PUBLISHED }
            .groupBy { it.market }
        val missingMarkets = Market.entries.filter { marketCounts[ it ].isNullOrEmpty() }
        if (missingMarkets.isNotEmpty()) {
            return Result.Rejected("Preloaded history is missing published rows for: ${missingMarkets.joinToString()}")
        }
        val shallowMarkets = Market.entries.filter { market ->
            marketCounts[market].orEmpty().size < 30
        }
        if (shallowMarkets.isNotEmpty()) {
            return Result.Rejected("At least 30 published historical rows per market are required; insufficient: ${shallowMarkets.joinToString()}")
        }
        val installedChecksum = database.preloadedSeedChecksum()
        if (installedChecksum == checksum) return Result.AlreadyInstalled
        return try {
            database.seedPreloadedDataset(csv, rows, checksum)
            Result.Installed(rows.size, marketCounts.size, checksum)
        } catch (error: Exception) {
            Log.e(TAG, "Preloaded chart history could not be installed", error)
            Result.Rejected(error.message ?: "Database import failed")
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
