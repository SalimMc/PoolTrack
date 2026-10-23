package com.github.se.pooltrack.model.usage

/**
 * Records, per signed-in account, when and how often the app was opened, so the owner of the app
 * can see in the Firebase console how many people use it. Nothing about the user's pool data is
 * included.
 */
fun interface UsageRepository {

  /**
   * Notes that the app was just opened on version [appVersion]. Best effort: it does nothing while
   * nobody is signed in and never throws, since it must not get in the way of using the app.
   */
  suspend fun recordOpen(appVersion: String)
}
