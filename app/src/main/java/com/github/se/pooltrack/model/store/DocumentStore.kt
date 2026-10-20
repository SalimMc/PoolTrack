package com.github.se.pooltrack.model.store

import kotlinx.coroutines.flow.Flow

/**
 * One stored document.
 *
 * @property id The document's id within its collection.
 * @property data Its fields. Optional values are present as explicit `null`s.
 */
data class StoredDocument(val id: String, val data: Map<String, Any?>)

/**
 * The signed-in account's documents, grouped in named collections. The repositories keep all the
 * user's data here, and know nothing about Firebase: see [FirestoreDocumentStore].
 *
 * Reads are live and work offline, from the on-device cache. Writes take effect locally at once and
 * are synced when a connection is available, so none of them waits for the server, and they work
 * with no signal (e.g. at the pool).
 */
interface DocumentStore {

  /**
   * Emits the documents of [collection], again whenever one changes, including changes made on this
   * device that are not synced yet. Emits an empty list while nobody is signed in.
   */
  fun observe(collection: String): Flow<List<StoredDocument>>

  /**
   * Creates or replaces the document [id] of [collection] with [data].
   *
   * @throws IllegalStateException if nobody is signed in.
   */
  suspend fun set(collection: String, id: String, data: Map<String, Any?>)

  /**
   * Changes only [fields] of the document [id] of [collection]. Does nothing if it does not exist.
   *
   * @throws IllegalStateException if nobody is signed in.
   */
  suspend fun update(collection: String, id: String, fields: Map<String, Any?>)

  /**
   * Removes the document [id] of [collection].
   *
   * @throws IllegalStateException if nobody is signed in.
   */
  suspend fun delete(collection: String, id: String)
}
