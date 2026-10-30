package com.kitalonlabs.neogold

import android.app.Application
import android.content.pm.ApplicationInfo
import com.kitalonlabs.neogold.core.data.retention.RetentionComponent
import com.kitalonlabs.neogold.core.data.retention.RetentionManager
import com.kitalonlabs.neogold.core.data.retention.RetentionWorker
import com.kitalonlabs.neogold.core.security.crash.InHouseCrashHandler
import com.kitalonlabs.neogold.core.security.logging.TimberReleaseTree
import kotlinx.coroutines.launch
import timber.log.Timber

open class NeoGoldApplication : Application(), RetentionComponent {

    /** The app's one object graph (ADR-017). Built in [onCreate]; workers and the activity read it from here. */
    lateinit var container: NeoGoldContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // 1. Install in-house crash handler first, so anything that fails below is recorded
        InHouseCrashHandler.install(this)

        // 2. Configure logging
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(TimberReleaseTree())
        }

        // The object graph. Nothing in it touches the Keystore until the storage check asks for it.
        container = NeoGoldContainer(this)
        container.researchSession.registerScreenOffReceiver(this) // screen off locks research at once (TC-RSH-07)

        // 3. Verify the bundle first: nothing may read the model, the triage config or the trust list before this
        // (BUG-2026-007). The result is held by the container and gates screening, research and the triage switch.
        container.verifyAssets()
        if (!container.assets.isUsable) {
            Timber.w(
                "Asset manifest integrity check failed: %s. Screening and research are off.",
                container.assets.integrity
            )
        }

        // 4. Retention runs daily and on every start (PRD D7, doc 5 Section 2.5, BUG-2026-010). The start-up run is
        // the on-opened step of the storage coordinator; WorkManager covers the days the app is not opened.
        RetentionWorker.scheduleDaily(this)

        // 5. Check the keys and open the database off the main thread.
        container.applicationScope.launch { container.storage.open() }
    }

    override suspend fun retentionManager(): RetentionManager? = container.retentionManager()
}
