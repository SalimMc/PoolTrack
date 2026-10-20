package com.github.se.pooltrack.model.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateRepositoryGithubTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val apiUrl = "https://api.example.com/releases/latest"
  private val prefix = "https://github.com/test/app/releases/download/"
  private val apkUrl = "${prefix}v1.2.0/app.apk"
  private val apkBytes = ByteArray(200_000) { (it % 251).toByte() }

  private val routes = mutableMapOf<String, () -> HttpResponse>()
  private val http = HttpSource { url -> routes.getValue(url)() }
  private val repository =
      UpdateRepositoryGithub(context, http, apiUrl, prefix, UnconfinedTestDispatcher())

  private val update =
      AppUpdate("1.2.0", "", apkUrl, sizeBytes = apkBytes.size.toLong(), sha256 = null)

  private fun serve(url: String, bytes: ByteArray, contentLength: Long = bytes.size.toLong()) {
    routes[url] = { HttpResponse(ByteArrayInputStream(bytes), contentLength) }
  }

  private fun serveRelease(tag: String = "v1.2.0", prerelease: Boolean = false) {
    val json =
        """
        {"tag_name":"$tag","prerelease":$prerelease,"draft":false,"body":"Faster start",
         "assets":[{"name":"app.apk","browser_download_url":"$apkUrl","size":42,
                    "digest":"sha256:${sha256(apkBytes)}"}]}
        """
            .trimIndent()
    serve(apiUrl, json.toByteArray())
  }

  private fun sha256(bytes: ByteArray) =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun updatesDir() = File(context.cacheDir, "updates")

  // --- checkForUpdate ---

  @Test
  fun checkForUpdate_returnsTheRelease_whenItIsNewer() = runTest {
    serveRelease(tag = "v1.2.0")

    assertEquals(
        AppUpdate("1.2.0", "Faster start", apkUrl, sizeBytes = 42, sha256 = sha256(apkBytes)),
        repository.checkForUpdate("1.1.0"),
    )
  }

  @Test
  fun checkForUpdate_returnsNull_whenAlreadyUpToDate() = runTest {
    serveRelease(tag = "v1.1.0")

    assertNull(repository.checkForUpdate("1.1.0"))
  }

  @Test
  fun checkForUpdate_returnsNull_whenTheReleaseIsOlder() = runTest {
    serveRelease(tag = "v1.0.0")

    assertNull(repository.checkForUpdate("1.1.0"))
  }

  @Test
  fun checkForUpdate_returnsNull_forPreReleases() = runTest {
    serveRelease(tag = "v2.0.0", prerelease = true)

    assertNull(repository.checkForUpdate("1.1.0"))
  }

  @Test
  fun checkForUpdate_returnsNull_whenTheRequestFails() = runTest {
    routes[apiUrl] = { throw IOException("offline") }

    assertNull(repository.checkForUpdate("1.1.0"))
  }

  @Test
  fun checkForUpdate_returnsNull_whenTheReleaseHasNoChecksum() = runTest {
    serve(
        apiUrl,
        """
        {"tag_name":"v1.2.0","assets":[{"name":"app.apk","browser_download_url":"$apkUrl",
         "size":42,"digest":null}]}
        """
            .trimIndent()
            .toByteArray(),
    )

    assertNull(repository.checkForUpdate("1.1.0"))
  }

  @Test
  fun checkForUpdate_returnsNull_whenTheResponseIsTooLarge() = runTest {
    serveRelease()
    val small =
        UpdateRepositoryGithub(context, http, apiUrl, prefix, UnconfinedTestDispatcher(), 20)

    assertNull(small.checkForUpdate("1.1.0"))
  }

  @Test
  fun checkForUpdate_returnsNull_whenTheResponseIsNotValidJson() = runTest {
    serve(apiUrl, "<html>rate limited</html>".toByteArray())

    assertNull(repository.checkForUpdate("1.1.0"))
  }

  // --- download ---

  @Test
  fun download_savesTheApk_andReportsProgressUpToOneHundredPercent() = runTest {
    serve(apkUrl, apkBytes)

    val events = repository.download(update).toList()

    assertEquals(DownloadStatus.Progress(null), events.first())
    val fractions = events.filterIsInstance<DownloadStatus.Progress>().mapNotNull { it.fraction }
    assertEquals(fractions.sorted(), fractions)
    assertEquals(1f, fractions.last(), 0f)
    val apk = (events.last() as DownloadStatus.Finished).apk
    assertEquals(File(updatesDir(), "PoolTrack-1.2.0.apk"), apk)
    assertTrue(apkBytes.contentEquals(apk.readBytes()))
  }

  @Test
  fun download_usesTheReleaseSize_whenTheServerSendsNoLength() = runTest {
    serve(apkUrl, apkBytes, contentLength = -1)

    val events = repository.download(update).toList()

    assertEquals(DownloadStatus.Progress(1f), events[events.size - 2])
  }

  @Test
  fun download_reportsNoFractions_whenTheTotalSizeIsUnknown() = runTest {
    serve(apkUrl, apkBytes, contentLength = -1)

    val events = repository.download(update.copy(sizeBytes = 0)).toList()

    assertEquals(DownloadStatus.Progress(null), events.first())
    assertEquals(2, events.size)
    assertTrue(events.last() is DownloadStatus.Finished)
  }

  @Test
  fun download_accepts_aMatchingChecksum() = runTest {
    serve(apkUrl, apkBytes)

    val events = repository.download(update.copy(sha256 = sha256(apkBytes))).toList()

    assertTrue(events.last() is DownloadStatus.Finished)
  }

  @Test
  fun download_fails_andDeletesTheFile_whenTheChecksumDiffers() = runTest {
    serve(apkUrl, apkBytes)

    val error = runCatching { repository.download(update.copy(sha256 = "00")).toList() }

    assertEquals("Checksum mismatch", error.exceptionOrNull()?.message)
    assertFalse(File(updatesDir(), "PoolTrack-1.2.0.apk").exists())
  }

  @Test
  fun download_fails_andDeletesTheFile_whenTheSizeDiffers() = runTest {
    serve(apkUrl, apkBytes)

    val error = runCatching { repository.download(update.copy(sizeBytes = 999)).toList() }

    assertTrue(error.exceptionOrNull() is IOException)
    assertFalse(File(updatesDir(), "PoolTrack-1.2.0.apk").exists())
  }

  @Test
  fun download_fails_whenTheServerAnnouncesTooLargeAFile() = runTest {
    serve(apkUrl, apkBytes)
    val small =
        UpdateRepositoryGithub(
            context,
            http,
            apiUrl,
            prefix,
            UnconfinedTestDispatcher(),
            maxApkBytes = 1_000,
        )

    val error = runCatching { small.download(update).toList() }

    assertEquals("Update is larger than 1000 bytes", error.exceptionOrNull()?.message)
    assertEquals(emptyList<File>(), updatesDir().listFiles()?.toList())
  }

  @Test
  fun download_stops_whenTheStreamIsLargerThanItClaimed() = runTest {
    // No length is announced and the release lists none either, so only the stream can tell.
    serve(apkUrl, apkBytes, contentLength = -1)
    val small =
        UpdateRepositoryGithub(
            context,
            http,
            apiUrl,
            prefix,
            UnconfinedTestDispatcher(),
            maxApkBytes = 1_000,
        )

    val error = runCatching { small.download(update.copy(sizeBytes = 0)).toList() }

    assertEquals("Update is larger than 1000 bytes", error.exceptionOrNull()?.message)
    assertEquals(emptyList<File>(), updatesDir().listFiles()?.toList())
  }

  @Test
  fun download_fails_whenTheRequestFails() = runTest {
    routes[apkUrl] = { throw IOException("HTTP 404") }

    val error = runCatching { repository.download(update).toList() }

    assertEquals("HTTP 404", error.exceptionOrNull()?.message)
    assertEquals(emptyList<File>(), updatesDir().listFiles()?.toList())
  }

  @Test
  fun download_removesApksFromEarlierUpdates() = runTest {
    updatesDir().mkdirs()
    val stale = File(updatesDir(), "PoolTrack-1.0.0.apk").apply { writeText("old") }
    serve(apkUrl, apkBytes)

    repository.download(update).toList()

    assertFalse(stale.exists())
  }

  // --- install ---

  @Test
  fun canInstall_followsThePermissionTheUserGranted() {
    shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
    assertFalse(repository.canInstall())

    shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
    assertTrue(repository.canInstall())
  }

  @Test
  fun install_opensTheSystemInstaller_whenAllowed() {
    // FileProvider only matches a file to its root when the next path character is '/', so on a
    // Windows host it rejects every file. Devices and the Linux CI are not affected.
    assumeFalse(System.getProperty("os.name").startsWith("Windows"))
    shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
    updatesDir().mkdirs()
    val apk = File(updatesDir(), "PoolTrack-1.2.0.apk").apply { writeText("apk") }

    val result = repository.install(apk)

    assertEquals(InstallResult.STARTED, result)
    val intent = shadowOf(context as Application).nextStartedActivity
    assertEquals(Intent.ACTION_VIEW, intent.action)
    assertEquals("application/vnd.android.package-archive", intent.type)
    assertEquals("content", intent.data?.scheme)
    assertEquals("${context.packageName}.fileprovider", intent.data?.authority)
    assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
  }

  @Test
  fun install_opensTheSettingsToAllowInstalls_whenNotAllowedYet() {
    shadowOf(context.packageManager).setCanRequestPackageInstalls(false)

    val result = repository.install(File(updatesDir(), "PoolTrack-1.2.0.apk"))

    assertEquals(InstallResult.PERMISSION_REQUIRED, result)
    val intent = shadowOf(context as Application).nextStartedActivity
    assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
    assertEquals("package:${context.packageName}", intent.dataString)
  }

  @Test
  fun install_fails_whenNoActivityCanHandleTheIntent() {
    val brokenContext = mockk<Context>(relaxed = true)
    every { brokenContext.applicationContext } returns brokenContext
    every { brokenContext.packageManager.canRequestPackageInstalls() } returns false
    every { brokenContext.startActivity(any()) } throws ActivityNotFoundException()

    val result = UpdateRepositoryGithub(brokenContext).install(File("x.apk"))

    assertEquals(InstallResult.FAILED, result)
  }
}
