package com.github.se.pooltrack.model.usage

import com.github.se.pooltrack.model.backup.FirebaseMocks
import com.github.se.pooltrack.model.backup.failedTask
import com.github.se.pooltrack.model.backup.voidTask
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import io.mockk.every
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsageRepositoryFirestoreTest {

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun usageDocument_holdsTheLastOpenTheVersionAndAnIncrementOfTheCount() {
    val document = usageDocument(nowEpochMilli = 1_700_000_000_000, appVersion = "1.10.1")

    assertEquals(setOf("lastOpenedAtEpochMilli", "appVersion", "openCount"), document.keys)
    assertEquals(1_700_000_000_000, document["lastOpenedAtEpochMilli"])
    assertEquals("1.10.1", document["appVersion"])
    // A FieldValue has no equals(), so only its kind can be checked.
    assertTrue(document["openCount"] is FieldValue)
  }

  @Test
  fun recordOpen_mergesTheUsageIntoTheUserDocument_whenSignedIn() = runTest {
    val firebase = FirebaseMocks(uid = "user-42")
    val written = slot<Map<String, Any>>()
    every { firebase.userDocument.set(capture(written), SetOptions.merge()) } returns voidTask()

    UsageRepositoryFirestore(now = { 1234L }).recordOpen("1.10.1")

    verify(exactly = 1) { firebase.firestore.collection("users") }
    verify(exactly = 1) { firebase.usersCollection.document("user-42") }
    assertEquals(1234L, written.captured["lastOpenedAtEpochMilli"])
    assertEquals("1.10.1", written.captured["appVersion"])
    assertTrue(written.captured["openCount"] is FieldValue)
  }

  @Test
  fun recordOpen_doesNothing_whenSignedOut() = runTest {
    val firebase = FirebaseMocks(uid = null)

    UsageRepositoryFirestore().recordOpen("1.10.1")

    verify(exactly = 0) { firebase.firestore.collection(any()) }
  }

  @Test
  fun recordOpen_swallowsTheFailure_whenTheWriteFails() = runTest {
    val firebase = FirebaseMocks()
    every { firebase.userDocument.set(any<Map<String, Any>>(), any<SetOptions>()) } returns
        failedTask(RuntimeException("offline"))

    UsageRepositoryFirestore().recordOpen("1.10.1")

    verify(exactly = 1) { firebase.userDocument.set(any<Map<String, Any>>(), any<SetOptions>()) }
  }

  @Test
  fun provider_defaultsToTheFirestoreRepository() {
    assertTrue(UsageRepositoryProvider.repository is UsageRepositoryFirestore)
  }
}
