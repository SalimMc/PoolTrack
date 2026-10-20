package com.github.se.pooltrack.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.entry.EntryRepositoryProvider
import com.github.se.pooltrack.model.hint.Hint
import com.github.se.pooltrack.model.hint.HintRepositoryProvider
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.model.subscription.SubscriptionRepositoryProvider
import com.github.se.pooltrack.ui.history.heatmap.HistoryOverviewTestTags
import com.github.se.pooltrack.ui.history.heatmap.MonthCalendarTestTags
import com.github.se.pooltrack.ui.history.heatmap.WeekStripTestTags
import com.github.se.pooltrack.ui.history.heatmap.YearCalendarTestTags
import com.github.se.pooltrack.ui.navigation.NavigationActions
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.ui.navigation.Screen
import com.github.se.pooltrack.utils.FakeEntryRepository
import com.github.se.pooltrack.utils.FakeHintRepository
import com.github.se.pooltrack.utils.FakeSubscriptionRepository
import com.github.se.pooltrack.utils.MainDispatcherRule
import io.mockk.mockk
import io.mockk.verify
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A tall screen: the heatmap card sits above the list and would push the rows out of view.
@Config(qualifiers = "w360dp-h1200dp")
@RunWith(RobolectricTestRunner::class)
class HistoryScreenRobolectricTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()
  @get:Rule val composeTestRule = createComposeRule()

  private val zone = ZoneId.systemDefault()
  private val today = LocalDate.now(zone)

  private fun entryAt(day: LocalDate, hour: Int = 12, minute: Int = 0) =
      Entry(
          timestampEpochMilli = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli(),
          subscriptionId = "sub-1",
      )

  private fun show(repository: FakeEntryRepository, navigationActions: NavigationActions? = null) {
    val viewModel = HistoryViewModel(repository, FakeSubscriptionRepository(), FakeHintRepository())
    composeTestRule.setContent { HistoryScreen(viewModel, navigationActions) }
  }

  private fun timeLabel(entry: Entry): String =
      DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
          .withLocale(Locale.getDefault())
          .withZone(zone)
          .format(Instant.ofEpochMilli(entry.timestampEpochMilli))

  @Test
  fun historyScreen_showsEmptyState_whenNoEntriesRecorded() {
    show(FakeEntryRepository())

    composeTestRule.onNodeWithTag(HistoryScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()
    composeTestRule.onNodeWithText("No entries yet").assertIsDisplayed()
    composeTestRule.onNodeWithText("Confirmed pool visits will show up here.").assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_LIST).assertCountEquals(0)
  }

  @Test
  fun historyScreen_listsEntriesUnderWeekHeaders_withTheirVisitCounts() {
    val thisWeekMorning = entryAt(today, hour = 8)
    val thisWeekEvening = entryAt(today, hour = 18)
    val older = entryAt(today.minusWeeks(3), hour = 9)
    show(FakeEntryRepository(listOf(older, thisWeekMorning, thisWeekEvening)))

    composeTestRule.onNodeWithTag(HistoryScreenTestTags.ENTRY_LIST).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.EMPTY_MESSAGE).assertCountEquals(0)
    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.WEEK_HEADER).assertCountEquals(2)
    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(3)
    composeTestRule.onNodeWithText("This week").assertIsDisplayed()
    composeTestRule
        .onNode(
            hasTestTag(HistoryScreenTestTags.WEEK_HEADER) and hasAnyDescendant(hasText("2 visits"))
        )
        .assertExists()
    composeTestRule
        .onNode(
            hasTestTag(HistoryScreenTestTags.WEEK_HEADER) and hasAnyDescendant(hasText("1 visit"))
        )
        .assertExists()
    composeTestRule.onNodeWithText(timeLabel(thisWeekMorning)).assertIsDisplayed()
    composeTestRule.onNodeWithText(timeLabel(thisWeekEvening)).assertIsDisplayed()
    composeTestRule.onNodeWithText(timeLabel(older)).assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "en-rUS-w360dp-h1200dp")
  fun historyScreen_startsWeeksOnMonday_evenWithAUsLocale() {
    // A Sunday and the Monday after it: one week in the US (Sunday start), two on Monday start.
    val sunday = today.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY)).minusWeeks(1)
    show(FakeEntryRepository(listOf(entryAt(sunday), entryAt(sunday.plusDays(1)))))

    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.WEEK_HEADER).assertCountEquals(2)
  }

  @Test
  fun historyScreen_namesTheWeekBeforeThisOne_lastWeek() {
    show(FakeEntryRepository(listOf(entryAt(today.minusWeeks(1)))))

    composeTestRule.onNodeWithText("Last week").assertIsDisplayed()
  }

  private fun swipeAwayFirstEntry() {
    composeTestRule
        .onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM)
        .onFirst()
        .performTouchInput { swipeLeft() }
    composeTestRule.waitForIdle()
  }

  /** Swipes the first row to the left and confirms the dialog that asks whether to delete it. */
  private fun deleteFirstEntry() {
    swipeAwayFirstEntry()
    composeTestRule.onNodeWithTag(HistoryScreenTestTags.CONFIRM_DELETE_BUTTON).performClick()
    composeTestRule.waitForIdle()
  }

  @Test
  fun historyScreen_asksBeforeDeleting_andChangesNothingYet_whenAnEntryIsSwiped() {
    val entry = entryAt(today)
    val repository = FakeEntryRepository(listOf(entry))
    show(repository)

    swipeAwayFirstEntry()

    composeTestRule.onNodeWithText("Delete this visit?").assertIsDisplayed()
    composeTestRule.onNode(hasText("will be removed.", substring = true)).assertIsDisplayed()
    assertEquals(listOf(entry), repository.storedEntries)
  }

  @Test
  fun historyScreen_keepsTheEntry_andTheRowComesBack_whenDeletingIsCancelled() {
    val entry = entryAt(today)
    val repository = FakeEntryRepository(listOf(entry))
    show(repository)
    swipeAwayFirstEntry()

    composeTestRule.onNodeWithTag(HistoryScreenTestTags.CANCEL_DELETE_BUTTON).performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf(entry), repository.storedEntries)
    composeTestRule.onAllNodesWithText("Delete this visit?").assertCountEquals(0)
    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(1)
    composeTestRule.onNodeWithText(timeLabel(entry)).assertIsDisplayed()
  }

  @Test
  fun deletionSummary_namesTheDayAndTime() {
    val entry = entryAt(today, hour = 18)

    assertEquals(
        "The visit on Today at ${timeLabel(entry)} will be removed.",
        deletionSummary(entry),
    )
  }

  @Test
  fun historyScreen_deletesOnlyThatEntry_whenSwipedAway() {
    val keep = entryAt(today, hour = 8)
    val remove = entryAt(today, hour = 18)
    val repository = FakeEntryRepository(listOf(keep, remove))
    show(repository)

    // Most recent first, so the first row is the 18:00 entry.
    deleteFirstEntry()

    assertEquals(listOf(keep), repository.storedEntries)
    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(1)
    composeTestRule.onNodeWithText("Entry deleted").assertIsDisplayed()
  }

  @Test
  fun historyScreen_showsEmptyState_whenLastEntryDeleted() {
    val repository = FakeEntryRepository(listOf(entryAt(today)))
    show(repository)

    deleteFirstEntry()

    assertEquals(emptyList<Entry>(), repository.storedEntries)
    composeTestRule.onNodeWithTag(HistoryScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()
  }

  @Test
  fun historyScreen_restoresTheEntryExactly_whenDeleteUndone() {
    val entry = entryAt(today).copy(swimDistanceMeters = 900, swimDurationMillis = 60_000L)
    val repository = FakeEntryRepository(listOf(entry))
    show(repository)
    deleteFirstEntry()

    composeTestRule.onNodeWithText(HistoryScreenTestTags.UNDO_ADD_ACTION).performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf(entry), repository.storedEntries)
    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(1)
  }

  @Test
  fun historyScreen_doesNotDelete_whenTheRowIsOnlyTapped() {
    val entry = entryAt(today)
    val repository = FakeEntryRepository(listOf(entry))
    show(repository)

    composeTestRule.onNodeWithTag(HistoryScreenTestTags.ENTRY_ITEM).performClick()

    assertEquals(listOf(entry), repository.storedEntries)
  }

  @Test
  fun historyScreen_showsNewEntry_whenRepositoryChangesAfterComposition() {
    val repository = FakeEntryRepository()
    show(repository)
    composeTestRule.onNodeWithTag(HistoryScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()

    runBlocking { repository.addEntry(entryAt(today)) }
    composeTestRule.waitForIdle()

    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(1)
  }

  @Test
  fun historyScreen_showsTheTimeAndSwimDuration_whenNoDistanceWasLogged() {
    val reported = entryAt(today, hour = 8).copy(swimDurationMillis = 4_320_000L)
    val unreported = entryAt(today.minusDays(1), hour = 9)
    show(FakeEntryRepository(listOf(reported, unreported)))

    composeTestRule.onNodeWithText(timeLabel(reported)).assertIsDisplayed()
    composeTestRule.onNodeWithText("1h 12min").assertIsDisplayed()
    composeTestRule.onNodeWithText(timeLabel(unreported)).assertIsDisplayed()
  }

  @Test
  fun historyScreen_leadsWithTheLoggedDistance_overTheTime() {
    val logged = entryAt(today, hour = 8).copy(swimDistanceMeters = 1200)
    val both =
        entryAt(today.minusDays(1), hour = 9)
            .copy(swimDurationMillis = 4_320_000L, swimDistanceMeters = 800)
    show(FakeEntryRepository(listOf(logged, both)))

    composeTestRule.onNodeWithText("1200 m").assertIsDisplayed()
    composeTestRule.onNodeWithText(timeLabel(logged)).assertIsDisplayed()
    composeTestRule.onNodeWithText("800 m").assertIsDisplayed()
    composeTestRule.onNodeWithText("${timeLabel(both)} · 1h 12min").assertIsDisplayed()
  }

  @Test
  fun historyScreen_totalsTheWeeksDistance_inItsHeader() {
    val first = entryAt(today, hour = 8).copy(swimDistanceMeters = 1200)
    val second = entryAt(today.minusMonths(2), hour = 9).copy(swimDistanceMeters = 800)
    show(FakeEntryRepository(listOf(first, second)))

    composeTestRule
        .onNode(
            hasTestTag(HistoryScreenTestTags.WEEK_HEADER) and
                hasAnyDescendant(hasText("1 visit · 1.2 km"))
        )
        .assertExists()
    composeTestRule
        .onNode(
            hasTestTag(HistoryScreenTestTags.WEEK_HEADER) and
                hasAnyDescendant(hasText("1 visit · 800 m"))
        )
        .assertExists()
  }

  @Test
  fun historyScreen_opensEntryDetails_whenEntryTapped() {
    val entry = entryAt(today)
    val repository = FakeEntryRepository(listOf(entry))
    val navigationActions = mockk<NavigationActions>(relaxed = true)
    show(repository, navigationActions)

    composeTestRule.onNodeWithText(timeLabel(entry)).performClick()

    verify(exactly = 1) { navigationActions.navigateToEntryDetails(entry.timestampEpochMilli) }
    assertEquals(listOf(entry), repository.storedEntries)
  }

  @Test
  fun historyScreen_ignoresEntryTap_whenNoNavigationActions() {
    val entry = entryAt(today)
    show(FakeEntryRepository(listOf(entry)))

    composeTestRule.onNodeWithText(timeLabel(entry)).performClick()

    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(1)
  }

  @Test
  fun historyScreen_navigatesToTappedTab_whenBottomTabSelected() {
    val navigationActions = mockk<NavigationActions>(relaxed = true)
    show(FakeEntryRepository(), navigationActions)

    composeTestRule.onNodeWithTag(NavigationTestTags.HOME_TAB).performClick()
    composeTestRule.onNodeWithTag(NavigationTestTags.SUBSCRIPTION_TAB).performClick()

    verify(exactly = 1) { navigationActions.navigateTo(Screen.Home) }
    verify(exactly = 1) { navigationActions.navigateTo(Screen.Subscription) }
  }

  @Test
  fun historyScreen_ignoresTabTap_whenNoNavigationActions() {
    show(FakeEntryRepository())

    composeTestRule.onNodeWithTag(NavigationTestTags.HOME_TAB).performClick()

    composeTestRule.onNodeWithTag(HistoryScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()
  }

  @Test
  fun historyViewModel_readsProviderRepository_whenCreatedWithDefaults() {
    val entry = entryAt(today)
    EntryRepositoryProvider.repository = FakeEntryRepository(listOf(entry))
    SubscriptionRepositoryProvider.repository = FakeSubscriptionRepository()
    HintRepositoryProvider.repository = FakeHintRepository()
    try {
      assertEquals(listOf(entry), HistoryViewModel().entries.value)
    } finally {
      // The providers are lateinit singletons: un-initialise them so later tests are unaffected.
      listOf(EntryRepositoryProvider, SubscriptionRepositoryProvider, HintRepositoryProvider)
          .forEach { provider ->
            provider::class.java.getDeclaredField("repository").apply {
              isAccessible = true
              set(provider, null)
            }
          }
    }
  }

  private fun addYesterdayAtNoon() {
    composeTestRule.onNodeWithTag(HistoryScreenTestTags.ADD_PAST_ENTRY_BUTTON).performClick()
    composeTestRule.onNodeWithTag(AddPastEntrySheetTestTags.YESTERDAY_CHIP).performClick()
    composeTestRule.onNodeWithTag(AddPastEntrySheetTestTags.ADD_BUTTON).performClick()
    composeTestRule.waitForIdle()
  }

  @Test
  fun historyScreen_opensTheAddSheet_whenTheAddButtonIsTapped() {
    show(FakeEntryRepository())

    composeTestRule.onNodeWithTag(HistoryScreenTestTags.ADD_PAST_ENTRY_BUTTON).performClick()

    composeTestRule.onNodeWithTag(AddPastEntrySheetTestTags.SHEET).assertIsDisplayed()
  }

  @Test
  fun historyScreen_addsAPastEntryAndClosesTheSheet_withAnUndoSnackbar() {
    val repository = FakeEntryRepository()
    show(repository)

    addYesterdayAtNoon()

    val expected = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    assertEquals(listOf(expected), repository.storedEntries.map { it.timestampEpochMilli })
    composeTestRule.onAllNodesWithTag(AddPastEntrySheetTestTags.SHEET).assertCountEquals(0)
    composeTestRule.onNodeWithText("Entry added").assertIsDisplayed()
  }

  @Test
  fun historyScreen_removesTheEntry_whenUndoIsTapped() {
    val repository = FakeEntryRepository()
    show(repository)
    addYesterdayAtNoon()

    composeTestRule.onNodeWithText(HistoryScreenTestTags.UNDO_ADD_ACTION).performClick()
    composeTestRule.waitForIdle()

    assertEquals(emptyList<Entry>(), repository.storedEntries)
  }

  @Test
  fun historyScreen_showsAnInlineError_whenTheEntryAlreadyExists() {
    val repository = FakeEntryRepository()
    show(repository)
    addYesterdayAtNoon()

    // The sheet remembers the day and time just added, so adding again hits the same instant.
    composeTestRule.onNodeWithTag(HistoryScreenTestTags.ADD_PAST_ENTRY_BUTTON).performClick()
    composeTestRule.onNodeWithTag(AddPastEntrySheetTestTags.ADD_BUTTON).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(AddPastEntrySheetTestTags.SHEET).assertIsDisplayed()
    composeTestRule.onNodeWithTag(AddPastEntrySheetTestTags.ERROR).assertIsDisplayed()
    assertEquals(1, repository.storedEntries.size)
  }

  private val expiredPass =
      Subscription(
          id = "pass",
          uri = "content://pass",
          displayName = "pass",
          addedAtEpochMilli = System.currentTimeMillis(),
          expiresAtEpochMilli = Instant.now().minusSeconds(86_400).toEpochMilli(),
      )

  @Test
  fun historyScreen_offersToLinkUnlinkedEntries_andLinksThemWithUndo() {
    val unlinked = Entry(entryAt(today.minusDays(30)).timestampEpochMilli, subscriptionId = null)
    val repository = FakeEntryRepository(listOf(unlinked))
    val viewModel =
        HistoryViewModel(
            repository,
            FakeSubscriptionRepository(listOf(expiredPass)),
            FakeHintRepository(),
        )
    composeTestRule.setContent { HistoryScreen(viewModel) }

    composeTestRule.onNodeWithTag(HistoryScreenTestTags.LINK_BANNER).assertIsDisplayed()
    composeTestRule.onNodeWithTag(HistoryScreenTestTags.LINK_BUTTON).performClick()
    composeTestRule.waitForIdle()

    assertEquals("pass", repository.storedEntries.single().subscriptionId)
    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.LINK_BANNER).assertCountEquals(0)
    composeTestRule.onNodeWithText("1 entry linked").assertIsDisplayed()

    composeTestRule.onNodeWithText(HistoryScreenTestTags.UNDO_ADD_ACTION).performClick()
    composeTestRule.waitForIdle()
    assertEquals(null, repository.storedEntries.single().subscriptionId)
  }

  @Test
  fun historyScreen_hidesTheLinkBanner_whenNothingCanBeLinked() {
    show(FakeEntryRepository(listOf(Entry(entryAt(today).timestampEpochMilli))))

    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.LINK_BANNER).assertCountEquals(0)
  }

  private fun expandCalendar() {
    composeTestRule.onNodeWithTag(HistoryOverviewTestTags.TOGGLE).performClick()
    composeTestRule.waitForIdle()
  }

  @Test
  fun historyScreen_showsACompactOverview_withJustTheWeek() {
    show(FakeEntryRepository(listOf(entryAt(today))))

    composeTestRule.onNodeWithTag(HistoryOverviewTestTags.CARD).assertIsDisplayed()
    composeTestRule.onNodeWithTag(WeekStripTestTags.day(today)).assertExists()
    composeTestRule.onAllNodesWithTag(MonthCalendarTestTags.CALENDAR).assertCountEquals(0)
  }

  @Test
  fun historyScreen_hidesTheOverview_whenThereAreNoEntries() {
    show(FakeEntryRepository())

    composeTestRule.onAllNodesWithTag(HistoryOverviewTestTags.CARD).assertCountEquals(0)
  }

  @Test
  fun historyScreen_expandsTheOverviewToTheMonthCalendar_andCollapsesIt() {
    show(FakeEntryRepository(listOf(entryAt(today))))

    expandCalendar()

    composeTestRule.onNodeWithTag(MonthCalendarTestTags.CALENDAR).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(WeekStripTestTags.STRIP).assertCountEquals(0)

    expandCalendar()

    composeTestRule.onNodeWithTag(WeekStripTestTags.STRIP).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(MonthCalendarTestTags.CALENDAR).assertCountEquals(0)
  }

  @Test
  fun historyScreen_keepsEveryEntryListed_whenADayIsTapped() {
    show(FakeEntryRepository(listOf(entryAt(today), entryAt(today.minusDays(40)))))

    composeTestRule.onNodeWithTag(WeekStripTestTags.day(today)).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(2)
    composeTestRule.onAllNodesWithText("No visit on Today").assertCountEquals(0)
  }

  @Test
  fun historyScreen_saysSoWhenTheTappedDayHasNoVisit() {
    show(FakeEntryRepository(listOf(entryAt(today.minusDays(40)))))

    composeTestRule.onNodeWithTag(WeekStripTestTags.day(today)).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("No visit on Today").assertIsDisplayed()
  }

  @Test
  fun historyScreen_zoomsOutToTheYear_withAShadedTilePerMonth() {
    show(FakeEntryRepository(listOf(entryAt(today), entryAt(today.minusDays(40)))))
    expandCalendar()

    composeTestRule.onNodeWithTag(MonthCalendarTestTags.ZOOM_OUT).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(YearCalendarTestTags.CALENDAR).assertIsDisplayed()
    composeTestRule.onAllNodesWithTag(MonthCalendarTestTags.CALENDAR).assertCountEquals(0)
    composeTestRule.onNodeWithTag(YearCalendarTestTags.month(YearMonth.from(today))).assertExists()
  }

  @Test
  fun historyScreen_zoomsBackIntoATappedMonth() {
    val older = today.minusMonths(1).withDayOfMonth(15)
    show(FakeEntryRepository(listOf(entryAt(today), entryAt(older))))
    expandCalendar()
    composeTestRule.onNodeWithTag(MonthCalendarTestTags.ZOOM_OUT).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(YearCalendarTestTags.month(YearMonth.from(older))).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag(MonthCalendarTestTags.day(older)).assertExists()
    composeTestRule.onAllNodesWithTag(YearCalendarTestTags.CALENDAR).assertCountEquals(0)
  }

  @Test
  fun historyScreen_marksTheWeeksDistanceAsLogged_whenSomeVisitsHaveNone() {
    val withDistance = entryAt(today, hour = 8).copy(swimDistanceMeters = 1200)
    val without = entryAt(today, hour = 18)
    show(FakeEntryRepository(listOf(withDistance, without)))

    composeTestRule
        .onNode(
            hasTestTag(HistoryScreenTestTags.WEEK_HEADER) and
                hasAnyDescendant(hasText("2 visits · 1.2 km logged"))
        )
        .assertExists()
  }

  @Test
  fun historyScreen_showsTheSwipeTip_untilItIsDismissed() {
    show(FakeEntryRepository(listOf(entryAt(today))))
    composeTestRule.onNodeWithTag(HistoryScreenTestTags.SWIPE_HINT).assertIsDisplayed()

    composeTestRule.onNodeWithTag(HistoryScreenTestTags.SWIPE_HINT_DISMISS).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.SWIPE_HINT).assertCountEquals(0)
  }

  @Test
  fun historyScreen_hidesTheSwipeTip_whenItWasDismissedBefore() {
    val viewModel =
        HistoryViewModel(
            FakeEntryRepository(listOf(entryAt(today))),
            FakeSubscriptionRepository(),
            FakeHintRepository(dismissed = setOf(Hint.SwipeToDelete)),
        )
    composeTestRule.setContent { HistoryScreen(viewModel) }
    composeTestRule.waitForIdle()

    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.SWIPE_HINT).assertCountEquals(0)
  }

  @Test
  fun historyScreen_dismissesTheSwipeTip_afterAVisitWasSwipedAway() {
    show(FakeEntryRepository(listOf(entryAt(today), entryAt(today.minusDays(40)))))

    deleteFirstEntry()

    composeTestRule.onAllNodesWithTag(HistoryScreenTestTags.SWIPE_HINT).assertCountEquals(0)
  }
}
