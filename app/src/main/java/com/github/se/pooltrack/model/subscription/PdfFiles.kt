package com.github.se.pooltrack.model.subscription

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Where the PDF of each subscription is on this device. The PDF is a file the user picked, so only
 * this device can open it, and it stays out of the account's data (see [DocumentStore]): after a
 * reinstall a subscription is back without its PDF, until the user adds it again.
 */
interface PdfFiles {

  /** Emits the content URI of the PDF of each subscription that has one on this device, by id. */
  val uris: Flow<Map<String, String>>

  /** Remembers that the subscription [id] has its PDF at [uri]. */
  suspend fun save(id: String, uri: String)

  /** Forgets the PDF of the subscription [id]. */
  suspend fun remove(id: String)

  /** Keeps [uri] readable across app and device restarts. */
  fun keepReadable(uri: String)

  /** A human-readable label for the PDF at [uri], e.g. its file name. */
  fun displayNameOf(uri: String): String
}

private val Context.pdfFilesDataStore by preferencesDataStore(name = "pdf_files")

private fun urisKey(uid: String) = stringPreferencesKey("pdf_uris_json_$uid")

/** [PdfFiles] on-device, using Jetpack DataStore, separately for each account. */
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
          context.pdfFilesDataStore.data.map { prefs ->
            prefs[urisKey(uid)]?.let { Json.decodeFromString<Map<String, String>>(it) }
                ?: emptyMap()
          }
        }
      }

  override suspend fun save(id: String, uri: String) {
    change { it + (id to uri) }
  }

  override suspend fun remove(id: String) {
    var released: String? = null
    change { current ->
      val uri = current[id]
      val rest = current - id
      // Several subscriptions can share one file: keep the permission while any still uses it.
      if (uri != null && uri !in rest.values) released = uri
      rest
    }
    released?.let { releasePermission(it) }
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
