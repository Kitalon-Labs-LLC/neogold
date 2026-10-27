package com.kitalonlabs.neogold.feature.screening.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * TC-LOG-09: the recheck notification carries no baby details and never needs the notification
 * permission to be granted for the app to keep working (NG-048, FR-36; BUG-2026-034).
 *
 * API 33 is the first level where POST_NOTIFICATIONS is a runtime permission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecheckNotificationHelperTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun test_TC_LOG_09_text_carries_no_baby_details() {
        val notification = RecheckNotificationHelper.buildNotification(app)

        assertThat(notification.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("NeoGold")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("A recheck is due")
    }

    @Test
    fun test_TC_LOG_09_posts_nothing_when_the_notification_permission_is_denied() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        RecheckNotificationHelper.showRecheckNotification(app)

        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    @Test
    @Config(sdk = [32])
    fun test_TC_LOG_09_posts_one_notification_on_api_32_where_no_runtime_permission_exists() {
        RecheckNotificationHelper.showRecheckNotification(app)

        assertThat(shadowOf(manager).allNotifications).hasSize(1)
    }

    @Test
    @Config(sdk = [32])
    fun test_TC_LOG_09_posts_nothing_on_api_32_when_the_user_switched_notifications_off() {
        shadowOf(manager).setNotificationsEnabled(false)

        RecheckNotificationHelper.showRecheckNotification(app)

        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    @Test
    fun test_TC_LOG_09_posts_one_notification_when_the_permission_is_granted() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        RecheckNotificationHelper.showRecheckNotification(app)

        assertThat(shadowOf(manager).allNotifications).hasSize(1)
    }
}
