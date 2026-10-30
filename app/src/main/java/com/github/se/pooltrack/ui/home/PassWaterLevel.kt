package com.github.se.pooltrack.ui.home

import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.model.subscription.addedAt
import com.github.se.pooltrack.model.subscription.expiresAt
import java.time.Duration
import java.time.Instant

/**
 * How full the "pool" drawn behind the active pass is, from 0 (used up) to 1 (untouched). It
 * follows the same limit as Home's pass message: the time left until [Subscription.expiresAt] if
 * there is one, otherwise the entries left out of [Subscription.maxEntries]. A pass with neither is
 * always full.
 *
 * @param usedCount How many entries were made with [subscription].
 * @param now The instant to measure the time left from.
 */
fun passWaterLevel(
    subscription: Subscription,
    usedCount: Int,
    now: Instant = Instant.now(),
): Float {
  val expiresAt = subscription.expiresAt
  if (expiresAt != null) {
    if (!expiresAt.isAfter(now)) return 0f
    val total = Duration.between(subscription.addedAt, expiresAt).toMillis()
    // Added on or after its own expiration date (a wrong clock): no span to measure.
    if (total <= 0L) return 1f
    val left = Duration.between(now, expiresAt).toMillis()
    return (left.toFloat() / total).coerceIn(0f, 1f)
  }
  val maxEntries = subscription.maxEntries
  if (maxEntries != null && maxEntries > 0) {
    return ((maxEntries - usedCount).toFloat() / maxEntries).coerceIn(0f, 1f)
  }
  return 1f
}
