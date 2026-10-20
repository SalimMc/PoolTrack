package com.github.se.pooltrack.model.migration

import android.content.Context
import com.github.se.pooltrack.model.auth.AuthRepositoryProvider
import com.github.se.pooltrack.model.entry.EntryRepositoryLocal
import com.github.se.pooltrack.model.store.FirestoreDocumentStore
import com.github.se.pooltrack.model.subscription.PdfFilesLocal
import com.github.se.pooltrack.model.subscription.SubscriptionRepositoryLocal
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private const val PREFERENCES = "data_migration"

/**
 * Moves the data an account kept only on this device by older versions to Firestore, the first time
 * that account is signed in here (see [LocalDataMigration]). Keeps running while the app is, so an
 * account that signs in later is migrated as well.
 */
suspend fun migrateLocalDataOfSignedInAccounts(context: Context) {
  val appContext = context.applicationContext
  val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
  AuthRepositoryProvider.repository
      .getCurrentUser()
      .map { it?.uid }
      .filterNotNull()
      .distinctUntilChanged()
      .collect { uid ->
        val account = flowOf(uid)
        val key = "firestore_migrated_$uid"
        runCatching {
          LocalDataMigration(
                  legacyEntries = EntryRepositoryLocal(appContext, account),
                  legacySubscriptions = SubscriptionRepositoryLocal(appContext, account),
                  store = FirestoreDocumentStore(account),
                  pdfFiles = PdfFilesLocal(appContext, account),
                  isDone = { preferences.getBoolean(key, false) },
                  markDone = { preferences.edit().putBoolean(key, true).apply() },
              )
              .migrateOnce()
        }
      }
}
