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
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.security.AndroidMasterKeyProvider
import com.kitalonlabs.neogold.core.security.DefaultDatabaseKeyProvider
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import com.kitalonlabs.neogold.sync.research.api.ResearchApiClient
import com.kitalonlabs.neogold.sync.research.crypto.AndroidDeviceKeyManager
import com.kitalonlabs.neogold.sync.research.crypto.ClockManager
import com.kitalonlabs.neogold.sync.research.crypto.DeviceAssertionMinter
import com.kitalonlabs.neogold.sync.research.enrollment.StudyRevocationManager
import com.kitalonlabs.neogold.sync.research.outbox.OutboxProcessor
import com.kitalonlabs.neogold.sync.research.outbox.OutboxResult
import com.kitalonlabs.neogold.sync.research.outbox.ReconciliationManager
import timber.log.Timber

/**
 * Foreground worker responsible for processing outbox items (FR-31, NFR-12, TC-SYNC-10..12).
 * Operates as a foreground worker with dataSync type on API 34+ to allow processing
 * long backlogs over throttled networks past the standard 10-minute worker limit.
 */
open class ResearchUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    protected open fun createOutboxProcessor(): OutboxProcessor {
        val masterKeyProvider = AndroidMasterKeyProvider(applicationContext)
        val databaseKeyProvider = DefaultDatabaseKeyProvider(applicationContext, masterKeyProvider)
        val fileKeysetProvider = DefaultFileKeysetProvider(applicationContext, masterKeyProvider)
        val encryptedFileStore = DefaultEncryptedFileStore(applicationContext, fileKeysetProvider)
        val database = NeoGoldDatabase.buildDatabase(applicationContext, databaseKeyProvider)

        val deviceKeyManager = AndroidDeviceKeyManager()
        val clockManager = ClockManager()
        val okHttpClient = okhttp3.OkHttpClient.Builder().build()
        val apiClient = ResearchApiClient(
            okHttpClient = okHttpClient,
            clockManager = clockManager,
            keyManager = deviceKeyManager
        )

        val packageInfo = try {
            applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
        } catch (_: Exception) {
            null
        }
        val appVersion = packageInfo?.versionName ?: "1.0.0"

        val revocationManager = StudyRevocationManager(
            apiClient = apiClient,
            keyManager = deviceKeyManager,
            studyDao = database.researchStudyDao(),
            appVersion = appVersion
        )

        val reconciliationManager = ReconciliationManager(
            apiClient = apiClient,
            outboxDao = database.outboxDao(),
            studyDao = database.researchStudyDao(),
            sessionDao = database.screeningSessionDao(),
            captureDao = database.captureDao(),
            batchDao = database.cardBatchDao(),
            participantDao = database.researchParticipantDao(),
            tsbDao = database.tsbMeasurementDao(),
            appVersion = appVersion
        )

        return OutboxProcessor(
            apiClient = apiClient,
            outboxDao = database.outboxDao(),
            studyDao = database.researchStudyDao(),
            batchDao = database.cardBatchDao(),
            participantDao = database.researchParticipantDao(),
            sessionDao = database.screeningSessionDao(),
            captureDao = database.captureDao(),
            tsbDao = database.tsbMeasurementDao(),
            encryptedFileStore = encryptedFileStore,
            revocationManager = revocationManager,
            reconciliationManager = reconciliationManager,
            appVersion = appVersion
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(
            title = "NeoGold Study Sync",
            content = "Uploading research data..."
        )
    }

    override suspend fun doWork(): Result {
        val outboxProcessor = createOutboxProcessor()

        // 1. Reset any IN_FLIGHT rows to PENDING (killed run recovers, doc 7 Section 5.3, TC-SYNC-11)
        // Note: process() will also reset in-flight, but we also ensure it before promoting foreground
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Timber.w(e, "Could not set foreground info")
        }

        return try {
            when (val outcome = outboxProcessor.process()) {
                is OutboxResult.Success -> {
                    Timber.i("Outbox processor processed ${outcome.itemsProcessed} items successfully")
                    Result.success()
                }
                is OutboxResult.Paused -> {
                    Timber.d("Study is paused; retrying later")
                    Result.retry()
                }
                is OutboxResult.Closed, is OutboxResult.Revoked -> {
                    Timber.w("Study closed or device revoked: $outcome")
                    Result.failure()
                }
                is OutboxResult.AppVersionUnsupported -> {
                    Timber.e("App version unsupported: min is ${outcome.minVersion}")
                    Result.failure()
                }
                is OutboxResult.Error -> {
                    Timber.w(outcome.cause, "Outbox processing failed: ${outcome.message}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error in ResearchUploadWorker")
            Result.retry()
        }
    }

    private fun createForegroundInfo(title: String, content: String): ForegroundInfo {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Research Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows upload status when synchronizing research study data"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
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
