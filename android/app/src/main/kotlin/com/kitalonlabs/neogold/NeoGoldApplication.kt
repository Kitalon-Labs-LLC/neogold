package com.kitalonlabs.neogold

import android.app.Application
import android.content.pm.ApplicationInfo
import com.kitalonlabs.neogold.core.security.crash.InHouseCrashHandler
import com.kitalonlabs.neogold.core.security.integrity.BundleManifestVerifier
import com.kitalonlabs.neogold.core.security.integrity.IntegrityStateManager
import com.kitalonlabs.neogold.core.security.logging.TimberReleaseTree
import timber.log.Timber

open class NeoGoldApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Install in-house crash handler
        InHouseCrashHandler.install(this)

        // 2. Configure logging
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(TimberReleaseTree())
        }

        // 3. Verify bundle manifest integrity at startup
        val integrityResult = BundleManifestVerifier.verifyAssets(this)
        IntegrityStateManager.updateIntegrity(integrityResult)
        if (!integrityResult.isScreeningAllowed) {
            Timber.w("Asset manifest integrity check failed: %s. Screening disabled.", integrityResult)
        }
    }
}
