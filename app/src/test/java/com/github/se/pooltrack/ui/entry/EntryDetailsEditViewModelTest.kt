package com.github.se.pooltrack.ui.entry

import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.utils.FakeEntryRepository
import com.github.se.pooltrack.utils.FakeSubscriptionRepository
import com.github.se.pooltrack.utils.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class EntryDetailsEditViewModelTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private val older = Entry(timestampEpochMilli = 1_000L, subscriptionId = "sub-1")
  private val newer = Entry(timestampEpochMilli = 2_000L, subscriptionId = "sub-1")

  private fun viewModel(entries: FakeEntryRepository) =
      EntryDetailsViewModel(entries, FakeSubscriptionRepository())

  @Test
  fun onEditDistance_startsFromTheLoggedDistance() {
    val logged = older.copy(swimDistanceMeters = 1200)
    val viewModel = viewModel(FakeEntryRepository(listOf(logged, newer)))
    viewModel.loadEntry(logged.timestampEpochMilli)

    viewModel.onEditDistance()

    assertEquals("1200", viewModel.distanceInput.value)
    assertEquals(true, viewModel.isDistanceInputValid.value)
  }

  @Test
  fun onEditDistance_startsEmpty_whenNoDistanceWasLogged() {
    val viewModel = viewModel(FakeEntryRepository(listOf(older, newer)))
    viewModel.loadEntry(older.timestampEpochMilli)

    viewModel.onEditDistance()

    assertEquals("", viewModel.distanceInput.value)
    assertEquals(false, viewModel.isDistanceInputValid.value)
  }

  @Test
  fun onEditDistance_doesNothing_whenNoEntryIsLoaded() {
    val viewModel = viewModel(FakeEntryRepository(listOf(older, newer)))

    viewModel.onEditDistance()

    assertNull(viewModel.distanceInput.value)
  }

  @Test
  fun onDistanceInputChanged_keepsOnlyDigits_andTheMaximumLength() {
    val viewModel = viewModel(FakeEntryRepository(listOf(older)))
    viewModel.loadEntry(older.timestampEpochMilli)
    viewModel.onEditDistance()

    viewModel.onDistanceInputChanged("12a3.4 m")
    assertEquals("1234", viewModel.distanceInput.value)

    viewModel.onDistanceInputChanged("123456789")
    assertEquals("12345", viewModel.distanceInput.value)
  }

  @Test
  fun onDistanceInputChanged_isIgnored_whenNoEditIsGoingOn() {
    val viewModel = viewModel(FakeEntryRepository(listOf(older)))
    viewModel.loadEntry(older.timestampEpochMilli)

    viewModel.onDistanceInputChanged("500")

    assertNull(viewModel.distanceInput.value)
  }

  @Test
  fun isDistanceInputValid_isFalse_forZeroAndTooLongDistances() {
    val viewModel = viewModel(FakeEntryRepository(listOf(older)))
    viewModel.loadEntry(older.timestampEpochMilli)
    viewModel.onEditDistance()

    viewModel.onDistanceInputChanged("0")
    assertEquals(false, viewModel.isDistanceInputValid.value)

    viewModel.onDistanceInputChanged("99999")
    assertEquals(false, viewModel.isDistanceInputValid.value)

    viewModel.onDistanceInputChanged("50000")
    assertEquals(true, viewModel.isDistanceInputValid.value)
  }

  @Test
  fun onDistanceEditSaved_replacesTheDistanceOfTheLoadedEntryOnly() {
    val logged = older.copy(swimDistanceMeters = 1200)
    val repository = FakeEntryRepository(listOf(logged, newer))
    val viewModel = viewModel(repository)
    viewModel.loadEntry(logged.timestampEpochMilli)
    viewModel.onEditDistance()
    viewModel.onDistanceInputChanged("1500")

    viewModel.onDistanceEditSaved()

    assertEquals(logged.copy(swimDistanceMeters = 1500), repository.storedEntries.first())
    assertEquals(newer, repository.storedEntries.last())
    assertNull(viewModel.distanceInput.value)
    assertEquals(1500, viewModel.details.value?.entry?.swimDistanceMeters)
  }

  @Test
  fun onDistanceEditSaved_addsADistance_toAnEntryWithoutOne() {
    val repository = FakeEntryRepository(listOf(older, newer))
    val viewModel = viewModel(repository)
    viewModel.loadEntry(older.timestampEpochMilli)
    viewModel.onEditDistance()
    viewModel.onDistanceInputChanged("800")

    viewModel.onDistanceEditSaved()

    val saved =
        repository.storedEntries.first { it.timestampEpochMilli == older.timestampEpochMilli }
    assertEquals(800, saved.swimDistanceMeters)
  }

  @Test
  fun onDistanceEditSaved_changesNothing_whenTheInputIsInvalid() {
    val repository = FakeEntryRepository(listOf(older, newer))
    val viewModel = viewModel(repository)
    viewModel.loadEntry(older.timestampEpochMilli)
    viewModel.onEditDistance()
    viewModel.onDistanceInputChanged("0")

    viewModel.onDistanceEditSaved()

    assertEquals(listOf(older, newer), repository.storedEntries)
    assertEquals("0", viewModel.distanceInput.value)
  }

  @Test
  fun onDistanceEditSaved_changesNothing_whenNoEntryIsLoaded() {
    val repository = FakeEntryRepository(listOf(older, newer))
    val viewModel = viewModel(repository)

    viewModel.onDistanceInputChanged("700")
    viewModel.onDistanceEditSaved()

    assertEquals(listOf(older, newer), repository.storedEntries)
  }

  @Test
  fun onDistanceEditSaved_changesNothing_whenNoEditWasStarted() {
    val repository = FakeEntryRepository(listOf(older, newer))
    val viewModel = viewModel(repository)
    viewModel.loadEntry(older.timestampEpochMilli)

    viewModel.onDistanceEditSaved()

    assertEquals(listOf(older, newer), repository.storedEntries)
    assertNull(viewModel.distanceInput.value)
  }

  @Test
  fun onDistanceEditCancelled_changesNothing() {
    val repository = FakeEntryRepository(listOf(older, newer))
    val viewModel = viewModel(repository)
    viewModel.loadEntry(older.timestampEpochMilli)
    viewModel.onEditDistance()
    viewModel.onDistanceInputChanged("700")

    viewModel.onDistanceEditCancelled()

    assertEquals(listOf(older, newer), repository.storedEntries)
    assertNull(viewModel.distanceInput.value)
  }

  @Test
  fun sanitizeDistanceInput_dropsEverythingButDigits() {
    assertEquals("1200", sanitizeDistanceInput(" 1,200 m "))
    assertEquals("", sanitizeDistanceInput("abc"))
  }
}
