package com.github.se.pooltrack.model.entry

import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a single confirmed pool entry, i.e. a scan the scanner accepted.
 *
 * @property timestampEpochMilli When the entry was confirmed, as epoch milliseconds (kept as a
 *   primitive since [kotlinx.serialization] has no built-in support for [Instant]).
 * @property subscriptionId The subscription that was active when this entry was confirmed, or
 *   `null` if none was (or for entries recorded before this was tracked). This is what lets an
 *   entry-limited subscription count how many of its entries have actually been used.
 * @property swimDurationMillis How long the swim lasted, in milliseconds, once the user picked
 *   their phone back up and it was reported to them; `null` for entries recorded after swims
 *   stopped being timed (the app asks for a distance instead), and for older ones that never got
 *   it. Kept so old entries still show their duration.
 * @property swimDistanceMeters How far the user swam, in metres, as they logged it; `null` until
 *   they do (or for entries recorded before distances were logged).
 * @property awaitingDistance Whether the user still has to log [swimDistanceMeters]. `true` only
 *   for entries created by confirming a scan, so entries added by hand, imported or recorded before
 *   distances existed are never waiting for one.
 */
@Serializable
data class Entry(
    val timestampEpochMilli: Long,
    val subscriptionId: String? = null,
    val swimDurationMillis: Long? = null,
    val swimDistanceMeters: Int? = null,
    val awaitingDistance: Boolean = false,
)

/** The instant this entry was confirmed. */
val Entry.timestamp: Instant
  get() = Instant.ofEpochMilli(timestampEpochMilli)

/**
 * Whether [other] is the same entry, i.e. has the same timestamp and subscription. Unlike `==` this
 * ignores the swim duration and distance, which change after the entry was recorded, so an older
 * snapshot of an entry still matches it.
 */
fun Entry.isSameEntryAs(other: Entry): Boolean =
    timestampEpochMilli == other.timestampEpochMilli && subscriptionId == other.subscriptionId

/**
 * Rebuilds an entry from the fields mirrored to Firestore, or `null` if they are unusable.
 * [Entry.awaitingDistance] is never restored: an old swim must not send the user back to the "log
 * your distance" screen.
 */
fun entryFromBackup(data: Map<String, Any?>): Entry? {
  val timestamp = (data["timestampEpochMilli"] as? Number)?.toLong() ?: return null
  return Entry(
      timestampEpochMilli = timestamp,
      subscriptionId = data["subscriptionId"] as? String,
      swimDurationMillis = (data["swimDurationMillis"] as? Number)?.toLong(),
      swimDistanceMeters = (data["swimDistanceMeters"] as? Number)?.toInt(),
  )
}
