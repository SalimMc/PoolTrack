package com.github.se.pooltrack.ui.history.heatmap

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(qualifiers = "w360dp-h900dp")
@RunWith(RobolectricTestRunner::class)
class MonthCalendarTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val today = LocalDate.of(2026, 10, 15)
  private val months = listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10))

  private fun show(
      countsByDay: Map<LocalDate, Int> = emptyMap(),
      selectedDate: LocalDate? = null,
      onDayClick: (LocalDate) -> Unit = {},
      initialMonth: YearMonth = months.last(),
      onZoomOut: ((YearMonth) -> Unit)? = null,
      onMonthChanged: (YearMonth) -> Unit = {},
  ) {
    composeTestRule.setContent {
      MonthCalendar(
          months = months,
          countsByDay = countsByDay,
          today = today,
          selectedDate = selectedDate,
          onDayClick = onDayClick,
          locale = Locale.US,
          initialMonth = initialMonth,
          onZoomOut = onZoomOut,
          onMonthChanged = onMonthChanged,
      )
    }
  }

  private fun assertTitle(text: String) {
    composeTestRule.onNodeWithTag(MonthCalendarTestTags.TITLE).assertIsDisplayed()
    composeTestRule.onNodeWithText(text).assertIsDisplayed()
  }

  @Test
  fun monthCalendar_refusesToShowNoMonths() {
    assertThrows(IllegalArgumentException::class.java) {
      composeTestRule.setContent {
        MonthCalendar(
            months = emptyList(),
            countsByDay = emptyMap(),
            today = today,
            selectedDate = null,
            onDayClick = {},
            initialMonth = YearMonth.of(2026, 10),
        )
      }
    }
  }

  @Test
  fun monthCalendar_opensOnTheLastMonth_byDefault() {
    show()

    assertTitle("October 2026")
  }

  @Test
  fun monthCalendar_opensOnTheGivenMonth() {
    show(initialMonth = YearMonth.of(2026, 9))

    assertTitle("September 2026")
  }

  @Test
  fun monthCalendar_opensOnTheLastMonth_whenTheGivenOneIsNotListed() {
    show(initialMonth = YearMonth.of(2020, 1))

    assertTitle("October 2026")
  }

  @Test
  fun monthCalendar_reportsTheMonthOnScreen_andEveryChange() {
    val shown = mutableListOf<YearMonth>()
    show(onMonthChanged = { shown += it })

    composeTestRule.onNodeWithTag(MonthCalendarTestTags.PREVIOUS).performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), shown)
    assertTitle("September 2026")
  }

  @Test
  fun monthCalendar_cannotGoBeyondItsFirstAndLastMonth() {
    show(initialMonth = months.first())

    composeTestRule.onNodeWithTag(MonthCalendarTestTags.PREVIOUS).assertIsNotEnabled()
    repeat(2) {
      composeTestRule.onNodeWithTag(MonthCalendarTestTags.NEXT).performClick()
      composeTestRule.waitForIdle()
    }

    composeTestRule.onNodeWithTag(MonthCalendarTestTags.NEXT).assertIsNotEnabled()
    assertTitle("October 2026")
  }

  @Test
  fun monthCalendar_countsTheVisitsOfTheMonthOnScreen() {
    show(countsByDay = mapOf(LocalDate.of(2026, 10, 3) to 1, LocalDate.of(2026, 9, 3) to 4))

    composeTestRule.onNodeWithText("1 visit").assertIsDisplayed()
  }

  @Test
  fun monthCalendar_saysThereWereNoVisits_andPluralizesTheCount() {
    show(
        countsByDay = mapOf(LocalDate.of(2026, 10, 3) to 2, LocalDate.of(2026, 10, 4) to 1),
        initialMonth = YearMonth.of(2026, 9),
    )
    composeTestRule.onNodeWithText("No visits").assertIsDisplayed()

    composeTestRule.onNodeWithTag(MonthCalendarTestTags.NEXT).performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("3 visits").assertIsDisplayed()
  }

  @Test
  fun monthCalendar_hidesTheYearButton_unlessZoomingOutIsPossible() {
    show()

    composeTestRule.onNodeWithTag(MonthCalendarTestTags.ZOOM_OUT).assertDoesNotExist()
  }

  @Test
  fun monthCalendar_zoomsOutFromTheMonthOnScreen() {
    var zoomedFrom: YearMonth? = null
    show(onZoomOut = { zoomedFrom = it })

    composeTestRule.onNodeWithTag(MonthCalendarTestTags.ZOOM_OUT).performClick()

    assertEquals(YearMonth.of(2026, 10), zoomedFrom)
  }

  @Test
  fun monthCalendar_reportsTheTappedDay_butNeverALaterOne() {
    val clicked = mutableListOf<LocalDate>()
    show(onDayClick = { clicked += it }, selectedDate = LocalDate.of(2026, 10, 2))

    composeTestRule
        .onNodeWithTag(MonthCalendarTestTags.day(LocalDate.of(2026, 10, 10)))
        .performClick()
    composeTestRule.onNodeWithTag(MonthCalendarTestTags.day(today)).performClick()
    composeTestRule
        .onNodeWithTag(MonthCalendarTestTags.day(LocalDate.of(2026, 10, 20)))
        .performClick()

    assertEquals(listOf(LocalDate.of(2026, 10, 10), today), clicked)
  }
}
