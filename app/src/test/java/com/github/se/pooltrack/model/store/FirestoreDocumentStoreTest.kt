package com.github.se.pooltrack.model.store

import com.github.se.pooltrack.model.backup.completedTask
import com.github.se.pooltrack.model.backup.failedTask
import com.github.se.pooltrack.model.backup.voidTask
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FirestoreDocumentStoreTest {

  private val firestore: FirebaseFirestore = mockk()
  private val users: CollectionReference = mockk()
  private val userDocument: DocumentReference = mockk()
  private val collection: CollectionReference = mockk()
  private val document: DocumentReference = mockk()
  private val registration: ListenerRegistration = mockk(relaxed = true)

  private val currentUid = MutableStateFlow<String?>("user-1")
  private val store = FirestoreDocumentStore(currentUid) { firestore }

  private var listener: EventListener<QuerySnapshot>? = null

  init {
    every { firestore.collection("users") } returns users
    every { users.document(any<String>()) } returns userDocument
    every { userDocument.collection(any()) } returns collection
    every { collection.document(any<String>()) } returns document
    every { document.set(any<Any>()) } returns voidTask()
    every { document.update(any<Map<String, Any?>>()) } returns voidTask()
    every { document.delete() } returns voidTask()
    every { collection.addSnapshotListener(any<EventListener<QuerySnapshot>>()) } answers
        {
          listener = firstArg()
          registration
        }
  }

  private fun snapshotOf(vararg documents: Pair<String, Map<String, Any>?>): QuerySnapshot {
    val snapshots = documents.map { (id, data) ->
      mockk<DocumentSnapshot>().also {
        every { it.id } returns id
        every { it.data } returns data
      }
    }
    return mockk<QuerySnapshot>().also { every { it.documents } returns snapshots }
  }

  // region writes

  @Test
  fun set_writesTheDocumentUnderTheUsersCollection() = runTest {
    val data = mapOf("a" to 1, "b" to null)

    store.set("entries", "doc-1", data)

    verify(exactly = 1) { users.document("user-1") }
    verify(exactly = 1) { userDocument.collection("entries") }
    verify(exactly = 1) { collection.document("doc-1") }
    verify(exactly = 1) { document.set(data) }
  }

  @Test
  fun update_changesOnlyTheGivenFields() = runTest {
    store.update("entries", "doc-1", mapOf("swimDistanceMeters" to 500))

    verify(exactly = 1) { document.update(mapOf("swimDistanceMeters" to 500)) }
  }

  @Test
  fun delete_removesTheDocument() = runTest {
    store.delete("entries", "doc-1")

    verify(exactly = 1) { document.delete() }
  }

  @Test
  fun writes_fail_whenNobodyIsSignedIn() {
    currentUid.value = null

    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking { store.set("entries", "doc-1", mapOf("a" to 1)) }
    }
    verify(exactly = 0) { document.set(any<Any>()) }
  }

  @Test
  fun writes_doNotWaitForTheServer_norThrow_whenTheyAreRejected() = runTest {
    every { document.set(any<Any>()) } returns failedTask(RuntimeException("rejected"))

    store.set("entries", "doc-1", mapOf("a" to 1))

    verify(exactly = 1) { document.set(mapOf("a" to 1)) }
  }

  // endregion

  // region observe

  @Test
  fun observe_emitsTheDocumentsOfTheCollection_andEveryChange() = runTest {
    val emissions = mutableListOf<List<StoredDocument>>()
    val job =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          store.observe("entries").collect { emissions += it }
        }

    listener!!.onEvent(snapshotOf("a" to mapOf("x" to 1)), null)
    listener!!.onEvent(snapshotOf("a" to mapOf("x" to 1), "b" to mapOf("x" to 2)), null)

    assertEquals(
        listOf(
            listOf(StoredDocument("a", mapOf("x" to 1))),
            listOf(StoredDocument("a", mapOf("x" to 1)), StoredDocument("b", mapOf("x" to 2))),
        ),
        emissions,
    )
    verify { userDocument.collection("entries") }
    job.cancel()
  }

  @Test
  fun observe_treatsADocumentWithoutDataAsEmpty() = runTest {
    val emissions = mutableListOf<List<StoredDocument>>()
    val job =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          store.observe("entries").collect { emissions += it }
        }

    listener!!.onEvent(snapshotOf("a" to null), null)

    assertEquals(listOf(listOf(StoredDocument("a", emptyMap()))), emissions)
    job.cancel()
  }

  @Test
  fun observe_keepsTheLastDocuments_whenListeningFails() = runTest {
    val emissions = mutableListOf<List<StoredDocument>>()
    val job =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          store.observe("entries").collect { emissions += it }
        }
    listener!!.onEvent(snapshotOf("a" to mapOf("x" to 1)), null)

    listener!!.onEvent(
        null,
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED),
    )

    assertEquals(1, emissions.size)
    job.cancel()
  }

  @Test
  fun observe_stopsListening_whenTheCollectorIsCancelled() = runTest {
    val job =
        launch(UnconfinedTestDispatcher(testScheduler)) { store.observe("entries").collect {} }

    job.cancel()
    job.join()

    verify(exactly = 1) { registration.remove() }
  }

  @Test
  fun observe_isEmptyWithoutListening_whenNobodyIsSignedIn() = runTest {
    currentUid.value = null
    val emissions = mutableListOf<List<StoredDocument>>()
    val job =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          store.observe("entries").collect { emissions += it }
        }

    assertEquals(listOf(emptyList<StoredDocument>()), emissions)
    verify(exactly = 0) { collection.addSnapshotListener(any<EventListener<QuerySnapshot>>()) }
    job.cancel()
  }

  // endregion

  // region offline cache

  @Test
  fun enableOfflineCache_appliesPersistentSettings() {
    every { firestore.firestoreSettings = any() } just Runs

    FirestoreDocumentStore.enableOfflineCache { firestore }

    verify(exactly = 1) { firestore.firestoreSettings = any() }
  }

  @Test
  fun enableOfflineCache_isIgnored_whenFirebaseIsNotInitialised() {
    FirestoreDocumentStore.enableOfflineCache { error("Firebase is not initialised") }

    assertTrue(true)
  }

  @Test
  fun enableOfflineCache_isIgnored_whenFirestoreWasAlreadyUsed() {
    every { firestore.firestoreSettings = any() } throws IllegalStateException("already started")

    FirestoreDocumentStore.enableOfflineCache { firestore }

    assertTrue(true)
  }

  // endregion

  @Suppress("unused") private val unusedCompletedTask = completedTask(Unit)
}
