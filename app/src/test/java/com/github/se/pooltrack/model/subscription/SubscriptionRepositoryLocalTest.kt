package com.github.se.pooltrack.model.subscription

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.se.pooltrack.model.ResolverContext
import com.github.se.pooltrack.model.backup.FirebaseMocks
import com.github.se.pooltrack.model.clearPreferencesDataStore
import com.github.se.pooltrack.model.productionPreferencesDataStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SubscriptionRepositoryLocalTest {

  private val pdfUri = "content://docs/pass.pdf"
  private val currentUid = MutableStateFlow<String?>("user-1")

  private lateinit var appContext: Context
  private lateinit var resolver: ContentResolver
  private lateinit var firebase: FirebaseMocks
  private lateinit var repository: SubscriptionRepositoryLocal

  @Before
  fun setUp() = runTest {
    appContext = RuntimeEnvironment.getApplication()
    clearPreferencesDataStore(appContext, FILE_CLASS, PROPERTY)
    resolver = mockk(relaxed = true)
    answerDisplayName("pass.pdf")
    firebase = FirebaseMocks(uid = "user-1")
    repository = SubscriptionRepositoryLocal(ResolverContext(appContext, resolver), currentUid)
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  private fun answerCursor(cursor: Cursor?) {
    every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
  }

  private fun answerDisplayName(name: String?) {
    val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
    cursor.addRow(arrayOf<Any?>(name))
    answerCursor(cursor)
  }

  @Test
  fun getSubscriptions_isEmpty_whenNothingWasAdded() = runTest {
    assertEquals(emptyList<Subscription>(), repository.getSubscriptions().first())
  }

  @Test
  fun getActiveSubscription_isNull_whenNothingWasAdded() = runTest {
    assertNull(repository.getActiveSubscription().first())
  }

  @Test
  fun addSubscription_storesSubscriptionAndMakesItActive() = runTest {
    val before = System.currentTimeMillis()
    val added =
        repository.addSubscription(
            uri = pdfUri,
            expiresAtEpochMilli = 9_000L,
            maxEntries = 10,
            price = 60.0,
        )
    val after = System.currentTimeMillis()

    // The production code reads the system clock, so only its range can be asserted.
    assertTrue(added.addedAtEpochMilli in before..after)
    val expected =
        Subscription(
            id = added.id,
            uri = pdfUri,
            displayName = "pass.pdf",
            addedAtEpochMilli = added.addedAtEpochMilli,
            expiresAtEpochMilli = 9_000L,
            maxEntries = 10,
            price = 60.0,
        )
    assertEquals(expected, added)
    assertEquals(UUID.fromString(added.id).toString(), added.id)
    assertEquals(listOf(expected), repository.getSubscriptions().first())
    assertEquals(expected, repository.getActiveSubscription().first())
  }

  @Test
  fun addSubscription_leavesOptionalFieldsNull_whenNotGiven() = runTest {
    val added = repository.addSubscription(uri = pdfUri)

    assertNull(added.expiresAtEpochMilli)
    assertNull(added.maxEntries)
    assertNull(added.price)
  }

  @Test
  fun addSubscription_generatesDistinctIds_whenAddedTwice() = runTest {
    val first = repository.addSubscription(uri = pdfUri)
    val second = repository.addSubscription(uri = pdfUri)

    assertNotEquals(first.id, second.id)
  }

  @Test
  fun addSubscription_requestsPersistableReadPermission() = runTest {
    repository.addSubscription(uri = pdfUri)

    verify(exactly = 1) {
      resolver.takePersistableUriPermission(
          Uri.parse(pdfUri),
          Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    }
  }

  @Test
  fun addSubscription_usesGenericName_whenProviderReturnsNoCursor() = runTest {
    answerCursor(null)

    assertEquals("Subscription", repository.addSubscription(uri = pdfUri).displayName)
  }

  @Test
  fun addSubscription_usesGenericName_whenCursorIsEmpty() = runTest {
    answerCursor(MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)))

    assertEquals("Subscription", repository.addSubscription(uri = pdfUri).displayName)
  }

  @Test
  fun addSubscription_usesGenericName_whenCursorHasNoDisplayNameColumn() = runTest {
    val cursor = MatrixCursor(arrayOf(OpenableColumns.SIZE))
    cursor.addRow(arrayOf<Any?>(12L))
    answerCursor(cursor)

    assertEquals("Subscription", repository.addSubscription(uri = pdfUri).displayName)
  }

  @Test
  fun addSubscription_usesGenericName_whenDisplayNameIsNull() = runTest {
    answerDisplayName(null)

    assertEquals("Subscription", repository.addSubscription(uri = pdfUri).displayName)
  }

  @Test
  fun addSubscription_mirrorsFieldsWithoutUriToFirestore_whenSignedIn() = runTest {
    val added =
        repository.addSubscription(
            uri = pdfUri,
            expiresAtEpochMilli = 9_000L,
            maxEntries = null,
            price = 60.0,
        )

    verify(exactly = 1) { firebase.userDocument.collection("subscriptions") }
    verify(exactly = 1) { firebase.targetCollection.document(added.id) }
    verify(exactly = 1) {
      firebase.targetDocument.set(
          mapOf(
              "id" to added.id,
              "displayName" to "pass.pdf",
              "addedAtEpochMilli" to added.addedAtEpochMilli,
              "expiresAtEpochMilli" to 9_000L,
              "maxEntries" to null,
              "price" to 60.0,
          )
      )
    }
  }

  @Test
  fun addSubscription_stillStoresLocally_whenMirroringFails() = runTest {
    firebase.failWrites()

    val added = repository.addSubscription(uri = pdfUri)

    assertEquals(listOf(added), repository.getSubscriptions().first())
  }

  @Test
  fun addSubscription_doesNotMirror_whenSignedOut() = runTest {
    val signedOut = FirebaseMocks(uid = null)
    val signedOutRepository =
        SubscriptionRepositoryLocal(
            ResolverContext(RuntimeEnvironment.getApplication(), resolver),
            currentUid,
        )

    val added = signedOutRepository.addSubscription(uri = pdfUri)

    verify(exactly = 0) { signedOut.firestore.collection(any()) }
    assertEquals(listOf(added), signedOutRepository.getSubscriptions().first())
  }

  @Test
  fun getSubscriptions_sortsMostRecentlyAddedFirst() = runTest {
    val oldest = stored(id = "a", addedAt = 2_000L)
    val newest = stored(id = "b", addedAt = 4_000L)
    val middle = stored(id = "c", addedAt = 3_000L)
    productionPreferencesDataStore(appContext, FILE_CLASS, PROPERTY).edit {
      it[stringPreferencesKey("subscriptions_json_user-1")] =
          Json.encodeToString(listOf(oldest, newest, middle))
    }

    assertEquals(listOf(newest, middle, oldest), repository.getSubscriptions().first())
  }

  private fun stored(id: String, addedAt: Long) =
      Subscription(id = id, uri = pdfUri, displayName = id, addedAtEpochMilli = addedAt)

  @Test
  fun addSubscription_makesNewestTheActiveOne_whenSeveralAdded() = runTest {
    repository.addSubscription(uri = pdfUri)
    val second = repository.addSubscription(uri = pdfUri)

    assertEquals(second, repository.getActiveSubscription().first())
  }

  @Test
  fun setActiveSubscription_switchesActiveSubscription() = runTest {
    val first = repository.addSubscription(uri = pdfUri)
    repository.addSubscription(uri = pdfUri)

    repository.setActiveSubscription(first.id)

    assertEquals(first, repository.getActiveSubscription().first())
  }

  @Test
  fun getActiveSubscription_isNull_whenActiveIdMatchesNothing() = runTest {
    repository.addSubscription(uri = pdfUri)

    repository.setActiveSubscription("unknown")

    assertNull(repository.getActiveSubscription().first())
  }

  @Test
  fun updateSubscription_changesOnlyEditableFields_andKeepsActiveness() = runTest {
    val first = repository.addSubscription(uri = pdfUri, expiresAtEpochMilli = 9_000L)
    val second = repository.addSubscription(uri = pdfUri)

    repository.updateSubscription(
        id = first.id,
        displayName = "Renamed",
        expiresAtEpochMilli = null,
        maxEntries = 5,
        price = 12.5,
    )

    val expected =
        first.copy(
            displayName = "Renamed",
            expiresAtEpochMilli = null,
            maxEntries = 5,
            price = 12.5,
        )
    assertEquals(listOf(second, expected), repository.getSubscriptions().first())
    assertEquals(second, repository.getActiveSubscription().first())
  }

  @Test
  fun updateSubscription_updatesActiveSubscription_whenEditingTheActiveOne() = runTest {
    val only = repository.addSubscription(uri = pdfUri)

    repository.updateSubscription(only.id, "New name", 7_000L, null, null)

    assertEquals(
        only.copy(displayName = "New name", expiresAtEpochMilli = 7_000L),
        repository.getActiveSubscription().first(),
    )
  }

  @Test
  fun updateSubscription_mirrorsUpdatedFieldsToFirestore() = runTest {
    val added = repository.addSubscription(uri = pdfUri)
    firebase.clearRecordedCalls()

    repository.updateSubscription(added.id, "Renamed", null, 10, 60.0)

    verify(exactly = 1) { firebase.targetCollection.document(added.id) }
    verify(exactly = 1) {
      firebase.targetDocument.set(
          mapOf(
              "id" to added.id,
              "displayName" to "Renamed",
              "addedAtEpochMilli" to added.addedAtEpochMilli,
              "expiresAtEpochMilli" to null,
              "maxEntries" to 10,
              "price" to 60.0,
          )
      )
    }
  }

  @Test
  fun updateSubscription_isANoOp_whenIdIsUnknown() = runTest {
    val added = repository.addSubscription(uri = pdfUri)
    firebase.clearRecordedCalls()

    repository.updateSubscription("unknown", "X", null, null, null)

    assertEquals(listOf(added), repository.getSubscriptions().first())
    verify(exactly = 0) { firebase.targetCollection.document(any()) }
  }

  @Test
  fun updateSubscription_stillStoresLocally_whenMirroringFails() = runTest {
    val added = repository.addSubscription(uri = pdfUri)
    firebase.failWrites()

    repository.updateSubscription(added.id, "Renamed", null, null, null)

    assertEquals("Renamed", repository.getSubscriptions().first().single().displayName)
  }

  @Test
  fun deleteSubscription_removesOnlyThatSubscription() = runTest {
    val first = repository.addSubscription(uri = pdfUri)
    val second = repository.addSubscription(uri = pdfUri)

    repository.deleteSubscription(first.id)

    assertEquals(listOf(second), repository.getSubscriptions().first())
    assertEquals(second, repository.getActiveSubscription().first())
  }

  @Test
  fun deleteSubscription_leavesNoActiveSubscription_whenDeletingTheActiveOne() = runTest {
    val only = repository.addSubscription(uri = pdfUri)

    repository.deleteSubscription(only.id)

    assertEquals(emptyList<Subscription>(), repository.getSubscriptions().first())
    assertNull(repository.getActiveSubscription().first())
  }

  @Test
  fun deleteSubscription_removesMirroredDocument_whenSignedIn() = runTest {
    val added = repository.addSubscription(uri = pdfUri)
    firebase.clearRecordedCalls()

    repository.deleteSubscription(added.id)

    verify(exactly = 1) { firebase.userDocument.collection("subscriptions") }
    verify(exactly = 1) { firebase.targetCollection.document(added.id) }
    verify(exactly = 1) { firebase.targetDocument.delete() }
  }

  @Test
  fun deleteSubscription_isANoOp_whenIdIsUnknown() = runTest {
    val added = repository.addSubscription(uri = pdfUri)

    repository.deleteSubscription("unknown")

    assertEquals(listOf(added), repository.getSubscriptions().first())
    assertEquals(added, repository.getActiveSubscription().first())
  }

  @Test
  fun getSubscriptions_onlyShowsTheSignedInAccountsSubscriptions() = runTest {
    val mine = repository.addSubscription(uri = pdfUri)

    currentUid.value = "user-2"
    assertEquals(emptyList<Subscription>(), repository.getSubscriptions().first())
    assertNull(repository.getActiveSubscription().first())
    val theirs = repository.addSubscription(uri = pdfUri)
    assertEquals(listOf(theirs), repository.getSubscriptions().first())

    currentUid.value = "user-1"
    assertEquals(listOf(mine), repository.getSubscriptions().first())
    assertEquals(mine, repository.getActiveSubscription().first())
  }

  @Test
  fun setActiveSubscription_onlyAffectsTheSignedInAccount() = runTest {
    val mine = repository.addSubscription(uri = pdfUri)
    currentUid.value = "user-2"

    repository.setActiveSubscription(mine.id)

    currentUid.value = "user-1"
    assertEquals(mine, repository.getActiveSubscription().first())
  }

  @Test
  fun getSubscriptions_isEmpty_whenSignedOut() = runTest {
    repository.addSubscription(uri = pdfUri)

    currentUid.value = null

    assertEquals(emptyList<Subscription>(), repository.getSubscriptions().first())
    assertNull(repository.getActiveSubscription().first())
  }

  @Test
  fun writes_fail_whenSignedOut() = runTest {
    currentUid.value = null

    assertTrue(
        runCatching { repository.addSubscription(uri = pdfUri) }.exceptionOrNull()
            is IllegalStateException
    )
    assertTrue(
        runCatching { repository.setActiveSubscription("a") }.exceptionOrNull()
            is IllegalStateException
    )
    assertTrue(
        runCatching { repository.updateSubscription("a", "n", null, null, null) }.exceptionOrNull()
            is IllegalStateException
    )
    assertTrue(
        runCatching { repository.deleteSubscription("a") }.exceptionOrNull()
            is IllegalStateException
    )
    verify(exactly = 0) { resolver.takePersistableUriPermission(any(), any()) }
  }

  @Test
  fun getSubscriptions_claimsLegacySubscriptions_forFirstAccountOnly() = runTest {
    val legacy = stored(id = "legacy", addedAt = 2_000L)
    seedLegacy(Json.encodeToString(listOf(legacy)), activeId = "legacy")

    assertEquals(listOf(legacy), repository.getSubscriptions().first())
    assertEquals(legacy, repository.getActiveSubscription().first())

    currentUid.value = "user-2"
    assertEquals(emptyList<Subscription>(), repository.getSubscriptions().first())
    val prefs = productionPreferencesDataStore(appContext, FILE_CLASS, PROPERTY).data.first()
    assertNull(prefs[LEGACY_SUBSCRIPTIONS_KEY])
    assertNull(prefs[LEGACY_ACTIVE_ID_KEY])
  }

  @Test
  fun addSubscription_keepsLegacySubscriptions_whenClaimingThem() = runTest {
    val legacy = stored(id = "legacy", addedAt = 2_000L)
    seedLegacy(Json.encodeToString(listOf(legacy)), activeId = null)

    val added = repository.addSubscription(uri = pdfUri)

    assertEquals(listOf(added, legacy), repository.getSubscriptions().first())
  }

  @Test
  fun setActiveSubscription_dropsLegacyData_whenAccountAlreadyHasItsOwn() = runTest {
    val mine = repository.addSubscription(uri = pdfUri)
    seedLegacy(Json.encodeToString(listOf(stored(id = "legacy", addedAt = 1L))), "legacy")

    repository.setActiveSubscription(mine.id)

    assertEquals(listOf(mine), repository.getSubscriptions().first())
    assertEquals(mine, repository.getActiveSubscription().first())
    val prefs = productionPreferencesDataStore(appContext, FILE_CLASS, PROPERTY).data.first()
    assertNull(prefs[LEGACY_SUBSCRIPTIONS_KEY])
  }

  @Test
  fun deleteSubscription_claimsLegacySubscriptionsBeforeDeleting() = runTest {
    val legacy = stored(id = "legacy", addedAt = 2_000L)
    seedLegacy(Json.encodeToString(listOf(legacy)), activeId = "legacy")

    repository.deleteSubscription("legacy")

    assertEquals(emptyList<Subscription>(), repository.getSubscriptions().first())
    assertNull(repository.getActiveSubscription().first())
  }

  private suspend fun seedLegacy(subscriptionsJson: String, activeId: String?) {
    productionPreferencesDataStore(appContext, FILE_CLASS, PROPERTY).edit { prefs ->
      prefs[LEGACY_SUBSCRIPTIONS_KEY] = subscriptionsJson
      activeId?.let { prefs[LEGACY_ACTIVE_ID_KEY] = it }
    }
  }

  @Test
  fun attachPdf_setsTheUri_ofThatSubscriptionOnly() = runTest {
    val first = repository.addSubscription(pdfUri)
    val second = repository.addSubscription("content://docs/other.pdf")

    repository.attachPdf(first.id, "content://docs/new.pdf")

    val stored = repository.getSubscriptions().first().associateBy { it.id }
    assertEquals("content://docs/new.pdf", stored.getValue(first.id).uri)
    assertEquals("content://docs/other.pdf", stored.getValue(second.id).uri)
    verify { resolver.takePersistableUriPermission(Uri.parse("content://docs/new.pdf"), any()) }
  }

  @Test
  fun attachPdf_doesNothing_forAnUnknownSubscription() = runTest {
    val subscription = repository.addSubscription(pdfUri)

    repository.attachPdf("missing", "content://docs/new.pdf")

    assertEquals(listOf(subscription), repository.getSubscriptions().first())
  }

  private companion object {
    const val FILE_CLASS =
        "com.github.se.pooltrack.model.subscription.SubscriptionRepositoryLocalKt"
    const val PROPERTY = "subscriptionDataStore"
    val LEGACY_SUBSCRIPTIONS_KEY = stringPreferencesKey("subscriptions_json")
    val LEGACY_ACTIVE_ID_KEY = stringPreferencesKey("active_subscription_id")
  }
}
