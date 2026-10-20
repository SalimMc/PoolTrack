package com.github.se.pooltrack.model.entry

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.se.pooltrack.model.backup.deleteFromFirestore
import com.github.se.pooltrack.model.backup.fetchFromFirestore
import com.github.se.pooltrack.model.backup.mirrorToFirestore
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.entryDataStore by preferencesDataStore(name = "entry_prefs")

// Each account's entries live under their own key, so one account never sees another's.
private fun entriesKey(uid: String) = stringPreferencesKey("entries_json_$uid")

// Entries recorded before they were scoped to an account: first a JSON list, and before that
// plain newline-separated timestamps with no subscription association. The first account to sign
// in afterwards claims them (see claimLegacyEntries), since it's the device's owner.
private val LEGACY_ENTRIES_KEY = stringPreferencesKey("entries_json")
private val LEGACY_ENTRY_TIMESTAMPS_KEY = stringPreferencesKey("entry_timestamps")
private const val LEGACY_TIMESTAMP_SEPARATOR = "\n"

private const val BACKUP_COLLECTION = "entries"

// Entry has no id of its own (entries are deduped structurally, see readEntries/deleteEntry
// below), so this synthesizes a stable-enough Firestore document id from its own fields.
private fun Entry.backupDocId() = "${timestampEpochMilli}_${subscriptionId ?: "none"}"

/**
 * Stores confirmed entries on-device, using Jetpack DataStore, separately for each account.
 *
 * @param currentUid Emits the signed-in account's uid, or `null` when signed out. Reads only ever
 *   see that account's entries (none when signed out), and writes require someone to be signed in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryRepositoryLocal(
    private val context: Context,
    private val currentUid: Flow<String?>,
) : EntryRepository {

  override fun getEntries(): Flow<List<Entry>> =
      currentUid.distinctUntilChanged().flatMapLatest { uid ->
        if (uid == null) {
          flowOf(emptyList())
        } else {
          channelFlow {
            context.entryDataStore.edit { claimLegacyEntries(it, uid) }
            // A fresh install has no entries stored for this account at all (not even an empty
            // list): bring back the backup in the background, the stored data then re-emits.
            if (context.entryDataStore.data.first()[entriesKey(uid)] == null) {
              launch { restoreEntries(uid) }
            }
            context.entryDataStore.data
                .map { prefs -> readEntries(prefs, uid).sortedByDescending { it.timestamp } }
                .collect { send(it) }
          }
        }
      }

  override suspend fun addEntry(entry: Entry) {
    val uid = requireUid()
    context.entryDataStore.edit { prefs ->
      claimLegacyEntries(prefs, uid)
      prefs[entriesKey(uid)] = Json.encodeToString(readEntries(prefs, uid) + entry)
    }
    mirrorEntry(entry)
  }

  override suspend fun recordSwimDuration(entry: Entry, durationMillis: Long) {
    val uid = requireUid()
    var updated: Entry? = null
    context.entryDataStore.edit { prefs ->
      claimLegacyEntries(prefs, uid)
      val existing = readEntries(prefs, uid)
      if (entry !in existing) return@edit
      val withDuration = entry.copy(swimDurationMillis = durationMillis)
      updated = withDuration
      prefs[entriesKey(uid)] =
          Json.encodeToString(existing.map { if (it == entry) withDuration else it })
    }
    updated?.let { mirrorEntry(it) }
  }

  override suspend fun recordSwimDistance(entry: Entry, meters: Int) {
    val uid = requireUid()
    var updated: Entry? = null
    context.entryDataStore.edit { prefs ->
      claimLegacyEntries(prefs, uid)
      val existing = readEntries(prefs, uid)
      // Matched like deleteEntry, so a snapshot taken before the entry changed still finds it.
      val stored = existing.firstOrNull { it.isSameEntryAs(entry) } ?: return@edit
      val withDistance = stored.copy(swimDistanceMeters = meters, awaitingDistance = false)
      updated = withDistance
      prefs[entriesKey(uid)] =
          Json.encodeToString(existing.map { if (it === stored) withDistance else it })
    }
    updated?.let { mirrorEntry(it) }
  }

  override suspend fun deleteEntry(entry: Entry) {
    val uid = requireUid()
    context.entryDataStore.edit { prefs ->
      claimLegacyEntries(prefs, uid)
      prefs[entriesKey(uid)] =
          Json.encodeToString(readEntries(prefs, uid).filterNot { it.isSameEntryAs(entry) })
    }
    deleteFromFirestore(collection = BACKUP_COLLECTION, docId = entry.backupDocId())
  }

  private suspend fun mirrorEntry(entry: Entry) {
    mirrorToFirestore(
        collection = BACKUP_COLLECTION,
        docId = entry.backupDocId(),
        data =
            mapOf(
                "timestampEpochMilli" to entry.timestampEpochMilli,
                "subscriptionId" to entry.subscriptionId,
                "swimDurationMillis" to entry.swimDurationMillis,
                "swimDistanceMeters" to entry.swimDistanceMeters,
                "awaitingDistance" to entry.awaitingDistance,
            ),
    )
  }

  /** Merges the entries backed up in Firestore into the stored ones, skipping any already there. */
  private suspend fun restoreEntries(uid: String) {
    val restored = fetchFromFirestore(BACKUP_COLLECTION)?.mapNotNull(::entryFromBackup)
    if (restored.isNullOrEmpty()) return
    context.entryDataStore.edit { prefs ->
      val existing = readEntries(prefs, uid)
      val missing = restored.filter { entry -> existing.none { it.isSameEntryAs(entry) } }
      prefs[entriesKey(uid)] = Json.encodeToString(existing + missing)
    }
  }

  private suspend fun requireUid(): String =
      checkNotNull(currentUid.first()) { "Entries can only be changed while signed in" }

  private fun readEntries(prefs: Preferences, uid: String): List<Entry> =
      prefs[entriesKey(uid)]?.let { Json.decodeFromString<List<Entry>>(it) } ?: emptyList()

  /** Moves any entries stored before they were scoped to an account over to [uid]'s. */
  private fun claimLegacyEntries(prefs: MutablePreferences, uid: String) {
    val legacy = readLegacyEntries(prefs) ?: return
    if (prefs[entriesKey(uid)] == null) {
      prefs[entriesKey(uid)] = Json.encodeToString(legacy)
    }
    prefs.remove(LEGACY_ENTRIES_KEY)
    prefs.remove(LEGACY_ENTRY_TIMESTAMPS_KEY)
  }

  /** The unscoped entries, from either legacy format, or `null` if there are none. */
  private fun readLegacyEntries(prefs: Preferences): List<Entry>? {
    prefs[LEGACY_ENTRIES_KEY]?.let {
      return Json.decodeFromString<List<Entry>>(it)
    }
    return prefs[LEGACY_ENTRY_TIMESTAMPS_KEY]?.let { raw ->
      raw.split(LEGACY_TIMESTAMP_SEPARATOR)
          .filter { it.isNotBlank() }
          .map { Entry(timestampEpochMilli = Instant.parse(it).toEpochMilli()) }
    }
  }
}
