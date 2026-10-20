package com.github.se.pooltrack.model.subscription

import com.github.se.pooltrack.utils.FakeDocumentStore
import com.github.se.pooltrack.utils.FakePdfFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRepositoryFirestoreTest {

  private val store = FakeDocumentStore()
  private val pdfFiles = FakePdfFiles()
  private val repository = SubscriptionRepositoryFirestore(store, pdfFiles)

  private fun backedUp(id: String, addedAt: Long) =
      Subscription(
              id = id,
              uri = "",
              displayName = "Ticket $id.PDF",
              addedAtEpochMilli = addedAt,
              price = 275.0,
          )
          .also { runBlockingSet(it) }

  private fun runBlockingSet(subscription: Subscription) =
      kotlinx.coroutines.runBlocking {
        store.set("subscriptions", subscription.id, subscription.toDocument())
      }

  @Test
  fun getSubscriptions_isEmpty_whenNothingWasAdded() = runTest {
    assertEquals(emptyList<Subscription>(), repository.getSubscriptions().first())
    assertNull(repository.getActiveSubscription().first())
  }

  @Test
  fun addSubscription_storesItsDetails_keepsThePdfOnTheDevice_andActivatesIt() = runTest {
    val added =
        repository.addSubscription(
            uri = "content://docs/pass.pdf",
            expiresAtEpochMilli = 5_000L,
            price = 60.0,
        )

    assertEquals("pass.pdf", added.displayName)
    assertEquals(listOf("content://docs/pass.pdf"), pdfFiles.keptReadable)
    // The PDF's URI is only meaningful on this device, so it is not stored with the account.
    assertEquals(
        mapOf(
            "id" to added.id,
            "displayName" to "pass.pdf",
            "addedAtEpochMilli" to added.addedAtEpochMilli,
            "expiresAtEpochMilli" to 5_000L,
            "maxEntries" to null,
            "price" to 60.0,
        ),
        store.document("subscriptions", added.id),
    )
    assertEquals(listOf(added), repository.getSubscriptions().first())
    assertEquals(added, repository.getActiveSubscription().first())
  }

  @Test
  fun getSubscriptions_listsMostRecentlyAddedFirst() = runTest {
    val old = backedUp("old", addedAt = 1_000L)
    val new = backedUp("new", addedAt = 2_000L)

    assertEquals(listOf(new, old), repository.getSubscriptions().first())
  }

  @Test
  fun getSubscriptions_comeBackWithoutAPdf_onAnotherDevice() = runTest {
    val restored = backedUp("a", addedAt = 1_000L)

    val subscription = repository.getSubscriptions().first().single()

    assertEquals(restored, subscription)
    assertFalse(subscription.hasPdf)
  }

  @Test
  fun getSubscriptions_pairsEachWithItsPdfOnThisDevice() = runTest {
    backedUp("a", addedAt = 1_000L)
    backedUp("b", addedAt = 2_000L)
    pdfFiles.save("a", "content://docs/a.pdf")

    val byId = repository.getSubscriptions().first().associateBy { it.id }

    assertEquals("content://docs/a.pdf", byId.getValue("a").uri)
    assertFalse(byId.getValue("b").hasPdf)
  }

  @Test
  fun getSubscriptions_skipsDocumentsWithoutAnId() = runTest {
    store.set("subscriptions", "broken", mapOf("displayName" to "x"))
    val valid = backedUp("a", addedAt = 1_000L)

    assertEquals(listOf(valid), repository.getSubscriptions().first())
  }

  @Test
  fun attachPdf_givesThePdfBack_withoutTouchingTheStoredDetails() = runTest {
    backedUp("a", addedAt = 1_000L)
    val before = store.document("subscriptions", "a")

    repository.attachPdf("a", "content://docs/a.pdf")

    assertEquals("content://docs/a.pdf", repository.getSubscriptions().first().single().uri)
    assertEquals(before, store.document("subscriptions", "a"))
    assertEquals(listOf("content://docs/a.pdf"), pdfFiles.keptReadable)
  }

  @Test
  fun updateSubscription_changesTheEditableDetails() = runTest {
    backedUp("a", addedAt = 1_000L)

    repository.updateSubscription("a", "Renamed", 9_000L, null, 80.0)

    val updated = repository.getSubscriptions().first().single()
    assertEquals("Renamed", updated.displayName)
    assertEquals(9_000L, updated.expiresAtEpochMilli)
    assertEquals(80.0, updated.price)
    assertEquals(1_000L, updated.addedAtEpochMilli)
  }

  @Test
  fun updateSubscription_doesNothing_forAnUnknownId() = runTest {
    repository.updateSubscription("missing", "x", null, null, null)

    assertEquals(emptyList<Subscription>(), repository.getSubscriptions().first())
  }

  @Test
  fun setActiveSubscription_makesOnlyThatOneActive() = runTest {
    val first = backedUp("a", addedAt = 1_000L)
    val second = backedUp("b", addedAt = 2_000L)

    repository.setActiveSubscription("a")
    assertEquals(first, repository.getActiveSubscription().first())

    repository.setActiveSubscription("b")
    assertEquals(second, repository.getActiveSubscription().first())
  }

  @Test
  fun deleteSubscription_removesItAndItsPdf_andLeavesNoneActiveWhenItWasActive() = runTest {
    val added = repository.addSubscription("content://docs/pass.pdf")

    repository.deleteSubscription(added.id)

    assertEquals(emptyList<Subscription>(), repository.getSubscriptions().first())
    assertNull(repository.getActiveSubscription().first())
    assertTrue(pdfFiles.uris.first().isEmpty())
  }

  @Test
  fun deleteSubscription_keepsTheActiveOne_whenAnotherIsDeleted() = runTest {
    val first = repository.addSubscription("content://docs/a.pdf")
    val second = repository.addSubscription("content://docs/b.pdf")

    repository.deleteSubscription(first.id)

    assertEquals(second, repository.getActiveSubscription().first())
  }
}
