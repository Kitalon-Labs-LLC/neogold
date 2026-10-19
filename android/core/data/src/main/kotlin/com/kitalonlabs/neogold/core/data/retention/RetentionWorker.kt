package com.kitalonlabs.neogold.core.data.retention

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kitalonlabs.neogold.core.data.datastore.DefaultNeoGoldDataStore
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import com.kitalonlabs.neogold.core.security.AndroidMasterKeyProvider
import com.kitalonlabs.neogold.core.security.DefaultDatabaseKeyProvider
import com.kitalonlabs.neogold.core.security.DefaultEncryptedFileStore
import com.kitalonlabs.neogold.core.security.DefaultFileKeysetProvider
import java.util.concurrent.TimeUnit

class RetentionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val masterKeyProvider = AndroidMasterKeyProvider(applicationContext)
            val databaseKeyProvider = DefaultDatabaseKeyProvider(applicationContext, masterKeyProvider)
            val fileKeysetProvider = DefaultFileKeysetProvider(applicationContext, masterKeyProvider)
            val encryptedFileStore = DefaultEncryptedFileStore(applicationContext, fileKeysetProvider)
            val database = NeoGoldDatabase.buildDatabase(applicationContext, databaseKeyProvider)
            val dataStore = DefaultNeoGoldDataStore(applicationContext)

            val retentionManager = RetentionManager(
                database = database,
                dataStore = dataStore,
                encryptedFileStore = encryptedFileStore
            )

            retentionManager.runAllRetentionJobs()
            Result.success()
        } catch (e: Exception) {
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
