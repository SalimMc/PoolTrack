package com.github.se.pooltrack.model.swim

import com.github.se.pooltrack.model.entry.EntryRepository
import com.github.se.pooltrack.model.entry.timestamp
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.model.subscription.SubscriptionRepository
import com.github.se.pooltrack.model.subscription.isExpired
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first

/** How many days without a swim make up one gap; a reminder is due after each of them. */
const val INACTIVITY_GAP_DAYS = 7L

/** At most this many reminders are sent for one stretch without a swim. */
const val MAX_INACTIVITY_REMINDERS = 3

/** Reminders are only sent from this time of day (local) on... */
val REMINDER_WINDOW_START: LocalTime = LocalTime.of(9, 0)

/** ...and until this one, so they never arrive at night. */
val REMINDER_WINDOW_END: LocalTime = LocalTime.of(22, 0)

/**
 * Whether the user should be reminded to swim right now.
 *
 * A reminder is due once the last entry is exactly 7, 14 or 21 calendar days old, so one is sent
 * per week of inactivity and none after the third; a new entry restarts the count. Nothing is sent
 * without any entry, without a usable subscription, or outside the daytime window.
 *
 * @param lastEntryAt When the user last entered the pool, or `null` if never.
 * @param activeSubscription The active subscription, or `null` if there is none.
 * @param entriesUsed How many entries were made with [activeSubscription].
 * @param now The instant to check against.
 * @param zone The time zone the days and the daytime window are counted in.
 */
fun isInactivityReminderDue(
    lastEntryAt: Instant?,
    activeSubscription: Subscription?,
    entriesUsed: Int,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
  if (lastEntryAt == null || activeSubscription == null) return false
  if (activeSubscription.isExpired) return false
  val maxEntries = activeSubscription.maxEntries
  if (maxEntries != null && entriesUsed >= maxEntries) return false
  val time = now.atZone(zone).toLocalTime()
  if (time < REMINDER_WINDOW_START || time > REMINDER_WINDOW_END) return false
  val daysSince =
      ChronoUnit.DAYS.between(
          lastEntryAt.atZone(zone).toLocalDate(),
          now.atZone(zone).toLocalDate(),
      )
  return daysSince > 0 &&
      daysSince % INACTIVITY_GAP_DAYS == 0L &&
      daysSince / INACTIVITY_GAP_DAYS <= MAX_INACTIVITY_REMINDERS
}

/**
 * Checks, when asked, whether the user should be nudged to swim again. Knows nothing about
 * notifications or scheduling: [remind] shows the reminder.
 *
 * @property entryRepository Where the entries live.
 * @property subscriptionRepository Where the subscriptions live.
 * @property remind Shows the reminder to the user.
 */
class InactivityReminder(
    private val entryRepository: EntryRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val remind: () -> Unit,
) {

  /**
   * Shows the reminder if one is due at [now].
   *
   * @return `true` if the reminder was shown.
   */
  suspend fun check(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val entries = entryRepository.getEntries().first()
    val subscription = subscriptionRepository.getActiveSubscription().first()
    val due =
        isInactivityReminderDue(
            lastEntryAt = entries.maxOfOrNull { it.timestamp },
            activeSubscription = subscription,
            entriesUsed = entries.count { it.subscriptionId == subscription?.id },
            now = now,
            zone = zone,
        )
    if (due) remind()
    return due
  }
}
