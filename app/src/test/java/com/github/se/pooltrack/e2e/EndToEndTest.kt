package com.github.se.pooltrack.e2e

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.github.se.pooltrack.PoolTrackApp
import com.github.se.pooltrack.model.auth.AuthRepository
import com.github.se.pooltrack.model.auth.AuthRepositoryProvider
import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.entry.EntryRepositoryFirestore
import com.github.se.pooltrack.model.entry.EntryRepositoryProvider
import com.github.se.pooltrack.model.subscription.PassZoomRepositoryProvider
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.model.subscription.SubscriptionRepositoryFirestore
import com.github.se.pooltrack.model.subscription.SubscriptionRepositoryProvider
import com.github.se.pooltrack.model.subscription.renderFirstPdfPage
import com.github.se.pooltrack.model.subscription.toDocument
import com.github.se.pooltrack.model.update.UpdateRepositoryProvider
import com.github.se.pooltrack.ui.theme.PoolTrackTheme
import com.github.se.pooltrack.utils.FakeAuthRepository
import com.github.se.pooltrack.utils.FakeDocumentStore
import com.github.se.pooltrack.utils.FakePassZoomRepository
import com.github.se.pooltrack.utils.FakePdfFiles
import com.github.se.pooltrack.utils.FakeUpdateRepository
import com.github.se.pooltrack.utils.FirebaseTestApp
import com.github.se.pooltrack.utils.MainDispatcherRule
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base of the end-to-end tests: it shows the whole app, [PoolTrackApp], with the real ViewModels,
 * repositories and navigation, and fakes only what is outside of the app:
 * - Firestore, through a [FakeDocumentStore] under the real Firestore repositories,
 * - Google sign-in, through a [FakeAuthRepository],
 * - the PDF files on the device, through a [FakePdfFiles], the system document picker, through a
 *   registry that answers with [pickedPdf], and the PDF renderer, which Robolectric cannot run,
 * - GitHub, through a [FakeUpdateRepository].
 */
// A tall screen, so the whole content of the long screens is on it. Not 360dp wide: at that width
// Robolectric's text measurement never settles on the subscription details dialog.
@Config(qualifiers = "w320dp-h1200dp")
@RunWith(RobolectricTestRunner::class)
abstract class EndToEndTest {

  @get:Rule(order = 0) val mainDispatcherRule = MainDispatcherRule()
  @get:Rule(order = 1) val composeRule = createComposeRule()

  protected lateinit var store: FakeDocumentStore
  protected lateinit var pdfFiles: FakePdfFiles
  protected lateinit var auth: FakeAuthRepository
  protected lateinit var updates: FakeUpdateRepository

  protected val zone: ZoneId = ZoneId.systemDefault()
  protected val today: LocalDate = LocalDate.now(zone)

  /** What the system document picker returns, `null` when the user backs out of it. */
  protected var pickedPdf: Uri? = Uri.parse("content://documents/pass.pdf")

  /** How many times the document picker was opened. */
  protected var pickerLaunches = 0

  private lateinit var originalAuth: AuthRepository
  private lateinit var previousLocale: Locale

  @Before
  fun setUpWorld() {
    previousLocale = Locale.getDefault()
    Locale.setDefault(Locale.US)
    FirebaseTestApp.ensureInitialized(ApplicationProvider.getApplicationContext())
    originalAuth = AuthRepositoryProvider.repository
    store = FakeDocumentStore()
    pdfFiles = FakePdfFiles()
    auth = FakeAuthRepository(initialUser = FakeAuthRepository.GOOGLE_USER)
    updates = FakeUpdateRepository()
    pickedPdf = Uri.parse("content://documents/pass.pdf")
    pickerLaunches = 0
    // PdfRenderer is a native Android class that Robolectric cannot run: the renderer is the
    // boundary to the outside world here, and it "renders" a tiny bitmap.
    mockkStatic("com.github.se.pooltrack.model.subscription.PdfThumbnailKt")
    every { renderFirstPdfPage(any(), any()) } answers
        {
          Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }
  }

  @After
  fun tearDownWorld() {
    unmockkStatic("com.github.se.pooltrack.model.subscription.PdfThumbnailKt")
    AuthRepositoryProvider.repository = originalAuth
    Locale.setDefault(previousLocale)
  }

  /** Answers every document picker launch with [pickedPdf]. */
  private val pickerOwner =
      object : ActivityResultRegistryOwner {
        override val activityResultRegistry =
            object : ActivityResultRegistry() {
              override fun <I : Any?, O : Any?> onLaunch(
                  requestCode: Int,
                  contract: ActivityResultContract<I, O>,
                  input: I,
                  options: ActivityOptionsCompat?,
              ) {
                pickerLaunches++
                @Suppress("UNCHECKED_CAST") dispatchResult(requestCode, pickedPdf as O)
              }
            }
      }

  /** Wires the providers to the fakes, like `MainActivity` does at startup. */
  protected fun wireProviders() {
    AuthRepositoryProvider.repository = auth
    EntryRepositoryProvider.repository = EntryRepositoryFirestore(store)
    SubscriptionRepositoryProvider.repository = SubscriptionRepositoryFirestore(store, pdfFiles)
    PassZoomRepositoryProvider.repository = FakePassZoomRepository()
    UpdateRepositoryProvider.repository = updates
  }

  /** Shows the whole app. */
  protected fun launchApp() {
    wireProviders()
    composeRule.setContent {
      CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerOwner) {
        PoolTrackTheme { PoolTrackApp() }
      }
    }
    composeRule.waitForIdle()
  }

  // ---------- what is already in the account when the app starts ----------

  /** An entry confirmed [daysAgo] days ago at [hour]:[minute], stored in the account. */
  protected fun seedEntry(
      daysAgo: Long,
      hour: Int = 12,
      minute: Int = 0,
      subscriptionId: String? = null,
      distance: Int? = null,
      durationMillis: Long? = null,
  ): Entry {
    val entry =
        Entry(
            timestampEpochMilli =
                today
                    .minusDays(daysAgo)
                    .atTime(hour, minute)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli(),
            subscriptionId = subscriptionId,
            swimDistanceMeters = distance,
            swimDurationMillis = durationMillis,
        )
    runBlocking { EntryRepositoryFirestore(store).addEntry(entry) }
    return entry
  }

  /** An entry that was confirmed [minutesAgo] minutes ago and still awaits its distance. */
  protected fun seedWaitingEntry(minutesAgo: Long): Entry {
    val entry =
        Entry(
            timestampEpochMilli = Instant.now().minusSeconds(minutesAgo * 60).toEpochMilli(),
            awaitingDistance = true,
        )
    runBlocking { EntryRepositoryFirestore(store).addEntry(entry) }
    return entry
  }

  /**
   * A subscription restored from the account: its data is there, its PDF is only there when
   * [withPdf] is set, like after reinstalling the app.
   */
  protected fun seedSubscription(
      id: String = "sub-1",
      name: String = "Season pass",
      addedDaysAgo: Long = 30,
      expiresInDays: Long? = null,
      maxEntries: Int? = null,
      price: Double? = null,
      withPdf: Boolean = true,
      active: Boolean = true,
  ): Subscription {
    val subscription =
        Subscription(
            id = id,
            uri = if (withPdf) "content://documents/$id.pdf" else "",
            displayName = name,
            addedAtEpochMilli =
                today.minusDays(addedDaysAgo).atStartOfDay(zone).toEpochSecond() * 1000,
            expiresAtEpochMilli =
                expiresInDays?.let { today.plusDays(it).atStartOfDay(zone).toEpochSecond() * 1000 },
            maxEntries = maxEntries,
            price = price,
        )
    val repository = SubscriptionRepositoryFirestore(store, pdfFiles)
    runBlocking {
      store.set("subscriptions", id, subscription.toDocument())
      if (withPdf) pdfFiles.save(id, subscription.uri)
      if (active) repository.setActiveSubscription(id)
    }
    return subscription
  }

  // ---------- driving the UI ----------

  protected fun tag(tag: String): SemanticsNodeInteraction = composeRule.onNodeWithTag(tag)

  /** Clicks the last of several elements showing [text], i.e. the one of the topmost dialog. */
  protected fun clickTopmostText(text: String) {
    composeRule.onAllNodesWithText(text).onLast().performClick()
  }

  /** The element tagged [tag] even where its parent merged it into its own semantics. */
  protected fun unmerged(tag: String): SemanticsNodeInteraction =
      composeRule.onNode(hasTestTag(tag), useUnmergedTree = true)

  protected fun click(tag: String) {
    tag(tag).performClick()
  }

  protected fun clickText(text: String) {
    composeRule.onNodeWithText(text).performClick()
  }

  /** Asserts that the element tagged [tag] shows [text] somewhere inside it. */
  protected fun assertShows(tag: String, text: String) {
    composeRule
        .onNode(hasTestTag(tag) and hasAnyDescendant(hasText(text, substring = true)))
        .assertExists("$tag does not show '$text'")
  }

  /** The system Back button. */
  protected fun pressBack() {
    @Suppress("UNCHECKED_CAST")
    val rule =
        composeRule
            as AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>
    rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    composeRule.waitForIdle()
  }

  protected fun tagExists(tag: String): Boolean =
      composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

  @OptIn(ExperimentalTestApi::class)
  protected fun waitForTag(tag: String) {
    composeRule.waitUntilExactlyOneExists(hasTestTag(tag), timeoutMillis = 5_000)
  }
}
