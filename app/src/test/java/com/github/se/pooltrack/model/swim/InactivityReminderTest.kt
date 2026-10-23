package com.github.se.pooltrack.model.swim

import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.utils.FakeEntryRepository
import com.github.se.pooltrack.utils.FakeSubscriptionRepository
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InactivityReminderTest {

  private val zone = ZoneOffset.UTC
  private val lastEntry = Instant.parse("2026-10-01T10:00:00Z")
  private val subscription =
      Subscription(id = "a", uri = "content://a", displayName = "A", addedAtEpochMilli = 1)

  private fun due(
      daysLater: Long,
      hour: Int = 18,
      lastEntryAt: Instant? = lastEntry,
      active: Subscription? = subscription,
      used: Int = 1,
  ): Boolean {
    val now = lastEntry.plusSeconds(daysLater * 86_400).atZone(zone).withHour(hour).toInstant()
    return isInactivityReminderDue(lastEntryAt, active, used, now, zone)
  }

  @Test
  fun dueAfterEachFullWeekWithoutSwimming_upToThree() {
    assertTrue(due(7))
    assertTrue(due(14))
    assertTrue(due(21))
  }

  @Test
  fun notDueOtherDaysNorAfterTheThirdReminder() {
    assertFalse(due(0))
    assertFalse(due(6))
    assertFalse(due(8))
    assertFalse(due(28))
    assertFalse(due(35))
  }

  @Test
  fun countsCalendarDaysNotElapsedHours() {
    val lateEntry = Instant.parse("2026-10-01T23:30:00Z")
    val now = Instant.parse("2026-10-08T18:00:00Z")

    assertTrue(isInactivityReminderDue(lateEntry, subscription, 1, now, zone))
  }

  @Test
  fun notDueWithoutEntryOrSubscription() {
    assertFalse(due(7, lastEntryAt = null))
    assertFalse(due(7, active = null))
  }

  @Test
  fun notDueWhenSubscriptionExpired() {
    assertFalse(due(7, active = subscription.copy(expiresAtEpochMilli = 1)))
  }

  @Test
  fun dueWhenSubscriptionHasNotExpiredYet() {
    val future = Instant.now().plusSeconds(86_400 * 365).toEpochMilli()

    assertTrue(due(7, active = subscription.copy(expiresAtEpochMilli = future)))
  }

  @Test
  fun notDueWhenEntriesAreUsedUp() {
    assertFalse(due(7, active = subscription.copy(maxEntries = 5), used = 5))
    assertTrue(due(7, active = subscription.copy(maxEntries = 5), used = 4))
  }

  @Test
  fun notDueAtNight() {
    assertFalse(due(7, hour = 3))
    assertFalse(due(7, hour = 23))
    assertTrue(due(7, hour = 9))
  }

  @Test
  fun check_remindsWhenDue() = runTest {
    var reminders = 0
    val reminder =
        InactivityReminder(
            FakeEntryRepository(listOf(Entry(lastEntry.toEpochMilli(), subscriptionId = "a"))),
            FakeSubscriptionRepository(listOf(subscription), initialActiveId = "a"),
        ) {
          reminders++
        }

    val result = reminder.check(Instant.parse("2026-10-08T18:00:00Z"), zone)

    assertTrue(result)
    assertEquals(1, reminders)
  }

  @Test
  fun check_staysSilentWhenNotDue() = runTest {
    var reminders = 0
    val reminder =
        InactivityReminder(
            FakeEntryRepository(listOf(Entry(lastEntry.toEpochMilli(), subscriptionId = "a"))),
            FakeSubscriptionRepository(listOf(subscription), initialActiveId = "a"),
        ) {
          reminders++
        }

    val result = reminder.check(Instant.parse("2026-10-05T18:00:00Z"), zone)

    assertFalse(result)
    assertEquals(0, reminders)
  }

  @Test
  fun check_staysSilentWithoutEntries() = runTest {
    var reminders = 0
    val reminder =
        InactivityReminder(
            FakeEntryRepository(),
            FakeSubscriptionRepository(listOf(subscription), initialActiveId = "a"),
        ) {
          reminders++
        }

    assertFalse(reminder.check(Instant.parse("2026-10-08T18:00:00Z"), zone))
    assertEquals(0, reminders)
  }

  @Test
  fun check_staysSilentWithoutAnActiveSubscription() = runTest {
    var reminders = 0
    val reminder =
        InactivityReminder(
            FakeEntryRepository(listOf(Entry(lastEntry.toEpochMilli(), subscriptionId = "a"))),
            FakeSubscriptionRepository(listOf(subscription)),
        ) {
          reminders++
        }

    assertFalse(reminder.check(Instant.parse("2026-10-08T18:00:00Z"), zone))
    assertEquals(0, reminders)
  }

  @Test
  fun check_countsOnlyTheEntriesOfTheActiveSubscription() = runTest {
    var reminders = 0
    val used = Entry(lastEntry.toEpochMilli(), subscriptionId = "other")
    val reminder =
        InactivityReminder(
            FakeEntryRepository(listOf(used, Entry(lastEntry.toEpochMilli() - 1, "a"))),
            FakeSubscriptionRepository(listOf(subscription), initialActiveId = "a"),
        ) {
          reminders++
        }

    assertTrue(reminder.check(Instant.parse("2026-10-08T18:00:00Z"), zone))
    assertEquals(1, reminders)
  }
}
