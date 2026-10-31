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
 *
 * These calls read WorkManager's state with a blocking `get()`, so they belong off the main thread; the app calls them
 * from its own scope.
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
     * What a request for a background upload should do, given the states of the work already under the unique name:
     * - a run that is waiting (ENQUEUED or BLOCKED) will upload everything that is pending by then, so nothing is added;
     * - a run in progress has already read its list of rows, so one more run is queued behind it and what was saved
     *   during the run is not left until the next save;
     * - otherwise a run is queued.
     *
     * @return the policy to enqueue with, or null when nothing needs to be added
     */
    fun policyForBackgroundUpload(existing: List<WorkInfo.State>): ExistingWorkPolicy? = when {
        existing.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> null
        existing.any { it == WorkInfo.State.RUNNING } -> ExistingWorkPolicy.APPEND_OR_REPLACE
        else -> ExistingWorkPolicy.REPLACE
    }

    /**
     * Queues the background upload unless one is already waiting (see [policyForBackgroundUpload]).
     */
    fun scheduleBackgroundUpload(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val policy = policyForBackgroundUpload(statesOfUniqueWork(workManager)) ?: return
        val request = OneTimeWorkRequestBuilder<ResearchUploadWorker>()
            .setConstraints(createConstraints())
            .build()

        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
    }

    /**
     * Triggered by "Upload now" (doc 6 R07, TC-SYNC-10):
     * - If a worker is currently RUNNING, does not spawn a second runner; active run continues.
     * - If waiting (ENQUEUED or BLOCKED) or idle, replaces it with an expedited work request.
     */
    fun triggerUploadNow(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (statesOfUniqueWork(workManager).any { it == WorkInfo.State.RUNNING }) {
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

    // A failed status read is treated as "no work": a new run is better than none, and a duplicate run is harmless.
    @Suppress("TooGenericExceptionCaught")
    private fun statesOfUniqueWork(workManager: WorkManager): List<WorkInfo.State> = try {
        workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get().map { it.state }
    } catch (e: Exception) {
        Timber.w("Could not read the upload work status: %s", e.javaClass.simpleName)
        emptyList()
    }
}
