package com.kitalonlabs.neogold.feature.screening.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Handles scheduling and displaying local recheck notifications (NG-048, FR-36, NFR-13, TC-LOG-09).
 *
 * Privacy rule:
 * Recheck reminders contain NO baby details, names, register numbers, or clinical results on the lock screen.
 * Notification text is strictly "A recheck is due".
 */
object RecheckNotificationHelper {

    const val CHANNEL_ID = "neogold_rechecks"
    const val NOTIFICATION_ID = 4001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Recheck Reminders"
            val descriptionText = "Notifications when a scheduled jaundice recheck is due"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the recheck notification ensuring no private identifiers are present.
     */
    fun buildNotification(
        context: Context,
        contentIntent: PendingIntent? = null
    ): android.app.Notification {
        createNotificationChannel(context)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NeoGold")
            .setContentText("A recheck is due")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .apply {
                if (contentIntent != null) {
                    setContentIntent(contentIntent)
                }
            }
            .build()
    }

    /**
     * Shows the notification only if the user has allowed notifications. When they are blocked,
     * nothing is posted and the app carries on: the Home screen due list still shows the recheck
     * (FR-36, TC-LOG-09; BUG-2026-034).
     */
    fun showRecheckNotification(context: Context, contentIntent: PendingIntent? = null) {
        // POST_NOTIFICATIONS is a runtime permission from API 33. Earlier versions have no such
        // permission, so there the only switch is the user's notification setting.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        } else if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return
        }
        try {
            val notification = buildNotification(context, contentIntent)
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The permission can still be revoked between the check and the call.
        }
    }
}
