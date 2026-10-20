package com.github.se.pooltrack.utils

import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.model.subscription.SubscriptionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * In-memory [SubscriptionRepository] for unit tests. Added subscriptions get the ids `sub-1`,
 * `sub-2`, ... and an `addedAtEpochMilli` equal to that same counter, so their order is
 * predictable.
 */
class FakeSubscriptionRepository(
    initialSubscriptions: List<Subscription> = emptyList(),
    initialActiveId: String? = null,
) : SubscriptionRepository {

  private val subscriptions = MutableStateFlow(initialSubscriptions)
  private val activeId = MutableStateFlow(initialActiveId)
  private var addedCount = 0

  /** Every stored subscription, in insertion order. */
  val storedSubscriptions: List<Subscription>
    get() = subscriptions.value

  /** The id of the active subscription, or `null` if none is active. */
  val storedActiveId: String?
    get() = activeId.value

  override fun getSubscriptions(): Flow<List<Subscription>> = subscriptions.map { list ->
    list.sortedByDescending { it.addedAtEpochMilli }
  }

  override fun getActiveSubscription(): Flow<Subscription?> =
      combine(subscriptions, activeId) { list, id -> list.find { it.id == id } }

  override suspend fun addSubscription(
      uri: String,
      expiresAtEpochMilli: Long?,
      maxEntries: Int?,
      price: Double?,
  ): Subscription {
    addedCount++
    val subscription =
        Subscription(
            id = "sub-$addedCount",
            uri = uri,
            displayName = "Subscription",
            addedAtEpochMilli = addedCount.toLong(),
            expiresAtEpochMilli = expiresAtEpochMilli,
            maxEntries = maxEntries,
            price = price,
        )
    subscriptions.value = subscriptions.value + subscription
    activeId.value = subscription.id
    return subscription
  }

  override suspend fun updateSubscription(
      id: String,
      displayName: String,
      expiresAtEpochMilli: Long?,
      maxEntries: Int?,
      price: Double?,
  ) {
    subscriptions.value =
        subscriptions.value.map {
          if (it.id == id) {
            it.copy(
                displayName = displayName,
                expiresAtEpochMilli = expiresAtEpochMilli,
                maxEntries = maxEntries,
                price = price,
            )
          } else {
            it
          }
        }
  }

  override suspend fun attachPdf(id: String, uri: String) {
    subscriptions.value = subscriptions.value.map { if (it.id == id) it.copy(uri = uri) else it }
  }

  override suspend fun setActiveSubscription(id: String) {
    activeId.value = id
  }

  override suspend fun deleteSubscription(id: String) {
    subscriptions.value = subscriptions.value.filter { it.id != id }
    if (activeId.value == id) activeId.value = null
  }
}
