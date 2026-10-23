package com.github.se.pooltrack.model.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloadHelpersTest {

  @get:Rule val tmp = TemporaryFolder()

  private val bytes = ByteArray(300_000) { (it % 251).toByte() }

  private fun sha() = MessageDigest.getInstance("SHA-256")

  @Test
  fun percentDone_isNull_whenTotalIsUnknown() {
    assertNull(percentDone(10, 0))
  }

  @Test
  fun percentDone_isCappedAtOneHundred() {
    assertEquals(50, percentDone(50, 100))
    assertEquals(100, percentDone(500, 100))
  }

  @Test
  fun prepareApkFile_clearsTheDirectoryAndNamesTheFile() {
    val dir = tmp.newFolder("updates")
    File(dir, "old.apk").writeText("x")

    val apk = prepareApkFile(dir, "1.2.0")

    assertEquals(File(dir, "PoolTrack-1.2.0.apk"), apk)
    assertTrue(dir.isDirectory)
    assertEquals(0, dir.listFiles()!!.size)
  }

  @Test
  fun deleteQuietly_deletesAnExistingFileAndToleratesAMissingOne() {
    val file = tmp.newFile("a.apk")

    assertTrue(deleteQuietly(file))
    assertFalse(file.exists())
    assertTrue(deleteQuietly(file))
  }

  @Test
  fun deleteQuietly_reportsFailure_whenTheFileCannotBeDeleted() {
    val dir = tmp.newFolder("full")
    File(dir, "child").writeText("x")

    assertFalse(deleteQuietly(dir))
  }

  @Test
  fun copyApk_writesTheFileDigestsItAndReportsProgress() = runTest {
    val target = tmp.newFile("out.apk")
    val digest = sha()
    val progress = mutableListOf<Float>()

    val written =
        copyApk(ByteArrayInputStream(bytes), target, digest, bytes.size.toLong(), 1_000_000L) {
          progress += it
        }

    assertEquals(bytes.size.toLong(), written)
    assertTrue(bytes.contentEquals(target.readBytes()))
    assertTrue(sha().digest(bytes).contentEquals(digest.digest()))
    assertEquals(1f, progress.last(), 0f)
    assertEquals(progress.distinct(), progress)
  }

  @Test
  fun copyApk_reportsNoProgress_whenTheSizeIsUnknown() = runTest {
    val progress = mutableListOf<Float>()

    copyApk(ByteArrayInputStream(bytes), tmp.newFile("o.apk"), sha(), 0L, 1_000_000L) {
      progress += it
    }

    assertTrue(progress.isEmpty())
  }

  @Test
  fun copyApk_refuses_whenTheAnnouncedSizeIsTooLarge() = runTest {
    try {
      copyApk(ByteArrayInputStream(bytes), tmp.newFile("big.apk"), sha(), 2_000_000L, 1_000L) {}
      fail("expected IOException")
    } catch (_: IOException) {}
  }

  @Test
  fun copyApk_refuses_whenMoreBytesArriveThanAllowed() = runTest {
    try {
      copyApk(ByteArrayInputStream(bytes), tmp.newFile("big2.apk"), sha(), 0L, 1_000L) {}
      fail("expected IOException")
    } catch (_: IOException) {}
  }
}
