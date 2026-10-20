package com.github.se.pooltrack.model.subscription

import kotlinx.coroutines.flow.Flow

/** Represents a repository that manages the user's subscription PDFs. */
interface SubscriptionRepository {

  /** Emits every subscription that was ever added, most recently added first. */
  fun getSubscriptions(): Flow<List<Subscription>>

  /** Emits the currently active subscription, or `null` if none is active. */
  fun getActiveSubscription(): Flow<Subscription?>

  /**
   * Adds a new subscription and marks it active. The implementation keeps the PDF readable across
   * app and device restarts, and derives a human-readable label for it from the file itself.
   *
   * @param uri The content URI of the PDF the user picked, as a string.
   * @param expiresAtEpochMilli When it expires, as epoch milliseconds, or `null` for no date-based
   *   expiration.
   * @param maxEntries The number of entries it's good for, or `null` for no entry-count limit.
   * @param price How much it cost, or `null` if not recorded.
   * @return The newly created [Subscription].
   */
  suspend fun addSubscription(
      uri: String,
      expiresAtEpochMilli: Long? = null,
      maxEntries: Int? = null,
      price: Double? = null,
  ): Subscription

  /**
   * Replaces the editable details of the subscription with [id]. Its id, PDF and added date never
   * change, and entries already recorded against it are left alone. Does nothing if [id] is
   * unknown.
   *
   * @param id The identifier of the subscription to update.
   * @param displayName The new human-readable label.
   * @param expiresAtEpochMilli When it expires, as epoch milliseconds, or `null` for no date-based
   *   expiration.
   * @param maxEntries The number of entries it's good for, or `null` for no entry-count limit.
   * @param price How much it cost, or `null` if not recorded.
   */
  suspend fun updateSubscription(
      id: String,
      displayName: String,
      expiresAtEpochMilli: Long?,
      maxEntries: Int?,
      price: Double?,
  )

  /**
   * Gives the subscription with [id] a PDF, e.g. one restored from the backup without it. The
   * implementation keeps the PDF readable across restarts. The subscription's other details and its
   * entries are left alone. Does nothing if [id] is unknown.
   *
   * @param id The identifier of the subscription.
   * @param uri The content URI of the PDF the user picked, as a string.
   */
  suspend fun attachPdf(id: String, uri: String)

  /**
   * Marks the subscription with [id] as the active one, deactivating any other.
   *
   * @param id The identifier of the subscription to activate.
   */
  suspend fun setActiveSubscription(id: String)

  /**
   * Removes a subscription. If it was the active one, no subscription is active afterwards.
   *
   * Entries made with the subscription are kept, still tagged with its id, so the history stays
   * intact.
   *
   * @param id The identifier of the subscription to remove.
   */
  suspend fun deleteSubscription(id: String)
}
