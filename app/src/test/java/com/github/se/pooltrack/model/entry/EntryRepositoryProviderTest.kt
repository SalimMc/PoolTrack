package com.github.se.pooltrack.model.entry

import com.github.se.pooltrack.model.auth.AuthRepository
import com.github.se.pooltrack.model.auth.AuthRepositoryProvider
import com.github.se.pooltrack.utils.FakeAuthRepository
import com.github.se.pooltrack.utils.FakeEntryRepository
import com.github.se.pooltrack.utils.FirebaseTestApp
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EntryRepositoryProviderTest {

  private var previous: EntryRepository? = null

  private lateinit var previousAuth: AuthRepository
  private val auth = FakeAuthRepository(initialUser = FakeAuthRepository.GOOGLE_USER)

  @Before
  fun setUp() {
    // init() reads AuthRepositoryProvider, whose default builds the real Firebase repository.
    FirebaseTestApp.ensureInitialized(RuntimeEnvironment.getApplication())
    previousAuth = AuthRepositoryProvider.repository
    AuthRepositoryProvider.repository = auth
    previous = currentRepository()
  }

  @After
  fun tearDown() {
    setRepository(previous)
    AuthRepositoryProvider.repository = previousAuth
  }

  // `repository` is a lateinit, so the only way to put the singleton back into its "never
  // initialised" state is through its backing field.
  private fun field() =
      EntryRepositoryProvider::class.java.getDeclaredField("repository").apply {
        isAccessible = true
      }

  private fun currentRepository() = field().get(EntryRepositoryProvider) as? EntryRepository

  private fun setRepository(repository: EntryRepository?) {
    field().set(EntryRepositoryProvider, repository)
  }

  @Test
  fun init_createsFirestoreRepository_whenNoneWasSet() {
    setRepository(null)

    EntryRepositoryProvider.init(RuntimeEnvironment.getApplication())

    assertTrue(EntryRepositoryProvider.repository is EntryRepositoryFirestore)
  }

  @Test
  fun init_keepsExistingRepository_whenOneWasAlreadySet() {
    val fake = FakeEntryRepository()
    EntryRepositoryProvider.repository = fake

    EntryRepositoryProvider.init(RuntimeEnvironment.getApplication())

    assertSame(fake, EntryRepositoryProvider.repository)
  }
}
