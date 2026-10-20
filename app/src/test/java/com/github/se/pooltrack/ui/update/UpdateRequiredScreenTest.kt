package com.github.se.pooltrack.ui.update

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.se.pooltrack.utils.FakeUpdateRepository
import com.github.se.pooltrack.utils.FakeUpdateRepository.Companion.APK
import com.github.se.pooltrack.utils.FakeUpdateRepository.Companion.UPDATE
import com.github.se.pooltrack.utils.MainDispatcherRule
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateRequiredScreenTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()
  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private lateinit var originalLocale: Locale
  private var confirmed = 0
  private var cancelled = 0

  @Before
  fun setUp() {
    originalLocale = Locale.getDefault()
    Locale.setDefault(Locale.US)
  }

  @After
  fun tearDown() {
    Locale.setDefault(originalLocale)
  }

  private var skipped = 0

  private fun show(state: UpdateUiState) {
    composeTestRule.setContent {
      UpdateRequiredContent(
          state,
          onConfirm = { confirmed++ },
          onCancelDownload = { cancelled++ },
          onSkip = { skipped++ },
      )
    }
  }

  private fun assertTagIsGone(tag: String) {
    composeTestRule.onNodeWithTag(tag).assertDoesNotExist()
  }

  @Test
  fun screen_showsNothing_whenThereIsNoUpdate() {
    show(UpdateUiState.None)

    assertTagIsGone(UpdateRequiredScreenTestTags.SCREEN)
  }

  @Test
  fun screen_requiresTheUpdate_withItsVersionSizeAndNotes() {
    show(UpdateUiState.Available(UPDATE))

    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.TITLE)
        .assertTextEquals("Update required")
    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.MESSAGE)
        .assertTextEquals("Install the new version to keep using PoolTrack.")
    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.VERSION)
        .assertTextEquals("Version 1.2.0 (12.3 MB)")
    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.NOTES)
        .assertTextEquals("• Faster start")
    composeTestRule.onNodeWithText("Update").assertIsDisplayed()
  }

  @Test
  fun dialog_hidesTheSizeAndNotes_whenTheReleaseHasNone() {
    show(UpdateUiState.Available(UPDATE.copy(sizeBytes = 0, notes = "")))

    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.VERSION)
        .assertTextEquals("Version 1.2.0")
    assertTagIsGone(UpdateRequiredScreenTestTags.NOTES)
  }

  @Test
  fun screen_offersNoWayToSkipTheUpdate() {
    show(UpdateUiState.Available(UPDATE))

    composeTestRule.onNodeWithTag(UpdateRequiredScreenTestTags.CONFIRM_BUTTON).performClick()
    assertEquals(1, confirmed)
    assertTagIsGone(UpdateRequiredScreenTestTags.CANCEL_BUTTON)
    composeTestRule.onNodeWithText("Later").assertDoesNotExist()
  }

  @Test
  fun screen_showsTheDownloadPercentage_andOnlyOffersCancel() {
    show(UpdateUiState.Downloading(UPDATE, 0.42f))

    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.TITLE)
        .assertTextEquals("Downloading update")
    composeTestRule.onNodeWithTag(UpdateRequiredScreenTestTags.PROGRESS).assertIsDisplayed()
    composeTestRule.onNodeWithText("42%").assertIsDisplayed()
    assertTagIsGone(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)

    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.CANCEL_BUTTON)
        .assertTextEquals("Cancel")
    composeTestRule.onNodeWithTag(UpdateRequiredScreenTestTags.CANCEL_BUTTON).performClick()
    assertEquals(1, cancelled)
  }

  @Test
  fun screen_showsAnIndeterminateBar_untilTheSizeIsKnown() {
    show(UpdateUiState.Downloading(UPDATE, null))

    composeTestRule.onNodeWithTag(UpdateRequiredScreenTestTags.PROGRESS).assertIsDisplayed()
    composeTestRule.onNodeWithText("Starting download…").assertIsDisplayed()
  }

  @Test
  fun screen_tellsTheAppWillClose_whenReadyToInstall() {
    show(UpdateUiState.ReadyToInstall(UPDATE, APK))

    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.TITLE)
        .assertTextEquals("Ready to install")
    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.MESSAGE)
        .assertTextEquals("PoolTrack will close to finish the update. Open it again afterwards.")
    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
        .assertTextEquals("Install")
  }

  @Test
  fun screen_explainsHowToAllowInstalls_whenPermissionIsNeeded() {
    show(UpdateUiState.ReadyToInstall(UPDATE, APK, needsPermission = true))

    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.MESSAGE)
        .assertTextEquals(
            "Allow PoolTrack to install apps in the settings that just opened, then come back here."
        )
  }

  @Test
  fun screen_showsTheError_andOnlyOffersARetry() {
    show(UpdateUiState.Failed(UPDATE, "Checksum mismatch"))

    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.TITLE)
        .assertTextEquals("Update failed")
    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.MESSAGE)
        .assertTextEquals("Checksum mismatch")
    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.CONFIRM_BUTTON)
        .assertTextEquals("Retry")
    assertTagIsGone(UpdateRequiredScreenTestTags.CANCEL_BUTTON)
  }

  @Test
  fun updateRequiredScreen_walksThroughDownloadAndInstall_withViewModel() {
    val repository = FakeUpdateRepository(update = UPDATE)
    val viewModel = UpdateViewModel(repository, "1.1.0")
    composeTestRule.setContent { UpdateRequiredScreen(viewModel) }

    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.TITLE)
        .assertTextEquals("Update required")

    composeTestRule.onNodeWithTag(UpdateRequiredScreenTestTags.CONFIRM_BUTTON).performClick()
    assertEquals(listOf(APK), repository.installedApks)
    composeTestRule
        .onNodeWithTag(UpdateRequiredScreenTestTags.TITLE)
        .assertTextEquals("Ready to install")

    composeTestRule.onNodeWithTag(UpdateRequiredScreenTestTags.CONFIRM_BUTTON).performClick()
    assertEquals(listOf(APK, APK), repository.installedApks)
    composeTestRule.onNodeWithTag(UpdateRequiredScreenTestTags.SCREEN).assertIsDisplayed()
  }

  @Test
  fun formatSize_showsMegabytesWithOneDecimal() {
    assertEquals("0.0 MB", formatSize(0))
    assertEquals("1.0 MB", formatSize(1_048_576))
    assertEquals("12.3 MB", formatSize(12_900_000))
  }

  @Test
  fun toPlainNotes_dropsMarkdownMarkers_andKeepsBullets() {
    val markdown = "## What's new\n- **Faster** start\n* Fix bug\n\n\n\nThanks"

    assertEquals("What's new\n• Faster start\n• Fix bug\n\nThanks", markdown.toPlainNotes())
  }

  @Test
  fun toPlainNotes_isEmpty_forBlankNotes() {
    assertEquals("", " \n ".toPlainNotes())
  }

  @Test
  fun screen_offersNoWayOut_afterTheFirstFailure() {
    show(UpdateUiState.Failed(UPDATE, "Checksum mismatch", failures = 1))

    assertTagIsGone(UpdateRequiredScreenTestTags.SKIP_BUTTON)
  }

  @Test
  fun screen_offersToContinueWithoutUpdating_afterRepeatedFailures() {
    show(UpdateUiState.Failed(UPDATE, "Checksum mismatch", failures = 3))

    composeTestRule.onNodeWithText("Continue without updating").assertIsDisplayed()
    composeTestRule.onNodeWithTag(UpdateRequiredScreenTestTags.SKIP_BUTTON).performClick()

    assertEquals(1, skipped)
  }
}
