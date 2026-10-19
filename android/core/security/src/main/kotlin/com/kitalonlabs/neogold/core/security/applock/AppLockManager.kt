package com.kitalonlabs.neogold.core.security.applock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages device-level app lock with BiometricPrompt and KeyguardManager fallback (FR-35, NG-054, doc 8 Section 6.3).
 *
 * Rules:
 * - Allows device credentials (PIN, pattern, password, fingerprint).
 * - KeyguardManager confirm-credential screen fallback on API 28 and 29.
 * - Re-locks after 5 minutes in background or on cold start if enabled.
 */
class AppLockManager(
    private val keyguardManagerProvider: (Context) -> KeyguardManager? = { ctx ->
        ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }
) {

    companion object {
        const val BACKGROUND_LOCK_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
        const val REQUEST_CODE_CONFIRM_DEVICE_CREDENTIAL = 1001
    }

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private var backgroundTimestamp: Long? = null
    var isAppLockEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                _isLocked.value = false
                backgroundTimestamp = null
            }
        }

    fun lock() {
        if (isAppLockEnabled) {
            _isLocked.value = true
        }
    }

    fun unlock() {
        _isLocked.value = false
        backgroundTimestamp = null
    }

    /**
     * Called when the app moves to background.
     */
    fun onAppBackgrounded(now: Long = System.currentTimeMillis()) {
        if (isAppLockEnabled) {
            backgroundTimestamp = now
        }
    }

    /**
     * Called when the app returns to foreground.
     * Re-locks if background duration >= 5 minutes (doc 8 Section 6.2, 6.3).
     */
    fun onAppForegrounded(now: Long = System.currentTimeMillis()) {
        if (isAppLockEnabled) {
            val bgTime = backgroundTimestamp
            if (bgTime != null && (now - bgTime) >= BACKGROUND_LOCK_TIMEOUT_MS) {
                _isLocked.value = true
            }
        }
        backgroundTimestamp = null
    }

    /**
     * Determines whether the device has a secure screen lock (PIN, pattern, password, biometric).
     */
    fun isDeviceSecure(context: Context): Boolean {
        val km = keyguardManagerProvider(context) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            km.isDeviceSecure
        } else {
            km.isKeyguardSecure
        }
    }

    /**
     * Authenticates the user using BiometricPrompt on API 30+,
     * falling back to KeyguardManager confirm-credential on API 28 and 29.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "NeoGold App Lock",
        subtitle: String = "Unlock to access patient screening and records",
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: CharSequence) -> Unit
    ) {
        val km = keyguardManagerProvider(activity)

        // On API 28 and 29, or when BiometricPrompt cannot offer device credentials directly,
        // use KeyguardManager confirm-credential fallback (doc 8 Section 6.3).
        if (Build.VERSION.SDK_INT in 26..29) {
            launchKeyguardConfirmCredential(activity, title, subtitle, km, onSuccess, onError)
            return
        }

        // On API 30+ (Android 11+), BiometricPrompt fully supports BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    unlock()
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // If biometric prompt cannot authenticate or user canceled, try Keyguard fallback if secure
                    if (errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                        errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT ||
                        errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE
                    ) {
                        launchKeyguardConfirmCredential(activity, title, subtitle, km, onSuccess, onError)
                    } else {
                        onError(errorCode, errString)
                    }
                }
            }

            val biometricPrompt = BiometricPrompt(activity, executor, callback)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            // Fallback to KeyguardManager confirm credential
            launchKeyguardConfirmCredential(activity, title, subtitle, km, onSuccess, onError)
        }
    }

    private fun launchKeyguardConfirmCredential(
        activity: Activity,
        title: String,
        subtitle: String,
        km: KeyguardManager?,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: CharSequence) -> Unit
    ) {
        if (km == null || !km.isDeviceSecure) {
            // No lock screen set on device: cannot lock
            unlock()
            onSuccess()
            return
        }

        val intent: Intent? = km.createConfirmDeviceCredentialIntent(title, subtitle)
        if (intent != null) {
            try {
                activity.startActivityForResult(intent, REQUEST_CODE_CONFIRM_DEVICE_CREDENTIAL)
            } catch (e: Exception) {
                onError(-1, e.message ?: "Failed to launch confirm device credential")
            }
        } else {
            unlock()
            onSuccess()
        }
    }

    /**
     * Handles activity result for KeyguardManager fallback.
     */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode == REQUEST_CODE_CONFIRM_DEVICE_CREDENTIAL) {
            if (resultCode == Activity.RESULT_OK) {
                unlock()
                return true
            }
        }
        return false
    }
}
