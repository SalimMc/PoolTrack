package com.github.se.pooltrack.model.subscription

import com.github.se.pooltrack.model.store.DocumentStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val COLLECTION = "subscriptions"
private const val SETTINGS = "settings"
private const val SETTINGS_ID = "state"
private const val ACTIVE_ID_FIELD = "activeSubscriptionId"

/** The fields a subscription is stored with, which never include its PDF (see [PdfFiles]). */
fun Subscription.toDocument(): Map<String, Any?> =
    mapOf(
        "id" to id,
        "displayName" to displayName,
        "addedAtEpochMilli" to addedAtEpochMilli,
        "expiresAtEpochMilli" to expiresAtEpochMilli,
        "maxEntries" to maxEntries,
        "price" to price,
    )

/**
 * Keeps the subscriptions in the [DocumentStore], so they follow the account to any device and stay
 * readable and writable offline. Only the PDF stays on the device, in [pdfFiles].
 */
class SubscriptionRepositoryFirestore(
    private val store: DocumentStore,
    private val pdfFiles: PdfFiles,
) : SubscriptionRepository {

  override fun getSubscriptions(): Flow<List<Subscription>> =
      combine(store.observe(COLLECTION), pdfFiles.uris) { documents, uris ->
        documents
            .mapNotNull { document ->
              subscriptionFromBackup(document.data)?.copy(uri = uris[document.id].orEmpty())
            }
            .sortedByDescending { it.addedAtEpochMilli }
      }

  override fun getActiveSubscription(): Flow<Subscription?> =
      combine(getSubscriptions(), activeId()) { subscriptions, id ->
        subscriptions.find { it.id == id }
      }

  override suspend fun addSubscription(
      uri: String,
      expiresAtEpochMilli: Long?,
      maxEntries: Int?,
      price: Double?,
  ): Subscription {
    pdfFiles.keepReadable(uri)
    val subscription =
        Subscription(
            id = UUID.randomUUID().toString(),
            uri = uri,
            displayName = pdfFiles.displayNameOf(uri),
            addedAtEpochMilli = System.currentTimeMillis(),
            expiresAtEpochMilli = expiresAtEpochMilli,
            maxEntries = maxEntries,
            price = price,
        )
    pdfFiles.save(subscription.id, uri)
    store.set(COLLECTION, subscription.id, subscription.toDocument())
    setActiveSubscription(subscription.id)
    return subscription
  }

  override suspend fun updateSubscription(
      id: String,
      displayName: String,
      expiresAtEpochMilli: Long?,
      maxEntries: Int?,
      price: Double?,
  ) {
    store.update(
        COLLECTION,
        id,
        mapOf(
            "displayName" to displayName,
            "expiresAtEpochMilli" to expiresAtEpochMilli,
            "maxEntries" to maxEntries,
            "price" to price,
        ),
    )
  }

  override suspend fun attachPdf(id: String, uri: String) {
    pdfFiles.keepReadable(uri)
    pdfFiles.save(id, uri)
  }

  override suspend fun setActiveSubscription(id: String) {
    store.set(SETTINGS, SETTINGS_ID, mapOf(ACTIVE_ID_FIELD to id))
  }

  override suspend fun deleteSubscription(id: String) {
    val wasActive = activeId().first() == id
    store.delete(COLLECTION, id)
    pdfFiles.remove(id)
    if (wasActive) store.set(SETTINGS, SETTINGS_ID, mapOf(ACTIVE_ID_FIELD to null))
  }

  /** Emits the id of the active subscription, or `null` if none is. */
  private fun activeId(): Flow<String?> =
      store.observe(SETTINGS).map { documents ->
        documents.find { it.id == SETTINGS_ID }?.data?.get(ACTIVE_ID_FIELD) as? String
      }
}
