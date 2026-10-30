package com.github.se.pooltrack.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.se.pooltrack.model.auth.AuthRepository
import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.ui.account.AccountButtonTestTags
import com.github.se.pooltrack.ui.account.AccountViewModel
import com.github.se.pooltrack.ui.navigation.NavigationActions
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.ui.navigation.Screen
import com.github.se.pooltrack.utils.FakeAuthRepository
import com.github.se.pooltrack.utils.FakeEntryRepository
import com.github.se.pooltrack.utils.FakeSubscriptionRepository
import com.github.se.pooltrack.utils.MainDispatcherRule
import io.mockk.mockk
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real [HomeScreen] with a real [HomeViewModel] over in-memory fakes. HomeScreen reads
 * the clock directly (stat dates, the entry cooldown), so every date below is built relative to the
 * current time with margins far larger than the test's runtime.
 */
// Tall screen so the whole scrolling column is on screen and assertIsDisplayed is meaningful.
@Config(qualifiers = "w400dp-h1600dp")
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {

  private val mainDispatcherRule = MainDispatcherRule()
  private val composeRule = createComposeRule()

  @get:Rule val rules: RuleChain = RuleChain.outerRule(mainDispatcherRule).around(composeRule)

  private val zone = ZoneId.systemDefault()
  private lateinit var originalLocale: Locale

  private val entries = FakeEntryRepository()
  private val subscriptions = FakeSubscriptionRepository()
  private val auth = FakeAuthRepository(initialUser = FakeAuthRepository.GOOGLE_USER)
  private val navigatedTo = mutableListOf<Screen>()
  private val navigationActions =
      object : NavigationActions(mockk(relaxed = true)) {
        override fun navigateTo(screen: Screen) {
          navigatedTo += screen
        }
      }

  @Before
  fun setUp() {
    originalLocale = Locale.getDefault()
    Locale.setDefault(Locale.US)
  }

  @After
  fun tearDown() {
    Locale.setDefault(originalLocale)
  }

  /**
   * Gives HomeScreen's embedded AccountButton an AccountViewModel on [auth] through the
   * ViewModelStoreOwner, since it takes no constructor parameter and the default repository would
   * reach Firebase.
   */
  private class TestViewModelOwner(authRepository: AuthRepository) :
      ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory = viewModelFactory {
      initializer { AccountViewModel(authRepository) }
    }
    override val defaultViewModelCreationExtras: CreationExtras = CreationExtras.Empty
  }

  private fun setContent(
      withNavigation: Boolean = true,
      entryRepository: FakeEntryRepository = entries,
      subscriptionRepository: FakeSubscriptionRepository = subscriptions,
      authRepository: AuthRepository = auth,
  ) {
    val viewModel = HomeViewModel(entryRepository, subscriptionRepository)
    val owner = TestViewModelOwner(authRepository)
    composeRule.setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        HomeScreen(viewModel, if (withNavigation) navigationActions else null)
      }
    }
  }

  private fun entryAt(instant: Instant, subscriptionId: String? = null) =
      Entry(timestampEpochMilli = instant.toEpochMilli(), subscriptionId = subscriptionId)

  private fun daysAgo(days: Long): Instant = Instant.now().minus(Duration.ofDays(days))

  private fun startOfDay(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()

  private fun noonOf(date: LocalDate): Instant = date.atTime(12, 0).atZone(zone).toInstant()

  private fun pass(
      id: String = "a",
      expiresAt: Instant? = null,
      maxEntries: Int? = null,
      price: Double? = null,
  ) =
      Subscription(
          id = id,
          uri = "content://$id",
          displayName = id,
          addedAtEpochMilli = 1L,
          expiresAtEpochMilli = expiresAt?.toEpochMilli(),
          maxEntries = maxEntries,
          price = price,
      )

  private fun activeSubscriptionRepo(subscription: Subscription, vararg others: Subscription) =
      FakeSubscriptionRepository(listOf(subscription) + others, subscription.id)

  /** The tile tagged [tag] shows [value] (and [label]) among its children. */
  private fun assertTile(tag: String, value: String, label: String? = null) {
    composeRule
        .onNode(hasTestTag(tag) and hasAnyDescendant(hasText(value)), useUnmergedTree = true)
        .assertIsDisplayed()
    if (label != null) {
      composeRule
          .onNode(hasTestTag(tag) and hasAnyDescendant(hasText(label)), useUnmergedTree = true)
          .assertIsDisplayed()
    }
  }

  private fun assertTagAbsent(tag: String) {
    composeRule.onNodeWithTag(tag).assertDoesNotExist()
  }

  // region weekly chart legend

  @Test
  fun homeScreen_splitsItsCardsIntoSections_andLabelsTheLastSwim() {
    setContent()

    composeRule.onNodeWithText("ACTIVITY").assertExists()
    composeRule.onNodeWithText("SPENDING").assertExists()
    composeRule.onNodeWithText("LAST SWIM").assertIsDisplayed()
  }

  @Test
  fun homeScreen_showsAFloatPerEntry_forAnEntryLimitedPass() {
    setContent(subscriptionRepository = activeSubscriptionRepo(pass(maxEntries = 10)))

    composeRule
        .onNodeWithTag(HomeScreenTestTags.PASS_ENTRY_FLOATS, useUnmergedTree = true)
        .assertExists()
  }

  @Test
  fun homeScreen_showsNoFloats_forADatedPass() {
    val dated = pass(expiresAt = Instant.now().plus(Duration.ofDays(30)), maxEntries = 10)
    setContent(subscriptionRepository = activeSubscriptionRepo(dated))

    composeRule.onNodeWithTag(HomeScreenTestTags.EXPIRATION_BANNER).assertIsDisplayed()
    composeRule
        .onNodeWithTag(HomeScreenTestTags.PASS_ENTRY_FLOATS, useUnmergedTree = true)
        .assertDoesNotExist()
  }

  @Test
  fun barBrush_isSolidAtTheTop_onlyForTheHighlightedBar() {
    val color = Color(0xFF00668A)

    assertEquals(
        Brush.verticalGradient(listOf(color, color.copy(alpha = 0.6f))),
        barBrush(color, highlighted = true),
    )
    assertEquals(
        Brush.verticalGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.27f))),
        barBrush(color, highlighted = false),
    )
  }

  @Test
  fun weeklyChart_showsNoLegend_untilABarIsTapped() {
    setContent()

    assertTagAbsent(HomeScreenTestTags.WEEKLY_CHART_LEGEND)
  }

  @Test
  fun weeklyChart_showsTheWeekOfATappedBar_andHidesItOnSecondTap() {
    val monday = LocalDate.now(zone).with(DayOfWeek.MONDAY)
    val repo = FakeEntryRepository(listOf(entryAt(noonOf(monday))))
    setContent(entryRepository = repo)
    val lastBar = {
      composeRule.onAllNodesWithTag(HomeScreenTestTags.WEEKLY_CHART_BAR)[RECENT_WEEKS - 1]
    }

    lastBar().performClick()
    composeRule
        .onNodeWithTag(HomeScreenTestTags.WEEKLY_CHART_LEGEND)
        .assertTextEquals(weekLegend(monday, 1, Locale.US))

    lastBar().performClick()
    assertTagAbsent(HomeScreenTestTags.WEEKLY_CHART_LEGEND)
  }

  @Test
  fun weekLegend_spansMondayToSunday_andPluralizesEntries() {
    val monday = LocalDate.of(2026, 10, 12)

    assertEquals("12 Oct - 18 Oct: 3 entries", weekLegend(monday, 3, Locale.US))
    assertEquals("12 Oct - 18 Oct: 1 entry", weekLegend(monday, 1, Locale.US))
    assertEquals("12 Oct - 18 Oct: 0 entries", weekLegend(monday, 0, Locale.US))
  }

  // endregion

  // region empty state

  @Test
  fun homeScreen_showsEmptyStats_whenNoEntriesAndNoSubscriptions() {
    setContent()

    composeRule.onNodeWithTag(NavigationTestTags.TOP_BAR_TITLE).assertIsDisplayed()
    composeRule.onNodeWithText("No entries yet").assertIsDisplayed()
    composeRule.onNodeWithText("since your last swim").assertIsDisplayed()
    assertTile(HomeScreenTestTags.THIS_WEEK_STAT, "0", "This week")
    assertTile(HomeScreenTestTags.THIS_MONTH_STAT, "0", "This month")
    assertTile(HomeScreenTestTags.TOTAL_ENTRIES_STAT, "0", "Total entries")
    assertTile(HomeScreenTestTags.AVERAGE_PER_WEEK_STAT, "-", "Avg. per week")
    assertTile(HomeScreenTestTags.STREAK_STAT, "-", "Week streak")
    assertTile(HomeScreenTestTags.BEST_WEEK_STAT, "-", "Best week")
    composeRule.onNodeWithTag(HomeScreenTestTags.WEEKLY_CHART).assertIsDisplayed()
    composeRule.onNodeWithText("Last 8 weeks").assertIsDisplayed()
    assertTile(HomeScreenTestTags.TOTAL_SPENT_STAT, "-", "Total spent")
    assertTile(HomeScreenTestTags.COST_PER_ENTRY_STAT, "-", "Cost / entry")
    assertTagAbsent(HomeScreenTestTags.FAVORITE_DAY_BANNER)
  }

  @Test
  fun homeScreen_showsAddSubscriptionTodo_whenNoActiveSubscription() {
    setContent()

    composeRule.onNodeWithTag(HomeScreenTestTags.NO_SUBSCRIPTION_TODO).assertIsDisplayed()
    composeRule.onNodeWithText("To do: add a subscription").assertIsDisplayed()
    composeRule
        .onNodeWithText("Add or activate one to start tracking your entries.")
        .assertIsDisplayed()
    assertTagAbsent(HomeScreenTestTags.EXPIRATION_BANNER)
  }

  @Test
  fun noSubscriptionTodo_navigatesToSubscriptions_whenClicked() {
    setContent()

    composeRule.onNodeWithTag(HomeScreenTestTags.NO_SUBSCRIPTION_TODO).performClick()

    assertEquals(listOf<Screen>(Screen.Subscription), navigatedTo)
  }

  @Test
  fun noSubscriptionTodo_doesNothing_whenThereIsNoNavigation() {
    setContent(withNavigation = false)

    composeRule.onNodeWithTag(HomeScreenTestTags.NO_SUBSCRIPTION_TODO).performClick()

    assertTrue(navigatedTo.isEmpty())
    composeRule.onNodeWithTag(HomeScreenTestTags.NO_SUBSCRIPTION_TODO).assertIsDisplayed()
  }

  // endregion

  // region populated state

  @Test
  fun homeScreen_showsAllStats_whenEntriesAndSubscriptionsExist() {
    val today = LocalDate.now(zone)
    // Two entries today and one 40 days ago: 3 entries over 6 calendar weeks since the first.
    runBlocking {
      entries.addEntry(entryAt(Instant.now(), "a"))
      entries.addEntry(entryAt(Instant.now(), "a"))
      entries.addEntry(entryAt(daysAgo(40), "a"))
    }
    val populatedSubscriptions =
        FakeSubscriptionRepository(
            listOf(
                pass("a", maxEntries = 10, price = 60.0),
                pass("b", price = 29.9).copy(addedAtEpochMilli = 2L),
            ),
            "a",
        )
    setContent(subscriptionRepository = populatedSubscriptions)

    composeRule.onNodeWithText("Today").assertIsDisplayed()
    assertTile(HomeScreenTestTags.THIS_WEEK_STAT, "2")
    assertTile(HomeScreenTestTags.THIS_MONTH_STAT, "2")
    assertTile(HomeScreenTestTags.TOTAL_ENTRIES_STAT, "3")
    assertTile(HomeScreenTestTags.AVERAGE_PER_WEEK_STAT, "0.5")
    assertTile(HomeScreenTestTags.STREAK_STAT, "1 week", "Week streak")
    assertTile(HomeScreenTestTags.BEST_WEEK_STAT, "2", "Best week")
    assertTile(HomeScreenTestTags.TOTAL_SPENT_STAT, "89.90")
    assertTile(HomeScreenTestTags.COST_PER_ENTRY_STAT, "29.97")
    val dayName = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
    composeRule.onNodeWithTag(HomeScreenTestTags.FAVORITE_DAY_BANNER).assertIsDisplayed()
    composeRule.onNodeWithText("You usually swim on $dayName").assertIsDisplayed()
    composeRule.onNodeWithText("7 of 10 entries left").assertIsDisplayed()
  }

  @Test
  fun homeScreen_excludesOlderEntriesFromThisWeek_whenEightDaysOld() {
    runBlocking {
      entries.addEntry(entryAt(Instant.now()))
      entries.addEntry(entryAt(daysAgo(8)))
    }
    setContent()

    assertTile(HomeScreenTestTags.THIS_WEEK_STAT, "1")
    assertTile(HomeScreenTestTags.TOTAL_ENTRIES_STAT, "2")
  }

  @Test
  fun homeScreen_updatesStats_whenAnEntryIsRecordedAfterwards() {
    setContent()
    composeRule.onNodeWithText("No entries yet").assertIsDisplayed()

    runBlocking { entries.addEntry(entryAt(Instant.now())) }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Today").assertIsDisplayed()
    assertTile(HomeScreenTestTags.TOTAL_ENTRIES_STAT, "1")
    assertTile(HomeScreenTestTags.AVERAGE_PER_WEEK_STAT, "1.0")
  }

  @Test
  fun homeScreen_swapsTodoForBanner_whenASubscriptionBecomesActive() {
    runBlocking { subscriptions.addSubscription("content://a", null, null, null) }
    setContent()
    // addSubscription activates the new pass, so it already shows the "no expiration" banner.
    composeRule.onNodeWithText("Your active pass has no expiration").assertIsDisplayed()

    runBlocking { subscriptions.deleteSubscription("sub-1") }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag(HomeScreenTestTags.NO_SUBSCRIPTION_TODO).assertIsDisplayed()
    assertTagAbsent(HomeScreenTestTags.EXPIRATION_BANNER)
  }

  // endregion

  // region last swim label

  @Test
  fun lastSwimHero_showsOneDay_whenLastEntryWasYesterday() {
    runBlocking { entries.addEntry(entryAt(daysAgo(1))) }
    setContent()

    composeRule.onNodeWithText("1 day").assertIsDisplayed()
  }

  @Test
  fun lastSwimHero_showsPluralDays_whenLastEntryWasDaysAgo() {
    runBlocking { entries.addEntry(entryAt(daysAgo(3))) }
    setContent()

    composeRule.onNodeWithText("3 days").assertIsDisplayed()
  }

  // endregion

  // region favorite day

  @Test
  fun favoriteDayBanner_namesTheMostFrequentWeekday() {
    val someMonday = LocalDate.now(zone).with(DayOfWeek.MONDAY).minusWeeks(4)
    val someTuesday = someMonday.plusDays(1)
    runBlocking {
      entries.addEntry(entryAt(noonOf(someMonday)))
      entries.addEntry(entryAt(noonOf(someMonday.plusWeeks(1))))
      entries.addEntry(entryAt(noonOf(someTuesday)))
    }
    setContent()

    composeRule.onNodeWithText("You usually swim on Monday").assertIsDisplayed()
  }

  // endregion

  // region cost stats

  @Test
  fun costStats_showDashes_whenNoPriceWasRecorded() {
    runBlocking { entries.addEntry(entryAt(Instant.now())) }
    runBlocking { subscriptions.addSubscription("content://a", null, 10, null) }
    setContent()

    assertTile(HomeScreenTestTags.TOTAL_SPENT_STAT, "-")
    assertTile(HomeScreenTestTags.COST_PER_ENTRY_STAT, "-")
  }

  @Test
  fun costStats_showTotalButNoCostPerEntry_whenMoneyWasSpentWithoutEntries() {
    runBlocking { subscriptions.addSubscription("content://a", null, 10, 45.5) }
    setContent()

    assertTile(HomeScreenTestTags.TOTAL_SPENT_STAT, "45.50")
    assertTile(HomeScreenTestTags.COST_PER_ENTRY_STAT, "-")
  }

  @Test
  fun costStats_dividePricesByEveryEntry_notJustTheActiveSubscription() {
    runBlocking {
      entries.addEntry(entryAt(daysAgo(2), "other"))
      entries.addEntry(entryAt(daysAgo(1)))
      subscriptions.addSubscription("content://a", null, 10, 40.0)
    }
    setContent()

    assertTile(HomeScreenTestTags.TOTAL_SPENT_STAT, "40.00")
    assertTile(HomeScreenTestTags.COST_PER_ENTRY_STAT, "20.00")
  }

  // endregion

  // region expiration banner: entry-count passes

  private fun setContentWithActive(subscription: Subscription, entryList: List<Entry> = listOf()) {
    setContent(
        entryRepository = FakeEntryRepository(entryList),
        subscriptionRepository = activeSubscriptionRepo(subscription),
    )
  }

  @Test
  fun expirationBanner_countsRemainingEntries_forAnEntryLimitedPass() {
    val entryList = List(3) { entryAt(daysAgo(it + 1L), "a") } + entryAt(daysAgo(9), "other")
    setContentWithActive(pass(maxEntries = 10), entryList)

    composeRule.onNodeWithTag(HomeScreenTestTags.EXPIRATION_BANNER).assertIsDisplayed()
    composeRule.onNodeWithText("7 of 10 entries left").assertIsDisplayed()
  }

  @Test
  fun expirationBanner_usesSingular_whenThePassHasOneEntryTotal() {
    setContentWithActive(pass(maxEntries = 1))

    composeRule.onNodeWithText("1 of 1 entry left").assertIsDisplayed()
  }

  @Test
  fun expirationBanner_neverGoesNegative_whenMoreEntriesThanTheLimit() {
    val entryList = List(3) { entryAt(daysAgo(it + 1L), "a") }
    setContentWithActive(pass(maxEntries = 2), entryList)

    composeRule.onNodeWithText("0 of 2 entries left").assertIsDisplayed()
  }

  @Test
  fun expirationBanner_saysNoExpiration_whenPassHasNeitherDateNorLimit() {
    setContentWithActive(pass())

    composeRule.onNodeWithText("Your active pass has no expiration").assertIsDisplayed()
  }

  // endregion

  // region expiration banner: date-limited passes

  private fun expiresOn(date: LocalDate, atNoon: Boolean = true) =
      pass(expiresAt = if (atNoon) noonOf(date) else startOfDay(date))

  @Test
  fun expirationBanner_saysExpiresToday_whenExpiryIsToday() {
    setContentWithActive(expiresOn(LocalDate.now(zone), atNoon = false))

    composeRule.onNodeWithText("Your active pass expires today").assertIsDisplayed()
  }

  @Test
  fun expirationBanner_saysExpiresTomorrow_whenExpiryIsTomorrow() {
    setContentWithActive(expiresOn(LocalDate.now(zone).plusDays(1)))

    composeRule.onNodeWithText("Your active pass expires tomorrow").assertIsDisplayed()
  }

  @Test
  fun expirationBanner_countsDays_whenExpiryIsWithinTheMonth() {
    setContentWithActive(expiresOn(LocalDate.now(zone).plusDays(5)))

    composeRule.onNodeWithText("Your active pass expires in 5 days").assertIsDisplayed()
  }

  @Test
  fun expirationBanner_countsMonths_whenExpiryIsMonthsAway() {
    setContentWithActive(expiresOn(LocalDate.now(zone).plusMonths(3).plusDays(3)))

    composeRule.onNodeWithText("Your active pass expires in 3 months").assertIsDisplayed()
  }

  @Test
  fun expirationBanner_usesSingularMonth_whenExpiryIsOneMonthAway() {
    setContentWithActive(expiresOn(LocalDate.now(zone).plusMonths(1).plusDays(2)))

    composeRule.onNodeWithText("Your active pass expires in 1 month").assertIsDisplayed()
  }

  @Test
  fun expirationBanner_countsYears_whenExpiryIsYearsAway() {
    setContentWithActive(expiresOn(LocalDate.now(zone).plusYears(2).plusDays(10)))

    composeRule.onNodeWithText("Your active pass expires in 2 years").assertIsDisplayed()
  }

  @Test
  fun expirationBanner_saysExpiredDaysAgo_whenExpiryPassed() {
    setContentWithActive(expiresOn(LocalDate.now(zone).minusDays(4)))

    composeRule.onNodeWithText("Your active pass expired 4 days ago").assertIsDisplayed()
  }

  @Test
  fun expirationBanner_saysExpiredOneDayAgo_whenExpiredYesterday() {
    setContentWithActive(expiresOn(LocalDate.now(zone).minusDays(1)))

    composeRule.onNodeWithText("Your active pass expired 1 day ago").assertIsDisplayed()
  }

  @Test
  fun expirationBanner_saysExpiredYearsAgo_whenExpiredLongAgo() {
    setContentWithActive(expiresOn(LocalDate.now(zone).minusYears(1).minusDays(3)))

    composeRule.onNodeWithText("Your active pass expired 1 year ago").assertIsDisplayed()
  }

  // endregion

  // region floating action button

  @Test
  fun fab_opensQuickView_whenThereIsNoRecentEntry() {
    setContent()

    composeRule.onNodeWithTag(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB).performClick()

    assertEquals(listOf<Screen>(Screen.SubscriptionQuickView), navigatedTo)
  }

  @Test
  fun fab_opensQuickView_whenTheCooldownHasJustEnded() {
    runBlocking {
      entries.addEntry(entryAt(Instant.now().minus(Duration.ofHours(5)).minusSeconds(60)))
    }
    setContent()

    composeRule.onNodeWithTag(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB).performClick()

    assertEquals(listOf<Screen>(Screen.SubscriptionQuickView), navigatedTo)
  }

  @Test
  fun fab_explainsTheCooldownInsteadOfNavigating_whenEnteredRecently() {
    runBlocking { entries.addEntry(entryAt(Instant.now().minus(Duration.ofHours(1)))) }
    setContent()

    composeRule.onNodeWithTag(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB).performClick()

    composeRule.onNode(hasText("Already entered the pool", substring = true)).assertIsDisplayed()
    composeRule.onNode(hasText("reopen in 3h", substring = true)).assertIsDisplayed()
    assertTrue(navigatedTo.isEmpty())
  }

  @Test
  fun fab_showsLessThanAMinute_whenTheCooldownIsAboutToEnd() {
    runBlocking {
      entries.addEntry(entryAt(Instant.now().minus(Duration.ofHours(5)).plusSeconds(20)))
    }
    setContent()

    composeRule.onNodeWithTag(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB).performClick()

    composeRule
        .onNode(hasText("reopen in less than a minute", substring = true))
        .assertIsDisplayed()
    assertTrue(navigatedTo.isEmpty())
  }

  @Test
  fun fab_doesNothingVisible_whenThereIsNoNavigationAndNoCooldown() {
    setContent(withNavigation = false)

    composeRule.onNodeWithTag(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB).performClick()

    assertTrue(navigatedTo.isEmpty())
    composeRule.onNodeWithContentDescription("Open subscription").assertIsDisplayed()
  }

  // endregion

  // region navigation and account

  @Test
  fun bottomBar_navigatesToTheTappedTab() {
    setContent()

    composeRule.onNodeWithTag(NavigationTestTags.HISTORY_TAB).performClick()
    composeRule.onNodeWithTag(NavigationTestTags.SUBSCRIPTION_TAB).performClick()
    composeRule.onNodeWithTag(NavigationTestTags.HOME_TAB).performClick()

    assertEquals(listOf(Screen.History, Screen.Subscription, Screen.Home), navigatedTo)
  }

  @Test
  fun bottomBar_ignoresTaps_whenThereIsNoNavigation() {
    setContent(withNavigation = false)

    composeRule.onNodeWithTag(NavigationTestTags.HISTORY_TAB).performClick()

    assertTrue(navigatedTo.isEmpty())
  }

  @Test
  fun accountButton_opensSignOutDialog_whenSignedIn() {
    setContent()

    composeRule.onNodeWithTag(AccountButtonTestTags.BUTTON).performClick()

    composeRule.onNodeWithTag(AccountButtonTestTags.SIGN_OUT_DIALOG).assertIsDisplayed()
  }

  @Test
  fun accountError_isShownInTheSnackbar_whenSignInFails() {
    val failingAuth =
        FakeAuthRepository(googleSignInResult = Result.failure(IllegalStateException("No network")))
    setContent(authRepository = failingAuth)

    composeRule.onNodeWithTag(AccountButtonTestTags.BUTTON).performClick()

    composeRule.onNodeWithText("No network").assertIsDisplayed()
  }

  // endregion
}
