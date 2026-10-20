package com.github.se.pooltrack.ui.subscription

import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.utils.FakeEntryRepository
import com.github.se.pooltrack.utils.FakeSubscriptionRepository
import com.github.se.pooltrack.utils.MainDispatcherRule
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SubscriptionViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val older =
      Subscription(id = "old", uri = "content://old", displayName = "Old", addedAtEpochMilli = 1L)
  private val newer =
      Subscription(id = "new", uri = "content://new", displayName = "New", addedAtEpochMilli = 2L)

  @Test
  fun subscriptions_listsEverySubscriptionMostRecentFirst() {
    val viewModel =
        SubscriptionViewModel(
            FakeSubscriptionRepository(listOf(older, newer)),
            FakeEntryRepository(),
        )

    assertEquals(listOf(newer, older), viewModel.subscriptions.value)
  }

  @Test
  fun subscriptions_areSortedByExpirationFarthestFirst() {
    val soon = older.copy(id = "soon", expiresAtEpochMilli = 100L)
    val far = newer.copy(id = "far", expiresAtEpochMilli = 200L)
    val viewModel =
        SubscriptionViewModel(FakeSubscriptionRepository(listOf(far, soon)), FakeEntryRepository())

    assertEquals(listOf(far, soon), viewModel.subscriptions.value)
  }

  @Test
  fun activeSubscription_isTheActiveOne_whenOneIsActive() {
    val viewModel =
        SubscriptionViewModel(
            FakeSubscriptionRepository(listOf(older, newer), "old"),
            FakeEntryRepository(),
        )

    assertEquals(older, viewModel.activeSubscription.value)
  }

  @Test
  fun lastEntryTimestamp_isMostRecentEntry_whenEntriesRecorded() {
    val entries =
        FakeEntryRepository(
            listOf(Entry(timestampEpochMilli = 5_000L), Entry(timestampEpochMilli = 9_000L))
        )
    val viewModel = SubscriptionViewModel(FakeSubscriptionRepository(), entries)

    assertEquals(Instant.ofEpochMilli(9_000L), viewModel.lastEntryTimestamp.value)
  }

  @Test
  fun lastEntryTimestamp_isNull_whenNoEntriesRecorded() {
    val viewModel = SubscriptionViewModel(FakeSubscriptionRepository(), FakeEntryRepository())

    assertNull(viewModel.lastEntryTimestamp.value)
  }

  @Test
  fun entryCountsBySubscriptionId_countsTaggedEntries_ignoringUntaggedOnes() {
    val entries =
        FakeEntryRepository(
            listOf(
                Entry(timestampEpochMilli = 1L, subscriptionId = "old"),
                Entry(timestampEpochMilli = 2L, subscriptionId = "new"),
                Entry(timestampEpochMilli = 3L, subscriptionId = "new"),
                Entry(timestampEpochMilli = 4L, subscriptionId = null),
            )
        )
    val viewModel = SubscriptionViewModel(FakeSubscriptionRepository(), entries)

    assertEquals(mapOf("old" to 1, "new" to 2), viewModel.entryCountsBySubscriptionId.value)
  }

  @Test
  fun onSubscriptionPicked_addsSubscriptionWithEveryDetail_andMakesItActive() {
    val subscriptions = FakeSubscriptionRepository(listOf(older), "old")
    val viewModel = SubscriptionViewModel(subscriptions, FakeEntryRepository())

    viewModel.onSubscriptionPicked(
        uri = "content://picked",
        expiresAtEpochMilli = 42L,
        maxEntries = 10,
        price = 60.0,
    )

    val added = subscriptions.storedSubscriptions.last()
    assertEquals("content://picked", added.uri)
    assertEquals(42L, added.expiresAtEpochMilli)
    assertEquals(10, added.maxEntries)
    assertEquals(60.0, added.price!!, 0.0)
    assertEquals(added, viewModel.activeSubscription.value)
    assertEquals(2, viewModel.subscriptions.value.size)
  }

  @Test
  fun onSubscriptionPicked_leavesOptionalDetailsEmpty_whenNotGiven() {
    val subscriptions = FakeSubscriptionRepository()
    val viewModel = SubscriptionViewModel(subscriptions, FakeEntryRepository())

    viewModel.onSubscriptionPicked(uri = "content://picked")

    val added = subscriptions.storedSubscriptions.single()
    assertNull(added.expiresAtEpochMilli)
    assertNull(added.maxEntries)
    assertNull(added.price)
  }

  @Test
  fun onSetActive_makesThatSubscriptionActive() {
    val subscriptions = FakeSubscriptionRepository(listOf(older, newer), "new")
    val viewModel = SubscriptionViewModel(subscriptions, FakeEntryRepository())

    viewModel.onSetActive("old")

    assertEquals("old", subscriptions.storedActiveId)
    assertEquals(older, viewModel.activeSubscription.value)
  }

  @Test
  fun onAttachPdf_givesTheSubscriptionItsPdf_andLeavesTheOthersAlone() {
    val restored = older.copy(uri = "")
    val subscriptions = FakeSubscriptionRepository(listOf(restored, newer), "new")
    val viewModel = SubscriptionViewModel(subscriptions, FakeEntryRepository())

    viewModel.onAttachPdf("old", "content://docs/pass.pdf")

    assertEquals(
        listOf(restored.copy(uri = "content://docs/pass.pdf"), newer),
        subscriptions.storedSubscriptions,
    )
    assertEquals("new", subscriptions.storedActiveId)
  }

  @Test
  fun onDeleteSubscription_removesIt_andLeavesNoneActive_whenItWasActive() {
    val subscriptions = FakeSubscriptionRepository(listOf(older, newer), "new")
    val viewModel = SubscriptionViewModel(subscriptions, FakeEntryRepository())

    viewModel.onDeleteSubscription("new")

    assertEquals(listOf(older), viewModel.subscriptions.value)
    assertNull(viewModel.activeSubscription.value)
  }

  @Test
  fun onDeleteSubscription_keepsActiveSubscription_whenAnotherIsDeleted() {
    val subscriptions = FakeSubscriptionRepository(listOf(older, newer), "new")
    val viewModel = SubscriptionViewModel(subscriptions, FakeEntryRepository())

    viewModel.onDeleteSubscription("old")

    assertEquals(listOf(newer), viewModel.subscriptions.value)
    assertEquals(newer, viewModel.activeSubscription.value)
  }

  @Test
  fun onEditSubscription_updatesDetails_andTrimsName() {
    val subscriptions = FakeSubscriptionRepository(listOf(older, newer), "new")
    val viewModel = SubscriptionViewModel(subscriptions, FakeEntryRepository())

    viewModel.onEditSubscription("old", "  Renamed  ", 5_000L, null, 20.0)

    val expected =
        older
            .copy(displayName = "Renamed", expiresAtEpochMilli = 5_000L, maxEntries = null)
            .copy(price = 20.0)
    assertEquals(listOf(newer, expected), viewModel.subscriptions.value)
    assertEquals("new", viewModel.activeSubscription.value?.id)
  }

  @Test
  fun onEditSubscription_switchingType_clearsTheOtherField() {
    val dated = older.copy(expiresAtEpochMilli = 5_000L)
    val viewModel =
        SubscriptionViewModel(FakeSubscriptionRepository(listOf(dated)), FakeEntryRepository())

    viewModel.onEditSubscription("old", "Old", null, 10, null)

    val edited = viewModel.subscriptions.value.single()
    assertNull(edited.expiresAtEpochMilli)
    assertEquals(10, edited.maxEntries)
  }

  @Test
  fun onEditSubscription_keepsEntries_whenMaxEntriesDropsBelowUsedCount() {
    val limited = older.copy(maxEntries = 10)
    val entries =
        FakeEntryRepository(
            List(5) { Entry(timestampEpochMilli = it.toLong(), subscriptionId = "old") }
        )
    val viewModel = SubscriptionViewModel(FakeSubscriptionRepository(listOf(limited)), entries)

    viewModel.onEditSubscription("old", "Old", null, 2, null)

    assertEquals(2, viewModel.subscriptions.value.single().maxEntries)
    assertEquals(5, entries.storedEntries.size)
    assertEquals(mapOf("old" to 5), viewModel.entryCountsBySubscriptionId.value)
  }

  @Test
  fun onEditSubscription_ignoresInvalidInput() {
    val repository = FakeSubscriptionRepository(listOf(older))
    val viewModel = SubscriptionViewModel(repository, FakeEntryRepository())

    viewModel.onEditSubscription("old", "   ", null, null, null)
    viewModel.onEditSubscription("old", "X", null, 0, null)
    viewModel.onEditSubscription("old", "X", 5_000L, 3, null)

    assertEquals(listOf(older), viewModel.subscriptions.value)
  }

  @Test
  fun onScannerAccepted_recordsOneEntryTaggedWithTheActiveSubscription() {
    val entries = FakeEntryRepository()
    val viewModel =
        SubscriptionViewModel(FakeSubscriptionRepository(listOf(older, newer), "new"), entries)
    val before = Instant.now().toEpochMilli()

    viewModel.onScannerAccepted()

    val after = Instant.now().toEpochMilli()
    val entry = entries.storedEntries.single()
    assertEquals("new", entry.subscriptionId)
    assertTrue(entry.awaitingDistance)
    assertNull(entry.swimDistanceMeters)
    assertTrue(entry.timestampEpochMilli in before..after)
    assertEquals(
        Instant.ofEpochMilli(entry.timestampEpochMilli),
        viewModel.lastEntryTimestamp.value,
    )
  }

  @Test
  fun onScannerAccepted_recordsUntaggedEntry_whenNoSubscriptionIsActive() {
    val entries = FakeEntryRepository()
    val viewModel = SubscriptionViewModel(FakeSubscriptionRepository(listOf(older)), entries)

    viewModel.onScannerAccepted()

    assertNull(entries.storedEntries.single().subscriptionId)
  }
}
