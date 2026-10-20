package com.github.se.pooltrack.e2e

import androidx.compose.ui.test.assertIsDisplayed
import com.github.se.pooltrack.ui.home.HomeScreenTestTags
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home: what the statistics and the expiration banner say about the data in the account. */
class HomeStatsFlowTest : EndToEndTest() {

  private fun daysFromToday(untilDay: java.time.LocalDate): Long =
      ChronoUnit.DAYS.between(today, untilDay)

  @Test
  fun emptyAccount_showsNoEntriesYet_andDashesInsteadOfStatistics() {
    launchApp()

    assertShows(HomeScreenTestTags.LAST_SWIM_HERO, "No entries yet")
    assertShows(HomeScreenTestTags.AVERAGE_PER_WEEK_STAT, "-")
    assertShows(HomeScreenTestTags.STREAK_STAT, "-")
    assertShows(HomeScreenTestTags.BEST_WEEK_STAT, "-")
    assertShows(HomeScreenTestTags.TOTAL_SPENT_STAT, "-")
    assertShows(HomeScreenTestTags.COST_PER_ENTRY_STAT, "-")
    tag(HomeScreenTestTags.FAVORITE_DAY_BANNER).assertDoesNotExist()
    tag(HomeScreenTestTags.NO_SUBSCRIPTION_TODO).assertIsDisplayed()
  }

  @Test
  fun weeklySwimmer_seesTheirStreak_favoriteDay_averageAndCosts() {
    seedSubscription(id = "old", name = "Old pass", price = 40.0, active = false)
    seedSubscription(id = "sub-1", price = 60.0, maxEntries = 20)
    // The same weekday for four weeks in a row, the latest one today.
    listOf(0L, 7L, 14L, 21L).forEach { seedEntry(daysAgo = it, subscriptionId = "sub-1") }
    launchApp()

    assertShows(HomeScreenTestTags.LAST_SWIM_HERO, "Today")
    assertShows(HomeScreenTestTags.TOTAL_ENTRIES_STAT, "4")
    assertShows(HomeScreenTestTags.STREAK_STAT, "4 weeks")
    assertShows(HomeScreenTestTags.BEST_WEEK_STAT, "1")
    assertShows(HomeScreenTestTags.AVERAGE_PER_WEEK_STAT, "1.0")
    assertShows(HomeScreenTestTags.TOTAL_SPENT_STAT, "100.00")
    assertShows(HomeScreenTestTags.COST_PER_ENTRY_STAT, "25.00")
    val weekday =
        today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
    assertShows(HomeScreenTestTags.FAVORITE_DAY_BANNER, "You usually swim on $weekday")
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "16 of 20 entries left")
    tag(HomeScreenTestTags.WEEKLY_CHART).assertIsDisplayed()
  }

  @Test
  fun lastSwimYesterday_isWordedAsOneDay() {
    seedEntry(daysAgo = 1)
    launchApp()
    assertShows(HomeScreenTestTags.LAST_SWIM_HERO, "1 day")
  }

  @Test
  fun lastSwimAWeekAgo_isWordedInDays_andTheStreakIsOneWeekOrBroken() {
    seedEntry(daysAgo = 9)
    seedEntry(daysAgo = 10)
    launchApp()

    assertShows(HomeScreenTestTags.LAST_SWIM_HERO, "9 days")
    assertShows(HomeScreenTestTags.TOTAL_ENTRIES_STAT, "2")
  }

  // ---------- the expiration banner ----------

  private fun bannerFor(expiresInDays: Long?, maxEntries: Int? = null, used: Int = 0) {
    seedSubscription(id = "sub-1", expiresInDays = expiresInDays, maxEntries = maxEntries)
    repeat(used) { seedEntry(daysAgo = (it + 1).toLong(), subscriptionId = "sub-1") }
    launchApp()
  }

  @Test
  fun banner_saysThePassHasNoExpiration() {
    bannerFor(expiresInDays = null)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "Your active pass has no expiration")
  }

  @Test
  fun banner_saysThePassExpiresToday() {
    bannerFor(expiresInDays = 0)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "Your active pass expires today")
  }

  @Test
  fun banner_saysThePassExpiresTomorrow() {
    bannerFor(expiresInDays = 1)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "Your active pass expires tomorrow")
  }

  @Test
  fun banner_countsTheDaysOfAPassAboutToExpire() {
    bannerFor(expiresInDays = 3)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "Your active pass expires in 3 days")
  }

  @Test
  fun banner_roundsAPassExpiringLaterToMonths() {
    bannerFor(expiresInDays = daysFromToday(today.plusMonths(5).plusDays(2)))
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "Your active pass expires in 5 months")
  }

  @Test
  fun banner_roundsAPassExpiringMuchLaterToYears() {
    bannerFor(expiresInDays = daysFromToday(today.plusYears(1).plusDays(3)))
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "Your active pass expires in 1 year")
  }

  @Test
  fun banner_saysHowLongAgoAPassExpired() {
    bannerFor(expiresInDays = daysFromToday(today.minusMonths(3).minusDays(2)))
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "Your active pass expired 3 months ago")
  }

  @Test
  fun banner_saysAPassExpiredADayAgo() {
    bannerFor(expiresInDays = -1)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "Your active pass expired 1 day ago")
  }

  @Test
  fun banner_countsTheEntriesLeftOfALimitedPass() {
    bannerFor(expiresInDays = null, maxEntries = 10, used = 3)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "7 of 10 entries left")
  }

  @Test
  fun banner_isSingularForTheLastEntry_andNeverNegative() {
    bannerFor(expiresInDays = null, maxEntries = 1, used = 0)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "1 of 1 entry left")
  }

  @Test
  fun banner_saysNoEntriesAreLeft_onceTheLimitIsUsedUp() {
    bannerFor(expiresInDays = null, maxEntries = 2, used = 3)
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "0 of 2 entries left")
  }

  @Test
  fun stats_countOnlyTheEntriesOfTheActiveSubscriptionAgainstItsLimit() {
    seedSubscription(id = "sub-1", maxEntries = 5)
    seedEntry(daysAgo = 2, subscriptionId = "sub-1")
    seedEntry(daysAgo = 3, subscriptionId = "other")
    seedEntry(daysAgo = 4)
    launchApp()

    assertEquals(3, store.ids("entries").size)
    assertShows(HomeScreenTestTags.TOTAL_ENTRIES_STAT, "3")
    assertShows(HomeScreenTestTags.EXPIRATION_BANNER, "4 of 5 entries left")
  }
}
