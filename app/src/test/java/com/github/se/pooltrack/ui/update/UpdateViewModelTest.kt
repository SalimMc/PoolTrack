package com.github.se.pooltrack.ui.update

import com.github.se.pooltrack.model.update.DownloadStatus
import com.github.se.pooltrack.model.update.InstallResult
import com.github.se.pooltrack.utils.FakeUpdateRepository
import com.github.se.pooltrack.utils.FakeUpdateRepository.Companion.APK
import com.github.se.pooltrack.utils.FakeUpdateRepository.Companion.UPDATE
import com.github.se.pooltrack.utils.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class UpdateViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private fun viewModel(repository: FakeUpdateRepository) =
      UpdateViewModel(repository, currentVersion = "1.1.0")

  @Test
  fun init_showsNothing_whenThereIsNoNewRelease() {
    val viewModel = viewModel(FakeUpdateRepository(update = null))

    assertEquals(UpdateUiState.None, viewModel.state.value)
  }

  @Test
  fun init_offersTheUpdate_whenAReleaseIsNewer() {
    val repository = FakeUpdateRepository(update = UPDATE)

    val viewModel = viewModel(repository)

    assertEquals(UpdateUiState.Available(UPDATE), viewModel.state.value)
    assertEquals("1.1.0", repository.checkedVersion)
  }

  @Test
  fun onUpdateClick_downloadsThenOpensTheInstaller_inOneStep() {
    val repository = FakeUpdateRepository(update = UPDATE)
    val viewModel = viewModel(repository)

    viewModel.onUpdateClick()

    assertEquals(listOf(UPDATE), repository.downloadedUpdates)
    assertEquals(listOf(APK), repository.installedApks)
    assertEquals(UpdateUiState.ReadyToInstall(UPDATE, APK), viewModel.state.value)
  }

  @Test
  fun onUpdateClick_showsTheDownloadProgress() {
    val downloads = MutableSharedFlow<DownloadStatus>(extraBufferCapacity = 8)
    val viewModel = viewModel(FakeUpdateRepository(update = UPDATE, downloadFlow = downloads))

    viewModel.onUpdateClick()
    assertEquals(UpdateUiState.Downloading(UPDATE, null), viewModel.state.value)

    downloads.tryEmit(DownloadStatus.Progress(0.4f))
    assertEquals(UpdateUiState.Downloading(UPDATE, 0.4f), viewModel.state.value)

    downloads.tryEmit(DownloadStatus.Finished(APK))
    assertEquals(UpdateUiState.ReadyToInstall(UPDATE, APK), viewModel.state.value)
  }

  @Test
  fun onUpdateClick_doesNothing_whenThereIsNoUpdateToInstall() {
    val repository = FakeUpdateRepository(update = null)
    val viewModel = viewModel(repository)

    viewModel.onUpdateClick()

    assertEquals(UpdateUiState.None, viewModel.state.value)
    assertEquals(emptyList<Any>(), repository.downloadedUpdates)
  }

  @Test
  fun download_fails_withTheErrorMessage() {
    val repository =
        FakeUpdateRepository(
            update = UPDATE,
            downloadFlow = flow { throw IOException("Checksum mismatch") },
        )
    val viewModel = viewModel(repository)

    viewModel.onUpdateClick()

    assertEquals(UpdateUiState.Failed(UPDATE, "Checksum mismatch"), viewModel.state.value)
    assertEquals(emptyList<Any>(), repository.installedApks)
  }

  @Test
  fun download_fails_withADefaultMessage_whenTheErrorHasNone() {
    val repository =
        FakeUpdateRepository(update = UPDATE, downloadFlow = flow { throw IOException() })
    val viewModel = viewModel(repository)

    viewModel.onUpdateClick()

    assertEquals(UpdateUiState.Failed(UPDATE, "Download failed"), viewModel.state.value)
  }

  @Test
  fun onUpdateClick_retriesTheDownload_afterAFailure() {
    val repository =
        FakeUpdateRepository(
            update = UPDATE,
            downloadFlow = flow { throw IOException("offline") },
        )
    val viewModel = viewModel(repository)
    viewModel.onUpdateClick()

    repository.downloadFlow = flow { emit(DownloadStatus.Finished(APK)) }
    viewModel.onUpdateClick()

    assertEquals(listOf(UPDATE, UPDATE), repository.downloadedUpdates)
    assertEquals(UpdateUiState.ReadyToInstall(UPDATE, APK), viewModel.state.value)
  }

  @Test
  fun install_asksForPermission_whenInstallsAreNotAllowedYet() {
    val repository =
        FakeUpdateRepository(update = UPDATE, installResult = InstallResult.PERMISSION_REQUIRED)
    val viewModel = viewModel(repository)

    viewModel.onUpdateClick()

    assertEquals(
        UpdateUiState.ReadyToInstall(UPDATE, APK, needsPermission = true),
        viewModel.state.value,
    )
  }

  @Test
  fun install_fails_whenNoInstallerCanBeOpened() {
    val repository = FakeUpdateRepository(update = UPDATE, installResult = InstallResult.FAILED)
    val viewModel = viewModel(repository)

    viewModel.onUpdateClick()

    assertEquals(
        UpdateUiState.Failed(UPDATE, "Couldn't open the installer"),
        viewModel.state.value,
    )
  }

  @Test
  fun onResume_carriesOnInstalling_onceThePermissionWasGranted() {
    val repository =
        FakeUpdateRepository(
            update = UPDATE,
            installResult = InstallResult.PERMISSION_REQUIRED,
            canInstallResult = false,
        )
    val viewModel = viewModel(repository)
    viewModel.onUpdateClick()

    // Back from the settings without having allowed anything: don't open them again.
    viewModel.onResume()
    assertEquals(listOf(APK), repository.installedApks)

    repository.canInstallResult = true
    repository.installResult = InstallResult.STARTED
    viewModel.onResume()

    assertEquals(listOf(APK, APK), repository.installedApks)
    assertEquals(UpdateUiState.ReadyToInstall(UPDATE, APK), viewModel.state.value)
  }

  @Test
  fun onResume_doesNothing_whenNoPermissionWasNeeded() {
    val repository = FakeUpdateRepository(update = UPDATE)
    val viewModel = viewModel(repository)
    viewModel.onUpdateClick()

    viewModel.onResume()

    assertEquals(listOf(APK), repository.installedApks)
  }

  @Test
  fun onInstallClick_opensTheInstallerAgain() {
    val repository = FakeUpdateRepository(update = UPDATE)
    val viewModel = viewModel(repository)
    viewModel.onUpdateClick()

    viewModel.onInstallClick()

    assertEquals(listOf(APK, APK), repository.installedApks)
  }

  @Test
  fun onInstallClick_doesNothing_beforeTheDownloadIsDone() {
    val repository = FakeUpdateRepository(update = UPDATE)
    val viewModel = viewModel(repository)

    viewModel.onInstallClick()

    assertEquals(emptyList<Any>(), repository.installedApks)
    assertEquals(UpdateUiState.Available(UPDATE), viewModel.state.value)
  }

  @Test
  fun onCancelDownload_stopsTheDownload_butStillRequiresTheUpdate() {
    val downloads = MutableSharedFlow<DownloadStatus>(extraBufferCapacity = 8)
    val repository = FakeUpdateRepository(update = UPDATE, downloadFlow = downloads)
    val viewModel = viewModel(repository)
    viewModel.onUpdateClick()
    assertEquals(1, downloads.subscriptionCount.value)

    viewModel.onCancelDownload()

    assertEquals(0, downloads.subscriptionCount.value)
    assertEquals(UpdateUiState.Available(UPDATE), viewModel.state.value)
    assertNull(repository.installedApks.firstOrNull())
  }

  @Test
  fun onCancelDownload_doesNothing_whenNotDownloading() {
    val viewModel = viewModel(FakeUpdateRepository(update = UPDATE))

    viewModel.onCancelDownload()

    assertEquals(UpdateUiState.Available(UPDATE), viewModel.state.value)
  }

  @Test
  fun onResume_neverChecksAgain_soARunningSessionIsNotInterrupted() {
    var now = 0L
    val repository = FakeUpdateRepository(update = null)
    val viewModel = UpdateViewModel(repository, "1.1.0", now = { now })

    repository.update = UPDATE
    now = 24 * 60 * 60 * 1000L
    viewModel.onResume()

    assertEquals(1, repository.checkCount)
    assertEquals(UpdateUiState.None, viewModel.state.value)
  }

  @Test
  fun init_dropsAnAnswerThatArrivesTooLate_untilTheNextLaunch() {
    var now = 0L
    val gate = CompletableDeferred<Unit>()
    val repository = FakeUpdateRepository(update = UPDATE, checkGate = gate)
    val viewModel = UpdateViewModel(repository, "1.1.0", now = { now })

    now = STARTUP_CHECK_WINDOW_MILLIS + 1
    gate.complete(Unit)

    assertEquals(UpdateUiState.None, viewModel.state.value)
  }

  @Test
  fun init_acceptsAnAnswerWithinTheStartupWindow() {
    var now = 0L
    val gate = CompletableDeferred<Unit>()
    val repository = FakeUpdateRepository(update = UPDATE, checkGate = gate)
    val viewModel = UpdateViewModel(repository, "1.1.0", now = { now })

    now = STARTUP_CHECK_WINDOW_MILLIS
    gate.complete(Unit)

    assertEquals(UpdateUiState.Available(UPDATE), viewModel.state.value)
  }

  private fun failingDownloads() = flow<DownloadStatus> { throw IOException("Checksum mismatch") }

  @Test
  fun onSkipClick_doesNothing_beforeTheUpdateFailedSeveralTimes() {
    val viewModel =
        viewModel(FakeUpdateRepository(update = UPDATE, downloadFlow = failingDownloads()))
    viewModel.onUpdateClick()
    viewModel.onUpdateClick()

    viewModel.onSkipClick()

    val state = viewModel.state.value as UpdateUiState.Failed
    assertEquals(2, state.failures)
    assertEquals(false, state.canSkip)
  }

  @Test
  fun onSkipClick_letsTheUserContinue_afterTheUpdateFailedThreeTimes() {
    val viewModel =
        viewModel(FakeUpdateRepository(update = UPDATE, downloadFlow = failingDownloads()))
    repeat(MAX_FAILURES_BEFORE_SKIP) { viewModel.onUpdateClick() }
    assertEquals(true, (viewModel.state.value as UpdateUiState.Failed).canSkip)

    viewModel.onSkipClick()

    assertEquals(UpdateUiState.None, viewModel.state.value)
  }

  @Test
  fun failedInstalls_count_towardsTheEscape() {
    val repository = FakeUpdateRepository(update = UPDATE, installResult = InstallResult.FAILED)
    val viewModel = viewModel(repository)
    repeat(MAX_FAILURES_BEFORE_SKIP) { viewModel.onUpdateClick() }

    viewModel.onSkipClick()

    assertEquals(UpdateUiState.None, viewModel.state.value)
  }

  @Test
  fun onSkipClick_doesNothing_whenNoUpdateFailed() {
    val viewModel = viewModel(FakeUpdateRepository(update = UPDATE))

    viewModel.onSkipClick()

    assertEquals(UpdateUiState.Available(UPDATE), viewModel.state.value)
  }
}
