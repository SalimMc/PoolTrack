package com.github.se.pooltrack.model.entry

import com.github.se.pooltrack.utils.FakeDocumentStore
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EntryRepositoryFirestoreTest {

  private val store = FakeDocumentStore()
  private val repository = EntryRepositoryFirestore(store)

  private val oldest = Entry(timestampEpochMilli = 1_000L, subscriptionId = "pass-a")
  private val middle = Entry(timestampEpochMilli = 2_000L, subscriptionId = null)
  private val newest = Entry(timestampEpochMilli = 3_000L, subscriptionId = "pass-b")

  @Test
  fun getEntries_isEmpty_whenNothingWasRecorded() = runTest {
    assertEquals(emptyList<Entry>(), repository.getEntries().first())
    assertNull(repository.getLastEntryTimestamp().first())
  }

  @Test
  fun addEntry_storesTheEntryUnderADerivedDocumentId() = runTest {
    repository.addEntry(oldest)
    repository.addEntry(middle)

    assertEquals(listOf("1000_pass-a", "2000_none"), store.ids("entries"))
    assertEquals(
        mapOf(
            "timestampEpochMilli" to 1_000L,
            "subscriptionId" to "pass-a",
            "swimDurationMillis" to null,
            "swimDistanceMeters" to null,
            "awaitingDistance" to false,
        ),
        store.document("entries", "1000_pass-a"),
    )
  }

  @Test
  fun getEntries_listsMostRecentFirst() = runTest {
    repository.addEntry(middle)
    repository.addEntry(newest)
    repository.addEntry(oldest)

    assertEquals(listOf(newest, middle, oldest), repository.getEntries().first())
    assertEquals(Instant.ofEpochMilli(3_000L), repository.getLastEntryTimestamp().first())
  }

  @Test
  fun getEntries_keepsWhatTheEntryWasStoredWith() = runTest {
    val entry =
        Entry(
            timestampEpochMilli = 5_000L,
            subscriptionId = "pass-a",
            swimDurationMillis = 60_000L,
            swimDistanceMeters = 750,
            awaitingDistance = true,
        )
    repository.addEntry(entry)

    assertEquals(listOf(entry), repository.getEntries().first())
  }

  @Test
  fun getEntries_skipsDocumentsWithoutATimestamp() = runTest {
    store.set("entries", "broken", mapOf("subscriptionId" to "pass-a"))
    repository.addEntry(oldest)

    assertEquals(listOf(oldest), repository.getEntries().first())
  }

  @Test
  fun deleteEntry_removesOnlyThatEntry() = runTest {
    repository.addEntry(oldest)
    repository.addEntry(newest)

    repository.deleteEntry(oldest)

    assertEquals(listOf(newest), repository.getEntries().first())
  }

  @Test
  fun deleteEntry_updatesTheLastEntryTimestamp() = runTest {
    repository.addEntry(oldest)
    repository.addEntry(newest)

    repository.deleteEntry(newest)

    assertEquals(Instant.ofEpochMilli(1_000L), repository.getLastEntryTimestamp().first())
  }

  @Test
  fun recordSwimDistance_storesTheDistance_andStopsAwaitingIt() = runTest {
    val awaiting = oldest.copy(awaitingDistance = true)
    repository.addEntry(awaiting)

    repository.recordSwimDistance(awaiting, 1_200)

    assertEquals(
        listOf(oldest.copy(swimDistanceMeters = 1_200, awaitingDistance = false)),
        repository.getEntries().first(),
    )
  }

  @Test
  fun recordSwimDistance_doesNothing_whenTheEntryWasDeleted() = runTest {
    repository.recordSwimDistance(oldest, 1_200)

    assertEquals(emptyList<Entry>(), repository.getEntries().first())
  }

  @Test
  fun recordSwimDuration_storesTheDuration() = runTest {
    repository.addEntry(oldest)

    repository.recordSwimDuration(oldest, 90_000L)

    assertEquals(
        listOf(oldest.copy(swimDurationMillis = 90_000L)),
        repository.getEntries().first(),
    )
  }

  @Test
  fun setSubscription_movesTheEntryToAnotherSubscription() = runTest {
    repository.addEntry(oldest)

    repository.setSubscription(oldest, "pass-b")

    assertEquals(
        listOf(oldest.copy(subscriptionId = "pass-b")),
        repository.getEntries().first(),
    )
    assertEquals(listOf("1000_pass-b"), store.ids("entries"))
  }

  @Test
  fun writes_fail_whenNobodyIsSignedIn() = runTest {
    store.signedIn = false

    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking { repository.addEntry(oldest) }
    }
  }

  @Test
  fun documents_roundTrip_throughTheirStoredFields() {
    val entry =
        Entry(
            timestampEpochMilli = 9L,
            subscriptionId = "x",
            swimDurationMillis = 5L,
            swimDistanceMeters = 100,
            awaitingDistance = true,
        )

    assertEquals(entry, entryFromDocument(entry.toDocument()))
    assertEquals("9_x", entry.documentId)
    assertEquals("9_none", entry.copy(subscriptionId = null).documentId)
  }
}
