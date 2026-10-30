package com.github.se.pooltrack.ui.poolstay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.utils.FakeEntryRepository
import com.github.se.pooltrack.utils.MainDispatcherRule
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PoolStayScreenTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()
  @get:Rule(order = 1) val composeTestRule = createComposeRule()

  private val enteredAt = Instant.parse("2026-10-05T10:00:00Z")
  private val waiting = Entry(enteredAt.toEpochMilli(), awaitingDistance = true)

  private fun show(repository: FakeEntryRepository, minutesAfterEntry: Long) {
    val ticker = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
    val viewModel =
        PoolStayViewModel(
            repository,
            clock = { enteredAt.plus(Duration.ofMinutes(minutesAfterEntry)) },
            ticker = ticker,
        )
    composeTestRule.setContent { PoolStayScreen(viewModel) }
  }

  @Test
  fun poolStayScreen_welcomesTheUserAndOffersCancel_insideTheCancelWindow() {
    show(FakeEntryRepository(listOf(waiting)), minutesAfterEntry = 4)

    composeTestRule
        .onNodeWithTag(PoolStayScreenTestTags.ENJOY_MESSAGE)
        .assertTextContains("You're in. Enjoy your swim!")
    composeTestRule
        .onNodeWithTag(PoolStayScreenTestTags.CANCEL_WINDOW_MESSAGE)
        .assertTextContains("6:00", substring = true)
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.CANCEL_BUTTON).assertIsDisplayed()
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.DISTANCE_INPUT).assertDoesNotExist()
  }

  @Test
  fun poolStayScreen_cancelButton_removesTheEntry() {
    val repository = FakeEntryRepository(listOf(waiting))
    show(repository, minutesAfterEntry = 1)

    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.CANCEL_BUTTON).performClick()

    assertEquals(emptyList<Entry>(), repository.storedEntries)
  }

  @Test
  fun poolStayScreen_asksForTheDistance_withoutSkipOrCancel_afterTheCancelWindow() {
    show(FakeEntryRepository(listOf(waiting)), minutesAfterEntry = 30)

    composeTestRule
        .onNodeWithTag(PoolStayScreenTestTags.LOG_TITLE)
        .assertTextContains("How far did you swim?")
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.DISTANCE_INPUT).assertIsDisplayed()
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.CANCEL_BUTTON).assertDoesNotExist()
    composeTestRule.onNodeWithText("Skip").assertDoesNotExist()
  }

  @Test
  fun poolStayScreen_saveIsDisabled_untilTheDistanceIsValid() {
    show(FakeEntryRepository(listOf(waiting)), minutesAfterEntry = 30)
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.SAVE_BUTTON).assertIsNotEnabled()

    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.DISTANCE_INPUT).performTextInput("0")
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.SAVE_BUTTON).assertIsNotEnabled()
    composeTestRule
        .onNodeWithText("Enter a distance between 1 and 50000 metres")
        .assertIsDisplayed()

    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.DISTANCE_INPUT).performTextInput("5")
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.SAVE_BUTTON).assertIsEnabled()
  }

  @Test
  fun poolStayScreen_save_storesTheDistance() {
    val repository = FakeEntryRepository(listOf(waiting))
    show(repository, minutesAfterEntry = 30)

    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.DISTANCE_INPUT).performTextInput("1200")
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.SAVE_BUTTON).performClick()

    assertEquals(
        listOf(waiting.copy(swimDistanceMeters = 1200, awaitingDistance = false)),
        repository.storedEntries,
    )
  }

  @Test
  fun poolStayScreen_lengthCounter_fillsTheDistanceInMetres() {
    show(FakeEntryRepository(listOf(waiting)), minutesAfterEntry = 30)

    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.LENGTHS_COUNT).assertTextEquals("0")
    repeat(3) { composeTestRule.onNodeWithTag(PoolStayScreenTestTags.LENGTHS_PLUS).performClick() }
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.LENGTHS_MINUS).performClick()

    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.LENGTHS_COUNT).assertTextEquals("2")
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.DISTANCE_INPUT).assertTextContains("50")
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.SAVE_BUTTON).assertIsEnabled()
  }

  @Test
  fun poolStayScreen_switchingTo50mPool_keepsTheLengths_andSavesTheirMetres() {
    val repository = FakeEntryRepository(listOf(waiting))
    show(repository, minutesAfterEntry = 30)

    repeat(4) { composeTestRule.onNodeWithTag(PoolStayScreenTestTags.LENGTHS_PLUS).performClick() }
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.poolLength(50)).performClick()
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.LENGTHS_COUNT).assertTextEquals("4")
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.SAVE_BUTTON).performClick()

    assertEquals(
        listOf(waiting.copy(swimDistanceMeters = 200, awaitingDistance = false)),
        repository.storedEntries,
    )
  }

  @Test
  fun poolStayScreen_lengthCounter_showsADash_forMetresThatAreNotWholeLengths() {
    show(FakeEntryRepository(listOf(waiting)), minutesAfterEntry = 30)

    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.DISTANCE_INPUT).performTextInput("1210")

    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.LENGTHS_COUNT).assertTextEquals("–")
  }

  @Test
  fun poolStayScreen_lengthCounter_clearsTheDistance_whenBackToZero() {
    show(FakeEntryRepository(listOf(waiting)), minutesAfterEntry = 30)

    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.LENGTHS_PLUS).performClick()
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.LENGTHS_MINUS).performClick()

    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.LENGTHS_COUNT).assertTextEquals("0")
    composeTestRule.onNodeWithTag(PoolStayScreenTestTags.SAVE_BUTTON).assertIsNotEnabled()
  }

  @Test
  fun formatCountdown_showsMinutesAndPaddedSeconds() {
    assertEquals("10:00", formatCountdown(Duration.ofMinutes(10)))
    assertEquals("9:05", formatCountdown(Duration.ofSeconds(545)))
    assertEquals("0:00", formatCountdown(Duration.ofSeconds(-3)))
  }
}
