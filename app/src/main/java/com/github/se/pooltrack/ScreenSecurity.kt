package com.github.se.pooltrack

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Marks this window as secure, or not. A secure window can't be screenshotted, screen-recorded or
 * cast, and shows blank in the recent-apps switcher. Compose dialogs inherit the flag from the
 * window they're opened over.
 */
fun Window.applyScreenSecurity(secure: Boolean) {
  if (secure) {
    addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  } else {
    clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
  }
}

/**
 * Keeps the activity's window secure while this composable is shown, so the subscription pass never
 * leaves the screen, and lets go of it again when it leaves. The rest of the app stays shareable.
 * Debug builds stay screenshot-able, for design reviews and bug reports.
 */
@Composable
fun SecureWindow(secure: Boolean = !BuildConfig.DEBUG) {
  val activity = LocalActivity.current ?: return
  DisposableEffect(activity, secure) {
    activity.window.applyScreenSecurity(secure)
    onDispose { activity.window.applyScreenSecurity(secure = false) }
  }
}
