package com.github.se.pooltrack.model.swim

import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.entry.timestamp
import java.time.Duration
import java.time.Instant

/**
 * How long after confirming an entry the user can still cancel it. Afterwards they have to log how
 * far they swam.
 */
val CANCEL_WINDOW: Duration = Duration.ofMinutes(10)

/** The longest distance the user can log, in metres. Nobody swims further in one visit. */
const val MAX_SWIM_DISTANCE_METERS = 50_000

/**
 * The distance the user typed, or `null` if it is not a whole number of metres between 1 and
 * [MAX_SWIM_DISTANCE_METERS]. Surrounding whitespace is ignored.
 */
fun parseSwimDistance(input: String): Int? =
    input
        .trim()
        .takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
        ?.toIntOrNull()
        ?.takeIf { it in 1..MAX_SWIM_DISTANCE_METERS }

/** Formats [meters] for display, e.g. `1200 m`. */
fun formatSwimDistance(meters: Int): String = "$meters m"

/** Whether [entry] still has to get its distance logged (and was not cancelled). */
fun isSwimPending(entry: Entry?): Boolean = entry != null && entry.awaitingDistance

/** The time left to cancel [entry] at [now], or `null` once [CANCEL_WINDOW] is over. */
fun remainingCancelWindow(entry: Entry, now: Instant): Duration? {
  val remaining = CANCEL_WINDOW - Duration.between(entry.timestamp, now)
  return if (remaining.isNegative || remaining.isZero) null else remaining
}

/**
 * Whether the user should be reminded to log [entry]'s distance at [now]: it is still waiting for
 * one and the cancel window is over. There is deliberately no upper time limit, logging is
 * mandatory.
 */
fun isLogReminderDue(entry: Entry?, now: Instant): Boolean =
    entry != null && entry.awaitingDistance && remainingCancelWindow(entry, now) == null

/** The pool lengths, in metres, the user can count their lengths in. */
val POOL_LENGTHS_METERS = listOf(25, 50)

/**
 * How many whole lengths of a [poolLengthMeters] pool make [meters], or `null` if they don't add up
 * to a whole number of lengths (or nothing was swum).
 */
fun metersToLengths(meters: Int?, poolLengthMeters: Int): Int? =
    if (meters == null || meters <= 0 || poolLengthMeters <= 0 || meters % poolLengthMeters != 0)
        null
    else meters / poolLengthMeters

/**
 * The distance after adding [delta] lengths of a [poolLengthMeters] pool to [meters] (`null` for
 * nothing yet). A distance that is not a whole number of lengths is rounded down to one first, and
 * the result stays between 0 and the longest [MAX_SWIM_DISTANCE_METERS] that fits in whole lengths.
 */
fun stepLengths(meters: Int?, poolLengthMeters: Int, delta: Int): Int {
  require(poolLengthMeters > 0) { "poolLengthMeters must be positive" }
  val lengths = (meters ?: 0).coerceAtLeast(0) / poolLengthMeters
  val maxLengths = MAX_SWIM_DISTANCE_METERS / poolLengthMeters
  return (lengths + delta).coerceIn(0, maxLengths) * poolLengthMeters
}

/**
 * The distance once the user says the pool is [toPoolLength] metres long instead of
 * [fromPoolLength]: the same number of lengths, now of the new pool. A distance that is not a whole
 * number of lengths was typed in metres and is kept as it is, as is one that would go past
 * [MAX_SWIM_DISTANCE_METERS].
 */
fun changePoolLength(meters: Int?, fromPoolLength: Int, toPoolLength: Int): Int? {
  val lengths = metersToLengths(meters, fromPoolLength) ?: return meters
  val converted = lengths.toLong() * toPoolLength
  return if (toPoolLength <= 0 || converted > MAX_SWIM_DISTANCE_METERS) meters
  else converted.toInt()
}
