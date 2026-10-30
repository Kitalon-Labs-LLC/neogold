package com.kitalonlabs.neogold.core.security.pin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Manages the in-memory research mode session unlock state and automatic re-locking
 * after 5 minutes of inactivity/backgrounding or immediately on screen off (doc 8 Section 6.2).
 */
class ResearchSessionManager(
    private val timeoutMillis: Long = INACTIVITY_TIMEOUT_MS
) {

    companion object {
        const val INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _activeStudyId = MutableStateFlow<String?>(null)
    val activeStudyId: StateFlow<String?> = _activeStudyId.asStateFlow()

    private var lastActiveTimestamp: Long = 0L
    private var backgroundedTimestamp: Long? = null
    private var screenReceiver: BroadcastReceiver? = null

    /**
     * Unlocks the research session for a verified study.
     */
    fun unlock(studyId: String, timestamp: Long = System.currentTimeMillis()) {
        _activeStudyId.value = studyId
        _isUnlocked.value = true
        lastActiveTimestamp = timestamp
        backgroundedTimestamp = null
        Timber.i("Research session unlocked")
    }

    /**
     * Explicitly locks the research session.
     */
    fun lock() {
        _isUnlocked.value = false
        _activeStudyId.value = null
        backgroundedTimestamp = null
        Timber.i("Research session locked")
    }

    /**
     * Called when user touches/interacts with research screens to keep session alive.
     */
    fun recordUserActivity(timestamp: Long = System.currentTimeMillis()) {
        if (_isUnlocked.value) {
            lastActiveTimestamp = timestamp
        }
    }

    /**
     * Called when the app enters the background.
     */
    fun onAppBackgrounded(timestamp: Long = System.currentTimeMillis()) {
        if (_isUnlocked.value) {
            backgroundedTimestamp = timestamp
        }
    }

    /**
     * Called when the app returns to the foreground.
     * Evaluates whether the 5-minute timeout elapsed.
     */
    fun onAppForegrounded(timestamp: Long = System.currentTimeMillis()) {
        if (!_isUnlocked.value) return

        val bgTime = backgroundedTimestamp
        if (bgTime != null && (timestamp - bgTime >= timeoutMillis)) {
            Timber.i("Research session background timeout exceeded (${timestamp - bgTime} ms), locking")
            lock()
        } else if (timestamp - lastActiveTimestamp >= timeoutMillis) {
            Timber.i("Research session inactivity timeout exceeded (${timestamp - lastActiveTimestamp} ms), locking")
            lock()
        } else {
            // Still valid; update last activity
            lastActiveTimestamp = timestamp
            backgroundedTimestamp = null
        }
    }

    /**
     * Registers a broadcast receiver to re-lock immediately on screen off.
     */
    fun registerScreenOffReceiver(context: Context) {
        if (screenReceiver != null) return

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    Timber.i("Screen turned off; re-locking research session")
                    lock()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        context.registerReceiver(screenReceiver, filter)
    }

    /**
     * Unregisters the screen off receiver.
     */
    @Suppress("TooGenericExceptionCaught") // unregistering a stale receiver must not crash
    fun unregisterScreenOffReceiver(context: Context) {
        screenReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Timber.w("Error unregistering screen off receiver (%s)", e.javaClass.name)
            }
            screenReceiver = null
        }
    }
}
