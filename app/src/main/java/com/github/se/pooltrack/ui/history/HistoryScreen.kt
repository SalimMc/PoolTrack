package com.github.se.pooltrack.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.entry.timestamp
import com.github.se.pooltrack.ui.history.heatmap.HistoryOverview
import com.github.se.pooltrack.ui.history.heatmap.entryCountsByDay
import com.github.se.pooltrack.ui.navigation.BottomNavigationMenu
import com.github.se.pooltrack.ui.navigation.NavigationActions
import com.github.se.pooltrack.ui.navigation.Screen
import com.github.se.pooltrack.ui.navigation.Tab
import com.github.se.pooltrack.ui.navigation.TopNavigationMenu
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object HistoryScreenTestTags {
  const val EMPTY_MESSAGE = "HistoryScreenEmptyMessage"
  const val ENTRY_LIST = "HistoryScreenEntryList"
  const val WEEK_HEADER = "HistoryScreenWeekHeader"
  const val SWIPE_HINT = "HistoryScreenSwipeHint"
  const val CONFIRM_DELETE_BUTTON = "HistoryScreenConfirmDeleteButton"
  const val CANCEL_DELETE_BUTTON = "HistoryScreenCancelDeleteButton"
  const val SWIPE_HINT_DISMISS = "HistoryScreenSwipeHintDismiss"
  const val ENTRY_ITEM = "HistoryScreenEntryItem"
  const val ADD_PAST_ENTRY_BUTTON = "HistoryScreenAddPastEntryButton"
  const val UNDO_ADD_ACTION = "Undo"
  const val LINK_BANNER = "HistoryScreenLinkBanner"
  const val LINK_BUTTON = "HistoryScreenLinkButton"
}

private val ZONE = ZoneId.systemDefault()

/** History weeks always start on Monday, whatever the phone's locale says. */
internal val HISTORY_FIRST_DAY_OF_WEEK = DayOfWeek.MONDAY

// Built fresh on every call rather than cached as a val, so a locale change while the app is
// running (without a process restart) is picked up instead of baked in at class-init time.
private fun entryTimeFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZONE)

/** HistoryScreen lists every confirmed pool entry, grouped by day, most recent first. */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = viewModel(),
    navigationActions: NavigationActions? = null,
) {
  val entries by viewModel.entries.collectAsState()
  var showAddSheet by remember { mutableStateOf(false) }
  var entryPendingDeletion by remember { mutableStateOf<Entry?>(null) }
  var highlightedDate by remember { mutableStateOf<LocalDate?>(null) }
  // Remembered across openings, so adding several entries from the same day takes one tap each.
  var lastAddedDate by remember { mutableStateOf(LocalDate.now(ZONE)) }
  var lastAddedTime by remember { mutableStateOf(LocalTime.of(12, 0)) }
  val addResult by viewModel.addPastEntryResult.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val linkSuggestions by viewModel.linkSuggestions.collectAsState()
  val showSwipeHint by viewModel.showSwipeHint.collectAsState()
  val listState = rememberLazyListState()

  // On success the sheet closes and an Undo snackbar appears. The snackbar is launched in its own
  // scope: clearing the result below would otherwise cancel this effect, and the snackbar with it.
  LaunchedEffect(addResult) {
    val added = addResult as? AddPastEntryResult.Added ?: return@LaunchedEffect
    showAddSheet = false
    viewModel.onAddPastEntryResultHandled()
    scope.launch {
      snackbarHostState.currentSnackbarData?.dismiss()
      val outcome =
          snackbarHostState.showSnackbar(
              message = "Entry added",
              actionLabel = HistoryScreenTestTags.UNDO_ADD_ACTION,
              withDismissAction = true,
          )
      if (outcome == SnackbarResult.ActionPerformed) viewModel.onDeleteEntry(added.entry)
    }
  }

  // Deleting is confirmed first (see the dialog below), and a snackbar still offers an Undo.
  val onDeleteEntry: (Entry) -> Unit = { entry ->
    viewModel.onDeleteEntry(entry)
    viewModel.onSwipeHintDismissed()
    scope.launch {
      snackbarHostState.currentSnackbarData?.dismiss()
      val outcome =
          snackbarHostState.showSnackbar(
              message = "Entry deleted",
              actionLabel = HistoryScreenTestTags.UNDO_ADD_ACTION,
              withDismissAction = true,
          )
      if (outcome == SnackbarResult.ActionPerformed) viewModel.onRestoreEntry(entry)
    }
  }

  if (showAddSheet) {
    AddPastEntrySheet(
        initialDate = lastAddedDate,
        initialTime = lastAddedTime,
        error = (addResult as? AddPastEntryResult.Refused)?.error,
        onInputChanged = viewModel::onAddPastEntryResultHandled,
        onAdd = { date, time ->
          lastAddedDate = date
          lastAddedTime = time
          viewModel.onAddPastEntry(date.atTime(time).atZone(ZONE).toInstant())
        },
        onDismiss = {
          showAddSheet = false
          viewModel.onAddPastEntryResultHandled()
        },
    )
  }

  entryPendingDeletion?.let { entry ->
    AlertDialog(
        onDismissRequest = { entryPendingDeletion = null },
        title = { Text("Delete this visit?") },
        text = { Text("${deletionSummary(entry)} You can undo it right after.") },
        confirmButton = {
          TextButton(
              onClick = {
                onDeleteEntry(entry)
                entryPendingDeletion = null
              },
              modifier = Modifier.testTag(HistoryScreenTestTags.CONFIRM_DELETE_BUTTON),
          ) {
            Text("Delete")
          }
        },
        dismissButton = {
          TextButton(
              onClick = { entryPendingDeletion = null },
              modifier = Modifier.testTag(HistoryScreenTestTags.CANCEL_DELETE_BUTTON),
          ) {
            Text("Cancel")
          }
        },
    )
  }

  Scaffold(
      topBar = { TopNavigationMenu(Screen.History) },
      snackbarHost = { SnackbarHost(snackbarHostState) },
      floatingActionButton = {
        ExtendedFloatingActionButton(
            onClick = { showAddSheet = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add past entry") },
            expanded = listState.firstVisibleItemIndex == 0,
            modifier = Modifier.testTag(HistoryScreenTestTags.ADD_PAST_ENTRY_BUTTON),
        )
      },
      bottomBar = {
        BottomNavigationMenu(
            selectedTab = Tab.History,
            onTabSelected = { tab -> navigationActions?.navigateTo(tab.destination) },
        )
      },
  ) { paddingValues ->
    if (entries.isEmpty()) {
      Column(
          modifier = Modifier.fillMaxSize().padding(paddingValues).padding(32.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(
            imageVector = Icons.Outlined.DateRange,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "No entries yet",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp).testTag(HistoryScreenTestTags.EMPTY_MESSAGE),
        )
        Text(
            text = "Confirmed pool visits will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
      }
    } else {
      val today = LocalDate.now(ZONE)
      val firstDayOfWeek = HISTORY_FIRST_DAY_OF_WEEK
      val countsByDay = remember(entries) { entryCountsByDay(entries, ZONE) }
      val rows =
          remember(entries, firstDayOfWeek) { buildHistoryRows(entries, firstDayOfWeek, ZONE) }
      val headerClearance = with(LocalDensity.current) { STICKY_HEADER_CLEARANCE.roundToPx() }
      // The overview, then the link banner and the swipe tip when they show, come before the rows.
      val leadingItems =
          1 + (if (linkSuggestions.isEmpty()) 0 else 1) + (if (showSwipeHint) 1 else 0)

      // A picked day stays highlighted for a moment, so the eye can find its row.
      LaunchedEffect(highlightedDate) {
        if (highlightedDate != null) {
          delay(HIGHLIGHT_MILLIS)
          highlightedDate = null
        }
      }

      val onDayPicked: (LocalDate) -> Unit = { day ->
        highlightedDate = day
        val index = rows.indexOfDay(day)
        scope.launch {
          if (index != null) {
            listState.animateScrollToItem(leadingItems + index, scrollOffset = -headerClearance)
          } else {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar("No visit on ${dayLabel(day, today)}")
          }
        }
      }

      LazyColumn(
          state = listState,
          modifier =
              Modifier.fillMaxSize()
                  .padding(paddingValues)
                  .testTag(HistoryScreenTestTags.ENTRY_LIST),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
      ) {
        item(key = "overview") {
          HistoryOverview(
              countsByDay = countsByDay,
              today = today,
              firstDayOfWeek = firstDayOfWeek,
              selectedDate = highlightedDate,
              onDayClick = onDayPicked,
              modifier = Modifier.padding(vertical = 4.dp),
          )
        }
        if (showSwipeHint) {
          item(key = "swipe-hint") {
            SwipeHint(
                onDismiss = viewModel::onSwipeHintDismissed,
                modifier = Modifier.padding(vertical = 4.dp),
            )
          }
        }
        if (linkSuggestions.isNotEmpty()) {
          item(key = "link-banner") {
            LinkBanner(
                count = linkSuggestions.size,
                onLink = {
                  val links = linkSuggestions
                  viewModel.onLinkEntries(links)
                  scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val outcome =
                        snackbarHostState.showSnackbar(
                            message =
                                if (links.size == 1) "1 entry linked"
                                else "${links.size} entries linked",
                            actionLabel = HistoryScreenTestTags.UNDO_ADD_ACTION,
                            withDismissAction = true,
                        )
                    if (outcome == SnackbarResult.ActionPerformed) viewModel.onUnlinkEntries(links)
                  }
                },
                modifier = Modifier.padding(vertical = 4.dp),
            )
          }
        }
        rows.forEach { row ->
          when (row) {
            is HistoryRow.Week ->
                stickyHeader(key = row.key) {
                  WeekHeader(
                      label = weekLabel(row.start, today, firstDayOfWeek),
                      visits = row.visits,
                      meters = row.meters,
                      loggedVisits = row.loggedVisits,
                  )
                }
            is HistoryRow.Item ->
                item(key = row.key) {
                  SwipeableEntryRow(
                      day = row.day,
                      isToday = row.day == today,
                      texts =
                          entryRowTexts(
                              row.entry,
                              entryTimeFormatter().format(row.entry.timestamp),
                          ),
                      highlighted = row.day == highlightedDate,
                      onClick = {
                        navigationActions?.navigateToEntryDetails(row.entry.timestampEpochMilli)
                      },
                      onDeleteRequested = { entryPendingDeletion = row.entry },
                  )
                }
          }
        }
      }
    }
  }
}

/** A one-time tip that visits can be swiped away, shown until it is dismissed. */
@Composable
private fun SwipeHint(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  Card(modifier = modifier.fillMaxWidth().testTag(HistoryScreenTestTags.SWIPE_HINT)) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
          text = "Tip: swipe a visit to the left to delete it.",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f).padding(vertical = 12.dp),
      )
      TextButton(
          onClick = onDismiss,
          modifier = Modifier.testTag(HistoryScreenTestTags.SWIPE_HINT_DISMISS),
      ) {
        Text("Got it")
      }
    }
  }
}

/** Offers to attach entries that belong to no subscription to the one that covers them. */
@Composable
private fun LinkBanner(count: Int, onLink: () -> Unit, modifier: Modifier = Modifier) {
  Card(modifier = modifier.fillMaxWidth().testTag(HistoryScreenTestTags.LINK_BANNER)) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
          text =
              if (count == 1) "1 entry isn't linked to a subscription"
              else "$count entries aren't linked to a subscription",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f).padding(vertical = 12.dp),
      )
      TextButton(
          onClick = onLink,
          modifier = Modifier.testTag(HistoryScreenTestTags.LINK_BUTTON),
      ) {
        Text("Link")
      }
    }
  }
}

/** "Today", "Yesterday", or a weekday/date, omitting the year unless [day] isn't this year. */
internal fun dayLabel(day: LocalDate, today: LocalDate): String =
    when (day) {
      today -> "Today"
      today.minusDays(1) -> "Yesterday"
      else -> {
        val pattern = if (day.year == today.year) "EEEE, MMM d" else "EEEE, MMM d, yyyy"
        day.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
      }
    }

/** How long a day picked in the calendar keeps its row tinted. */
private const val HIGHLIGHT_MILLIS = 2_500L

/** How far below the top a scrolled-to row stops, so the sticky week header doesn't cover it. */
private val STICKY_HEADER_CLEARANCE = 56.dp

/** "The visit on Wednesday, Oct 7 at 12:00 PM will be removed." */
internal fun deletionSummary(entry: Entry): String {
  val day = entry.timestamp.atZone(ZONE).toLocalDate()
  val time = entryTimeFormatter().format(entry.timestamp)
  return "The visit on ${dayLabel(day, LocalDate.now(ZONE))} at $time will be removed."
}
