package com.github.se.pooltrack.model.subscription

import android.content.Context
import com.github.se.pooltrack.model.auth.AuthRepositoryProvider
import com.github.se.pooltrack.model.store.FirestoreDocumentStore
import kotlinx.coroutines.flow.map

/**
 * Provides a single instance of the repository in the app. `repository` is mutable for testing
 * purposes. The Firestore implementation is scoped to the account signed in through
 * [AuthRepositoryProvider], and needs a [Context] for where the PDFs are on the device, so [init]
 * must be called (e.g. from `MainActivity`) before `repository` is first read.
 */
object SubscriptionRepositoryProvider {
  lateinit var repository: SubscriptionRepository

  /** Creates the Firestore repository, unless one was already set (e.g. a fake in a test). */
  fun init(context: Context) {
    if (!::repository.isInitialized) {
      val currentUid = AuthRepositoryProvider.repository.getCurrentUser().map { it?.uid }
      repository =
          SubscriptionRepositoryFirestore(
              FirestoreDocumentStore(currentUid),
              PdfFilesLocal(context.applicationContext, currentUid),
          )
    }
  }
}
