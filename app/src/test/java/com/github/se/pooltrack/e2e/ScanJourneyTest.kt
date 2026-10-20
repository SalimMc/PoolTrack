package com.github.se.pooltrack.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.github.se.pooltrack.model.entry.EntryRepositoryFirestore
import com.github.se.pooltrack.model.entry.documentId
import com.github.se.pooltrack.model.entry.toDocument
import com.github.se.pooltrack.ui.history.HistoryScreenTestTags
import com.github.se.pooltrack.ui.home.HomeScreenTestTags
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.ui.poolstay.PoolStayScreenTestTags
import com.github.se.pooltrack.ui.subscription.SubscriptionQuickViewScreenTestTags
import com.github.se.pooltrack.ui.subscription.SubscriptionScreenTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The main journey: add a subscription, show the pass, get scanned, log the swim. */
class ScanJourneyTest : EndToEndTest() {

  @Test
  fun newUser_addsAPass_declinedScanIsNotCounted_andAnAcceptedOneCanBeCancelled() {
    launchApp()

    // Home before anything: no entries, no subscription.
    tag(NavigationTestTags.TOP_BAR_TITLE).assertTextEquals("Home")
    tag(HomeScreenTestTags.NO_SUBSCRIPTION_TODO).assertIsDisplayed()
    assertShows(HomeScreenTestTags.TOTAL_ENTRIES_STAT, "0")

    // The todo card leads to the Subscriptions tab, which starts empty.
    click(HomeScreenTestTags.NO_SUBSCRIPTION_TODO)
    tag(SubscriptionScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()

    // Add the PDF: the picker answers, the details dialog asks for entries and the price.
    click(SubscriptionScreenTestTags.EMPTY_ADD_BUTTON)
    assertEquals(1, pickerLaunches)
    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertIsDisplayed()
    click(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_BUTTON)
    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_FIELD).performTextInput("10")
    tag(SubscriptionScreenTestTags.EXPIRATION_PRICE_FIELD).performTextInput("60")
    click(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON)

    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).assertIsDisplayed()
    unmerged(SubscriptionScreenTestTags.ACTIVE_BADGE).assertExists()
    assertEquals(listOf("content://documents/pass.pdf"), pdfFiles.keptReadable)
    assertEquals(1, store.ids("subscriptions").size)

    // Home now knows the pass and that 10 entries are left.
    click(NavigationTestTags.HOME_TAB)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "10 of 10 entries left")
    assertShows(HomeScreenTestTags.TOTAL_SPENT_STAT, "60")

    // Show the pass; a scan the scanner declines is simply not accepted: nothing is recorded.
    click(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB)
    waitForTag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE)
    tag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON).assertIsDisplayed()
    click(NavigationTestTags.GO_BACK_BUTTON)
    tag(HomeScreenTestTags.TOTAL_ENTRIES_STAT).assertIsDisplayed()
    assertTrue(store.ids("entries").isEmpty())

    // Show it again and accept: the pool stay screen takes over the whole app.
    click(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB)
    waitForTag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON)
    click(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON)
    tag(PoolStayScreenTestTags.ENJOY_MESSAGE).assertIsDisplayed()
    tag(PoolStayScreenTestTags.CANCEL_WINDOW_MESSAGE)
        .assertTextContains("Scanned by mistake?", substring = true)
    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertDoesNotExist()
    assertEquals(1, store.ids("entries").size)

    // Scanned by mistake? Cancelling removes the entry and gives the app back.
    click(PoolStayScreenTestTags.CANCEL_BUTTON)
    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertIsDisplayed()
    assertTrue(store.ids("entries").isEmpty())
  }

  @Test
  fun acceptedScan_isLoggedWithItsDistance_thenShowsInHomeHistoryAndDetails_andLocksThePass() {
    seedSubscription(maxEntries = 10, price = 50.0)
    launchApp()

    click(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB)
    waitForTag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON)
    click(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON)
    tag(PoolStayScreenTestTags.CANCEL_BUTTON).assertIsDisplayed()

    // Time passes: the cancel window is over, so the app asks how far the user swam. (The entry
    // is moved in the account's data, as if the app had been killed and reopened half an hour
    // later: the stay is derived from the stored entry.)
    waitHalfAnHour()
    tag(PoolStayScreenTestTags.LOG_TITLE).assertIsDisplayed()
    tag(PoolStayScreenTestTags.SAVE_BUTTON).assertIsNotEnabled()

    // An impossible distance is refused, digits only are kept, and a good one is saved.
    tag(PoolStayScreenTestTags.DISTANCE_INPUT).performTextInput("0")
    tag(PoolStayScreenTestTags.SAVE_BUTTON).assertIsNotEnabled()
    composeRule
        .onNodeWithText("Enter a distance between 1 and", substring = true)
        .assertIsDisplayed()
    tag(PoolStayScreenTestTags.DISTANCE_INPUT).performTextInput("x1.2k")
    tag(PoolStayScreenTestTags.SAVE_BUTTON).assertIsEnabled()
    tag(PoolStayScreenTestTags.DISTANCE_INPUT).assertTextContains("012", substring = true)
    click(PoolStayScreenTestTags.SAVE_BUTTON)

    // Back in the app: Home counts the swim, which used one of the 10 entries.
    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertIsDisplayed()
    assertShows(HomeScreenTestTags.TOTAL_ENTRIES_STAT, "1")
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "9 of 10 entries left")
    assertShows(HomeScreenTestTags.COST_PER_ENTRY_STAT, "50")
    assertShows(HomeScreenTestTags.LAST_SWIM_HERO, "Today")

    // The pass is locked for a while after an entry: the FAB explains instead of opening it.
    click(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB)
    composeRule.onNodeWithText("Already entered the pool", substring = true).assertIsDisplayed()
    tag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON).assertDoesNotExist()

    // History lists it with its distance, and the entry's page shows the same.
    click(NavigationTestTags.HISTORY_TAB)
    tag(HistoryScreenTestTags.ENTRY_ITEM).assertIsDisplayed()
    composeRule.onNodeWithText("12 m").assertIsDisplayed()
    click(HistoryScreenTestTags.ENTRY_ITEM)
    tag(NavigationTestTags.TOP_BAR_TITLE).assertTextEquals("Swim")
    composeRule.onNodeWithText("12 m").assertIsDisplayed()
  }

  @Test
  fun reopeningTheApp_whileASwimAwaitsItsDistance_landsOnTheDistanceScreen() {
    seedWaitingEntry(minutesAgo = 90)
    launchApp()

    tag(PoolStayScreenTestTags.LOG_TITLE).assertIsDisplayed()
    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertDoesNotExist()
    tag(PoolStayScreenTestTags.DISTANCE_INPUT).performTextInput("800")
    click(PoolStayScreenTestTags.SAVE_BUTTON)

    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertIsDisplayed()
    val stored = runBlocking { EntryRepositoryFirestore(store).getEntries().first() }
    assertEquals(800, stored.single().swimDistanceMeters)
    assertEquals(false, stored.single().awaitingDistance)
  }

  @Test
  fun passWithoutAnActiveSubscription_explainsWhereToAddOne() {
    seedSubscription(active = false)
    launchApp()

    click(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB)
    composeRule.onNodeWithText("No active subscription").assertIsDisplayed()
    click(NavigationTestTags.GO_BACK_BUTTON)
    tag(HomeScreenTestTags.NO_SUBSCRIPTION_TODO).assertIsDisplayed()
  }

  @Test
  fun passOfARestoredSubscription_saysItsPdfIsMissing_untilItIsAdded() {
    seedSubscription(name = "Restored pass", withPdf = false)
    launchApp()

    click(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB)
    composeRule.onNodeWithText("Pass PDF missing").assertIsDisplayed()
    tag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON).assertDoesNotExist()
    click(NavigationTestTags.GO_BACK_BUTTON)

    // Subscriptions: the detail dialog offers to add the PDF, the picker answers.
    click(NavigationTestTags.SUBSCRIPTION_TAB)
    click(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM)
    click(SubscriptionScreenTestTags.ATTACH_PDF_BUTTON)
    assertEquals(1, pickerLaunches)
    assertEquals("content://documents/pass.pdf", pdfFiles.keptReadable.single())

    // Now the pass can be shown and accepted.
    click(NavigationTestTags.HOME_TAB)
    click(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB)
    waitForTag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE)
    tag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON).assertIsDisplayed()
  }

  @Test
  fun addingThePdfOfARestoredSubscription_doesNothing_whenThePickerIsDismissed() {
    seedSubscription(withPdf = false)
    pickedPdf = null
    launchApp()

    click(NavigationTestTags.SUBSCRIPTION_TAB)
    click(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM)
    click(SubscriptionScreenTestTags.ATTACH_PDF_BUTTON)

    assertEquals(1, pickerLaunches)
    assertTrue(pdfFiles.keptReadable.isEmpty())
  }

  /** Moves the waiting entry half an hour into the past, as if that much time had gone by. */
  private fun waitHalfAnHour() {
    val repository = EntryRepositoryFirestore(store)
    runBlocking {
      val waiting = repository.getEntries().first().single()
      repository.deleteEntry(waiting)
      val older = waiting.copy(timestampEpochMilli = waiting.timestampEpochMilli - 30 * 60_000L)
      store.set("entries", older.documentId, older.toDocument())
    }
    composeRule.waitForIdle()
  }
}
