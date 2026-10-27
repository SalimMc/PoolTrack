package com.github.se.pooltrack

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.CompositionLocalProvider
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

  @Test
  fun secureWindow_doesNothing_withoutAnActivity() {
    composeRule.setContent {
      CompositionLocalProvider(LocalActivity provides null) { SecureWindow(secure = true) }
    }

    assertEquals(false, isSecure)
  }

  @Test
  fun secureWindow_securesOnlyReleaseBuilds_byDefault() {
    composeRule.setContent { SecureWindow() }

    assertEquals(!BuildConfig.DEBUG, isSecure)
  }

  @Test
  fun secureWindow_staysSecure_whenTheParentRecomposesWithTheSameFlag() {
    var tick by mutableStateOf(0)
    composeRule.setContent {
      tick
      SecureWindow(secure = true)
    }

    tick++
    composeRule.waitForIdle()

    assertEquals(true, isSecure)
  }
}
