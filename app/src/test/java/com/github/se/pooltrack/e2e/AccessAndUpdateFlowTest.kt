package com.github.se.pooltrack.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import com.github.se.pooltrack.model.store.DocumentStore
import com.github.se.pooltrack.model.store.StoredDocument
import com.github.se.pooltrack.model.update.DownloadStatus
import com.github.se.pooltrack.model.update.InstallResult
import com.github.se.pooltrack.ui.account.AccountButtonTestTags
import com.github.se.pooltrack.ui.account.AppVersionTextTestTags
import com.github.se.pooltrack.ui.account.SignInScreenTestTags
import com.github.se.pooltrack.ui.home.HomeScreenTestTags
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.ui.poolstay.PoolStayScreenTestTags
import com.github.se.pooltrack.ui.update.UpdateRequiredScreenTestTags
import com.github.se.pooltrack.utils.FakeAuthRepository
import com.github.se.pooltrack.utils.FakeUpdateRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Signing in and out, and the mandatory update that replaces the whole app. */
class AccessAndUpdateFlowTest : EndToEndTest() {

  private var entriesGate: CompletableDeferred<Unit>? = null

  /** A store whose first snapshots only arrive once [gate] is completed. */
  private class SlowStore(
      private val delegate: DocumentStore,
      private val gate: CompletableDeferred<Unit>,
  ) : DocumentStore by delegate {
    override fun observe(collection: String): Flow<List<StoredDocument>> = flow {
      gate.await()
      emitAll(delegate.observe(collection))
    }
  }

  override fun storeForApp(): DocumentStore = entriesGate?.let { SlowStore(store, it) } ?: store

  // ---------- sign-in gate and account ----------

  @Test
  fun signedOutUser_isGatedBehindSignIn_andReachesHomeOnceSignedIn() {
    auth = FakeAuthRepository(initialUser = null)
    launchApp()

    tag(SignInScreenTestTags.SIGN_IN_BUTTON).assertIsDisplayed()
    tag(AppVersionTextTestTags.VERSION).assertIsDisplayed()
    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertDoesNotExist()

    click(SignInScreenTestTags.SIGN_IN_BUTTON)

    tag(NavigationTestTags.TOP_BAR_TITLE).assertTextEquals("Home")
    tag(SignInScreenTestTags.SIGN_IN_BUTTON).assertDoesNotExist()
  }

  @Test
  fun failedSignIn_explainsWhy_andKeepsTheGate() {
    auth =
        FakeAuthRepository(
            initialUser = null,
            googleSignInResult = Result.failure(IllegalStateException("No Google account found")),
        )
    launchApp()

    click(SignInScreenTestTags.SIGN_IN_BUTTON)

    composeRule.onNodeWithText("No Google account found").assertIsDisplayed()
    tag(SignInScreenTestTags.SIGN_IN_BUTTON).assertIsDisplayed()
  }

  @Test
  fun failedSignInWithoutAMessage_saysItFailed() {
    auth =
        FakeAuthRepository(
            initialUser = null,
            googleSignInResult = Result.failure(IllegalStateException()),
        )
    launchApp()

    click(SignInScreenTestTags.SIGN_IN_BUTTON)

    composeRule.onNodeWithText("Sign-in failed").assertIsDisplayed()
  }

  @Test
  fun anonymousUser_isStillGatedBehindSignIn() {
    auth = FakeAuthRepository(initialUser = FakeAuthRepository.ANONYMOUS_USER)
    launchApp()

    tag(SignInScreenTestTags.SIGN_IN_BUTTON).assertIsDisplayed()
    tag(HomeScreenTestTags.LAST_SWIM_HERO).assertDoesNotExist()
  }

  @Test
  fun accountButton_showsWhoIsSignedIn_andSignsOut() {
    launchApp()

    click(AccountButtonTestTags.BUTTON)
    tag(AccountButtonTestTags.SIGN_OUT_DIALOG).assertIsDisplayed()
    composeRule.onNodeWithText("Signed in as Swimmer.", substring = true).assertIsDisplayed()
    tag(AppVersionTextTestTags.VERSION).assertIsDisplayed()

    // Closing keeps the user signed in.
    clickText("Close")
    tag(AccountButtonTestTags.SIGN_OUT_DIALOG).assertDoesNotExist()
    assertEquals(0, auth.signOutCalls)

    click(AccountButtonTestTags.BUTTON)
    click(AccountButtonTestTags.SIGN_OUT_BUTTON)

    assertEquals(1, auth.signOutCalls)
    tag(SignInScreenTestTags.SIGN_IN_BUTTON).assertIsDisplayed()
  }

  @Test
  fun accountDialog_fallsBackToTheEmail_thenToAGenericName() {
    auth =
        FakeAuthRepository(
            initialUser = FakeAuthRepository.GOOGLE_USER.copy(displayName = null),
        )
    launchApp()
    click(AccountButtonTestTags.BUTTON)
    composeRule.onNodeWithText("Signed in as a@b.c.", substring = true).assertIsDisplayed()
  }

  // ---------- mandatory update ----------

  private val apk = File("PoolTrack-1.2.0.apk")

  @Test
  fun newRelease_blocksTheApp_untilItIsDownloadedAndInstalled() {
    val progress = MutableStateFlow<DownloadStatus>(DownloadStatus.Progress(0.25f))
    updates = FakeUpdateRepository(update = FakeUpdateRepository.UPDATE, downloadFlow = progress)
    launchApp()

    tag(UpdateRequiredScreenTestTags.TITLE).assertTextEquals("Update required")
    assertEquals("Version 1.2.0 (12.3 MB)", textOf(UpdateRequiredScreenTestTags.VERSION))
    tag(UpdateRequiredScreenTestTags.NOTES).assertIsDisplayed()
    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertDoesNotExist()

    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    tag(UpdateRequiredScreenTestTags.TITLE).assertTextEquals("Downloading update")
    tag(UpdateRequiredScreenTestTags.PROGRESS).assertIsDisplayed()
    composeRule.onNodeWithText("25%").assertIsDisplayed()

    // Cancelling the download offers the same update again.
    click(UpdateRequiredScreenTestTags.CANCEL_BUTTON)
    tag(UpdateRequiredScreenTestTags.TITLE).assertTextEquals("Update required")

    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    progress.value = DownloadStatus.Progress(0.5f)
    composeRule.onNodeWithText("50%").assertIsDisplayed()
    progress.value = DownloadStatus.Finished(apk)
    composeRule.waitForIdle()

    // The installer opened by itself as soon as the download was done.
    assertEquals(listOf(apk), updates.installedApks)
    tag(UpdateRequiredScreenTestTags.TITLE).assertTextEquals("Ready to install")
    assertTrue(updates.downloadedUpdates.size == 2)
  }

  @Test
  fun failedDownload_canBeRetried() {
    var attempts = 0
    updates =
        FakeUpdateRepository(
            update = FakeUpdateRepository.UPDATE,
            downloadFlow =
                flow {
                  attempts++
                  if (attempts == 1) throw IOException("Connection lost")
                  emit(DownloadStatus.Finished(apk))
                },
        )
    launchApp()

    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    tag(UpdateRequiredScreenTestTags.TITLE).assertTextEquals("Update failed")
    tag(UpdateRequiredScreenTestTags.MESSAGE).assertTextEquals("Connection lost")

    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    assertEquals(listOf(apk), updates.installedApks)
  }

  @Test
  fun installNeedingAPermission_carriesOn_whenTheUserAllowedItAndComesBack() {
    updates =
        FakeUpdateRepository(
            update = FakeUpdateRepository.UPDATE,
            installResult = InstallResult.PERMISSION_REQUIRED,
            canInstallResult = false,
        )
    launchApp()

    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    tag(UpdateRequiredScreenTestTags.TITLE).assertTextEquals("Ready to install")
    composeRule
        .onNodeWithText("Allow PoolTrack to install apps", substring = true)
        .assertIsDisplayed()
    assertEquals(1, updates.installedApks.size)

    // Without the permission, installing again asks again.
    click(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
    assertEquals(2, updates.installedApks.size)

    // The user allowed it in the settings and comes back: the install carries on by itself.
    updates.canInstallResult = true
    updates.installResult = InstallResult.STARTED
    backgroundAndResume()
    assertEquals(3, updates.installedApks.size)
  }

  @Test
  fun newRelease_isOfferedBeforeSignIn_butAfterAPendingDistance() {
    auth = FakeAuthRepository(initialUser = null)
    updates = FakeUpdateRepository(update = FakeUpdateRepository.UPDATE)
    launchApp()
    tag(UpdateRequiredScreenTestTags.SCREEN).assertIsDisplayed()
    tag(SignInScreenTestTags.SIGN_IN_BUTTON).assertDoesNotExist()
  }

  @Test
  fun swimAwaitingItsDistance_comesBeforeTheUpdate() {
    seedWaitingEntry(minutesAgo = 90)
    updates = FakeUpdateRepository(update = FakeUpdateRepository.UPDATE)
    launchApp()

    tag(PoolStayScreenTestTags.LOG_TITLE).assertIsDisplayed()
    tag(UpdateRequiredScreenTestTags.SCREEN).assertDoesNotExist()
  }

  @Test
  fun whileTheStoredEntriesAreLoading_neitherTheUpdateNorTheAppIsShown() {
    // On the weak signal of a pool the first snapshot of the account's data comes late. The app
    // must not flash the update screen for a swim that may be waiting for its distance.
    entriesGate = CompletableDeferred()
    updates = FakeUpdateRepository(update = FakeUpdateRepository.UPDATE)
    launchApp()

    tag(UpdateRequiredScreenTestTags.SCREEN).assertDoesNotExist()
    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertDoesNotExist()
    tag(SignInScreenTestTags.SIGN_IN_BUTTON).assertDoesNotExist()

    entriesGate!!.complete(Unit)
    composeRule.waitForIdle()

    tag(UpdateRequiredScreenTestTags.SCREEN).assertIsDisplayed()
  }

  @Test
  fun offlineUpdateCheck_keepsTheAppUsable() {
    updates = FakeUpdateRepository(update = null)
    launchApp()

    tag(UpdateRequiredScreenTestTags.SCREEN).assertDoesNotExist()
    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertIsDisplayed()
  }

  private fun textOf(tag: String): String =
      tag(tag)
          .fetchSemanticsNode()
          .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
          .joinToString("") { it.text }
}
