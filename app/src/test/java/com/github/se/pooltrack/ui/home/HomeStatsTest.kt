package com.github.se.pooltrack.ui.home

import com.github.se.pooltrack.model.entry.Entry
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HomeStatsTest {

  // Wednesday 23 September 2026, noon UTC.
  private val now = Instant.parse("2026-09-23T12:00:00Z")
  private val zone = ZoneOffset.UTC

  private val wednesday = entryAt("2026-09-23T08:00:00Z")
  private val mondayMidnight = entryAt("2026-09-21T00:00:00Z")
  private val sundayLateNight = entryAt("2026-09-20T23:59:00Z")
  private val lastMonthMonday = entryAt("2026-08-31T10:00:00Z")
  private val allEntries = listOf(wednesday, mondayMidnight, sundayLateNight, lastMonthMonday)

  private lateinit var previousLocale: Locale

  @Before
  fun setUp() {
    previousLocale = Locale.getDefault()
    // Weeks start on Monday in France, so the week of `now` starts on the 21st.
    Locale.setDefault(Locale.FRANCE)
  }

  @After
  fun tearDown() {
    Locale.setDefault(previousLocale)
  }

  private fun entryAt(iso: String) = Entry(timestampEpochMilli = Instant.parse(iso).toEpochMilli())

  @Test
  fun computeHomeStats_returnsEmptyStats_whenNoEntries() {
    assertEquals(
        HomeStats(
            totalEntries = 0,
            entriesThisWeek = 0,
            entriesThisMonth = 0,
            daysSinceLastSwim = null,
            averageEntriesPerWeek = null,
            favoriteDayOfWeek = null,
        ),
        computeHomeStats(emptyList(), now, zone),
    )
  }

  @Test
  fun computeHomeStats_computesEveryStat_whenEntriesSpanWeeksAndMonths() {
    assertEquals(
        HomeStats(
            totalEntries = 4,
            // The week starts Monday the 21st at midnight, which is included; Sunday is not.
            entriesThisWeek = 2,
            // Everything except the 31st of August.
            entriesThisMonth = 3,
            daysSinceLastSwim = 0,
            // First entry on 31 August: 3 full weeks elapsed, plus the current one = 4 weeks.
            averageEntriesPerWeek = 1.0,
            // Two Mondays, one Wednesday, one Sunday.
            favoriteDayOfWeek = DayOfWeek.MONDAY,
            currentStreakWeeks = 2,
            longestStreakWeeks = 2,
            bestWeekEntries = 2,
            weeklyCounts = listOf(0, 0, 0, 0, 1, 0, 1, 2),
        ),
        computeHomeStats(allEntries, now, zone),
    )
  }

  @Test
  fun computeHomeStats_startsTheWeekOnMonday_evenWithAUsLocale() {
    Locale.setDefault(Locale.US)

    // Sunday the 20th belongs to the previous week, as in History: only Monday and Wednesday count.
    assertEquals(2, computeHomeStats(allEntries, now, zone).entriesThisWeek)
  }

  @Test
  fun computeHomeStats_countsStreaksInMondayWeeks_evenWithAUsLocale() {
    Locale.setDefault(Locale.US)

    val stats = computeHomeStats(allEntries, now, zone)

    assertEquals(2, stats.currentStreakWeeks)
    assertEquals(listOf(0, 0, 0, 0, 1, 0, 1, 2), stats.weeklyCounts)
  }

  @Test
  fun computeHomeStats_countsWholeDaysSinceLastSwim_whenLastEntryIsOlder() {
    val stats = computeHomeStats(listOf(entryAt("2026-09-18T20:00:00Z")), now, zone)

    assertEquals(5L, stats.daysSinceLastSwim)
  }

  @Test
  fun computeHomeStats_countsCalendarDaysInTheGivenZone() {
    // 23:30 UTC on the 22nd is already the 23rd in Zurich (UTC+2 in September).
    val stats =
        computeHomeStats(
            listOf(entryAt("2026-09-22T23:30:00Z")),
            now,
            ZoneId.of("Europe/Zurich"),
        )

    assertEquals(0L, stats.daysSinceLastSwim)
  }

  @Test
  fun computeHomeStats_computesStreaksBestWeekAndWeeklyCounts() {
    val stats = computeHomeStats(allEntries, now, zone)

    // Weeks of 31 Aug, 14 Sep (Sunday the 20th) and 21 Sep have entries; 7 Sep is empty.
    assertEquals(2, stats.currentStreakWeeks)
    assertEquals(2, stats.longestStreakWeeks)
    assertEquals(2, stats.bestWeekEntries)
    assertEquals(listOf(0, 0, 0, 0, 1, 0, 1, 2), stats.weeklyCounts)
  }

  @Test
  fun computeHomeStats_keepsStreakAlive_whenOnlyThisWeekIsEmpty() {
    val stats =
        computeHomeStats(
            listOf(entryAt("2026-09-15T10:00:00Z"), entryAt("2026-09-08T10:00:00Z")),
            now,
            zone,
        )

    assertEquals(2, stats.currentStreakWeeks)
  }

  @Test
  fun computeHomeStats_resetsCurrentStreak_whenAWeekIsMissed() {
    val stats =
        computeHomeStats(
            listOf(entryAt("2026-09-01T10:00:00Z"), entryAt("2026-09-08T10:00:00Z")),
            now,
            zone,
        )

    assertEquals(0, stats.currentStreakWeeks)
    assertEquals(2, stats.longestStreakWeeks)
  }
}
