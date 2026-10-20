package com.github.se.pooltrack.swim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.github.se.pooltrack.MainActivity
import com.github.se.pooltrack.R

/** Builds and posts the notifications of swim tracking. */
object SwimNotifications {

  const val TRACKING_CHANNEL_ID = "swim_tracking"
  const val REPORT_CHANNEL_ID = "swim_report"
  const val TRACKING_NOTIFICATION_ID = 1
  const val REPORT_NOTIFICATION_ID = 2
  const val INACTIVITY_CHANNEL_ID = "swim_inactivity"
  const val INACTIVITY_NOTIFICATION_ID = 3

  /** Creates both channels; safe to call repeatedly. */
  fun createChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            TRACKING_CHANNEL_ID,
            context.getString(R.string.swim_tracking_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        )
    )
    manager.createNotificationChannel(
        NotificationChannel(
            REPORT_CHANNEL_ID,
            context.getString(R.string.swim_report_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
    )
    manager.createNotificationChannel(
        NotificationChannel(
            INACTIVITY_CHANNEL_ID,
            context.getString(R.string.inactivity_reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    )
  }

  /** The silent, ongoing notification the foreground service has to show while it waits. */
  fun trackingNotification(context: Context): Notification =
      Notification.Builder(context, TRACKING_CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_swim_notification)
          .setContentTitle(context.getString(R.string.swim_tracking_ongoing_title))
          .setContentText(context.getString(R.string.swim_tracking_ongoing_text))
          .setOngoing(true)
          .setContentIntent(openAppIntent(context))
          .build()

  /**
   * Posts the "log how far you swam" reminder; tapping it opens the app, which then shows the
   * distance screen. Silently dropped if notifications are not allowed.
   */
  fun postLogReminder(context: Context) {
    val notification =
        Notification.Builder(context, REPORT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_swim_notification)
            .setContentTitle(context.getString(R.string.swim_report_title))
            .setContentText(context.getString(R.string.swim_report_text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
    context
        .getSystemService(NotificationManager::class.java)
        .notify(REPORT_NOTIFICATION_ID, notification)
  }

  /**
   * Posts the "you haven't swum in a while" nudge; tapping it opens the app. Silently dropped if
   * notifications are not allowed.
   */
  fun postInactivityReminder(context: Context) {
    createChannels(context)
    val notification =
        Notification.Builder(context, INACTIVITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_swim_notification)
            .setContentTitle(context.getString(R.string.inactivity_reminder_title))
            .setContentText(context.getString(R.string.inactivity_reminder_text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
    context
        .getSystemService(NotificationManager::class.java)
        .notify(INACTIVITY_NOTIFICATION_ID, notification)
  }

  /** Removes the reminder once it is no longer needed, e.g. the distance was logged in the app. */
  fun cancelLogReminder(context: Context) {
    context.getSystemService(NotificationManager::class.java).cancel(REPORT_NOTIFICATION_ID)
  }

  private fun openAppIntent(context: Context): PendingIntent =
      PendingIntent.getActivity(
          context,
          0,
          Intent(context, MainActivity::class.java),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
}
