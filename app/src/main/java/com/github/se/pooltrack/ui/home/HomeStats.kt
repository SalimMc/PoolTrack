package com.github.se.pooltrack.ui.home

import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.entry.timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields

/**
 * Summary statistics derived from the confirmed entries.
 *
 * @property totalEntries How many entries were ever confirmed.
 * @property entriesThisWeek How many entries fall within the current calendar week (Monday to
 *   Sunday).
 * @property entriesThisMonth How many entries fall within the current calendar month.
 * @property daysSinceLastSwim Whole days since the most recent entry, or `null` if there is none.
 * @property averageEntriesPerWeek Entries per calendar week since the first one, or `null` if there
 *   is none.
 * @property favoriteDayOfWeek The day of the week with the most entries, or `null` if there is
 *   none.
 * @property currentStreakWeeks Consecutive calendar weeks with at least one entry, ending with the
 *   current week (or the previous one if nothing was logged yet this week).
 * @property longestStreakWeeks The longest run of consecutive weeks with at least one entry.
 * @property bestWeekEntries The most entries logged in a single calendar week.
 * @property weeklyCounts Entries per calendar week for the last [RECENT_WEEKS] weeks, oldest first
 *   (the last item is the current week).
 * @property weekStarts The Monday each of the [weeklyCounts] weeks starts on, in the same order.
 */
data class HomeStats(
    val totalEntries: Int,
    val entriesThisWeek: Int,
    val entriesThisMonth: Int,
    val daysSinceLastSwim: Long?,
    val averageEntriesPerWeek: Double?,
    val favoriteDayOfWeek: DayOfWeek?,
    val currentStreakWeeks: Int = 0,
    val longestStreakWeeks: Int = 0,
    val bestWeekEntries: Int = 0,
    val weeklyCounts: List<Int> = List(RECENT_WEEKS) { 0 },
    val weekStarts: List<LocalDate> = emptyList(),
)

/** How many weeks the [HomeStats.weeklyCounts] chart covers. */
const val RECENT_WEEKS = 8

/** Computes [HomeStats] for [entries] as of [now], using [zone] to resolve calendar days/weeks. */
fun computeHomeStats(
    entries: List<Entry>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): HomeStats {
  val today = now.atZone(zone).toLocalDate()
  // Weeks always start on Monday, like in History, whatever the phone's locale says.
  val weekFields = WeekFields.ISO
  val startOfWeek = today.with(weekFields.dayOfWeek(), 1L)
  val startOfWeekInstant = startOfWeek.atStartOfDay(zone).toInstant()
  val thisMonth = YearMonth.from(today)

  val entriesThisWeek = entries.count { !it.timestamp.isBefore(startOfWeekInstant) }
  val entriesThisMonth = entries.count {
    YearMonth.from(it.timestamp.atZone(zone).toLocalDate()) == thisMonth
  }

  val entryDates = entries.map { it.timestamp.atZone(zone).toLocalDate() }
  val daysSinceLastSwim = entryDates.maxOrNull()?.let { ChronoUnit.DAYS.between(it, today) }
  val averageEntriesPerWeek =
      entryDates.minOrNull()?.let { firstEntry ->
        val weeksElapsed = ChronoUnit.WEEKS.between(firstEntry, today) + 1
        entries.size.toDouble() / weeksElapsed
      }
  val favoriteDayOfWeek =
      entryDates.groupingBy { it.dayOfWeek }.eachCount().maxByOrNull { it.value }?.key

  val entriesPerWeek = entryDates.groupingBy { it.with(weekFields.dayOfWeek(), 1L) }.eachCount()
  val weekStarts = entriesPerWeek.keys.sorted()
  var longestStreak = 0
  var run = 0
  weekStarts.forEachIndexed { i, week ->
    run = if (i > 0 && weekStarts[i - 1].plusWeeks(1) == week) run + 1 else 1
    longestStreak = maxOf(longestStreak, run)
  }
  var streakWeek = if (startOfWeek in entriesPerWeek) startOfWeek else startOfWeek.minusWeeks(1)
  var currentStreak = 0
  while (streakWeek in entriesPerWeek) {
    currentStreak++
    streakWeek = streakWeek.minusWeeks(1)
  }
  val recentWeekStarts = (RECENT_WEEKS - 1 downTo 0).map { startOfWeek.minusWeeks(it.toLong()) }
  val weeklyCounts = recentWeekStarts.map { entriesPerWeek[it] ?: 0 }

  return HomeStats(
      totalEntries = entries.size,
      entriesThisWeek = entriesThisWeek,
      entriesThisMonth = entriesThisMonth,
      daysSinceLastSwim = daysSinceLastSwim,
      averageEntriesPerWeek = averageEntriesPerWeek,
      favoriteDayOfWeek = favoriteDayOfWeek,
      currentStreakWeeks = currentStreak,
      longestStreakWeeks = longestStreak,
      bestWeekEntries = entriesPerWeek.values.maxOrNull() ?: 0,
      weeklyCounts = weeklyCounts,
      weekStarts = recentWeekStarts,
  )
}
