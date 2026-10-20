package com.github.se.pooltrack.model.entry

import com.github.se.pooltrack.model.store.DocumentStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val COLLECTION = "entries"

/**
 * The id of an entry's document. Entries have no id of their own (they are matched structurally,
 * see [isSameEntryAs]), so it is derived from the fields that identify one.
 */
val Entry.documentId: String
  get() = "${timestampEpochMilli}_${subscriptionId ?: "none"}"

/** The fields an entry is stored with. Optional values are written as explicit `null`s. */
fun Entry.toDocument(): Map<String, Any?> =
    mapOf(
        "timestampEpochMilli" to timestampEpochMilli,
        "subscriptionId" to subscriptionId,
        "swimDurationMillis" to swimDurationMillis,
        "swimDistanceMeters" to swimDistanceMeters,
        "awaitingDistance" to awaitingDistance,
    )

/** Rebuilds an entry from its stored fields, or `null` if they are unusable. */
fun entryFromDocument(data: Map<String, Any?>): Entry? =
    entryFromBackup(data)?.copy(awaitingDistance = data["awaitingDistance"] as? Boolean ?: false)

/**
 * Keeps the confirmed entries in the [DocumentStore], so they follow the account to any device and
 * stay readable and writable offline.
 */
class EntryRepositoryFirestore(private val store: DocumentStore) : EntryRepository {

  override fun getEntries(): Flow<List<Entry>> =
      store.observe(COLLECTION).map { documents ->
        documents.mapNotNull { entryFromDocument(it.data) }.sortedByDescending { it.timestamp }
      }

  override suspend fun addEntry(entry: Entry) {
    store.set(COLLECTION, entry.documentId, entry.toDocument())
  }

  override suspend fun deleteEntry(entry: Entry) {
    store.delete(COLLECTION, entry.documentId)
  }

  override suspend fun recordSwimDuration(entry: Entry, durationMillis: Long) {
    store.update(COLLECTION, entry.documentId, mapOf("swimDurationMillis" to durationMillis))
  }

  override suspend fun recordSwimDistance(entry: Entry, meters: Int) {
    store.update(
        COLLECTION,
        entry.documentId,
        mapOf("swimDistanceMeters" to meters, "awaitingDistance" to false),
    )
  }
}
