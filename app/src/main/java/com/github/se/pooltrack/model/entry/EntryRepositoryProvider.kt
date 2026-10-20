package com.github.se.pooltrack.model.entry

import android.content.Context
import com.github.se.pooltrack.model.auth.AuthRepositoryProvider
import com.github.se.pooltrack.model.store.FirestoreDocumentStore
import kotlinx.coroutines.flow.map

/**
 * Provides a single instance of the repository in the app. `repository` is mutable for testing
 * purposes. The Firestore implementation is scoped to the account signed in through
 * [AuthRepositoryProvider]. [init] must be called (e.g. from `MainActivity`) before `repository` is
 * first read.
 */
object EntryRepositoryProvider {
  lateinit var repository: EntryRepository

  /** Creates the Firestore repository, unless one was already set (e.g. a fake in a test). */
  fun init(context: Context) {
    if (!::repository.isInitialized) {
      repository =
          EntryRepositoryFirestore(
              FirestoreDocumentStore(
                  AuthRepositoryProvider.repository.getCurrentUser().map { it?.uid }
              )
          )
    }
  }
}
