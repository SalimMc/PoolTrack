package com.github.se.pooltrack.model.subscription

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

/**
 * One subscription PDF the user has added. Several can exist at once (e.g. an expired one kept for
 * reference and the new one replacing it), but only one is ever active at a time.
 *
 * @property id A locally-generated unique identifier.
 * @property uri The content URI of the PDF, as a string.
 * @property displayName A human-readable label, e.g. the file's original name.
 * @property addedAtEpochMilli When this subscription was added, as epoch milliseconds (kept as a
 *   primitive since [kotlinx.serialization] has no built-in support for [Instant]).
 * @property expiresAtEpochMilli When this subscription expires, as epoch milliseconds, or `null` if
 *   it has no date-based expiration. Mutually exclusive with [maxEntries] in the add flow, but
 *   nothing prevents both being set.
 * @property maxEntries The number of entries this subscription is good for, or `null` if it has no
 *   entry-count limit.
 * @property price How much this subscription cost, or `null` if not recorded.
 */
@Serializable
data class Subscription(
    val id: String,
    val uri: String,
    val displayName: String,
    val addedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long? = null,
    val maxEntries: Int? = null,
    val price: Double? = null,
)

/** The instant this subscription was added. */
val Subscription.addedAt: Instant
  get() = Instant.ofEpochMilli(addedAtEpochMilli)

/** The instant this subscription expires, or `null` if it has no date-based expiration. */
val Subscription.expiresAt: Instant?
  get() = expiresAtEpochMilli?.let { Instant.ofEpochMilli(it) }

/** Whether this subscription's expiration date, if any, has already passed. */
val Subscription.isExpired: Boolean
  get() = expiresAt?.isBefore(Instant.now()) == true

/**
 * How much each entry costs, computed as [price] divided by [maxEntries], or `null` if either is
 * missing. Only meaningful for entry-limited subscriptions - a date-based one doesn't have a fixed
 * number of entries to divide by.
 */
val Subscription.pricePerEntry: Double?
  get() {
    val price = price ?: return null
    val maxEntries = maxEntries ?: return null
    if (maxEntries <= 0) return null
    return price / maxEntries
  }

/**
 * Whole days between [now] and this subscription's expiration date, or `null` if it has none.
 * Negative once it's expired, matching [isExpired].
 */
fun Subscription.daysUntilExpiration(
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Long? = expiresAt?.let {
  ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), it.atZone(zone).toLocalDate())
}

/** Whether this subscription still has a PDF to show (a restored one has none until re-added). */
val Subscription.hasPdf: Boolean
  get() = uri.isNotBlank()

/**
 * Rebuilds a subscription from the fields mirrored to Firestore, or `null` if they are unusable.
 * The PDF's `uri` was never mirrored (it only means something on the device that picked it), so the
 * result has none, see [hasPdf].
 */
fun subscriptionFromBackup(data: Map<String, Any?>): Subscription? {
  val id = data["id"] as? String ?: return null
  val displayName = data["displayName"] as? String ?: return null
  val addedAt = (data["addedAtEpochMilli"] as? Number)?.toLong() ?: return null
  return Subscription(
      id = id,
      uri = "",
      displayName = displayName,
      addedAtEpochMilli = addedAt,
      expiresAtEpochMilli = (data["expiresAtEpochMilli"] as? Number)?.toLong(),
      maxEntries = (data["maxEntries"] as? Number)?.toInt(),
      price = (data["price"] as? Number)?.toDouble(),
  )
}
