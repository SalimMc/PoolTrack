package com.github.se.pooltrack.model.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private const val OWNER = "SamiPr0"
private const val REPO = "PoolTrack"
private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
private const val TRUSTED_DOWNLOAD_PREFIX = "https://github.com/$OWNER/$REPO/releases/download/"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val UPDATES_DIR = "updates"
private const val BUFFER_SIZE = 64 * 1024
private const val PERCENT = 100

private val json = Json { ignoreUnknownKeys = true }

/**
 * An [UpdateRepository] that reads the latest GitHub release of the app and installs its APK.
 *
 * The APK is downloaded to the app's cache (so it never needs storage permissions), checked against
 * the size and SHA-256 the release publishes, then handed to the system installer. Android itself
 * refuses to install it unless it is signed with the same key as the running app.
 */
class UpdateRepositoryGithub(
    context: Context,
    private val http: HttpSource = UrlConnectionSource(),
    private val latestReleaseUrl: String = LATEST_RELEASE_URL,
    private val trustedUrlPrefix: String = TRUSTED_DOWNLOAD_PREFIX,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxReleaseJsonBytes: Long = MAX_RELEASE_JSON_BYTES,
    private val maxApkBytes: Long = MAX_APK_BYTES,
) : UpdateRepository {

  private val context = context.applicationContext

  override suspend fun checkForUpdate(currentVersion: String): AppUpdate? =
      withContext(ioDispatcher) {
        try {
          val text =
              http.open(latestReleaseUrl).use { it.body.readTextLimited(maxReleaseJsonBytes) }
          json.decodeFromString<GithubRelease>(text).toAppUpdate(trustedUrlPrefix)?.takeIf {
            isNewerVersion(it.version, currentVersion)
          }
        } catch (_: IOException) {
          null
        } catch (_: IllegalArgumentException) {
          // Malformed JSON (SerializationException is an IllegalArgumentException).
          null
        }
      }

  override fun download(update: AppUpdate): Flow<DownloadStatus> = flow {
    val dir = File(context.cacheDir, UPDATES_DIR)
    // Drops APKs left over from earlier updates and from interrupted downloads.
    dir.deleteRecursively()
    dir.mkdirs()
    val apk = File(dir, "PoolTrack-${update.version}.apk")

    try {
      emit(DownloadStatus.Progress(null))
      val digest = MessageDigest.getInstance("SHA-256")
      var written = 0L
      var lastPercent = -1
      http.open(update.apkUrl).use { response ->
        val total = if (response.contentLength > 0) response.contentLength else update.sizeBytes
        if (total > maxApkBytes) throw IOException("Update is larger than $maxApkBytes bytes")
        apk.outputStream().use { out ->
          val buffer = ByteArray(BUFFER_SIZE)
          while (true) {
            currentCoroutineContext().ensureActive()
            val read = response.body.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            written += read
            if (written > maxApkBytes) throw IOException("Update is larger than $maxApkBytes bytes")
            if (total > 0) {
              val percent = (written * PERCENT / total).toInt().coerceAtMost(PERCENT)
              if (percent != lastPercent) {
                lastPercent = percent
                emit(DownloadStatus.Progress(percent / PERCENT.toFloat()))
              }
            }
          }
        }
      }
      verify(update, written, digest.digest().toHex())
      emit(DownloadStatus.Finished(apk))
    } catch (e: Throwable) {
      apk.delete()
      throw e
    }
  }
      .flowOn(ioDispatcher)

  override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

  override fun install(apk: File): InstallResult {
    if (!canInstall()) {
      val settings =
          Intent(
                  Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                  Uri.parse("package:${context.packageName}"),
              )
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      return launch(settings, InstallResult.PERMISSION_REQUIRED)
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    val installer =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return launch(installer, InstallResult.STARTED)
  }

  private fun launch(intent: Intent, onSuccess: InstallResult): InstallResult =
      try {
        context.startActivity(intent)
        onSuccess
      } catch (_: ActivityNotFoundException) {
        InstallResult.FAILED
      }

  private fun verify(update: AppUpdate, written: Long, sha256: String) {
    if (update.sizeBytes > 0 && written != update.sizeBytes) {
      throw IOException("Incomplete download: $written of ${update.sizeBytes} bytes")
    }
    if (update.sha256 != null && update.sha256 != sha256) {
      throw IOException("Checksum mismatch")
    }
  }
}

/** The release description is a few kilobytes; anything above this is not a release. */
internal const val MAX_RELEASE_JSON_BYTES = 1_000_000L

/** The app is a few megabytes; anything far above this is not an update. */
internal const val MAX_APK_BYTES = 100L * 1024 * 1024

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
