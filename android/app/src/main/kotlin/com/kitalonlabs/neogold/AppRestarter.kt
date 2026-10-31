package com.kitalonlabs.neogold

import android.app.Activity
import android.content.Context
import android.content.Intent
import kotlin.system.exitProcess

/**
 * Starts the app again in a new process. After "Delete all data" the old process still holds the closed database, the
 * old keys and the old settings in memory; a restart is how the app comes back empty (FR-33, TC-LOG-11).
 */
object AppRestarter {
    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(launch)
        }
        (context as? Activity)?.finishAffinity()
        exitProcess(0)
    }
}
