package com.github.se.pooltrack.model.backup

import android.content.Context
import com.github.se.pooltrack.model.clearPreferencesDataStore
import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.entry.EntryRepositoryLocal
import com.github.se.pooltrack.model.entry.entryFromBackup
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.model.subscription.SubscriptionRepositoryLocal
import com.github.se.pooltrack.model.subscription.hasPdf
import com.github.se.pooltrack.model.subscription.subscriptionFromBackup
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Bringing the Firestore backup back on a fresh install. */
@RunWith(RobolectricTestRunner::class)
class RestoreFromBackupTest {

  private val currentUid = MutableStateFlow<String?>("user-1")

  private lateinit var appContext: Context
  private lateinit var firebase: FirebaseMocks

  private val entryDoc =
      mapOf<String, Any?>(
          "timestampEpochMilli" to 1_000L,
          "subscriptionId" to "pass-a",
          "swimDurationMillis" to null,
          "swimDistanceMeters" to 750L,
          "awaitingDistance" to true,
      )
  private val passDoc =
      mapOf<String, Any?>(
          "id" to "pass-a",
          "displayName" to "Ticket 2026-2027.PDF",
          "addedAtEpochMilli" to 5_000L,
          "expiresAtEpochMilli" to null,
          "maxEntries" to null,
          "price" to 275.0,
      )

  @Before
  fun setUp() = runTest {
    appContext = RuntimeEnvironment.getApplication()
    clearPreferencesDataStore(
        appContext,
        "com.github.se.pooltrack.model.entry.EntryRepositoryLocalKt",
        "entryDataStore",
    )
    clearPreferencesDataStore(
        appContext,
        "com.github.se.pooltrack.model.subscription.SubscriptionRepositoryLocalKt",
        "subscriptionDataStore",
    )
    firebase = FirebaseMocks(uid = "user-1")
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  // region fetchFromFirestore

  @Test
  fun fetchFromFirestore_returnsTheDocumentFields_whenSignedIn() = runTest {
    firebase.returnDocuments(entryDoc)

    assertEquals(listOf(entryDoc), fetchFromFirestore("entries"))
    verify(exactly = 1) { firebase.usersCollection.document("user-1") }
    verify(exactly = 1) { firebase.userDocument.collection("entries") }
  }

  @Test
  fun fetchFromFirestore_isNull_whenSignedOut() = runTest {
    val signedOut = FirebaseMocks(uid = null)

    assertNull(fetchFromFirestore("entries"))
    verify(exactly = 0) { signedOut.firestore.collection(any()) }
  }

  @Test
  fun fetchFromFirestore_isNull_whenTheReadFails() = runTest {
    firebase.failReads()

    assertNull(fetchFromFirestore("entries"))
  }

  // endregion

  // region parsing

  @Test
  fun entryFromBackup_rebuildsTheEntry_butNeverAwaitsADistance() {
    assertEquals(
        Entry(timestampEpochMilli = 1_000L, subscriptionId = "pass-a", swimDistanceMeters = 750),
        entryFromBackup(entryDoc),
    )
  }

  @Test
  fun entryFromBackup_isNull_withoutATimestamp() {
    assertNull(entryFromBackup(mapOf("subscriptionId" to "pass-a")))
  }

  @Test
  fun subscriptionFromBackup_rebuildsTheSubscription_withoutItsPdf() {
    val subscription = subscriptionFromBackup(passDoc)!!

    assertEquals("pass-a", subscription.id)
    assertEquals("Ticket 2026-2027.PDF", subscription.displayName)
    assertEquals(5_000L, subscription.addedAtEpochMilli)
    assertEquals(275.0, subscription.price)
    assertNull(subscription.expiresAtEpochMilli)
    assertFalse(subscription.hasPdf)
  }

  @Test
  fun subscriptionFromBackup_isNull_withoutAnId() {
    assertNull(subscriptionFromBackup(passDoc - "id"))
  }

  // endregion

  // region entries

  @Test
  fun getEntries_restoresTheBackup_onAFreshInstall() = runTest {
    firebase.returnDocuments(entryDoc)
    val repository = EntryRepositoryLocal(appContext, currentUid)

    val entries = awaitNotEmpty { repository.getEntries().first { it.isNotEmpty() } }

    assertEquals(
        listOf(
            Entry(timestampEpochMilli = 1_000L, subscriptionId = "pass-a", swimDistanceMeters = 750)
        ),
        entries,
    )
  }

  @Test
  fun getEntries_doesNotRestoreAgain_afterTheUserDeletedEverything() = runTest {
    val repository = EntryRepositoryLocal(appContext, currentUid)
    val entry = Entry(timestampEpochMilli = 2_000L)
    repository.addEntry(entry)
    repository.deleteEntry(entry)
    firebase.returnDocuments(entryDoc)

    assertEquals(emptyList<Entry>(), repository.getEntries().first())
    verify(exactly = 0) { firebase.targetCollection.get() }
  }

  @Test
  fun getEntries_staysEmpty_whenTheBackupCannotBeRead() = runTest {
    firebase.failReads()
    val repository = EntryRepositoryLocal(appContext, currentUid)

    assertEquals(emptyList<Entry>(), repository.getEntries().first())
  }

  @Test
  fun getEntries_staysEmpty_whenTheBackupHoldsNothing() = runTest {
    firebase.returnDocuments()
    val repository = EntryRepositoryLocal(appContext, currentUid)

    assertEquals(emptyList<Entry>(), repository.getEntries().first())
  }

  // endregion

  // region subscriptions

  @Test
  fun getSubscriptions_restoresTheBackup_withoutThePdf_onAFreshInstall() = runTest {
    firebase.returnDocuments(passDoc)
    val repository = SubscriptionRepositoryLocal(appContext, currentUid)

    val subscriptions = awaitNotEmpty { repository.getSubscriptions().first { it.isNotEmpty() } }

    assertEquals(listOf("pass-a"), subscriptions.map { it.id })
    assertFalse(subscriptions.single().hasPdf)
  }

  @Test
  fun getActiveSubscription_isTheNewestUnexpiredRestoredOne() = runTest {
    val expired = passDoc + mapOf("id" to "old", "expiresAtEpochMilli" to 1L)
    firebase.returnDocuments(expired, passDoc)
    val repository = SubscriptionRepositoryLocal(appContext, currentUid)

    val active = awaitNotEmpty { repository.getActiveSubscription().first { it != null } }

    assertEquals("pass-a", active?.id)
    assertTrue(repository.getSubscriptions().first().any { it.id == "old" })
  }

  @Test
  fun getSubscriptions_staysEmpty_whenTheBackupHoldsNothing() = runTest {
    firebase.returnDocuments()
    val repository = SubscriptionRepositoryLocal(appContext, currentUid)

    assertEquals(emptyList<Subscription>(), repository.getSubscriptions().first())
  }

  @Test
  fun getSubscriptions_staysEmpty_whenTheBackupCannotBeRead() = runTest {
    firebase.failReads()
    val repository = SubscriptionRepositoryLocal(appContext, currentUid)

    assertEquals(emptyList<Subscription>(), repository.getSubscriptions().first())
  }

  @Test
  fun getActiveSubscription_isNone_whenEveryRestoredOneHasExpired() = runTest {
    firebase.returnDocuments(passDoc + mapOf("expiresAtEpochMilli" to 1L))
    val repository = SubscriptionRepositoryLocal(appContext, currentUid)

    val restored = awaitNotEmpty { repository.getSubscriptions().first { it.isNotEmpty() } }

    assertEquals(listOf("pass-a"), restored.map { it.id })
    assertNull(repository.getActiveSubscription().first())
  }

  // endregion

  /** DataStore writes on its own thread, so wait for them in real time rather than virtual time. */
  private suspend fun <T> awaitNotEmpty(block: suspend () -> T): T =
      withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }
}
