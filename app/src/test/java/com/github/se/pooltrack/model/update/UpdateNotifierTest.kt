package com.github.se.pooltrack.model.update

import com.github.se.pooltrack.utils.FakeUpdateRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotifierTest {

  private val repository = FakeUpdateRepository()
  private var lastNotified: String? = null
  private val notified = mutableListOf<AppUpdate>()

  private val notifier =
      UpdateNotifier(
          updateRepository = repository,
          currentVersion = "1.1.0",
          lastNotifiedVersion = { lastNotified },
          saveNotifiedVersion = { lastNotified = it },
          notify = { notified += it },
      )

  @Test
  fun check_doesNothing_whenTheAppIsUpToDate() = runTest {
    repository.update = null

    assertFalse(notifier.check())
    assertTrue(notified.isEmpty())
    assertEquals("1.1.0", repository.checkedVersion)
  }

  @Test
  fun check_notifiesAndRemembersTheVersion_whenAnUpdateIsNew() = runTest {
    repository.update = FakeUpdateRepository.UPDATE

    assertTrue(notifier.check())
    assertEquals(listOf(FakeUpdateRepository.UPDATE), notified)
    assertEquals("1.2.0", lastNotified)
  }

  @Test
  fun check_doesNotNotifyTwice_forTheSameVersion() = runTest {
    repository.update = FakeUpdateRepository.UPDATE

    notifier.check()
    assertFalse(notifier.check())

    assertEquals(1, notified.size)
  }

  @Test
  fun check_notifiesAgain_whenAnEvenNewerVersionAppears() = runTest {
    repository.update = FakeUpdateRepository.UPDATE
    notifier.check()
    repository.update = FakeUpdateRepository.UPDATE.copy(version = "1.3.0")

    assertTrue(notifier.check())
    assertEquals("1.3.0", lastNotified)
    assertEquals(2, notified.size)
  }
}
