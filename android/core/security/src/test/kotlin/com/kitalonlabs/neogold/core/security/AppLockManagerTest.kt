package com.kitalonlabs.neogold.core.security

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.security.applock.AppLockManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppLockManagerTest {

    private lateinit var appLockManager: AppLockManager

    @Before
    fun setUp() {
        appLockManager = AppLockManager()
    }

    @Test
    fun appLockDisabled_doesNotLockAfterFiveMinutes() {
        appLockManager.isAppLockEnabled = false
        val now = 1000000L

        appLockManager.onAppBackgrounded(now)
        appLockManager.onAppForegrounded(now + 6 * 60 * 1000L) // 6 minutes later

        assertThat(appLockManager.isLocked.value).isFalse()
    }

    @Test
    fun appLockEnabled_underFiveMinutes_doesNotLock_TC_PRIV_01() {
        appLockManager.isAppLockEnabled = true
        val now = 1000000L

        appLockManager.onAppBackgrounded(now)
        appLockManager.onAppForegrounded(now + 4 * 60 * 1000L) // 4 minutes later

        assertThat(appLockManager.isLocked.value).isFalse()
    }

    @Test
    fun appLockEnabled_afterFiveMinutes_locksApp_TC_PRIV_01() {
        appLockManager.isAppLockEnabled = true
        val now = 1000000L

        appLockManager.onAppBackgrounded(now)
        appLockManager.onAppForegrounded(now + 5 * 60 * 1000L + 1L) // 5 minutes + 1 ms

        assertThat(appLockManager.isLocked.value).isTrue()

        // Unlocking clears state
        appLockManager.unlock()
        assertThat(appLockManager.isLocked.value).isFalse()
    }

    @Test
    fun manualLock_whenEnabled_locks() {
        appLockManager.isAppLockEnabled = true
        appLockManager.lock()
        assertThat(appLockManager.isLocked.value).isTrue()

        appLockManager.unlock()
        assertThat(appLockManager.isLocked.value).isFalse()
    }

    @Test
    fun disablingLock_clearsLockedState() {
        appLockManager.isAppLockEnabled = true
        appLockManager.lock()
        assertThat(appLockManager.isLocked.value).isTrue()

        appLockManager.isAppLockEnabled = false
        assertThat(appLockManager.isLocked.value).isFalse()
    }

    @Test
    fun activityResult_unlocksWhenOk() {
        appLockManager.isAppLockEnabled = true
        appLockManager.lock()
        assertThat(appLockManager.isLocked.value).isTrue()

        val handled = appLockManager.onActivityResult(
            requestCode = AppLockManager.REQUEST_CODE_CONFIRM_DEVICE_CREDENTIAL,
            resultCode = android.app.Activity.RESULT_OK
        )

        assertThat(handled).isTrue()
        assertThat(appLockManager.isLocked.value).isFalse()
    }
}
