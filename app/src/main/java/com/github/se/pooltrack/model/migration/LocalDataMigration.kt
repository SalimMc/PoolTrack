package com.github.se.pooltrack.model.migration

import com.github.se.pooltrack.model.entry.EntryRepository
import com.github.se.pooltrack.model.entry.documentId
import com.github.se.pooltrack.model.entry.toDocument
import com.github.se.pooltrack.model.store.DocumentStore
import com.github.se.pooltrack.model.subscription.PdfFiles
import com.github.se.pooltrack.model.subscription.SubscriptionRepository
import com.github.se.pooltrack.model.subscription.hasPdf
import com.github.se.pooltrack.model.subscription.toDocument
import kotlinx.coroutines.flow.first

/**
 * Moves the data that older versions kept only on the device into the account's [DocumentStore],
 * once, so nobody loses what they recorded before the data moved to Firestore. Knows nothing about
 * where the old data lives or how it is stored: it only reads it through the old repositories.
 *
 * @property legacyEntries The repository the older versions stored the entries in.
 * @property legacySubscriptions The repository the older versions stored the subscriptions in.
 * @property store Where the data lives now.
 * @property pdfFiles Where the PDFs of the subscriptions are remembered on this device.
 * @property isDone Whether the migration already ran for the signed-in account.
 * @property markDone Remembers that the migration ran for the signed-in account.
 */
class LocalDataMigration(
    private val legacyEntries: EntryRepository,
    private val legacySubscriptions: SubscriptionRepository,
    private val store: DocumentStore,
    private val pdfFiles: PdfFiles,
    private val isDone: () -> Boolean,
    private val markDone: () -> Unit,
) {

  /**
   * Uploads the old entries, subscriptions and active subscription unless that was done already.
   *
   * @return `true` if anything was migrated, `false` if it had already run.
   */
  suspend fun migrateOnce(): Boolean {
    if (isDone()) return false
    val subscriptions = legacySubscriptions.getSubscriptions().first()
    val entries = legacyEntries.getEntries().first()
    val active = legacySubscriptions.getActiveSubscription().first()

    subscriptions.forEach { subscription ->
      if (subscription.hasPdf) pdfFiles.save(subscription.id, subscription.uri)
      store.set("subscriptions", subscription.id, subscription.toDocument())
    }
    entries.forEach { entry -> store.set("entries", entry.documentId, entry.toDocument()) }
    active?.let { store.set("settings", "state", mapOf("activeSubscriptionId" to it.id)) }
    markDone()
    return true
  }
}
