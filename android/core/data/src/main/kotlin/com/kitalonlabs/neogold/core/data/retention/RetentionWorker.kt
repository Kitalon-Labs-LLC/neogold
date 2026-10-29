package com.kitalonlabs.neogold.core.data.retention

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the retention worker needs from the app. The Application implements it, so the worker uses the one database and
 * the one set of keys the app already opened instead of building a second set of its own.
 */
interface RetentionComponent {
    /** The retention jobs over the open database, or null while storage cannot be read (a lost Keystore key). */
    suspend fun retentionManager(): RetentionManager?
}

/** Daily retention (doc 5 Section 2.5): register number erasure, screening purge, abandoned sessions, research. */
class RetentionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @Suppress("TooGenericExceptionCaught") // a failed run is retried with backoff; nothing here may crash the process
    override suspend fun doWork(): Result {
        return try {
            val manager = (applicationContext as? RetentionComponent)?.retentionManager()
            if (manager == null) {
                Result.retry()
            } else {
                manager.runAllRetentionJobs()
                Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "NeoGoldRetentionWorker"

        fun scheduleDaily(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}
