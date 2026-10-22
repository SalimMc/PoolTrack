package com.github.se.pooltrack.model.subscription

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
) : PdfFiles {

  override val uris: Flow<Map<String, String>> =
      currentUid.distinctUntilChanged().flatMapLatest { uid ->
        if (uid == null) {
          flowOf(emptyMap())
        } else {
          context.pdfFilesDataStore.data
              .map { prefs ->
                prefs[urisKey(uid)]?.let { Json.decodeFromString<Map<String, String>>(it) }
                    ?: emptyMap()
              }
              .onEach { copyRemaining(it) }
        }
      }

  private val copyLock = Mutex()

  override suspend fun save(id: String, uri: String) {
    val stored = copyIntoAppStorage(uri) ?: uri
    var replaced: String? = null
    change { current ->
      replaced = current[id]
      current + (id to stored)
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
      if (uri != null && uri.startsWith("content:") && uri !in rest.values) released = uri
      rest
    }
    released?.let { releasePermission(it) }
    removed?.let { deleteCopy(it) }
  }

  /** Copies the PDFs still linked to their original file, e.g. those added by an older version. */
  private suspend fun copyRemaining(uris: Map<String, String>) {
    if (uris.values.none { it.startsWith("content:") }) return
    copyLock.withLock {
      for ((id, uri) in uris) {
        if (!uri.startsWith("content:")) continue
        val copy = copyIntoAppStorage(uri) ?: continue
        var swapped = false
        change { current ->
          swapped = current[id] == uri
          if (swapped) current + (id to copy) else current
        }
        if (!swapped) deleteCopy(copy)
      }
    }
  }

  /** Copies the file at [uri] into the app's storage and returns its URI, or `null` if it fails. */
  private suspend fun copyIntoAppStorage(uri: String): String? =
      withContext(Dispatchers.IO) {
        if (!uri.startsWith("content:")) return@withContext null
        val directory = File(context.filesDir, PDF_DIRECTORY)
        val target = File(directory, "${UUID.randomUUID()}.pdf")
        try {
          directory.mkdirs()
          val input = context.contentResolver.openInputStream(Uri.parse(uri))
          if (input == null) return@withContext null
          input.use { source -> target.outputStream().use { source.copyTo(it) } }
          Uri.fromFile(target).toString()
        } catch (_: IOException) {
          target.delete()
          null
        } catch (_: SecurityException) {
          target.delete()
          null
        }
      }

  /** Deletes the file at [uri] if it is one of the copies made by [copyIntoAppStorage]. */
  private suspend fun deleteCopy(uri: String) {
    if (!uri.startsWith("file:")) return
    withContext(Dispatchers.IO) {
      val file = File(requireNotNull(Uri.parse(uri).path))
      if (file.parentFile == File(context.filesDir, PDF_DIRECTORY)) file.delete()
    }
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
