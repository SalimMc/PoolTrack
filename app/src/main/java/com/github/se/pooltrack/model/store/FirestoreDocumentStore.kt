package com.github.se.pooltrack.model.store

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.Firebase
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

private const val TAG = "FirestoreDocumentStore"

/**
 * A [DocumentStore] on Cloud Firestore, under `users/{uid}/{collection}/{id}`.
 *
 * Firestore keeps a persistent cache of the documents on the device, so reads keep working offline
 * and writes are queued until the connection returns. That is why the writes do not wait for the
 * server: the listeners already show them, and Firestore retries them by itself. A write the server
 * rejects (e.g. by the security rules) is logged, and the cache drops it again.
 *
 * @param currentUid Emits the signed-in account's uid, or `null` when signed out.
 * @param firestore The Firestore instance to use.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreDocumentStore(
    private val currentUid: Flow<String?>,
    private val firestore: () -> FirebaseFirestore = { Firebase.firestore },
) : DocumentStore {

  override fun observe(collection: String): Flow<List<StoredDocument>> =
      currentUid.distinctUntilChanged().flatMapLatest { uid ->
        if (uid == null) {
          flowOf(emptyList())
        } else {
          callbackFlow {
            val registration =
                collectionOf(uid, collection).addSnapshotListener { snapshot, error ->
                  if (error != null) {
                    Log.w(TAG, "Listening to $collection failed", error)
                    return@addSnapshotListener
                  }
                  snapshot?.let { documents ->
                    trySend(documents.documents.map { StoredDocument(it.id, it.data.orEmpty()) })
                  }
                }
            awaitClose { registration.remove() }
          }
        }
      }

  override suspend fun set(collection: String, id: String, data: Map<String, Any?>) {
    documentOf(requireUid(), collection, id).set(data).logFailure("Saving $collection/$id")
  }

  override suspend fun update(collection: String, id: String, fields: Map<String, Any?>) {
    documentOf(requireUid(), collection, id).update(fields).logFailure("Updating $collection/$id")
  }

  override suspend fun delete(collection: String, id: String) {
    documentOf(requireUid(), collection, id).delete().logFailure("Deleting $collection/$id")
  }

  private suspend fun requireUid(): String =
      checkNotNull(currentUid.first()) { "Data can only be changed while signed in" }

  private fun collectionOf(uid: String, collection: String): CollectionReference =
      firestore().collection("users").document(uid).collection(collection)

  private fun documentOf(uid: String, collection: String, id: String): DocumentReference =
      collectionOf(uid, collection).document(id)

  private fun <T> Task<T>.logFailure(what: String) {
    addOnFailureListener { Log.w(TAG, "$what was rejected or failed", it) }
  }

  companion object {

    /**
     * Makes sure the on-device cache is persistent and never evicts documents, so the data stays
     * readable offline for as long as the app is installed. Must run before Firestore is first
     * used; later calls are ignored.
     */
    fun enableOfflineCache(firestore: () -> FirebaseFirestore = { Firebase.firestore }) {
      runCatching {
        firestore().firestoreSettings =
            FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(
                    PersistentCacheSettings.newBuilder()
                        .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                        .build()
                )
                .build()
      }
    }
  }
}
