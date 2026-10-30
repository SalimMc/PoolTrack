package com.github.se.pooltrack.ui.subscription

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.subscription.PassZoomRepositoryProvider
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.model.subscription.renderPassFirstPage
import com.github.se.pooltrack.ui.navigation.NavigationActions
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.utils.FakeEntryRepository
import com.github.se.pooltrack.utils.FakePassZoomRepository
import com.github.se.pooltrack.utils.FakeSubscriptionRepository
import com.github.se.pooltrack.utils.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubscriptionQuickViewScreenTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val subscription =
      Subscription(
          id = "sub-1",
          uri = "content://pass/1",
          displayName = "Pass",
          addedAtEpochMilli = 1L,
      )

  private lateinit var navigationActions: NavigationActions

  @Before
  fun setUp() {
    navigationActions = mockk(relaxed = true)
    // The activity-scoped zoom ViewModel reads the provider's repository by default.
    PassZoomRepositoryProvider.repository = FakePassZoomRepository()
    // The real renderer needs a PDF behind a content URI: stub it with a tiny bitmap.
    mockkStatic("com.github.se.pooltrack.model.subscription.PdfThumbnailKt")
    every { renderPassFirstPage(any(), any()) } answers
        {
          Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        }
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  private fun show(
      subscriptions: FakeSubscriptionRepository =
          FakeSubscriptionRepository(listOf(subscription), subscription.id),
      entries: FakeEntryRepository = FakeEntryRepository(),
      navigation: NavigationActions? = navigationActions,
      awaitPass: Boolean = true,
  ) {
    val viewModel = SubscriptionViewModel(subscriptions, entries)
    composeTestRule.setContent { SubscriptionQuickViewScreen(viewModel, navigation) }
    // Let the background render finish while the stub is still installed.
    if (awaitPass) waitForPdfImage()
  }

  private fun entryAgo(ago: Duration) =
      Entry(Instant.now().minus(ago).toEpochMilli(), subscriptionId = subscription.id)

  private fun waitForPdfImage() {
    composeTestRule.waitUntil(5_000) {
      composeTestRule
          .onAllNodesWithTag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE)
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
  }

  private val screenBrightness: Float
    get() = composeTestRule.activity.window.attributes.screenBrightness

  @Test
  fun quickViewScreen_showsPassAndAcceptButton_whenSubscriptionActive() {
    show()

    composeTestRule.onNodeWithTag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON)
        .assertIsDisplayed()
    composeTestRule.onNodeWithText("Accepted").assertIsDisplayed()
    composeTestRule.onNodeWithText("Pinch to zoom", substring = true).assertIsDisplayed()
    composeTestRule
        .onAllNodesWithTag(SubscriptionQuickViewScreenTestTags.LOADING_INDICATOR)
        .assertCountEquals(0)
  }

  @Test
  fun quickViewScreen_asksWhetherTheGateLetUserIn_andExplainsDeclinedScans() {
    show()

    composeTestRule
        .onNodeWithTag(SubscriptionQuickViewScreenTestTags.CONFIRM_SHEET)
        .assertIsDisplayed()
    composeTestRule.onNodeWithText("Did the gate let you in?").assertIsDisplayed()
    composeTestRule.onNodeWithText("Declined? Keep the pass up and scan again.").assertIsDisplayed()
  }

  @Test
  fun quickViewScreen_showsLoadingIndicator_whenPageNotRendered() {
    every { renderPassFirstPage(any(), any()) } returns null
    show(awaitPass = false)

    verify(timeout = 5_000) { renderPassFirstPage(any(), any()) }
    composeTestRule.waitForIdle()
    composeTestRule
        .onNodeWithTag(SubscriptionQuickViewScreenTestTags.LOADING_INDICATOR)
        .assertIsDisplayed()
    composeTestRule
        .onAllNodesWithTag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE)
        .assertCountEquals(0)
  }

  @Test
  fun quickViewScreen_keepsPassShown_whenPinchedToZoom() {
    show()

    composeTestRule.onNodeWithTag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE).performTouchInput {
      pinch(
          start0 = Offset(centerX - 20f, centerY),
          end0 = Offset(centerX - 80f, centerY),
          start1 = Offset(centerX + 20f, centerY),
          end1 = Offset(centerX + 80f, centerY),
      )
    }

    composeTestRule.onNodeWithTag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE).assertIsDisplayed()
  }

  @Test
  fun quickViewScreen_showsNoActiveSubscriptionMessage_whenNoSubscription() {
    show(subscriptions = FakeSubscriptionRepository(), awaitPass = false)

    composeTestRule.onNodeWithText("No active subscription").assertIsDisplayed()
    composeTestRule
        .onNodeWithText("Add or activate one from the Subscriptions tab.")
        .assertIsDisplayed()
    composeTestRule
        .onAllNodesWithTag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON)
        .assertCountEquals(0)
  }

  @Test
  fun quickViewScreen_recordsEntryAndGoesBack_whenAcceptedTapped() {
    val entries = FakeEntryRepository()
    show(entries = entries)

    composeTestRule.onNodeWithTag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON).performClick()

    assertEquals(1, entries.storedEntries.size)
    assertEquals(subscription.id, entries.storedEntries.single().subscriptionId)
    verify(exactly = 1) { navigationActions.goBack() }
  }

  @Test
  fun quickViewScreen_recordsNothing_whenAcceptedNeverTapped() {
    val entries = FakeEntryRepository()
    show(entries = entries)
    composeTestRule.waitForIdle()

    assertTrue(entries.storedEntries.isEmpty())
    verify(exactly = 0) { navigationActions.goBack() }
  }

  @Test
  fun quickViewScreen_goesBack_whenBackButtonTapped() {
    show()

    composeTestRule.onNodeWithTag(NavigationTestTags.GO_BACK_BUTTON).performClick()

    verify(exactly = 1) { navigationActions.goBack() }
  }

  @Test
  fun quickViewScreen_showsCooldownMessageInsteadOfPass_whenRecentlyEntered() {
    show(entries = FakeEntryRepository(listOf(entryAgo(Duration.ofHours(1)))), awaitPass = false)

    composeTestRule.onNodeWithText("Already entered the pool").assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(SubscriptionQuickViewScreenTestTags.COOLDOWN_MESSAGE)
        .assertIsDisplayed()
    composeTestRule.onNodeWithText("You can show your pass again in 3h 59m.").assertIsDisplayed()
    composeTestRule
        .onAllNodesWithTag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON)
        .assertCountEquals(0)
  }

  @Test
  fun quickViewScreen_showsCooldownMessage_whenNoActiveSubscription() {
    show(
        subscriptions = FakeSubscriptionRepository(),
        entries = FakeEntryRepository(listOf(entryAgo(Duration.ofHours(4).plusMinutes(30)))),
        awaitPass = false,
    )

    composeTestRule.onNodeWithText("You can show your pass again in 29m.").assertIsDisplayed()
    composeTestRule
        .onAllNodesWithTag(SubscriptionQuickViewScreenTestTags.COOLDOWN_MESSAGE)
        .assertCountEquals(1)
  }

  @Test
  fun quickViewScreen_showsPass_whenCooldownEndedExactlyFiveHoursAgo() {
    show(entries = FakeEntryRepository(listOf(entryAgo(Duration.ofHours(5)))))

    composeTestRule
        .onNodeWithTag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON)
        .assertIsDisplayed()
    composeTestRule
        .onAllNodesWithTag(SubscriptionQuickViewScreenTestTags.COOLDOWN_MESSAGE)
        .assertCountEquals(0)
  }

  @Test
  fun quickViewScreen_showsLessThanAMinute_whenCooldownAlmostOver() {
    show(
        entries = FakeEntryRepository(listOf(entryAgo(Duration.ofHours(5).minusSeconds(20)))),
        awaitPass = false,
    )

    composeTestRule
        .onNodeWithText("You can show your pass again in less than a minute.")
        .assertIsDisplayed()
  }

  @Test
  fun quickViewScreen_locksPass_whenEntryRecordedWhileShown() {
    val entries = FakeEntryRepository()
    show(entries = entries)
    composeTestRule
        .onNodeWithTag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON)
        .assertIsDisplayed()

    runBlocking { entries.addEntry(entryAgo(Duration.ofMinutes(1))) }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("Already entered the pool").assertIsDisplayed()
  }

  @Test
  fun quickViewScreen_setsMaxBrightnessAndRestoresIt_whenPassHiddenByCooldown() {
    val entries = FakeEntryRepository()
    val originalBrightness = screenBrightness
    show(entries = entries)
    composeTestRule.waitForIdle()

    assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL, screenBrightness, 0f)

    runBlocking { entries.addEntry(entryAgo(Duration.ofMinutes(1))) }
    composeTestRule.waitForIdle()

    assertEquals(originalBrightness, screenBrightness, 0f)
  }

  @Test
  fun quickViewScreen_keepsBrightnessUntouched_whenNoActiveSubscription() {
    val originalBrightness = screenBrightness
    show(subscriptions = FakeSubscriptionRepository(), awaitPass = false)
    composeTestRule.waitForIdle()

    assertEquals(originalBrightness, screenBrightness, 0f)
  }

  @Test
  fun quickViewScreen_keepsBrightnessUntouched_whenCooldownActive() {
    val originalBrightness = screenBrightness
    show(entries = FakeEntryRepository(listOf(entryAgo(Duration.ofHours(1)))), awaitPass = false)
    composeTestRule.waitForIdle()

    assertEquals(originalBrightness, screenBrightness, 0f)
  }

  @Test
  fun quickViewScreen_stillRecordsEntry_whenNoNavigationActions() {
    val entries = FakeEntryRepository()
    show(entries = entries, navigation = null)

    composeTestRule.onNodeWithTag(NavigationTestTags.GO_BACK_BUTTON).performClick()
    composeTestRule.onNodeWithTag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON).performClick()

    assertEquals(1, entries.storedEntries.size)
  }
}
