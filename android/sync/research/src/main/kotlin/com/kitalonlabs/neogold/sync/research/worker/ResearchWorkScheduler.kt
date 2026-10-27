package com.kitalonlabs.neogold.sync.research.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import timber.log.Timber

/**
 * Manages unique WorkManager upload tasks for research study sync (FR-31, NFR-12, TC-SYNC-10, TC-SYNC-15).
 */
object ResearchWorkScheduler {
    const val UNIQUE_WORK_NAME = "neogold_research_upload"

    /**
     * Builds constraints requiring Wi-Fi / unmetered connection (TC-SYNC-15).
     */
    fun createConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
    }

    /**
     * Enqueues standard background upload if not already queued or running (ExistingWorkPolicy.KEEP).
     */
    fun scheduleBackgroundUpload(context: Context) {
        val request = OneTimeWorkRequestBuilder<ResearchUploadWorker>()
            .setConstraints(createConstraints())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Triggered by "Upload now" (doc 6 R07, TC-SYNC-10):
     * - If a worker is currently RUNNING, does not spawn a second runner; active run continues.
     * - If waiting (ENQUEUED or BLOCKED) or idle, replaces it with an expedited work request.
     */
    @Suppress("TooGenericExceptionCaught") // a failed status read must not crash; treat as no work
    fun triggerUploadNow(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val workInfos = try {
            workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
        } catch (e: Exception) {
            Timber.w(e, "Failed to inspect unique work status for $UNIQUE_WORK_NAME")
            emptyList()
        }

        val isRunning = workInfos.any { it.state == WorkInfo.State.RUNNING }
        if (isRunning) {
            Timber.d("Upload now ignored: worker is already running (TC-SYNC-10)")
            return
        }

        // Replace waiting run with an expedited one
        val expeditedRequest = OneTimeWorkRequestBuilder<ResearchUploadWorker>()
            .setConstraints(createConstraints())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            expeditedRequest
        )
    }

    /**
     * Cancels any scheduled or active upload work.
     */
    fun cancelSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
