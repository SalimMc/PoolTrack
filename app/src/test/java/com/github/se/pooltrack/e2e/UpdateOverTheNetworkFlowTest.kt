package com.github.se.pooltrack.e2e

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.github.se.pooltrack.model.update.HttpResponse
import com.github.se.pooltrack.model.update.HttpSource
import com.github.se.pooltrack.model.update.InstallResult
import com.github.se.pooltrack.model.update.UpdateRepository
import com.github.se.pooltrack.model.update.UpdateRepositoryGithub
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.ui.update.UpdateRequiredScreenTestTags
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * The mandatory update with the real updater: only the network is faked, by a [HttpSource] that
 * plays GitHub's API and download servers.
 */
class UpdateOverTheNetworkFlowTest : EndToEndTest() {

  private val apiUrl = "https://api.github.com/repos/SamiPr0/PoolTrack/releases/latest"
  private val downloadPrefix = "https://github.com/SamiPr0/PoolTrack/releases/download/"
  private val apkUrl = "${downloadPrefix}v99.0.0/PoolTrack.apk"
  private val apkBytes = ByteArray(300_000) { (it % 251).toByte() }

  private val routes = mutableMapOf<String, () -> HttpResponse>()
  private val requested = mutableListOf<String>()
  private val application: Application = ApplicationProvider.getApplicationContext()

  /** The APKs the app handed to the system installer, in order. */
  private val installedApks = mutableListOf<File>()

  override fun updatesForApp(): UpdateRepository {
    val real =
        UpdateRepositoryGithub(
            application,
            http =
                HttpSource { url ->
                  requested += url
                  routes[url]?.invoke() ?: throw IOException("HTTP 404 for $url")
                },
        )
    return object : UpdateRepository by real {
      override fun install(apk: File): InstallResult {
        installedApks += apk
        // Android's FileProvider rejects every file when the JVM runs on Windows (it matches paths
        // with '/'), which would crash the install step of a local run. The permission and
        // settings path of the real updater needs no FileProvider, so it always runs for real.
        val onWindows = System.getProperty("os.name").startsWith("Windows")
        return if (onWindows && canInstall()) InstallResult.STARTED else real.install(apk)
      }
    }
  }

  private fun sha256(bytes: ByteArray) =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun serveApk(bytes: ByteArray = apkBytes) {
    routes[apkUrl] = { HttpResponse(ByteArrayInputStream(bytes), bytes.size.toLong()) }
  }

  private fun serveRelease(
      tag: String = "v99.0.0",
      url: String = apkUrl,
      digestOf: ByteArray? = apkBytes,
      prerelease: Boolean = false,
  ) {
    val digest = digestOf?.let { "\"sha256:${sha256(it)}\"" } ?: "null"
    val json =
        """
        {"tag_name":"$tag","prerelease":$prerelease,"draft":false,"body":"- Faster start",
         "assets":[{"name":"PoolTrack.apk","browser_download_url":"$url",
         "size":${apkBytes.size},"digest":$digest}]}
        """
            .trimIndent()
    routes[apiUrl] = { HttpResponse(ByteArrayInputStream(json.toByteArray()), -1) }
  }

  private fun titleText(): String? =
      composeRule
          .onAllNodes(androidx.compose.ui.test.hasTestTag(UpdateRequiredScreenTestTags.TITLE))
          .fetchSemanticsNodes()
          .firstOrNull()
          ?.config
          ?.let { config ->
            if (config.contains(SemanticsProperties.Text)) {
              config[SemanticsProperties.Text].joinToString("") { it.text }
            } else {
              null
            }
          }

  private fun waitForTitle(title: String) {
    composeRule.waitUntil(timeoutMillis = 10_000) { titleText() == title }
  }

  @Test
  fun newerRelease_isDownloadedAndVerified_thenHandedToTheSystemInstaller() {
    serveRelease()
    serveApk()
    launchApp()

    waitForTitle("Update required")
    composeRule.onNodeWithText("Version 99.0.0", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("Faster start", substring = true).assertIsDisplayed()
    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertDoesNotExist()

    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    waitForTitle("Ready to install")

    assertEquals(listOf(apiUrl, apkUrl), requested)
    // The verified download is what the installer is given.
    val apk = installedApks.single()
    assertEquals("PoolTrack-99.0.0.apk", apk.name)
    assertTrue(apk.readBytes().contentEquals(apkBytes))
  }

  @Test
  fun corruptedDownload_isRefused_andARetryFromAGoodCopyInstalls() {
    serveRelease()
    serveApk(bytes = apkBytes.reversedArray())
    launchApp()
    waitForTitle("Update required")

    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    waitForTitle("Update failed")
    composeRule.onNodeWithText("Checksum mismatch").assertIsDisplayed()
    assertTrue(installedApks.isEmpty())

    serveApk()
    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    waitForTitle("Ready to install")
    assertEquals(1, installedApks.size)
  }

  @Test
  fun interruptedDownload_saysSo_andCanBeRetried() {
    serveRelease()
    routes[apkUrl] = { throw IOException("Connection reset") }
    launchApp()
    waitForTitle("Update required")

    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    waitForTitle("Update failed")
    composeRule.onNodeWithText("Connection reset").assertIsDisplayed()

    serveApk()
    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    waitForTitle("Ready to install")
  }

  @Test
  fun withoutTheInstallPermission_theSettingsOpen_andInstallingCarriesOnOnceAllowed() {
    shadowOf(application.packageManager).setCanRequestPackageInstalls(false)
    serveRelease()
    serveApk()
    launchApp()
    waitForTitle("Update required")

    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    waitForTitle("Ready to install")
    composeRule
        .onNodeWithText("Allow PoolTrack to install apps", substring = true)
        .assertIsDisplayed()
    val settings = shadowOf(application).nextStartedActivity
    assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)

    // The user allows it in the settings and comes back to the app.
    shadowOf(application.packageManager).setCanRequestPackageInstalls(true)
    backgroundAndResume()

    // The installer is opened by itself, without another tap.
    composeRule.waitUntil(timeoutMillis = 5_000) { installedApks.size == 2 }
  }

  @Test
  fun olderRelease_leavesTheAppUsable() {
    serveRelease(tag = "v1.0.0")
    launchApp()
    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertIsDisplayed()
    tag(UpdateRequiredScreenTestTags.SCREEN).assertDoesNotExist()
  }

  @Test
  fun releaseHostedElsewhere_isNeverOffered() {
    serveRelease(url = "https://evil.example.com/PoolTrack.apk")
    launchApp()

    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertIsDisplayed()
    tag(UpdateRequiredScreenTestTags.SCREEN).assertDoesNotExist()
  }

  @Test
  fun offlineAtTheCheck_leavesTheAppUsable() {
    launchApp()

    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertIsDisplayed()
    tag(UpdateRequiredScreenTestTags.SCREEN).assertDoesNotExist()
    assertEquals(listOf(apiUrl), requested)
  }

  @Test
  fun garbageFromTheServer_leavesTheAppUsable() {
    routes[apiUrl] = { HttpResponse(ByteArrayInputStream("<html>".toByteArray()), -1) }
    launchApp()

    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertIsDisplayed()
  }

  @Test
  fun slowStart_showsTheStartingStateUntilTheFirstBytesArrive_andCanBeCancelled() {
    serveRelease()
    val firstByte = CountDownLatch(1)
    routes[apkUrl] = {
      HttpResponse(
          object : InputStream() {
            private val real = ByteArrayInputStream(apkBytes)

            override fun read(): Int {
              firstByte.await(10, TimeUnit.SECONDS)
              return real.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
              firstByte.await(10, TimeUnit.SECONDS)
              return real.read(b, off, len)
            }
          },
          apkBytes.size.toLong(),
      )
    }
    launchApp()
    waitForTitle("Update required")

    // The indeterminate bar animates forever, so the test clock is stepped by hand meanwhile.
    composeRule.mainClock.autoAdvance = false
    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.onNodeWithText("Starting download…").assertIsDisplayed()
    tag(UpdateRequiredScreenTestTags.PROGRESS).assertIsDisplayed()

    click(UpdateRequiredScreenTestTags.CANCEL_BUTTON)
    composeRule.mainClock.advanceTimeByFrame()
    firstByte.countDown()
    composeRule.mainClock.autoAdvance = true
    waitForTitle("Update required")
  }
}
