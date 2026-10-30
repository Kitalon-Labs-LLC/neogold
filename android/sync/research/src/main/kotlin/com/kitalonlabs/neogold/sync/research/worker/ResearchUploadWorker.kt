package com.kitalonlabs.neogold.sync.research.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.kitalonlabs.neogold.sync.research.R
import com.kitalonlabs.neogold.sync.research.ResearchSyncComponent
import com.kitalonlabs.neogold.sync.research.outbox.OutboxProcessor
import com.kitalonlabs.neogold.sync.research.outbox.OutboxResult
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * Foreground worker responsible for processing outbox items (FR-31, NFR-12, TC-SYNC-10..12).
 * Operates as a foreground worker with dataSync type on API 34+ to allow processing
 * long backlogs over throttled networks past the standard 10-minute worker limit.
 *
 * It builds nothing itself: the processor comes from the application ([ResearchSyncComponent]), over the one open database
 * and the one set of keys. While the application has none (storage cannot be read, the bundle check failed) the worker
 * does nothing and tries again later.
 */
open class ResearchUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    protected open suspend fun outboxProcessor(): OutboxProcessor? =
        (applicationContext as? ResearchSyncComponent)?.outboxProcessor()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(
            title = applicationContext.getString(R.string.research_sync_title),
            content = applicationContext.getString(R.string.research_sync_text)
        )
    }

    // Only the kind of exception is logged: its message can carry a host or a server's own words (doc 8 Section 6.6).
    @Suppress("TooGenericExceptionCaught") // a worker must never crash the process; retry instead
    override suspend fun doWork(): Result {
        val processor = outboxProcessor()
        if (processor == null) {
            Timber.d("Upload skipped: storage or the bundle check is not ready")
            return Result.retry()
        }

        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Timber.w("Could not set foreground info: %s", e.javaClass.simpleName)
        }

        return try {
            when (val outcome = processor.process()) {
                is OutboxResult.Success -> {
                    Timber.i("Outbox processor processed %d items", outcome.itemsProcessed)
                    Result.success()
                }
                is OutboxResult.Paused -> {
                    Timber.d("Study is paused; retrying later")
                    Result.retry()
                }
                is OutboxResult.NotEnrolled -> {
                    Timber.d("Upload skipped: the phone is not set up on a study server")
                    Result.success()
                }
                is OutboxResult.Closed, is OutboxResult.Revoked -> {
                    Timber.w("Study closed or device revoked: %s", outcome.javaClass.simpleName)
                    Result.failure()
                }
                is OutboxResult.AppVersionUnsupported -> {
                    // the minimum version is the server's string: not logged
                    Timber.e("App version unsupported")
                    Result.failure()
                }
                is OutboxResult.Error -> {
                    Timber.w("Outbox processing failed: %s", outcome.message)
                    Result.retry()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e("Unexpected error in ResearchUploadWorker: %s", e.javaClass.simpleName)
            Result.retry()
        }
    }

    private fun createForegroundInfo(title: String, content: String): ForegroundInfo {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.research_sync_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = applicationContext.getString(R.string.research_sync_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "neogold_research_sync_channel"
        const val NOTIFICATION_ID = 9042
    }
}
