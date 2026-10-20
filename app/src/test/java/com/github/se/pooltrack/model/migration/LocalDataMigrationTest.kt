package com.github.se.pooltrack.model.migration

import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.utils.FakeDocumentStore
import com.github.se.pooltrack.utils.FakeEntryRepository
import com.github.se.pooltrack.utils.FakePdfFiles
import com.github.se.pooltrack.utils.FakeSubscriptionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataMigrationTest {

  private val pass =
      Subscription(
          id = "pass-a",
          uri = "content://docs/a.pdf",
          displayName = "a.pdf",
          addedAtEpochMilli = 1_000L,
          price = 60.0,
      )
  private val withoutPdf = pass.copy(id = "pass-b", uri = "", addedAtEpochMilli = 2_000L)
  private val entry = Entry(timestampEpochMilli = 5_000L, subscriptionId = "pass-a")

  private val store = FakeDocumentStore()
  private val pdfFiles = FakePdfFiles()
  private var done = false

  private fun migration(
      entries: List<Entry> = listOf(entry),
      subscriptions: List<Subscription> = listOf(pass, withoutPdf),
      activeId: String? = "pass-a",
  ) =
      LocalDataMigration(
          legacyEntries = FakeEntryRepository(entries),
          legacySubscriptions = FakeSubscriptionRepository(subscriptions, activeId),
          store = store,
          pdfFiles = pdfFiles,
          isDone = { done },
          markDone = { done = true },
      )

  @Test
  fun migrateOnce_uploadsEntriesSubscriptionsAndTheActiveOne() = runTest {
    assertTrue(migration().migrateOnce())

    assertEquals(listOf("5000_pass-a"), store.ids("entries"))
    assertEquals(listOf("pass-a", "pass-b"), store.ids("subscriptions").sorted())
    assertEquals(
        mapOf("activeSubscriptionId" to "pass-a"),
        store.document("settings", "state"),
    )
    assertTrue(done)
  }

  @Test
  fun migrateOnce_keepsThePdfsOnTheDevice_notInTheAccountData() = runTest {
    migration().migrateOnce()

    assertEquals(mapOf("pass-a" to "content://docs/a.pdf"), pdfFiles.uris.first())
    assertFalse(store.document("subscriptions", "pass-a")!!.containsKey("uri"))
  }

  @Test
  fun migrateOnce_doesNothing_whenItAlreadyRan() = runTest {
    done = true

    assertFalse(migration().migrateOnce())

    assertTrue(store.ids("entries").isEmpty())
    assertTrue(store.ids("subscriptions").isEmpty())
  }

  @Test
  fun migrateOnce_runsOnlyOnce_whenCalledTwice() = runTest {
    val migration = migration()

    assertTrue(migration.migrateOnce())
    assertFalse(migration.migrateOnce())
  }

  @Test
  fun migrateOnce_writesNoActiveSubscription_whenNoneWasActive() = runTest {
    migration(activeId = null).migrateOnce()

    assertNull(store.document("settings", "state"))
  }

  @Test
  fun migrateOnce_isNotMarkedDone_whenUploadingFails() = runTest {
    store.signedIn = false

    val failure = runCatching { migration().migrateOnce() }

    assertTrue(failure.isFailure)
    assertFalse(done)
  }
}
