package com.github.se.pooltrack.model.backup

import com.google.android.gms.tasks.Task
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Best-effort mirrors [data] to `users/{uid}/{collection}/{docId}` in Firestore, if a user is
 * currently signed in. A signed-out user, or any failure (offline, quota, etc.), is silently
 * skipped - this must never break the local write it's decorating.
 */
suspend fun mirrorToFirestore(collection: String, docId: String, data: Map<String, Any?>) {
  val uid = Firebase.auth.currentUser?.uid ?: return
  runCatching { userCollection(uid, collection).document(docId).set(data).awaitResult() }
}

/** The best-effort counterpart to [mirrorToFirestore], removing the mirrored document. */
suspend fun deleteFromFirestore(collection: String, docId: String) {
  val uid = Firebase.auth.currentUser?.uid ?: return
  runCatching { userCollection(uid, collection).document(docId).delete().awaitResult() }
}

/**
 * The best-effort counterpart of [mirrorToFirestore] for restoring: the fields of every document
 * under `users/{uid}/{collection}`, or `null` if nobody is signed in or the read failed (e.g.
 * offline), so a failed restore is never worse than having no backup.
 */
suspend fun fetchFromFirestore(collection: String): List<Map<String, Any?>>? {
  val uid = Firebase.auth.currentUser?.uid ?: return null
  return runCatching {
    userCollection(uid, collection).get().awaitResult().documents.mapNotNull { it.data }
  }
      .getOrNull()
}

private fun userCollection(uid: String, collection: String) =
    Firebase.firestore.collection("users").document(uid).collection(collection)

/**
 * Bridges a Play Services [Task] into a suspend call, without needing the
 * kotlinx-coroutines-play-services artifact just for this one call.
 */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
  addOnSuccessListener { continuation.resume(it) }
  addOnFailureListener { continuation.resumeWithException(it) }
}
