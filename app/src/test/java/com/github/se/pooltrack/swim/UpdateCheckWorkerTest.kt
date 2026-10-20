package com.github.se.pooltrack.swim

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class UpdateCheckWorkerTest {

  @Test
  fun postUpdateAvailable_showsTheVersionOnItsOwnChannel() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    SwimNotifications.postUpdateAvailable(context, "1.2.0")

    val manager = context.getSystemService(NotificationManager::class.java)
    val notification = shadowOf(manager).getNotification(SwimNotifications.UPDATE_NOTIFICATION_ID)
    assertNotNull(notification)
    assertEquals(SwimNotifications.UPDATE_CHANNEL_ID, notification.channelId)
    assertEquals(
        "Version 1.2.0 is ready to install. Open the app to update.",
        notification.extras.getCharSequence("android.text").toString(),
    )
  }
}
