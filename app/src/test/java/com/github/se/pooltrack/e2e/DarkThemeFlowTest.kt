package com.github.se.pooltrack.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import com.github.se.pooltrack.ui.home.HomeScreenTestTags
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.ui.subscription.SubscriptionScreenTestTags
import org.junit.Test
import org.robolectric.annotation.Config

/** The app on a phone set to its dark theme. */
@Config(qualifiers = "w320dp-h1200dp-night")
class DarkThemeFlowTest : EndToEndTest() {

  @Test
  fun darkTheme_showsEveryTab_withItsContent() {
    seedSubscription(maxEntries = 10, price = 30.0)
    seedEntry(daysAgo = 1, subscriptionId = "sub-1", distance = 400)
    launchApp()

    tag(NavigationTestTags.TOP_BAR_TITLE).assertTextEquals("Home")
    tag(HomeScreenTestTags.LAST_SWIM_HERO).assertIsDisplayed()

    click(NavigationTestTags.SUBSCRIPTION_TAB)
    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).assertIsDisplayed()

    click(NavigationTestTags.HISTORY_TAB)
    tag(NavigationTestTags.TOP_BAR_TITLE).assertTextEquals("History")
    composeRule.onNodeWithText("400 m").assertIsDisplayed()
  }
}
