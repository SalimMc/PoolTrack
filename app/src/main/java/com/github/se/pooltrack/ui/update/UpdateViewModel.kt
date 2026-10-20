package com.github.se.pooltrack.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.pooltrack.BuildConfig
import com.github.se.pooltrack.model.update.AppUpdate
import com.github.se.pooltrack.model.update.DownloadStatus
import com.github.se.pooltrack.model.update.InstallResult
import com.github.se.pooltrack.model.update.UpdateRepository
import com.github.se.pooltrack.model.update.UpdateRepositoryProvider
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** What the update prompt currently shows. */
sealed interface UpdateUiState {
  /**
   * The app can be used: it is up to date, or the check could not reach GitHub (e.g. offline at the
   * pool), in which case the pass must stay reachable.
   */
  data object None : UpdateUiState

  data class Available(val update: AppUpdate) : UpdateUiState

  /** [progress] is in `0..1`, or `null` while the total size is not known yet. */
  data class Downloading(val update: AppUpdate, val progress: Float?) : UpdateUiState

  /**
   * The APK is downloaded. [needsPermission] is true when installing was blocked because the user
   * still has to allow PoolTrack to install apps.
   */
  data class ReadyToInstall(
      val update: AppUpdate,
      val apk: File,
      val needsPermission: Boolean = false,
  ) : UpdateUiState

  /** [failures] counts the failed downloads and installs so far, see [canSkip]. */
  data class Failed(val update: AppUpdate, val message: String, val failures: Int = 1) :
      UpdateUiState {

    /** After a few failures the user may carry on without updating, until the next launch. */
    val canSkip: Boolean
      get() = failures >= MAX_FAILURES_BEFORE_SKIP
  }
}

/**
 * How many times downloading or installing may fail before the user is allowed to continue without
 * the update. A release that can never be installed (a broken file, or one signed with another key)
 * must not lock them out of their pass for good.
 */
internal const val MAX_FAILURES_BEFORE_SKIP = 3

/**
 * How long after the app starts a found update may still take over the screen. A slower answer is
 * dropped: by then the user may be showing their pass at the pool entrance, which an update prompt
 * must never interrupt. The update is simply offered again on the next launch.
 */
internal const val STARTUP_CHECK_WINDOW_MILLIS = 10_000L

/**
 * ViewModel behind the mandatory update. It checks for a new release once, when it is created, i.e.
 * when the app starts, and never again during the session, so a running session (viewing the
 * subscription PDF, a swim being tracked) is not interrupted: a release published meanwhile is
 * picked up on the next launch. Any state but [UpdateUiState.None] means the app must not be used
 * until the update is installed: it walks the user through downloading and installing, and the
 * installer opens by itself as soon as the download is done. The only way out is after
 * [MAX_FAILURES_BEFORE_SKIP] failures, see [onSkipClick], and it lasts until the next launch.
 *
 * Installing replaces the APK and kills the process, including the swim tracking service. The swim
 * itself is not lost (it is derived from the persisted entry), but its unlock notification only
 * resumes once the user opens the app again.
 *
 * @property repository Where releases are found, downloaded and installed.
 * @property currentVersion The version of the running app.
 * @property now The current time in epoch milliseconds, used to drop a check that answers too late.
 */
class UpdateViewModel(
    private val repository: UpdateRepository = UpdateRepositoryProvider.repository,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

  private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.None)
  val state: StateFlow<UpdateUiState> = _state.asStateFlow()

  private var downloadJob: Job? = null
  private var failures = 0
  private val startMillis = now()

  init {
    checkForUpdate()
  }

  /** Starts downloading the update, or retries after a failed download. */
  fun onUpdateClick() {
    val update =
        when (val current = _state.value) {
          is UpdateUiState.Available -> current.update
          is UpdateUiState.Failed -> current.update
          else -> return
        }
    download(update)
  }

  /** Opens the system installer for an already downloaded update. */
  fun onInstallClick() {
    val current = _state.value as? UpdateUiState.ReadyToInstall ?: return
    install(current.update, current.apk)
  }

  /**
   * To be called whenever the app comes back to the foreground. If the user was sent to the
   * settings to allow installs and did so, the installation carries on without another tap. It
   * never looks for a new release: that only happens at startup.
   */
  fun onResume() {
    val current = _state.value as? UpdateUiState.ReadyToInstall ?: return
    if (current.needsPermission && repository.canInstall()) install(current.update, current.apk)
  }

  /** Stops the download in progress. The update is still required, so it is offered again. */
  fun onCancelDownload() {
    val current = _state.value as? UpdateUiState.Downloading ?: return
    downloadJob?.cancel()
    downloadJob = null
    _state.value = UpdateUiState.Available(current.update)
  }

  private fun checkForUpdate() {
    viewModelScope.launch {
      val update = repository.checkForUpdate(currentVersion) ?: return@launch
      val tooLate = now() - startMillis > STARTUP_CHECK_WINDOW_MILLIS
      if (!tooLate && _state.value == UpdateUiState.None) {
        _state.value = UpdateUiState.Available(update)
      }
    }
  }

  private fun download(update: AppUpdate) {
    downloadJob?.cancel()
    _state.value = UpdateUiState.Downloading(update, progress = null)
    downloadJob = viewModelScope.launch {
      repository
          .download(update)
          .catch { error -> fail(update, error.message ?: "Download failed") }
          .collect { status ->
            when (status) {
              is DownloadStatus.Progress ->
                  _state.value = UpdateUiState.Downloading(update, status.fraction)
              is DownloadStatus.Finished -> install(update, status.apk)
            }
          }
    }
  }

  private fun install(update: AppUpdate, apk: File) {
    _state.value =
        when (repository.install(apk)) {
          InstallResult.STARTED -> UpdateUiState.ReadyToInstall(update, apk)
          InstallResult.PERMISSION_REQUIRED ->
              UpdateUiState.ReadyToInstall(update, apk, needsPermission = true)
          InstallResult.FAILED -> return fail(update, "Couldn't open the installer")
        }
  }

  private fun fail(update: AppUpdate, message: String) {
    failures++
    _state.value = UpdateUiState.Failed(update, message, failures)
  }

  /**
   * Lets the user carry on without the update, once it failed [MAX_FAILURES_BEFORE_SKIP] times. The
   * update is offered again on the next launch.
   */
  fun onSkipClick() {
    val current = _state.value as? UpdateUiState.Failed ?: return
    if (!current.canSkip) return
    downloadJob?.cancel()
    downloadJob = null
    _state.value = UpdateUiState.None
  }
}
