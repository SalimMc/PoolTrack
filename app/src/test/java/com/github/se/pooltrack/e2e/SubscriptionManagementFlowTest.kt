package com.github.se.pooltrack.e2e

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.github.se.pooltrack.ui.history.HistoryScreenTestTags
import com.github.se.pooltrack.ui.home.HomeScreenTestTags
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.ui.subscription.SubscriptionQuickViewScreenTestTags
import com.github.se.pooltrack.ui.subscription.SubscriptionScreenTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Subscriptions tab: managing the passes of the account. */
class SubscriptionManagementFlowTest : EndToEndTest() {

  private fun openSubscriptions() {
    click(NavigationTestTags.SUBSCRIPTION_TAB)
  }

  private fun openRow(index: Int) {
    composeRule
        .onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM)[index]
        .performClick()
    composeRule.waitForIdle()
  }

  private fun subscriptionDocument(id: String) = store.document("subscriptions", id)

  // ---------- adding ----------

  @Test
  fun backingOutOfThePicker_addsNothing() {
    pickedPdf = null
    launchApp()
    openSubscriptions()

    click(SubscriptionScreenTestTags.EMPTY_ADD_BUTTON)

    assertEquals(1, pickerLaunches)
    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertDoesNotExist()
    tag(SubscriptionScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()
  }

  @Test
  fun cancellingTheDetailsDialog_addsNothing() {
    launchApp()
    openSubscriptions()

    click(SubscriptionScreenTestTags.EMPTY_ADD_BUTTON)
    clickText("Cancel")

    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertDoesNotExist()
    assertTrue(store.ids("subscriptions").isEmpty())
  }

  @Test
  fun addingAPassWithAnExpirationDate_storesItAndMakesItActive() {
    launchApp()
    openSubscriptions()
    click(SubscriptionScreenTestTags.EMPTY_ADD_BUTTON)

    click(SubscriptionScreenTestTags.EXPIRATION_DATE_BUTTON)
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).assertIsNotEnabled()
    clickText("6 months")
    // The purchase date can be changed; the picker opens, is cancelled, then confirmed.
    click(SubscriptionScreenTestTags.EXPIRATION_PURCHASE_DATE_BUTTON)
    clickTopmostText("Cancel")
    click(SubscriptionScreenTestTags.EXPIRATION_PURCHASE_DATE_BUTTON)
    clickText("OK")
    tag(SubscriptionScreenTestTags.EXPIRATION_PRICE_FIELD).performTextInput("1x2.5")
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).assertIsEnabled()
    click(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON)

    val id = store.ids("subscriptions").single()
    val stored = subscriptionDocument(id)!!
    assertNotNull(stored["expiresAtEpochMilli"])
    assertEquals(12.5, (stored["price"] as Number).toDouble(), 0.0)
    unmerged(SubscriptionScreenTestTags.ACTIVE_BADGE).assertExists()
    assertShowsAnywhere("Expires")

    click(NavigationTestTags.HOME_TAB)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "Your active pass expires in")
  }

  private fun assertShowsAnywhere(text: String) {
    assertTrue(
        "'$text' is not shown",
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(),
    )
  }

  // ---------- several passes ----------

  @Test
  fun switchingThePass_changesWhichOneHomeAndTheScannerUse() {
    seedSubscription(id = "old", name = "Old pass", addedDaysAgo = 90, maxEntries = 5)
    seedSubscription(
        id = "new",
        name = "New pass",
        addedDaysAgo = 5,
        maxEntries = 20,
        active = false,
    )
    launchApp()
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "5 of 5 entries left")

    openSubscriptions()
    // Most recently added first.
    composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).assertCountEquals(2)
    openRow(0)
    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertIsDisplayed()
    click(SubscriptionScreenTestTags.SET_ACTIVE_BUTTON)
    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertDoesNotExist()

    click(NavigationTestTags.HOME_TAB)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "20 of 20 entries left")
    click(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB)
    waitForTag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE)
    tag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON).assertIsDisplayed()
  }

  @Test
  fun detailDialog_showsExpirationPriceAndPricePerEntry_andCloses() {
    seedSubscription(maxEntries = 10, price = 50.0)
    seedEntry(daysAgo = 2, subscriptionId = "sub-1")
    launchApp()
    openSubscriptions()

    assertShowsAnywhere("9 of 10 entries left · Price: 50.00")
    openRow(0)
    assertShowsAnywhere("Price: 50.00  ·  5.00 / entry")
    composeRule.onNodeWithContentDescription("Close").performClick()
    composeRule.waitForIdle()
    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertDoesNotExist()
  }

  @Test
  fun expiredPass_isMarkedExpired_inTheListAndTheDetails() {
    seedSubscription(expiresInDays = -3)
    launchApp()
    openSubscriptions()

    assertShowsAnywhere("Expired")
    openRow(0)
    assertShowsAnywhere("Expired")
  }

  // ---------- editing ----------

  @Test
  fun editingAPass_changesItsNameLimitAndPrice_andHomeFollows() {
    seedSubscription(name = "Season pass", maxEntries = 10, price = 60.0)
    launchApp()
    openSubscriptions()
    openRow(0)

    click(SubscriptionScreenTestTags.EDIT_BUTTON)
    tag(SubscriptionScreenTestTags.EDIT_NAME_FIELD).assertTextContains("Season pass")
    tag(SubscriptionScreenTestTags.EDIT_NAME_FIELD).performTextClearance()
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).assertIsNotEnabled()
    tag(SubscriptionScreenTestTags.EDIT_NAME_FIELD).performTextInput("Gym card")
    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_FIELD).performTextClearance()
    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_FIELD).performTextInput("25")
    tag(SubscriptionScreenTestTags.EXPIRATION_PRICE_FIELD).performTextClearance()
    tag(SubscriptionScreenTestTags.EXPIRATION_PRICE_FIELD).performTextInput("45.5")
    click(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON)

    val stored = subscriptionDocument("sub-1")!!
    assertEquals("Gym card", stored["displayName"])
    assertEquals(25, (stored["maxEntries"] as Number).toInt())
    assertEquals(45.5, (stored["price"] as Number).toDouble(), 0.0)
    assertShowsAnywhere("Gym card")

    click(NavigationTestTags.HOME_TAB)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "25 of 25 entries left")
    assertShows(HomeScreenTestTags.TOTAL_SPENT_STAT, "45.50")
  }

  @Test
  fun editingAPass_toExpireOnADate_dropsItsEntryLimit_andCancelChangesNothing() {
    seedSubscription(maxEntries = 10)
    launchApp()
    openSubscriptions()

    openRow(0)
    click(SubscriptionScreenTestTags.EDIT_BUTTON)
    click(SubscriptionScreenTestTags.EXPIRATION_DATE_BUTTON)
    clickText("1 year")
    clickTopmostText("Cancel")
    assertEquals(10, (subscriptionDocument("sub-1")!!["maxEntries"] as Number).toInt())

    openRow(0)
    click(SubscriptionScreenTestTags.EDIT_BUTTON)
    click(SubscriptionScreenTestTags.EXPIRATION_DATE_BUTTON)
    clickText("1 year")
    click(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON)

    val stored = subscriptionDocument("sub-1")!!
    assertNull(stored["maxEntries"])
    assertNotNull(stored["expiresAtEpochMilli"])
  }

  @Test
  fun editingAPass_backToNoLimit_dropsDateAndEntries() {
    seedSubscription(expiresInDays = 100)
    launchApp()
    openSubscriptions()
    openRow(0)

    click(SubscriptionScreenTestTags.EDIT_BUTTON)
    click(SubscriptionScreenTestTags.EXPIRATION_NONE_BUTTON)
    click(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON)

    val stored = subscriptionDocument("sub-1")!!
    assertNull(stored["expiresAtEpochMilli"])
    assertNull(stored["maxEntries"])
  }

  @Test
  fun editingAPass_toKeepItsDate_whenNoPresetIsPicked() {
    seedSubscription(expiresInDays = 100)
    launchApp()
    openSubscriptions()
    openRow(0)

    click(SubscriptionScreenTestTags.EDIT_BUTTON)
    tag(SubscriptionScreenTestTags.EXPIRATION_KEEP_BUTTON).assertExists()
    click(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON)

    assertNotNull(subscriptionDocument("sub-1")!!["expiresAtEpochMilli"])
  }

  // ---------- deleting ----------

  @Test
  fun deletingAPass_asksFirst_keepsItsEntries_andLeavesNoActivePass() {
    seedSubscription(id = "sub-1", name = "Season pass")
    seedEntry(daysAgo = 2, subscriptionId = "sub-1")
    seedEntry(daysAgo = 3, subscriptionId = "sub-1")
    launchApp()
    openSubscriptions()
    openRow(0)

    click(SubscriptionScreenTestTags.DELETE_BUTTON)
    tag(SubscriptionScreenTestTags.DELETE_ENTRIES_NOTE)
        .assertTextContains("Its 2 entries stay in your history.")
    click(SubscriptionScreenTestTags.CANCEL_DELETE_BUTTON)
    assertEquals(1, store.ids("subscriptions").size)

    openRow(0)
    click(SubscriptionScreenTestTags.DELETE_BUTTON)
    click(SubscriptionScreenTestTags.CONFIRM_DELETE_BUTTON)

    assertTrue(store.ids("subscriptions").isEmpty())
    assertEquals(2, store.ids("entries").size)
    assertTrue(pdfFiles.keptReadable.isEmpty())
    tag(SubscriptionScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()

    click(NavigationTestTags.HOME_TAB)
    tag(HomeScreenTestTags.NO_SUBSCRIPTION_TODO).assertIsDisplayed()
    click(NavigationTestTags.HISTORY_TAB)
    composeRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(2)
  }

  @Test
  fun deletingAPassWithoutEntries_hasNoEntriesNote_andSingularNoteForOne() {
    seedSubscription(id = "sub-1")
    launchApp()
    openSubscriptions()
    openRow(0)
    click(SubscriptionScreenTestTags.DELETE_BUTTON)
    tag(SubscriptionScreenTestTags.DELETE_ENTRIES_NOTE).assertDoesNotExist()
    click(SubscriptionScreenTestTags.CANCEL_DELETE_BUTTON)

    seedEntry(daysAgo = 1, subscriptionId = "sub-1")
    composeRule.waitForIdle()
    openRow(0)
    click(SubscriptionScreenTestTags.DELETE_BUTTON)
    tag(SubscriptionScreenTestTags.DELETE_ENTRIES_NOTE)
        .assertTextContains("Its 1 entry stays in your history.")
  }

  @Test
  fun addingASecondPass_keepsTheFirst_andMakesTheNewOneActive() {
    seedSubscription(id = "sub-1")
    launchApp()
    openSubscriptions()
    openRow(0)
    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertIsDisplayed()

    composeRule.onNodeWithContentDescription("Close").performClick()
    composeRule.waitForIdle()
    click(SubscriptionScreenTestTags.ADD_BUTTON)
    click(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON)

    composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).assertCountEquals(2)
    assertEquals(2, store.ids("subscriptions").size)
    assertFalse(pdfFiles.keptReadable.isEmpty())
    assertEquals(
        1,
        composeRule
            .onAllNodes(hasTestTag(SubscriptionScreenTestTags.ACTIVE_BADGE), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size,
    )
  }
}
