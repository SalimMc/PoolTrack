package com.github.se.pooltrack.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.github.se.pooltrack.model.entry.EntryRepositoryFirestore
import com.github.se.pooltrack.ui.entry.EntryDetailsScreenTestTags
import com.github.se.pooltrack.ui.history.AddPastEntrySheetTestTags
import com.github.se.pooltrack.ui.history.HistoryScreenTestTags
import com.github.se.pooltrack.ui.history.heatmap.HistoryOverviewTestTags
import com.github.se.pooltrack.ui.history.heatmap.MonthCalendarTestTags
import com.github.se.pooltrack.ui.history.heatmap.WeekStripTestTags
import com.github.se.pooltrack.ui.history.heatmap.YearCalendarTestTags
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The History tab: weeks, the calendar overview, back-filling entries, and entry details. */
class HistoryFlowTest : EndToEndTest() {

  private fun openHistory() {
    click(NavigationTestTags.HISTORY_TAB)
  }

  private fun storedEntries() = runBlocking { EntryRepositoryFirestore(store).getEntries().first() }

  private fun expandCalendar() {
    click(HistoryOverviewTestTags.TOGGLE)
    composeRule.waitForIdle()
  }

  // ---------- empty and listed ----------

  @Test
  fun emptyHistory_saysSo_andStillOffersToAddAPastEntry() {
    launchApp()
    openHistory()

    tag(HistoryScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()
    tag(HistoryOverviewTestTags.CARD).assertDoesNotExist()
    tag(HistoryScreenTestTags.ADD_PAST_ENTRY_BUTTON).assertIsDisplayed()
  }

  @Test
  fun entries_areGroupedInWeeksStartingOnMonday_mostRecentFirst() {
    // A Sunday and the Monday after it fall in two different weeks, a Monday and its Sunday in one.
    val lastSunday = today.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY)).minusWeeks(2)
    val monday = lastSunday.plusDays(1)
    val nextSunday = monday.plusDays(6)
    listOf(lastSunday, monday, nextSunday).forEach { day ->
      seedEntry(daysAgo = today.toEpochDay() - day.toEpochDay(), hour = 9)
    }
    seedEntry(daysAgo = 0, hour = 7, distance = 1200)
    seedEntry(daysAgo = 0, hour = 8, distance = 300, durationMillis = 72 * 60_000L)
    launchApp()
    openHistory()

    // This week (2 visits), the week of the Monday (2 visits), the week of the Sunday (1 visit).
    tag(HistoryScreenTestTags.ENTRY_LIST).assertIsDisplayed()
    composeRule.onAllNodesWithTag(HistoryScreenTestTags.WEEK_HEADER).assertCountEquals(3)
    composeRule.onNodeWithText("This week").assertIsDisplayed()
    composeRule
        .onNode(
            hasTestTag(HistoryScreenTestTags.WEEK_HEADER) and
                hasAnyDescendant(hasText("2 visits · 1.5 km"))
        )
        .assertExists()
    composeRule.onNodeWithText("1200 m").assertIsDisplayed()
    composeRule.onNodeWithText("1h 12min", substring = true).assertIsDisplayed()
  }

  // ---------- the calendar overview ----------

  @Test
  fun weekStrip_pickingADayWithoutAVisit_saysSo_andOneWithAVisitKeepsTheList() {
    seedEntry(daysAgo = 40)
    launchApp()
    openHistory()

    click(WeekStripTestTags.day(today))
    composeRule.onNodeWithText("No visit on Today").assertIsDisplayed()
    // The picked row would be highlighted, but there is none: the list keeps its entry.
    composeRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(1)
  }

  @Test
  fun monthCalendar_buttonsAndSwipesChangeTheMonth() {
    val older = today.minusMonths(2).withDayOfMonth(12)
    seedEntry(daysAgo = today.toEpochDay() - older.toEpochDay())
    seedEntry(daysAgo = 0)
    launchApp()
    openHistory()
    expandCalendar()

    val thisMonthTitle = textOf(MonthCalendarTestTags.TITLE)
    click(MonthCalendarTestTags.PREVIOUS)
    val previousTitle = textOf(MonthCalendarTestTags.TITLE)
    assertTrue(thisMonthTitle != previousTitle)
    click(MonthCalendarTestTags.PREVIOUS)

    // The month of the old entry: tapping its day highlights and scrolls to its row.
    click(MonthCalendarTestTags.day(older))
    composeRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(2)

    click(MonthCalendarTestTags.NEXT)
    assertEquals(previousTitle, textOf(MonthCalendarTestTags.TITLE))
    tag(MonthCalendarTestTags.CALENDAR).performTouchInput { swipeLeft() }
    composeRule.waitForIdle()
    assertEquals(thisMonthTitle, textOf(MonthCalendarTestTags.TITLE))
    tag(MonthCalendarTestTags.CALENDAR).performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    assertEquals(previousTitle, textOf(MonthCalendarTestTags.TITLE))
  }

  @Test
  fun yearCalendar_showsEveryYearOfHistory_andReturnsToTheTappedMonth() {
    val lastYear = today.minusYears(1).withMonth(3).withDayOfMonth(8)
    seedEntry(daysAgo = today.toEpochDay() - lastYear.toEpochDay())
    seedEntry(daysAgo = 0)
    launchApp()
    openHistory()
    expandCalendar()

    // Zoom out with the "< year" button, page to last year with the arrows, pick March.
    click(MonthCalendarTestTags.ZOOM_OUT)
    tag(YearCalendarTestTags.CALENDAR).assertIsDisplayed()
    val thisYear = textOf(YearCalendarTestTags.TITLE)
    click(YearCalendarTestTags.PREVIOUS)
    val lastYearTitle = textOf(YearCalendarTestTags.TITLE)
    assertTrue(thisYear != lastYearTitle)
    click(YearCalendarTestTags.NEXT)
    assertEquals(thisYear, textOf(YearCalendarTestTags.TITLE))
    click(YearCalendarTestTags.PREVIOUS)
    click(YearCalendarTestTags.month(YearMonth.from(lastYear)))

    tag(MonthCalendarTestTags.CALENDAR).assertIsDisplayed()
    click(MonthCalendarTestTags.day(lastYear))
    composeRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(2)
  }

  @Test
  fun pinchingTheCalendar_zoomsOutToTheYear_andBackIn() {
    seedEntry(daysAgo = 0)
    launchApp()
    openHistory()
    expandCalendar()

    tag(MonthCalendarTestTags.CALENDAR).performTouchInput {
      pinch(
          start0 = Offset(centerX - 10, centerY),
          end0 = Offset(centerX - 90, centerY),
          start1 = Offset(centerX + 10, centerY),
          end1 = Offset(centerX + 90, centerY),
      )
    }
    composeRule.waitForIdle()
    // Spreading the fingers is a zoom in, which on the month calendar changes nothing.
    tag(MonthCalendarTestTags.CALENDAR).assertIsDisplayed()

    tag(MonthCalendarTestTags.CALENDAR).performTouchInput {
      pinch(
          start0 = Offset(centerX - 90, centerY),
          end0 = Offset(centerX - 10, centerY),
          start1 = Offset(centerX + 90, centerY),
          end1 = Offset(centerX + 10, centerY),
      )
    }
    composeRule.waitForIdle()
    tag(YearCalendarTestTags.CALENDAR).assertIsDisplayed()

    tag(YearCalendarTestTags.CALENDAR).performTouchInput {
      pinch(
          start0 = Offset(centerX - 10, centerY),
          end0 = Offset(centerX - 90, centerY),
          start1 = Offset(centerX + 10, centerY),
          end1 = Offset(centerX + 90, centerY),
      )
    }
    composeRule.waitForIdle()
    tag(MonthCalendarTestTags.CALENDAR).assertIsDisplayed()
  }

  // ---------- adding a past entry ----------

  @Test
  fun addingAPastEntry_closesTheSheet_offersUndo_andUndoRemovesIt() {
    launchApp()
    openHistory()

    click(HistoryScreenTestTags.ADD_PAST_ENTRY_BUTTON)
    tag(AddPastEntrySheetTestTags.SHEET).assertIsDisplayed()
    click(AddPastEntrySheetTestTags.YESTERDAY_CHIP)
    click(AddPastEntrySheetTestTags.ADD_BUTTON)

    tag(AddPastEntrySheetTestTags.SHEET).assertDoesNotExist()
    composeRule.onNodeWithText("Entry added").assertIsDisplayed()
    assertEquals(1, storedEntries().size)
    tag(HistoryScreenTestTags.ENTRY_ITEM).assertIsDisplayed()

    clickText(HistoryScreenTestTags.UNDO_ADD_ACTION)

    assertTrue(storedEntries().isEmpty())
    tag(HistoryScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()
  }

  @Test
  fun addingAPastEntry_isRefused_forAMomentThatAlreadyHasOne_orIsInTheFuture() {
    val existing = seedEntry(daysAgo = 1, hour = 12, minute = 0)
    launchApp()
    openHistory()

    // Yesterday at 12:00 is the default time, and an entry is already there.
    click(HistoryScreenTestTags.ADD_PAST_ENTRY_BUTTON)
    click(AddPastEntrySheetTestTags.YESTERDAY_CHIP)
    click(AddPastEntrySheetTestTags.ADD_BUTTON)
    tag(AddPastEntrySheetTestTags.ERROR)
        .assertTextEquals("There's already an entry at that exact time.")
    assertEquals(listOf(existing), storedEntries())

    // Changing the day clears the stale message. Today at 23:59 can't have happened yet.
    click(AddPastEntrySheetTestTags.TODAY_CHIP)
    tag(AddPastEntrySheetTestTags.ERROR).assertDoesNotExist()
    click(AddPastEntrySheetTestTags.TIME_BUTTON)
    composeRule.onNodeWithText("OK").assertIsDisplayed()
    clickText("Cancel")
    tag(AddPastEntrySheetTestTags.SHEET).assertIsDisplayed()
  }

  @Test
  fun addingAPastEntry_withAPickedDateAndTime() {
    launchApp()
    openHistory()
    click(HistoryScreenTestTags.ADD_PAST_ENTRY_BUTTON)

    // The date picker opens on the shown day and is confirmed or cancelled.
    click(AddPastEntrySheetTestTags.DATE_BUTTON)
    clickText("Cancel")
    click(AddPastEntrySheetTestTags.DATE_BUTTON)
    clickText("OK")
    click(AddPastEntrySheetTestTags.TIME_BUTTON)
    clickText("OK")
    click(AddPastEntrySheetTestTags.ADD_BUTTON)

    // Today at the default noon: only added when noon has already gone by, refused otherwise.
    val refused = storedEntries().isEmpty()
    if (refused) {
      tag(AddPastEntrySheetTestTags.ERROR).assertTextEquals("That time hasn't happened yet.")
    } else {
      composeRule.onNodeWithText("Entry added").assertIsDisplayed()
    }
  }

  @Test
  fun dismissingTheSheet_addsNothing() {
    launchApp()
    openHistory()

    click(HistoryScreenTestTags.ADD_PAST_ENTRY_BUTTON)
    pressBack()
    composeRule.waitForIdle()

    tag(AddPastEntrySheetTestTags.SHEET).assertDoesNotExist()
    assertTrue(storedEntries().isEmpty())
  }

  // ---------- linking entries to the subscription that covers them ----------

  @Test
  fun entriesWithoutASubscription_canBeLinkedToTheOneThatCoversThem_andUnlinked() {
    seedSubscription(id = "sub-1", expiresInDays = 200)
    seedEntry(daysAgo = 3)
    seedEntry(daysAgo = 4)
    launchApp()
    openHistory()

    tag(HistoryScreenTestTags.LINK_BANNER).assertIsDisplayed()
    composeRule.onNodeWithText("2 entries aren't linked to a subscription").assertIsDisplayed()
    click(HistoryScreenTestTags.LINK_BUTTON)

    composeRule.onNodeWithText("2 entries linked").assertIsDisplayed()
    assertTrue(storedEntries().all { it.subscriptionId == "sub-1" })
    tag(HistoryScreenTestTags.LINK_BANNER).assertDoesNotExist()

    clickText(HistoryScreenTestTags.UNDO_ADD_ACTION)
    assertTrue(storedEntries().all { it.subscriptionId == null })
    tag(HistoryScreenTestTags.LINK_BANNER).assertIsDisplayed()
  }

  @Test
  fun singleUnlinkedEntry_isLinkedWithASingularMessage() {
    seedSubscription(id = "sub-1", expiresInDays = 200)
    seedEntry(daysAgo = 3)
    launchApp()
    openHistory()

    composeRule.onNodeWithText("1 entry isn't linked to a subscription").assertIsDisplayed()
    click(HistoryScreenTestTags.LINK_BUTTON)
    composeRule.onNodeWithText("1 entry linked").assertIsDisplayed()
  }

  // ---------- entry details ----------

  @Test
  fun entryDetails_showTheSwim_theDistanceCanBeEdited_andBackReturnsToHistory() {
    seedSubscription(id = "sub-1", name = "Season pass", maxEntries = 10, price = 50.0)
    seedEntry(daysAgo = 8, subscriptionId = "sub-1", distance = 500)
    seedEntry(daysAgo = 1, subscriptionId = "sub-1", distance = 600, durationMillis = 45 * 60_000L)
    launchApp()
    openHistory()

    // The newer swim is the second one of the pass, the older one the first.
    composeRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM)[0].performClick()
    composeRule.waitForIdle()
    tag(NavigationTestTags.TOP_BAR_TITLE).assertTextEquals("Swim")
    unmerged(EntryDetailsScreenTestTags.DATE).assertExists()
    tag(EntryDetailsScreenTestTags.SWIM_DISTANCE).assertTextContains("600", substring = true)
    assertShows(EntryDetailsScreenTestTags.SUBSCRIPTION, "Season pass")
    unmerged(EntryDetailsScreenTestTags.COST).assertExists()
    unmerged(EntryDetailsScreenTestTags.SINCE_PREVIOUS).assertExists()

    // Edit the distance: invalid values can't be saved, cancelling changes nothing.
    click(EntryDetailsScreenTestTags.EDIT_BUTTON)
    tag(EntryDetailsScreenTestTags.DISTANCE_FIELD).performTextClearance()
    tag(EntryDetailsScreenTestTags.SAVE_DISTANCE_BUTTON).assertIsNotEnabled()
    tag(EntryDetailsScreenTestTags.DISTANCE_FIELD).performTextInput("0")
    tag(EntryDetailsScreenTestTags.SAVE_DISTANCE_BUTTON).assertIsNotEnabled()
    click(EntryDetailsScreenTestTags.CANCEL_EDIT_BUTTON)
    assertEquals(600, storedEntries().first().swimDistanceMeters)

    click(EntryDetailsScreenTestTags.EDIT_BUTTON)
    tag(EntryDetailsScreenTestTags.DISTANCE_FIELD).performTextClearance()
    tag(EntryDetailsScreenTestTags.DISTANCE_FIELD).performTextInput("750")
    click(EntryDetailsScreenTestTags.SAVE_DISTANCE_BUTTON)
    assertEquals(750, storedEntries().first().swimDistanceMeters)
    tag(EntryDetailsScreenTestTags.SWIM_DISTANCE).assertTextContains("750", substring = true)

    click(NavigationTestTags.GO_BACK_BUTTON)
    tag(HistoryScreenTestTags.ENTRY_LIST).assertIsDisplayed()
    composeRule.onNodeWithText("750 m").assertIsDisplayed()
  }

  @Test
  fun entryDetails_deletingASwim_asksFirst_thenRemovesItFromHistory() {
    seedEntry(daysAgo = 2, distance = 400)
    seedEntry(daysAgo = 5, distance = 900)
    launchApp()
    openHistory()
    composeRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM)[0].performClick()
    composeRule.waitForIdle()

    click(EntryDetailsScreenTestTags.DELETE_BUTTON)
    composeRule.onNodeWithText("Delete this swim?").assertIsDisplayed()
    click(EntryDetailsScreenTestTags.CANCEL_DELETE_BUTTON)
    assertEquals(2, storedEntries().size)

    click(EntryDetailsScreenTestTags.DELETE_BUTTON)
    click(EntryDetailsScreenTestTags.CONFIRM_DELETE_BUTTON)

    assertEquals(listOf(900), storedEntries().map { it.swimDistanceMeters })
    tag(HistoryScreenTestTags.ENTRY_LIST).assertIsDisplayed()
    composeRule.onAllNodesWithTag(HistoryScreenTestTags.ENTRY_ITEM).assertCountEquals(1)
  }

  @Test
  fun entryDetails_ofASwimThatNoLongerExists_saysSo() {
    val entry = seedEntry(daysAgo = 2, distance = 400)
    launchApp()
    openHistory()
    click(HistoryScreenTestTags.ENTRY_ITEM)
    unmerged(EntryDetailsScreenTestTags.DATE).assertExists()

    // It is deleted from elsewhere (another device) while it is open.
    runBlocking { EntryRepositoryFirestore(store).deleteEntry(entry) }
    composeRule.waitForIdle()

    tag(EntryDetailsScreenTestTags.NOT_FOUND_MESSAGE).assertIsDisplayed()
    tag(EntryDetailsScreenTestTags.DELETE_BUTTON).assertDoesNotExist()
  }

  private fun textOf(tag: String): String =
      tag(tag)
          .fetchSemanticsNode()
          .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
          .joinToString("") { it.text }
}
