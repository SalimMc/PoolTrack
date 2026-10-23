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
class YearCalendarTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val today = LocalDate.of(2026, 10, 15)
  private val years = listOf(2024, 2025, 2026)

  private fun show(
      countsByDay: Map<LocalDate, Int> = emptyMap(),
      onMonthClick: (YearMonth) -> Unit = {},
      initialYear: Int = years.last(),
      onYearChanged: (Int) -> Unit = {},
  ) {
    composeTestRule.setContent {
      YearCalendar(
          years = years,
          countsByDay = countsByDay,
          today = today,
          onMonthClick = onMonthClick,
          initialYear = initialYear,
          onYearChanged = onYearChanged,
          locale = Locale.US,
      )
    }
  }

  private fun assertYear(year: Int) {
    composeTestRule.onNodeWithTag(YearCalendarTestTags.TITLE).assertIsDisplayed()
    composeTestRule.onNodeWithText(year.toString()).assertIsDisplayed()
  }

  @Test
  fun yearCalendar_refusesToShowNoYears() {
    assertThrows(IllegalArgumentException::class.java) {
      composeTestRule.setContent {
        YearCalendar(
            years = emptyList(),
            countsByDay = emptyMap(),
            today = today,
            onMonthClick = {},
            initialYear = 2026,
        )
      }
    }
  }

  @Test
  fun yearCalendar_opensOnTheLastYear_byDefault() {
    show()

    assertYear(2026)
  }

  @Test
  fun yearCalendar_opensOnTheGivenYear() {
    show(initialYear = 2025)

    assertYear(2025)
  }

  @Test
  fun yearCalendar_opensOnTheLastYear_whenTheGivenOneIsNotListed() {
    show(initialYear = 1999)

    assertYear(2026)
  }

  @Test
  fun yearCalendar_reportsTheYearOnScreen_andEveryChange() {
    val shown = mutableListOf<Int>()
    show(onYearChanged = { shown += it })

    composeTestRule.onNodeWithTag(YearCalendarTestTags.PREVIOUS).performClick()
    composeTestRule.waitForIdle()

    assertEquals(listOf(2026, 2025), shown)
  }

  @Test
  fun yearCalendar_cannotGoBeyondItsFirstAndLastYear() {
    show(initialYear = years.first())

    composeTestRule.onNodeWithTag(YearCalendarTestTags.PREVIOUS).assertIsNotEnabled()
    repeat(2) {
      composeTestRule.onNodeWithTag(YearCalendarTestTags.NEXT).performClick()
      composeTestRule.waitForIdle()
    }

    composeTestRule.onNodeWithTag(YearCalendarTestTags.NEXT).assertIsNotEnabled()
    assertYear(2026)
  }

  @Test
  fun yearCalendar_reportsTheTappedMonth_butNeverALaterOne() {
    val clicked = mutableListOf<YearMonth>()
    show(onMonthClick = { clicked += it })

    composeTestRule.onNodeWithTag(YearCalendarTestTags.month(YearMonth.of(2026, 3))).performClick()
    composeTestRule.onNodeWithTag(YearCalendarTestTags.month(YearMonth.of(2026, 12))).performClick()

    assertEquals(listOf(YearMonth.of(2026, 3)), clicked)
  }

  @Test
  fun yearCalendar_showsTheVisitsOfTheYear() {
    show(countsByDay = mapOf(LocalDate.of(2026, 3, 2) to 2, LocalDate.of(2025, 3, 2) to 5))

    composeTestRule.onNodeWithText("2 visits").assertIsDisplayed()
  }
}
