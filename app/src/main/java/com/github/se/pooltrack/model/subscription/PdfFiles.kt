package com.github.se.pooltrack.model.subscription

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Where the PDF of each subscription is on this device. The PDF stays out of the account's data
 * (see [DocumentStore]): after a reinstall a subscription is back without its PDF, until the user
 * adds it again. [PdfFilesLocal] copies each PDF into the app's own storage, so showing it never
 * depends on the original file (which may live in a cloud provider) or on a connection.
 */
interface PdfFiles {

  /** Emits the URI of the PDF of each subscription that has one on this device, by id. */
  val uris: Flow<Map<String, String>>

  /** Remembers that the subscription [id] has its PDF at [uri], keeping a copy on the device. */
  suspend fun save(id: String, uri: String)

  /** Forgets the PDF of the subscription [id]. */
  suspend fun remove(id: String)

  /** Keeps [uri] readable across app and device restarts. */
  fun keepReadable(uri: String)

  /** A human-readable label for the PDF at [uri], e.g. its file name. */
  fun displayNameOf(uri: String): String
}

private const val PDF_DIRECTORY = "pdfs"
private const val CONTENT_SCHEME = "content:"
private const val FILE_SCHEME = "file:"
private const val TAG = "PdfFiles"

private val Context.pdfFilesDataStore by preferencesDataStore(name = "pdf_files")

private fun urisKey(uid: String) = stringPreferencesKey("pdf_uris_json_$uid")

/**
 * [PdfFiles] on-device, using Jetpack DataStore, separately for each account. Each PDF is copied
 * into the app's private storage; a PDF that can't be copied (yet) is kept as the original link,
 * and copied the next time the URIs are read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PdfFilesLocal(
    private val context: Context,
    private val currentUid: Flow<String?>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PdfFiles {

  override val uris: Flow<Map<String, String>> =
      currentUid.distinctUntilChanged().flatMapLatest { uid ->
        if (uid == null) {
          flowOf(emptyMap())
        } else {
          // The copying runs beside the emissions, so a slow or unreachable original never holds
          // back the list of subscriptions.
          channelFlow {
            launch { copyRemaining(uid) }
            storedUris(uid).collect { send(it) }
          }
        }
      }

  private fun storedUris(uid: String): Flow<Map<String, String>> =
      context.pdfFilesDataStore.data.map { prefs ->
        prefs[urisKey(uid)]?.let { Json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()
      }

  private val copyLock = Mutex()

  override suspend fun save(id: String, uri: String) {
    val copy = copyIntoAppStorage(uri)
    val stored = copy ?: uri
    var replaced: String? = null
    try {
      change { current ->
        replaced = current[id]
        current + (id to stored)
      }
    } catch (e: Throwable) {
      // If nothing points to the copy it would stay in the storage for good.
      copy?.let { deleteIfUnlinked(it) }
      throw e
    }
    replaced?.takeIf { it != stored }?.let { deleteCopy(it) }
  }

  override suspend fun remove(id: String) {
    var released: String? = null
    var removed: String? = null
    change { current ->
      val uri = current[id]
      val rest = current - id
      removed = uri
      // Several subscriptions can share one file: keep the permission while any still uses it.
      if (uri != null && uri.startsWith(CONTENT_SCHEME) && uri !in rest.values) released = uri
      rest
    }
    released?.let { releasePermission(it) }
    removed?.let { deleteCopy(it) }
  }

  /** Copies the PDFs still linked to their original file, e.g. those added by an older version. */
  private suspend fun copyRemaining(uid: String) {
    if (storedUris(uid).first().values.none { it.startsWith(CONTENT_SCHEME) }) return
    copyLock.withLock {
      // Read again once it is this call's turn: another collector may have copied them meanwhile.
      for ((id, uri) in storedUris(uid).first()) {
        if (uri.startsWith(CONTENT_SCHEME)) relink(id, uri)
      }
    }
  }

  /** Replaces the link [uri] of the subscription [id] by a copy in the app's storage, if any. */
  private suspend fun relink(id: String, uri: String) {
    val copy = copyIntoAppStorage(uri) ?: return
    var swapped = false
    try {
      change { current ->
        swapped = current[id] == uri
        if (swapped) current + (id to copy) else current
      }
    } catch (e: Throwable) {
      deleteIfUnlinked(copy)
      throw e
    }
    if (!swapped) deleteCopy(copy)
  }

  /** Copies the file at [uri] into the app's storage and returns its URI, or `null` if it fails. */
  private suspend fun copyIntoAppStorage(uri: String): String? {
    if (!uri.startsWith(CONTENT_SCHEME)) return null
    val directory = File(context.filesDir, PDF_DIRECTORY)
    val target = File(directory, "${UUID.randomUUID()}.pdf")
    var delivered = false
    try {
      val copied = withContext(ioDispatcher) { streamInto(uri, directory, target) }
      delivered = copied
      return if (copied) Uri.fromFile(target).toString() else null
    } finally {
      // A failed copy leaves a partial file. So does a cancellation that arrives just as the copy
      // finishes: withContext then throws, and the caller never learns about the file.
      if (!delivered) deleteFile(target)
    }
  }

  /** Streams the file at [uri] into [target]; `false` if it can't be opened or read. */
  private fun streamInto(uri: String, directory: File, target: File): Boolean =
      try {
        directory.mkdirs()
        val input = context.contentResolver.openInputStream(Uri.parse(uri))
        if (input == null) {
          false
        } else {
          input.use { source -> target.outputStream().use { source.copyTo(it) } }
          true
        }
      } catch (_: IOException) {
        false
      } catch (_: SecurityException) {
        false
      }

  /** Best-effort delete: a file that can't be removed is only logged, nothing else can be done. */
  private fun deleteFile(file: File) {
    if (!file.delete() && file.exists()) Log.w(TAG, "Could not delete ${file.name}")
  }

  /**
   * Deletes the copy at [uri] unless a subscription points to it. A failure or cancellation can
   * arrive after the link was recorded, and the copy must then stay.
   */
  internal suspend fun deleteIfUnlinked(uri: String) =
      withContext(NonCancellable) {
        val uid = currentUid.first()
        if (uid == null || uri !in storedUris(uid).first().values) deleteCopy(uri)
      }

  /** Deletes the file at [uri] if it is one of the copies made by [copyIntoAppStorage]. */
  private fun deleteCopy(uri: String) {
    if (!uri.startsWith(FILE_SCHEME)) return
    val file = File(requireNotNull(Uri.parse(uri).path))
    if (file.parentFile == File(context.filesDir, PDF_DIRECTORY)) deleteFile(file)
  }

  /** Gives back the long-lived read permission taken by [keepReadable], so they don't pile up. */
  private fun releasePermission(uri: String) {
    try {
      context.contentResolver.releasePersistableUriPermission(
          Uri.parse(uri),
          Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    } catch (_: SecurityException) {
      // The permission was not held (any more), so there is nothing to give back.
    }
  }

  override fun keepReadable(uri: String) {
    context.contentResolver.takePersistableUriPermission(
        Uri.parse(uri),
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
  }

  /** Looks up [uri]'s display name via the content provider, falling back to a generic label. */
  override fun displayNameOf(uri: String): String {
    context.contentResolver
        .query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (nameIndex >= 0 && cursor.moveToFirst()) {
            cursor.getString(nameIndex)?.let {
              return it
            }
          }
        }
    return "Subscription"
  }

  private suspend fun change(transform: (Map<String, String>) -> Map<String, String>) {
    val uid = checkNotNull(currentUid.first()) { "PDFs can only be changed while signed in" }
    context.pdfFilesDataStore.edit { prefs ->
      val current =
          prefs[urisKey(uid)]?.let { Json.decodeFromString<Map<String, String>>(it) } ?: emptyMap()
      prefs[urisKey(uid)] = Json.encodeToString(transform(current))
    }
  }
}
