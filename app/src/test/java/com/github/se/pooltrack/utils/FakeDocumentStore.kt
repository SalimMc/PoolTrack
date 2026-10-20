package com.github.se.pooltrack.utils

import com.github.se.pooltrack.model.store.DocumentStore
import com.github.se.pooltrack.model.store.StoredDocument
import com.github.se.pooltrack.model.subscription.PdfFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [DocumentStore] for unit tests. Like the real one, [update] does nothing for a document
 * that does not exist, and the writes throw while [signedIn] is `false`.
 */
class FakeDocumentStore(var signedIn: Boolean = true) : DocumentStore {

  private val collections =
      MutableStateFlow<Map<String, Map<String, Map<String, Any?>>>>(emptyMap())

  /** The fields of the document [id] of [collection], or `null` if there is none. */
  fun document(collection: String, id: String): Map<String, Any?>? =
      collections.value[collection]?.get(id)

  /** The ids of the documents of [collection], in insertion order. */
  fun ids(collection: String): List<String> = collections.value[collection]?.keys.orEmpty().toList()

  override fun observe(collection: String): Flow<List<StoredDocument>> = collections.map { all ->
    all[collection].orEmpty().map { (id, data) -> StoredDocument(id, data) }
  }

  override suspend fun set(collection: String, id: String, data: Map<String, Any?>) {
    requireSignedIn()
    change(collection) { it + (id to data) }
  }

  override suspend fun update(collection: String, id: String, fields: Map<String, Any?>) {
    requireSignedIn()
    change(collection) { documents ->
      val existing = documents[id] ?: return@change documents
      documents + (id to existing + fields)
    }
  }

  override suspend fun delete(collection: String, id: String) {
    requireSignedIn()
    change(collection) { it - id }
  }

  private fun requireSignedIn() = check(signedIn) { "Data can only be changed while signed in" }

  private fun change(
      collection: String,
      transform: (Map<String, Map<String, Any?>>) -> Map<String, Map<String, Any?>>,
  ) {
    val all = collections.value
    collections.value = all + (collection to transform(all[collection].orEmpty()))
  }
}

/** In-memory [PdfFiles] for unit tests, recording the URIs it was asked to keep readable. */
class FakePdfFiles(initial: Map<String, String> = emptyMap()) : PdfFiles {

  private val stored = MutableStateFlow(initial)

  /** The URIs [keepReadable] was called with, in order. */
  val keptReadable = mutableListOf<String>()

  override val uris: Flow<Map<String, String>> = stored

  override suspend fun save(id: String, uri: String) {
    stored.value = stored.value + (id to uri)
  }

  override suspend fun remove(id: String) {
    stored.value = stored.value - id
  }

  override fun keepReadable(uri: String) {
    keptReadable += uri
  }

  override fun displayNameOf(uri: String): String = uri.substringAfterLast('/')
}
