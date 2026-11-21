package com.relab.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Durable, user-visible execution for the bounded AI research/learning loop. */
class AiResearchLoopWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val loopId = inputData.getString(KEY_LOOP_ID) ?: return Result.failure()
        createNotificationChannel()
        setForeground(foregroundInfo("Starting saved research loop…"))

        val store = AiResearchMemoryStore(applicationContext)
        return try {
            val session = withContext(Dispatchers.IO) {
                store.latestResumableLoop()?.takeIf { it.id == loopId }
                    ?: error("Saved research loop is missing or already completed")
            }
            val keys = withContext(Dispatchers.IO) {
                AiKeyVault(applicationContext).let {
                    it.read(AiKeyVault.GEMINI).orEmpty() to it.read(AiKeyVault.GROQ).orEmpty()
                }
            }
            require(keys.first.isNotBlank() && keys.second.isNotBlank()) {
                "Save both Gemini and Groq API keys before background research"
            }

            val completed = withContext(Dispatchers.IO) {
                AiResearchLoopEngine().run(
                    keys.first, keys.second, session.question, session.evidence,
                    session.priorMemory, session.maxRounds, session.checkpoint()
                ) { round, stage, output ->
                    store.saveLoopStage(loopId, round, stage, output)
                    updateNotification("Round $round/${session.maxRounds} · $stage checkpoint saved")
                }
            }
            withContext(Dispatchers.IO) { store.markLoopCompleted(loopId, completed) }
            updateNotification("Research completed · all ${completed.rounds.size} rounds saved")
            Result.success()
        } catch (error: Exception) {
            withContext(Dispatchers.IO) {
                runCatching { store.markLoopInterrupted(loopId, error.message ?: "Background research failed") }
            }
            updateNotification("Paused safely · saved stages are ready to resume")
            if (runAttemptCount < MAX_RETRIES && shouldRetry(error)) Result.retry() else Result.failure()
        }
    }

    private fun shouldRetry(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        val status = Regex("AI provider HTTP (\\d{3})").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (status != null && status in 400..499 && status != 408 && status != 429) return false
        return true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "RE-LAB background research", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows ongoing research and saved-stage progress." }
            notificationManager().createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("RE-LAB · Background research")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    private fun foregroundInfo(text: String): ForegroundInfo {
        val item = notification(text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, item, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, item)
        }
    }

    private fun updateNotification(text: String) {
        notificationManager().notify(NOTIFICATION_ID, notification(text))
    }

    private fun notificationManager() =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val KEY_LOOP_ID = "research_loop_id"
        const val UNIQUE_WORK_NAME = "relab-auto-research-loop"
        private const val CHANNEL_ID = "relab_background_research"
        private const val NOTIFICATION_ID = 73021
        private const val MAX_RETRIES = 4
    }
}

object AiResearchBackgroundWork {
    fun enqueue(context: Context, loopId: String) {
        val request = OneTimeWorkRequestBuilder<AiResearchLoopWorker>()
            .setInputData(workDataOf(AiResearchLoopWorker.KEY_LOOP_ID to loopId))
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            AiResearchLoopWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
