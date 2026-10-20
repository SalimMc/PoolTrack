package com.github.se.pooltrack.swim

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.se.pooltrack.model.entry.EntryRepositoryProvider
import com.github.se.pooltrack.model.subscription.SubscriptionRepositoryProvider
import com.github.se.pooltrack.model.swim.InactivityReminder
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Runs once a day, in the evening, and reminds the user to swim if they haven't for a while. */
class InactivityReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    EntryRepositoryProvider.init(applicationContext)
    SubscriptionRepositoryProvider.init(applicationContext)
    InactivityReminder(
            EntryRepositoryProvider.repository,
            SubscriptionRepositoryProvider.repository,
        ) {
          SwimNotifications.postInactivityReminder(applicationContext)
        }
        .check()
    return Result.success()
  }

  companion object {
    const val WORK_NAME = "inactivity_reminder"

    /** The time of day the daily check is aimed at. */
    val CHECK_TIME: LocalTime = LocalTime.of(18, 0)

    /** How long from [now] until the next [CHECK_TIME]. */
    fun delayUntilNextCheck(now: LocalDateTime = LocalDateTime.now()): Duration {
      var next = now.toLocalDate().atTime(CHECK_TIME)
      if (!next.isAfter(now)) next = next.plusDays(1)
      return Duration.between(now, next)
    }

    /** Schedules the daily check; does nothing if it already is. */
    fun schedule(context: Context) {
      val request =
          PeriodicWorkRequestBuilder<InactivityReminderWorker>(1, TimeUnit.DAYS)
              .setInitialDelay(delayUntilNextCheck())
              .build()
      WorkManager.getInstance(context)
          .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }
}
