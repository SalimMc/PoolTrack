package com.github.se.pooltrack.swim

import java.time.Duration
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class InactivityReminderWorkerTest {

  @Test
  fun delayUntilNextCheck_beforeCheckTime_waitsForToday() {
    val now = LocalDateTime.of(2026, 10, 20, 9, 0)

    assertEquals(Duration.ofHours(9), InactivityReminderWorker.delayUntilNextCheck(now))
  }

  @Test
  fun delayUntilNextCheck_afterCheckTime_waitsForTomorrow() {
    val now = LocalDateTime.of(2026, 10, 20, 19, 0)

    assertEquals(Duration.ofHours(23), InactivityReminderWorker.delayUntilNextCheck(now))
  }

  @Test
  fun delayUntilNextCheck_exactlyAtCheckTime_waitsAFullDay() {
    val now = LocalDateTime.of(2026, 10, 20, 18, 0)

    assertEquals(Duration.ofHours(24), InactivityReminderWorker.delayUntilNextCheck(now))
  }
}
