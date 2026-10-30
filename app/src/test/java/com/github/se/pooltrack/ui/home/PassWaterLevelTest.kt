package com.github.se.pooltrack.ui.home

import com.github.se.pooltrack.model.subscription.Subscription
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class PassWaterLevelTest {

  private val now = Instant.parse("2026-10-27T12:00:00Z")

  private fun subscription(
      addedAt: Instant = now.minus(Duration.ofDays(30)),
      expiresAt: Instant? = null,
      maxEntries: Int? = null,
  ) =
      Subscription(
          id = "sub",
          uri = "content://pass.pdf",
          displayName = "pass.pdf",
          addedAtEpochMilli = addedAt.toEpochMilli(),
          expiresAtEpochMilli = expiresAt?.toEpochMilli(),
          maxEntries = maxEntries,
      )

  @Test
  fun passWaterLevel_isEntriesLeftOverMax_forEntryLimitedPass() {
    assertEquals(0.6f, passWaterLevel(subscription(maxEntries = 10), usedCount = 4, now), 1e-6f)
  }

  @Test
  fun passWaterLevel_isFull_whenNoEntryUsedYet() {
    assertEquals(1f, passWaterLevel(subscription(maxEntries = 10), usedCount = 0, now), 0f)
  }

  @Test
  fun passWaterLevel_isEmpty_whenAllEntriesUsedOrMore() {
    assertEquals(0f, passWaterLevel(subscription(maxEntries = 10), usedCount = 10, now), 0f)
    assertEquals(0f, passWaterLevel(subscription(maxEntries = 10), usedCount = 12, now), 0f)
  }

  @Test
  fun passWaterLevel_isTimeLeftOverTotal_forDatedPass() {
    val pass = subscription(addedAt = now.minus(Duration.ofDays(30)), expiresAt = now.plusDays(10))

    assertEquals(0.25f, passWaterLevel(pass, usedCount = 7, now), 1e-6f)
  }

  @Test
  fun passWaterLevel_followsExpiration_whenPassHasBothLimits() {
    // Home's message talks about the expiration first, so the water does too.
    val pass =
        subscription(
            addedAt = now.minus(Duration.ofDays(10)),
            expiresAt = now.plusDays(30),
            maxEntries = 10,
        )

    assertEquals(0.75f, passWaterLevel(pass, usedCount = 9, now), 1e-6f)
  }

  @Test
  fun passWaterLevel_isEmpty_onceExpired_includingExactlyNow() {
    assertEquals(0f, passWaterLevel(subscription(expiresAt = now), usedCount = 0, now), 0f)
    assertEquals(
        0f,
        passWaterLevel(subscription(expiresAt = now.minusSeconds(1)), usedCount = 0, now),
        0f,
    )
  }

  @Test
  fun passWaterLevel_isFull_whenAddedAfterItsExpiration() {
    val pass = subscription(addedAt = now.plusDays(20), expiresAt = now.plusDays(10))

    assertEquals(1f, passWaterLevel(pass, usedCount = 0, now), 0f)
  }

  @Test
  fun passWaterLevel_isFull_withoutAnyLimit() {
    assertEquals(1f, passWaterLevel(subscription(), usedCount = 50, now), 0f)
  }

  @Test
  fun passWaterLevel_ignoresEntryLimit_ofZero() {
    assertEquals(1f, passWaterLevel(subscription(maxEntries = 0), usedCount = 3, now), 0f)
  }

  private fun Instant.plusDays(days: Long): Instant = plus(Duration.ofDays(days))
}
