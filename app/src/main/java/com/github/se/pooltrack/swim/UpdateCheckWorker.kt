package com.github.se.pooltrack.swim

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.se.pooltrack.BuildConfig
import com.github.se.pooltrack.model.update.UpdateNotifier
import com.github.se.pooltrack.model.update.UpdateRepositoryProvider
import java.util.concurrent.TimeUnit

/**
 * Runs a few times a day, while online, and notifies the user once when a new release is available,
 * so they do not have to open the app to find out.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    UpdateRepositoryProvider.init(applicationContext)
    val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    UpdateNotifier(
            updateRepository = UpdateRepositoryProvider.repository,
            currentVersion = BuildConfig.VERSION_NAME,
            lastNotifiedVersion = { preferences.getString(KEY_NOTIFIED_VERSION, null) },
            saveNotifiedVersion = {
              preferences.edit().putString(KEY_NOTIFIED_VERSION, it).apply()
            },
            notify = { SwimNotifications.postUpdateAvailable(applicationContext, it.version) },
        )
        .check()
    return Result.success()
  }

  companion object {
    const val WORK_NAME = "update_check"
    const val CHECK_INTERVAL_HOURS = 12L
    private const val PREFERENCES = "update_check"
    private const val KEY_NOTIFIED_VERSION = "notified_version"

    /** Schedules the periodic check; does nothing if it already is. */
    fun schedule(context: Context) {
      val request =
          PeriodicWorkRequestBuilder<UpdateCheckWorker>(CHECK_INTERVAL_HOURS, TimeUnit.HOURS)
              .setConstraints(
                  Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
              )
              .build()
      WorkManager.getInstance(context)
          .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }
}
