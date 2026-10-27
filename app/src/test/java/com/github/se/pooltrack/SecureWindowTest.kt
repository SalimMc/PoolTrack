package com.github.se.pooltrack

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureWindowTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private val isSecure: Boolean
    get() =
        composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

  @Test
  fun secureWindow_securesTheWindow_whileShown() {
    composeRule.setContent { SecureWindow(secure = true) }

    assertEquals(true, isSecure)
  }

  @Test
  fun secureWindow_releasesTheWindow_whenItLeavesTheComposition() {
    var shown by mutableStateOf(true)
    composeRule.setContent { if (shown) SecureWindow(secure = true) }
    assertEquals(true, isSecure)

    shown = false
    composeRule.waitForIdle()

    assertEquals(false, isSecure)
  }

  @Test
  fun secureWindow_leavesTheWindowShareable_whenNotSecure() {
    composeRule.setContent { SecureWindow(secure = false) }

    assertEquals(false, isSecure)
  }

  @Test
  fun secureWindow_followsTheSecureFlag_whenItChanges() {
    var secure by mutableStateOf(false)
    composeRule.setContent { SecureWindow(secure = secure) }
    assertEquals(false, isSecure)

    secure = true
    composeRule.waitForIdle()

    assertEquals(true, isSecure)
  }
}
