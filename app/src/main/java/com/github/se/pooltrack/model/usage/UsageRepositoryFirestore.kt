package com.github.se.pooltrack.model.usage

import com.github.se.pooltrack.model.backup.awaitResult
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore

/** The fields stored on `users/{uid}`: the last open, the app version, and how many opens. */
fun usageDocument(nowEpochMilli: Long, appVersion: String): Map<String, Any> =
    mapOf(
        "lastOpenedAtEpochMilli" to nowEpochMilli,
        "appVersion" to appVersion,
        "openCount" to FieldValue.increment(1),
    )

/**
 * Keeps the usage figures on the account's own `users/{uid}` document. They are merged into it, so
 * the first open creates it and the next ones only bump [usageDocument]'s fields.
 */
class UsageRepositoryFirestore(
    private val now: () -> Long = System::currentTimeMillis,
) : UsageRepository {

  override suspend fun recordOpen(appVersion: String) {
    val uid = Firebase.auth.currentUser?.uid ?: return
    runCatching {
      Firebase.firestore
          .collection("users")
          .document(uid)
          .set(usageDocument(now(), appVersion), SetOptions.merge())
          .awaitResult()
    }
  }
}
