package com.github.se.pooltrack.model.update

/**
 * Checks, when asked, whether a new release is out and tells the user once per version. Knows
 * nothing about notifications, storage or scheduling: [notify] shows the notification, and
 * [lastNotifiedVersion] / [saveNotifiedVersion] remember which version the user was told about, so
 * the same release is not announced again on every check.
 *
 * @property updateRepository Where new releases are looked up.
 * @property currentVersion The version of the running app.
 * @property lastNotifiedVersion The version the user was last notified about, or `null` if none.
 * @property saveNotifiedVersion Remembers the version the user was just notified about.
 * @property notify Shows the "update available" notification.
 */
class UpdateNotifier(
    private val updateRepository: UpdateRepository,
    private val currentVersion: String,
    private val lastNotifiedVersion: () -> String?,
    private val saveNotifiedVersion: (String) -> Unit,
    private val notify: (AppUpdate) -> Unit,
) {

  /**
   * Notifies the user if a release newer than the running app exists and they were not told about
   * it yet.
   *
   * @return `true` if a notification was shown.
   */
  suspend fun check(): Boolean {
    val update = updateRepository.checkForUpdate(currentVersion) ?: return false
    if (update.version == lastNotifiedVersion()) return false
    notify(update)
    saveNotifiedVersion(update.version)
    return true
  }
}
