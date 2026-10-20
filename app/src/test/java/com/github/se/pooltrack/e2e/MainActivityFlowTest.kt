package com.github.se.pooltrack.e2e

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.se.pooltrack.MainActivity
import com.github.se.pooltrack.model.auth.AuthRepositoryProvider
import com.github.se.pooltrack.model.entry.EntryRepositoryFirestore
import com.github.se.pooltrack.model.entry.EntryRepositoryProvider
import com.github.se.pooltrack.model.subscription.PassZoomRepositoryProvider
import com.github.se.pooltrack.model.subscription.SubscriptionRepositoryFirestore
import com.github.se.pooltrack.model.subscription.SubscriptionRepositoryProvider
import com.github.se.pooltrack.model.update.UpdateRepositoryProvider
import com.github.se.pooltrack.ui.home.HomeScreenTestTags
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.utils.FakeAuthRepository
import com.github.se.pooltrack.utils.FakeDocumentStore
import com.github.se.pooltrack.utils.FakePassZoomRepository
import com.github.se.pooltrack.utils.FakePdfFiles
import com.github.se.pooltrack.utils.FakeUpdateRepository
import com.github.se.pooltrack.utils.FirebaseTestApp
import com.github.se.pooltrack.utils.MainDispatcherRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real `MainActivity`, started the way Android starts it, on top of the account's data. */
@Config(qualifiers = "w320dp-h1200dp")
@RunWith(RobolectricTestRunner::class)
class MainActivityFlowTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()
  @get:Rule(order = 1) val composeRule = createEmptyComposeRule()

  private val store = FakeDocumentStore()
  private val originalAuth by lazy { AuthRepositoryProvider.repository }

  @Before
  fun setUp() {
    FirebaseTestApp.ensureInitialized(ApplicationProvider.getApplicationContext())
    WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
    originalAuth
    AuthRepositoryProvider.repository = FakeAuthRepository(FakeAuthRepository.GOOGLE_USER)
    EntryRepositoryProvider.repository = EntryRepositoryFirestore(store)
    SubscriptionRepositoryProvider.repository =
        SubscriptionRepositoryFirestore(store, FakePdfFiles())
    PassZoomRepositoryProvider.repository = FakePassZoomRepository()
    UpdateRepositoryProvider.repository = FakeUpdateRepository()
  }

  @After
  fun tearDown() {
    AuthRepositoryProvider.repository = originalAuth
  }

  @Test
  fun startingTheApp_whenNotificationsAreAllowed_asksForNothing() {
    shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      composeRule.onNodeWithTag(NavigationTestTags.TOP_BAR_TITLE).assertTextEquals("Home")
      scenario.onActivity { activity ->
        assertEquals(null, shadowOf(activity).lastRequestedPermission)
      }
    }
  }

  @Test
  fun startingTheApp_whenNotificationsAreNotAllowed_asksForThem() {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      composeRule.onNodeWithTag(HomeScreenTestTags.LAST_SWIM_HERO).assertIsDisplayed()
      scenario.onActivity { activity ->
        val requested = shadowOf(activity).lastRequestedPermission
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            requested?.requestedPermissions?.toList(),
        )
      }
    }
  }
}
