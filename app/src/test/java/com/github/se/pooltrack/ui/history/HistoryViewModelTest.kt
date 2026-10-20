package com.github.se.pooltrack.ui.history

import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.utils.FakeEntryRepository
import com.github.se.pooltrack.utils.FakeSubscriptionRepository
import com.github.se.pooltrack.utils.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class HistoryViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val older = Entry(timestampEpochMilli = 1_000L, subscriptionId = "sub-1")
  private val newer = Entry(timestampEpochMilli = 2_000L, subscriptionId = "sub-1")

  @Test
  fun entries_isEmpty_whenNoEntriesRecorded() {
    val viewModel = HistoryViewModel(FakeEntryRepository(), FakeSubscriptionRepository())

    assertEquals(emptyList<Entry>(), viewModel.entries.value)
  }

  @Test
  fun entries_listsEveryEntryMostRecentFirst_whenEntriesRecorded() {
    val viewModel =
        HistoryViewModel(
            FakeEntryRepository(listOf(older, newer)),
            FakeSubscriptionRepository(),
        )

    assertEquals(listOf(newer, older), viewModel.entries.value)
  }

  @Test
  fun entries_updates_whenRepositoryRecordsAnEntry() = runTest {
    val repository = FakeEntryRepository(listOf(older))
    val viewModel = HistoryViewModel(repository, FakeSubscriptionRepository())

    repository.addEntry(newer)

    assertEquals(listOf(newer, older), viewModel.entries.value)
  }

  @Test
  fun onDeleteEntry_removesOnlyThatEntry() {
    val repository = FakeEntryRepository(listOf(older, newer))
    val viewModel = HistoryViewModel(repository, FakeSubscriptionRepository())

    viewModel.onDeleteEntry(newer)

    assertEquals(listOf(older), repository.storedEntries)
    assertEquals(listOf(older), viewModel.entries.value)
  }

  private val now = Instant.parse("2026-10-06T12:00:00Z")
  private val past = Instant.parse("2025-09-15T15:49:34Z")

  private fun subscription(id: String, addedAt: String, expiresAt: String?) =
      Subscription(
          id = id,
          uri = "content://$id",
          displayName = id,
          addedAtEpochMilli = Instant.parse(addedAt).toEpochMilli(),
          expiresAtEpochMilli = expiresAt?.let { Instant.parse(it).toEpochMilli() },
      )

  @Test
  fun onAddPastEntry_recordsTheEntry_andReportsItAsAdded() {
    val repository = FakeEntryRepository()
    val viewModel = HistoryViewModel(repository, FakeSubscriptionRepository())

    viewModel.onAddPastEntry(past, now)

    val expected = Entry(timestampEpochMilli = past.toEpochMilli(), subscriptionId = null)
    assertEquals(listOf(expected), repository.storedEntries)
    assertEquals(AddPastEntryResult.Added(expected), viewModel.addPastEntryResult.value)
  }

  @Test
  fun onAddPastEntry_linksTheSubscriptionValidAtThatTime() {
    val expired = subscription("old", "2024-09-01T00:00:00Z", "2025-08-31T00:00:00Z")
    val valid = subscription("pass", "2025-09-14T08:39:00Z", "2026-09-14T08:39:00Z")
    val repository = FakeEntryRepository()
    val viewModel =
        HistoryViewModel(
            repository,
            FakeSubscriptionRepository(listOf(expired, valid)),
        )

    viewModel.onAddPastEntry(past, now)

    assertEquals("pass", repository.storedEntries.single().subscriptionId)
  }

  @Test
  fun onAddPastEntry_leavesSubscriptionEmpty_whenEveryoneExpiredBeforeThen() {
    val expired = subscription("expired", "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z")
    val repository = FakeEntryRepository()
    val viewModel =
        HistoryViewModel(
            repository,
            FakeSubscriptionRepository(listOf(expired)),
        )

    viewModel.onAddPastEntry(past, now)

    assertNull(repository.storedEntries.single().subscriptionId)
  }

  @Test
  fun onAddPastEntry_refusesAFutureTime_andRecordsNothing() {
    val repository = FakeEntryRepository()
    val viewModel = HistoryViewModel(repository, FakeSubscriptionRepository())

    viewModel.onAddPastEntry(now.plusSeconds(60), now)

    assertEquals(emptyList<Entry>(), repository.storedEntries)
    assertEquals(
        AddPastEntryResult.Refused(AddPastEntryError.InTheFuture),
        viewModel.addPastEntryResult.value,
    )
  }

  @Test
  fun onAddPastEntry_refusesADuplicateTimestamp() {
    val existing = Entry(timestampEpochMilli = past.toEpochMilli(), subscriptionId = "pass")
    val repository = FakeEntryRepository(listOf(existing))
    val viewModel = HistoryViewModel(repository, FakeSubscriptionRepository())

    viewModel.onAddPastEntry(past, now)

    assertEquals(listOf(existing), repository.storedEntries)
    assertEquals(
        AddPastEntryResult.Refused(AddPastEntryError.AlreadyRecorded),
        viewModel.addPastEntryResult.value,
    )
  }

  @Test
  fun onAddPastEntryResultHandled_clearsTheResult() {
    val viewModel = HistoryViewModel(FakeEntryRepository(), FakeSubscriptionRepository())
    viewModel.onAddPastEntry(past, now)

    viewModel.onAddPastEntryResultHandled()

    assertNull(viewModel.addPastEntryResult.value)
  }

  @Test
  fun subscriptionIdAt_prefersTheMostRecentlyAdded_whenSeveralWereValid() {
    val first = subscription("first", "2025-01-01T00:00:00Z", null)
    val second = subscription("second", "2025-06-01T00:00:00Z", null)

    assertEquals("second", subscriptionIdAt(past, listOf(first, second)))
  }

  @Test
  fun onAddPastEntry_linksASubscriptionAddedToTheAppAfterTheEntry() {
    // Subscriptions are "added" when put in the app, not when bought, so that date says nothing.
    val addedToday = subscription("pass", "2026-10-06T10:00:00Z", "2026-09-14T08:39:00Z")
    val repository = FakeEntryRepository()
    val viewModel =
        HistoryViewModel(
            repository,
            FakeSubscriptionRepository(listOf(addedToday)),
        )

    viewModel.onAddPastEntry(past, now)

    assertEquals("pass", repository.storedEntries.single().subscriptionId)
  }

  @Test
  fun subscriptionIdAt_prefersTheOneExpiringSoonest_overOneThatReplacedIt() {
    val old = subscription("old", "2026-10-01T00:00:00Z", "2026-09-14T00:00:00Z")
    val replacement = subscription("new", "2026-10-02T00:00:00Z", "2027-09-14T00:00:00Z")

    assertEquals("old", subscriptionIdAt(past, listOf(replacement, old)))
  }

  @Test
  fun subscriptionIdAt_prefersADatedSubscription_overOneWithoutExpiry() {
    val dated = subscription("dated", "2026-10-01T00:00:00Z", "2026-09-14T00:00:00Z")
    val open = subscription("open", "2026-10-02T00:00:00Z", null)

    assertEquals("dated", subscriptionIdAt(past, listOf(open, dated)))
  }

  private val unlinked = Entry(timestampEpochMilli = past.toEpochMilli(), subscriptionId = null)
  private val expiredPass = subscription("pass", "2026-10-06T10:00:00Z", "2026-09-14T08:39:00Z")

  @Test
  fun linkSuggestions_listsUnlinkedEntriesThatASubscriptionCovers() {
    val linked =
        Entry(timestampEpochMilli = past.plusSeconds(60).toEpochMilli(), subscriptionId = "pass")
    val viewModel =
        HistoryViewModel(
            FakeEntryRepository(listOf(unlinked, linked)),
            FakeSubscriptionRepository(listOf(expiredPass)),
        )

    assertEquals(listOf(EntryLink(unlinked, "pass")), viewModel.linkSuggestions.value)
  }

  @Test
  fun linkSuggestions_isEmpty_whenNoSubscriptionCoversTheEntry() {
    val viewModel =
        HistoryViewModel(
            FakeEntryRepository(listOf(unlinked)),
            FakeSubscriptionRepository(),
        )

    assertEquals(emptyList<EntryLink>(), viewModel.linkSuggestions.value)
  }

  @Test
  fun onLinkEntries_attachesTheEntriesToTheirSubscription_andClearsTheSuggestions() {
    val repository = FakeEntryRepository(listOf(unlinked))
    val viewModel =
        HistoryViewModel(
            repository,
            FakeSubscriptionRepository(listOf(expiredPass)),
        )

    viewModel.onLinkEntries(viewModel.linkSuggestions.value)

    assertEquals(listOf(unlinked.copy(subscriptionId = "pass")), repository.storedEntries)
    assertEquals(emptyList<EntryLink>(), viewModel.linkSuggestions.value)
  }

  @Test
  fun onUnlinkEntries_takesTheLinkBack() {
    val repository = FakeEntryRepository(listOf(unlinked))
    val viewModel =
        HistoryViewModel(
            repository,
            FakeSubscriptionRepository(listOf(expiredPass)),
        )
    val links = viewModel.linkSuggestions.value
    viewModel.onLinkEntries(links)

    viewModel.onUnlinkEntries(links)

    assertEquals(listOf(unlinked), repository.storedEntries)
  }
}
