package com.kitalonlabs.neogold.core.security.logging

import android.util.Log
import timber.log.Timber

/**
 * Release logging tree for Timber (TC-SEC-03, doc 8 Section 6.1).
 *
 * Drops all log messages below [Log.WARN] (i.e. VERBOSE, DEBUG, INFO).
 * Ensures that exception messages containing sensitive data are redacted.
 */
class TimberReleaseTree : Timber.Tree() {

    public override fun isLoggable(tag: String?, priority: Int): Boolean {
        return priority >= Log.WARN
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!isLoggable(tag, priority)) {
            return
        }

        // Ensure exception messages which may contain personal data/register numbers are redacted
        val sanitizedMessage = if (t != null && t.message != null && message.contains(t.message!!)) {
            message.replace(t.message!!, "[REDACTED_MESSAGE]")
        } else {
            message
        }

        if (priority == Log.ASSERT) {
            Log.wtf(tag, sanitizedMessage)
        } else {
            Log.println(priority, tag ?: "NeoGold", sanitizedMessage)
        }
    }
}
