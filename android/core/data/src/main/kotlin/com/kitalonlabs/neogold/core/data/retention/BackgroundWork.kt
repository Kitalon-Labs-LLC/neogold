package com.kitalonlabs.neogold.core.data.retention

import android.content.Context
import androidx.work.WorkManager
import androidx.work.await

/** Controls over the background work the app has scheduled (retention here; research upload in :sync:research). */
object BackgroundWork {
    /**
     * Cancels everything WorkManager has queued for this app, waits until that has taken effect, and forgets the
     * finished jobs. "Delete all data" and a reset call this first, so no job wakes up later and finds the database or
     * the keys gone (FR-33). It returns only after WorkManager has recorded the cancellation, so a job that was queued
     * is not started after the data is deleted.
     */
    suspend fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().await()
        workManager.pruneWork().await()
    }
}
