package com.github.se.pooltrack.e2e

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import com.github.se.pooltrack.model.entry.EntryRepositoryFirestore
import com.github.se.pooltrack.ui.history.HistoryScreenTestTags
import com.github.se.pooltrack.ui.home.HomeScreenTestTags
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.ui.poolstay.PoolStayScreenTestTags
import com.github.se.pooltrack.ui.subscription.SubscriptionScreenTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Error states: documents in the account that an older or newer version wrote, or that were edited
 * by hand, must never crash the app or trap the user. Unusable ones are skipped.
 */
class DamagedDataFlowTest : EndToEndTest() {

  private fun seedDamagedDocuments() {
    runBlocking {
      // Subscriptions missing a field the app cannot do without.
      store.set(
          "subscriptions",
          "no-id",
          mapOf("displayName" to "No id", "addedAtEpochMilli" to 1L),
      )
      store.set("subscriptions", "no-name", mapOf("id" to "no-name", "addedAtEpochMilli" to 1L))
      store.set(
          "subscriptions",
          "no-date",
          mapOf("id" to "no-date", "displayName" to "No date"),
      )
      store.set(
          "subscriptions",
          "wrong-type",
          mapOf("id" to "wrong-type", "displayName" to "Wrong type", "addedAtEpochMilli" to "soon"),
      )
      // Entries without a usable moment.
      store.set("entries", "no-time", mapOf("subscriptionId" to "sub-1"))
      store.set("entries", "text-time", mapOf("timestampEpochMilli" to "yesterday"))
    }
  }

  @Test
  fun unusableDocuments_areSkipped_andTheGoodOnesStillShow() {
    seedSubscription(id = "sub-1", name = "Good pass")
    seedEntry(daysAgo = 2, subscriptionId = "sub-1", distance = 300)
    seedDamagedDocuments()
    launchApp()

    tag(HomeScreenTestTags.EXPIRATION_BANNER).assertIsDisplayed()
    assertShows(HomeScreenTestTags.TOTAL_ENTRIES_STAT, "1")

    click(NavigationTestTags.SUBSCRIPTION_TAB)
    composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).assertCountEquals(1)

    click(NavigationTestTags.HISTORY_TAB)
    composeRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(1)
  }

  @Test
  fun anEntryWhoseAwaitingFlagIsMissingOrNotABoolean_neverTrapsTheUser() {
    runBlocking {
      val timestamp = System.currentTimeMillis() - 3 * 60 * 60 * 1000L
      store.set(
          "entries",
          "odd-1",
          mapOf("timestampEpochMilli" to timestamp, "awaitingDistance" to "yes"),
      )
      store.set(
          "entries",
          "odd-2",
          mapOf("timestampEpochMilli" to timestamp - 1000, "swimDistanceMeters" to 400),
      )
    }
    launchApp()

    tag(PoolStayScreenTestTags.LOG_TITLE).assertDoesNotExist()
    tag(NavigationTestTags.BOTTOM_NAVIGATION_MENU).assertIsDisplayed()
    val entries = runBlocking { EntryRepositoryFirestore(store).getEntries().first() }
    assertEquals(2, entries.size)
    assertFalse(entries.any { it.awaitingDistance })
  }
}
