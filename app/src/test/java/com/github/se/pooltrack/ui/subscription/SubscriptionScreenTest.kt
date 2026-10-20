package com.github.se.pooltrack.ui.subscription

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import com.github.se.pooltrack.model.entry.Entry
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.model.subscription.renderFirstPdfPage
import com.github.se.pooltrack.ui.navigation.NavigationActions
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.ui.navigation.Screen
import com.github.se.pooltrack.utils.FakeEntryRepository
import com.github.se.pooltrack.utils.FakeSubscriptionRepository
import com.github.se.pooltrack.utils.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubscriptionScreenTest {

  @get:Rule val mainDispatcherRule = MainDispatcherRule()
  @get:Rule val composeRule = createComposeRule()

  private lateinit var previousLocale: Locale

  private val renderedUris = mutableListOf<String>()
  private var renderedBitmap: Bitmap? = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

  private lateinit var pdfUri: Uri
  private lateinit var older: Subscription
  private lateinit var newer: Subscription

  @Before
  fun setUp() {
    previousLocale = Locale.getDefault()
    Locale.setDefault(Locale.US)
    // PdfRenderer is a native Android class Robolectric cannot run, and renderFirstPdfPage has no
    // injection seam, so the PDF boundary itself is stubbed: it "renders" a tiny bitmap.
    mockkStatic("com.github.se.pooltrack.model.subscription.PdfThumbnailKt")
    every { renderFirstPdfPage(any(), any()) } answers
        {
          renderedUris += secondArg<Uri>().toString()
          renderedBitmap
        }
    pdfUri = Uri.parse("content://pooltrack/pass.pdf")
    older =
        Subscription(
            id = "old",
            uri = pdfUri.toString(),
            displayName = "Old pass",
            addedAtEpochMilli = 1_000_000_000_000L,
        )
    newer = older.copy(id = "new", displayName = "New pass", addedAtEpochMilli = 1_700_000_000_000L)
  }

  @After
  fun tearDown() {
    Locale.setDefault(previousLocale)
    unmockkStatic("com.github.se.pooltrack.model.subscription.PdfThumbnailKt")
  }

  /** Registry that answers every launch with [result], recording the launched input. */
  private class FakeRegistry(private val result: Uri?) : ActivityResultRegistry() {
    var launchedInput: Any? = null

    override fun <I : Any?, O : Any?> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
      launchedInput = input
      @Suppress("UNCHECKED_CAST") dispatchResult(requestCode, result as O)
    }
  }

  private class RecordingNavigation : NavigationActions(mockk(relaxed = true)) {
    val destinations = mutableListOf<Screen>()

    override fun navigateTo(screen: Screen) {
      destinations += screen
    }
  }

  private fun setScreen(
      subscriptions: FakeSubscriptionRepository = FakeSubscriptionRepository(),
      entries: FakeEntryRepository = FakeEntryRepository(),
      pickedUri: Uri? = pdfUri,
      navigation: NavigationActions? = null,
  ): FakeRegistry {
    val registry = FakeRegistry(pickedUri)
    val owner =
        object : ActivityResultRegistryOwner {
          override val activityResultRegistry = registry
        }
    val viewModel = SubscriptionViewModel(subscriptions, entries)
    composeRule.setContent {
      CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        SubscriptionScreen(viewModel, navigation)
      }
    }
    return registry
  }

  private fun formatted(epochMilli: Long): String =
      DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
          .withZone(ZoneId.systemDefault())
          .format(Instant.ofEpochMilli(epochMilli))

  private fun tag(tag: String) = composeRule.onNodeWithTag(tag)

  private fun dialogText(text: String) =
      composeRule.onNode(
          hasText(text) and hasAnyAncestor(hasTestTag(SubscriptionScreenTestTags.DETAIL_DIALOG))
      )

  private fun openAddDialog() {
    tag(SubscriptionScreenTestTags.ADD_BUTTON).performClick()
    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertIsDisplayed()
  }

  // ---------- empty state ----------

  @Test
  fun subscriptionScreen_showsEmptyState_whenNoSubscriptions() {
    setScreen()

    tag(SubscriptionScreenTestTags.EMPTY_MESSAGE).assertTextEquals("No subscriptions yet")
    tag(SubscriptionScreenTestTags.EMPTY_ADD_BUTTON).assertIsDisplayed()
    composeRule.onNodeWithText("Subscriptions you add will show up here.").assertIsDisplayed()
    tag(SubscriptionScreenTestTags.SUBSCRIPTION_LIST).assertDoesNotExist()
  }

  @Test
  fun subscriptionScreen_emptyAddButton_launchesPdfPicker() {
    val registry = setScreen()

    tag(SubscriptionScreenTestTags.EMPTY_ADD_BUTTON).performClick()

    assertEquals(listOf("application/pdf"), (registry.launchedInput as Array<*>).toList())
    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_topBarAddButton_launchesPdfPicker() {
    val registry = setScreen()

    openAddDialog()

    assertEquals(listOf("application/pdf"), (registry.launchedInput as Array<*>).toList())
  }

  @Test
  fun subscriptionScreen_showsNoDialog_whenPickerReturnsNothing() {
    setScreen(pickedUri = null)

    tag(SubscriptionScreenTestTags.ADD_BUTTON).performClick()

    composeRule.waitForIdle()
    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertDoesNotExist()
  }

  // ---------- loaded list ----------

  @Test
  fun subscriptionScreen_listsSubscriptionsMostRecentFirst_withAddedDates() {
    setScreen(FakeSubscriptionRepository(listOf(older, newer)))

    tag(SubscriptionScreenTestTags.EMPTY_MESSAGE).assertDoesNotExist()
    composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).assertCountEquals(2)
    composeRule
        .onAllNodesWithTag(SubscriptionScreenTestTags.THUMBNAIL, useUnmergedTree = true)
        .assertCountEquals(2)
    val items = composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM)
    items[0].assertTextContains("New pass", substring = true)
    items[0].assertTextContains("Added ${formatted(newer.addedAtEpochMilli)}", substring = true)
    items[1].assertTextContains("Old pass", substring = true)
    items[1].assertTextContains("Added ${formatted(older.addedAtEpochMilli)}", substring = true)
  }

  @Test
  fun subscriptionScreen_marksOnlyTheActiveSubscriptionWithABadge() {
    setScreen(FakeSubscriptionRepository(listOf(older, newer), initialActiveId = "old"))

    composeRule
        .onAllNodesWithTag(SubscriptionScreenTestTags.ACTIVE_BADGE, useUnmergedTree = true)
        .assertCountEquals(1)
    composeRule
        .onNode(
            hasTestTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM) and
                hasAnyDescendant(hasTestTag(SubscriptionScreenTestTags.ACTIVE_BADGE)) and
                hasAnyDescendant(hasText("Old pass")),
            useUnmergedTree = true,
        )
        .assertExists()
  }

  @Test
  fun subscriptionScreen_hasNoBadge_whenNoSubscriptionIsActive() {
    setScreen(FakeSubscriptionRepository(listOf(older, newer)))

    composeRule
        .onAllNodesWithTag(SubscriptionScreenTestTags.ACTIVE_BADGE, useUnmergedTree = true)
        .assertCountEquals(0)
  }

  @Test
  fun subscriptionScreen_rowShowsFutureExpirationAndPrice() {
    val expiresAt = Instant.now().plus(Duration.ofDays(40))
    val subscription = newer.copy(expiresAtEpochMilli = expiresAt.toEpochMilli(), price = 29.9)
    setScreen(FakeSubscriptionRepository(listOf(subscription)))

    composeRule
        .onNodeWithText("Expires ${formatted(expiresAt.toEpochMilli())} · Price: 29.90")
        .assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_rowShowsExpired_whenExpirationDateHasPassed() {
    val expiresAt = Instant.now().minus(Duration.ofDays(3)).toEpochMilli()
    setScreen(FakeSubscriptionRepository(listOf(newer.copy(expiresAtEpochMilli = expiresAt))))

    composeRule.onNodeWithText("Expired ${formatted(expiresAt)}").assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_rowShowsRemainingEntries_usingOnlyThatSubscriptionsEntries() {
    val subscription = newer.copy(maxEntries = 10)
    val entries =
        FakeEntryRepository(
            listOf(
                Entry(timestampEpochMilli = 1L, subscriptionId = "new"),
                Entry(timestampEpochMilli = 2L, subscriptionId = "new"),
                Entry(timestampEpochMilli = 3L, subscriptionId = "other"),
                Entry(timestampEpochMilli = 4L, subscriptionId = null),
            )
        )
    setScreen(FakeSubscriptionRepository(listOf(subscription)), entries)

    composeRule.onNodeWithText("8 of 10 entries left").assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_rowShowsSingularEntry_andNeverNegative() {
    val subscription = newer.copy(maxEntries = 1)
    val entries =
        FakeEntryRepository(
            listOf(
                Entry(timestampEpochMilli = 1L, subscriptionId = "new"),
                Entry(timestampEpochMilli = 2L, subscriptionId = "new"),
            )
        )
    setScreen(FakeSubscriptionRepository(listOf(subscription)), entries)

    composeRule.onNodeWithText("0 of 1 entry left").assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_rowShowsPriceOnly_whenNoExpiration() {
    setScreen(FakeSubscriptionRepository(listOf(newer.copy(price = 5.0))))

    composeRule.onNodeWithText("Price: 5.00").assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_rowHasNoDetailLine_whenNoExpirationAndNoPrice() {
    setScreen(FakeSubscriptionRepository(listOf(newer)))

    composeRule.onNodeWithText("Price", substring = true).assertDoesNotExist()
    composeRule.onNodeWithText("Expire", substring = true).assertDoesNotExist()
    composeRule.onNodeWithText("left", substring = true).assertDoesNotExist()
  }

  // ---------- detail dialog ----------

  @Test
  fun subscriptionScreen_tappingRow_opensDetailDialogForThatSubscription() {
    setScreen(FakeSubscriptionRepository(listOf(older, newer)))

    composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM)[1].performClick()

    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertIsDisplayed()
    dialogText("Old pass").assertIsDisplayed()
    dialogText("Added ${formatted(older.addedAtEpochMilli)}").assertIsDisplayed()
    composeRule.onNodeWithText("Not active").assertIsDisplayed()
    tag(SubscriptionScreenTestTags.SET_ACTIVE_BUTTON).assertIsDisplayed()
    tag(SubscriptionScreenTestTags.DELETE_BUTTON).assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_detailDialog_offersToAddThePdf_whenItWasRestoredWithoutOne() {
    setScreen(FakeSubscriptionRepository(listOf(older.copy(uri = ""))))

    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()

    tag(SubscriptionScreenTestTags.ATTACH_PDF_BUTTON).assertIsDisplayed()
    composeRule.onNodeWithText("No PDF on this device").assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_detailDialog_hasNoAddPdfButton_whenThePdfIsThere() {
    setScreen(FakeSubscriptionRepository(listOf(older)))

    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()

    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertIsDisplayed()
    tag(SubscriptionScreenTestTags.ATTACH_PDF_BUTTON).assertDoesNotExist()
  }

  @Test
  fun subscriptionScreen_detailDialogOfActiveSubscription_hasNoSetActiveButton() {
    setScreen(FakeSubscriptionRepository(listOf(newer), initialActiveId = "new"))

    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()

    composeRule.onNodeWithText("Currently active").assertIsDisplayed()
    tag(SubscriptionScreenTestTags.SET_ACTIVE_BUTTON).assertDoesNotExist()
    tag(SubscriptionScreenTestTags.DELETE_BUTTON).assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_detailDialogShowsExpirationPriceAndPricePerEntry() {
    val subscription = newer.copy(maxEntries = 4, price = 10.0)
    val entries = FakeEntryRepository(listOf(Entry(1L, subscriptionId = "new")))
    setScreen(FakeSubscriptionRepository(listOf(subscription)), entries)

    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()

    composeRule.onNodeWithText("3 of 4 entries left").assertIsDisplayed()
    composeRule.onNodeWithText("Price: 10.00  ·  2.50 / entry").assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_detailDialogShowsExpiredInDateOfExpiration() {
    val expiresAt = Instant.now().minus(Duration.ofDays(2)).toEpochMilli()
    setScreen(FakeSubscriptionRepository(listOf(newer.copy(expiresAtEpochMilli = expiresAt))))

    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()

    dialogText("Expired ${formatted(expiresAt)}").assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_detailDialogOmitsOptionalLines_whenSubscriptionHasNone() {
    setScreen(FakeSubscriptionRepository(listOf(newer)))

    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()

    composeRule.onNodeWithText("Price", substring = true).assertDoesNotExist()
    composeRule.onNodeWithText("Expire", substring = true).assertDoesNotExist()
  }

  @Test
  fun subscriptionScreen_closeButton_dismissesDetailDialog() {
    setScreen(FakeSubscriptionRepository(listOf(newer)))
    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()

    composeRule.onNodeWithContentDescription("Close").performClick()

    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertDoesNotExist()
  }

  @Test
  fun subscriptionScreen_setActive_activatesSubscription_andClosesDialog() {
    val subscriptions = FakeSubscriptionRepository(listOf(older, newer), initialActiveId = "new")
    setScreen(subscriptions)
    composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM)[1].performClick()

    tag(SubscriptionScreenTestTags.SET_ACTIVE_BUTTON).performClick()

    assertEquals("old", subscriptions.storedActiveId)
    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertDoesNotExist()
    composeRule
        .onAllNodesWithTag(SubscriptionScreenTestTags.ACTIVE_BADGE, useUnmergedTree = true)
        .assertCountEquals(1)
  }

  // ---------- delete ----------

  @Test
  fun subscriptionScreen_delete_asksForConfirmation_beforeRemoving() {
    val subscriptions = FakeSubscriptionRepository(listOf(older, newer))
    setScreen(subscriptions)
    composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM)[0].performClick()

    tag(SubscriptionScreenTestTags.DELETE_BUTTON).performClick()

    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertDoesNotExist()
    composeRule.onNodeWithText("Delete this subscription?").assertIsDisplayed()
    composeRule.onNodeWithText("\"New pass\" will be permanently removed.").assertIsDisplayed()
    assertEquals(listOf(older, newer), subscriptions.storedSubscriptions)
  }

  @Test
  fun subscriptionScreen_deleteDialog_saysEntriesStay_whenSubscriptionHasEntries() {
    val entries =
        FakeEntryRepository(
            listOf(
                Entry(timestampEpochMilli = 1L, subscriptionId = "new"),
                Entry(timestampEpochMilli = 2L, subscriptionId = "new"),
                Entry(timestampEpochMilli = 3L, subscriptionId = "old"),
            )
        )
    setScreen(FakeSubscriptionRepository(listOf(older, newer)), entries)
    composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM)[0].performClick()

    tag(SubscriptionScreenTestTags.DELETE_BUTTON).performClick()

    tag(SubscriptionScreenTestTags.DELETE_ENTRIES_NOTE)
        .assertTextEquals("Its 2 entries stay in your history.")
  }

  @Test
  fun subscriptionScreen_deleteDialog_omitsEntriesNote_whenSubscriptionHasNoEntries() {
    setScreen(FakeSubscriptionRepository(listOf(newer)))
    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()

    tag(SubscriptionScreenTestTags.DELETE_BUTTON).performClick()

    tag(SubscriptionScreenTestTags.DELETE_ENTRIES_NOTE).assertDoesNotExist()
  }

  @Test
  fun deletionEntriesNote_isSingular_forOneEntry() {
    assertEquals("Its 1 entry stays in your history.", deletionEntriesNote(1))
    assertEquals("Its 5 entries stay in your history.", deletionEntriesNote(5))
  }

  @Test
  fun subscriptionScreen_confirmDelete_removesOnlyThatSubscription() {
    val subscriptions = FakeSubscriptionRepository(listOf(older, newer))
    setScreen(subscriptions)
    composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM)[0].performClick()
    tag(SubscriptionScreenTestTags.DELETE_BUTTON).performClick()

    tag(SubscriptionScreenTestTags.CONFIRM_DELETE_BUTTON).performClick()

    assertEquals(listOf(older), subscriptions.storedSubscriptions)
    composeRule.onNodeWithText("Delete this subscription?").assertDoesNotExist()
    composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).assertCountEquals(1)
  }

  @Test
  fun subscriptionScreen_confirmDeleteOfLastSubscription_showsEmptyState() {
    val subscriptions = FakeSubscriptionRepository(listOf(newer))
    setScreen(subscriptions)
    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()
    tag(SubscriptionScreenTestTags.DELETE_BUTTON).performClick()

    tag(SubscriptionScreenTestTags.CONFIRM_DELETE_BUTTON).performClick()

    assertTrue(subscriptions.storedSubscriptions.isEmpty())
    tag(SubscriptionScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_cancelDelete_keepsSubscription() {
    val subscriptions = FakeSubscriptionRepository(listOf(newer))
    setScreen(subscriptions)
    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()
    tag(SubscriptionScreenTestTags.DELETE_BUTTON).performClick()

    tag(SubscriptionScreenTestTags.CANCEL_DELETE_BUTTON).performClick()

    assertEquals(listOf(newer), subscriptions.storedSubscriptions)
    composeRule.onNodeWithText("Delete this subscription?").assertDoesNotExist()
  }

  // ---------- edit flow ----------

  private fun openEditDialog() {
    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()
    tag(SubscriptionScreenTestTags.EDIT_BUTTON).performClick()
    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_edit_prefillsFields_andSavesNewNameAndPrice() {
    val limited = newer.copy(maxEntries = 10, price = 60.0)
    val subscriptions = FakeSubscriptionRepository(listOf(limited))
    setScreen(subscriptions)
    openEditDialog()

    tag(SubscriptionScreenTestTags.EDIT_NAME_FIELD).assertTextContains("New pass")
    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_FIELD).assertTextContains("10")
    tag(SubscriptionScreenTestTags.EXPIRATION_PRICE_FIELD).assertTextContains("60")
    tag(SubscriptionScreenTestTags.EDIT_NAME_FIELD).performTextReplacement("Gym card")
    tag(SubscriptionScreenTestTags.EXPIRATION_PRICE_FIELD).performTextReplacement("45.5")
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).performClick()

    val saved = subscriptions.storedSubscriptions.single()
    assertEquals(limited.copy(displayName = "Gym card", price = 45.5), saved)
    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertDoesNotExist()
  }

  @Test
  fun subscriptionScreen_edit_keepsExistingExpiryUnlessChanged() {
    val dated = newer.copy(expiresAtEpochMilli = 4_000_000_000_000L)
    val subscriptions = FakeSubscriptionRepository(listOf(dated))
    setScreen(subscriptions)
    openEditDialog()

    tag(SubscriptionScreenTestTags.EXPIRATION_KEEP_BUTTON).assertIsDisplayed()
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).assertIsEnabled().performClick()

    assertEquals(dated, subscriptions.storedSubscriptions.single())
  }

  @Test
  fun subscriptionScreen_edit_switchingFromDateToEntries_clearsTheDate() {
    val dated = newer.copy(expiresAtEpochMilli = 4_000_000_000_000L)
    val subscriptions = FakeSubscriptionRepository(listOf(dated))
    setScreen(subscriptions)
    openEditDialog()

    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_BUTTON).performClick()
    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_FIELD).performTextInput("5")
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).performClick()

    val saved = subscriptions.storedSubscriptions.single()
    assertNull(saved.expiresAtEpochMilli)
    assertEquals(5, saved.maxEntries)
  }

  @Test
  fun subscriptionScreen_edit_blankNameDisablesSave() {
    setScreen(FakeSubscriptionRepository(listOf(newer)))
    openEditDialog()

    tag(SubscriptionScreenTestTags.EDIT_NAME_FIELD).performTextReplacement("  ")

    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).assertIsNotEnabled()
  }

  @Test
  fun subscriptionScreen_edit_cancelChangesNothing() {
    val subscriptions = FakeSubscriptionRepository(listOf(newer))
    setScreen(subscriptions)
    openEditDialog()

    tag(SubscriptionScreenTestTags.EDIT_NAME_FIELD).performTextReplacement("Other")
    composeRule.onNodeWithText("Cancel").performClick()

    assertEquals(listOf(newer), subscriptions.storedSubscriptions)
    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertDoesNotExist()
  }

  @Test
  fun subscriptionScreen_edit_isAvailableForExpiredSubscriptions() {
    val expired = newer.copy(expiresAtEpochMilli = 1_000L)
    setScreen(FakeSubscriptionRepository(listOf(expired)))

    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()

    tag(SubscriptionScreenTestTags.EDIT_BUTTON).assertIsDisplayed()
  }

  // ---------- add flow ----------

  @Test
  fun subscriptionScreen_addWithDefaults_storesPickedUriWithNoDetails() {
    val subscriptions = FakeSubscriptionRepository()
    setScreen(subscriptions)
    openAddDialog()

    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).assertIsEnabled().performClick()

    val added = subscriptions.storedSubscriptions.single()
    assertEquals(pdfUri.toString(), added.uri)
    assertNull(added.expiresAtEpochMilli)
    assertNull(added.maxEntries)
    assertNull(added.price)
    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertDoesNotExist()
    composeRule.onAllNodesWithTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).assertCountEquals(1)
  }

  @Test
  fun subscriptionScreen_cancelAdd_storesNothing() {
    val subscriptions = FakeSubscriptionRepository()
    setScreen(subscriptions)
    openAddDialog()

    composeRule.onNodeWithText("Cancel").performClick()

    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertDoesNotExist()
    assertTrue(subscriptions.storedSubscriptions.isEmpty())
  }

  @Test
  fun subscriptionScreen_addByEntries_requiresPositiveCount_andStoresIt() {
    val subscriptions = FakeSubscriptionRepository()
    setScreen(subscriptions)
    openAddDialog()
    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_BUTTON).performClick()

    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).assertIsNotEnabled()
    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_FIELD).performTextInput("0")
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).assertIsNotEnabled()
    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_FIELD).performTextInput("a1b2")
    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_FIELD).assertTextContains("012")
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).assertIsEnabled().performClick()

    val added = subscriptions.storedSubscriptions.single()
    assertEquals(12, added.maxEntries)
    assertNull(added.expiresAtEpochMilli)
  }

  @Test
  fun subscriptionScreen_addWithPrice_ignoresNonNumericCharacters() {
    val subscriptions = FakeSubscriptionRepository()
    setScreen(subscriptions)
    openAddDialog()

    tag(SubscriptionScreenTestTags.EXPIRATION_PRICE_FIELD).performTextInput("1x2.5-")
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).performClick()

    assertEquals(12.5, subscriptions.storedSubscriptions.single().price!!, 0.0)
  }

  @Test
  fun subscriptionScreen_addWithMalformedPrice_storesNoPrice() {
    val subscriptions = FakeSubscriptionRepository()
    setScreen(subscriptions)
    openAddDialog()

    tag(SubscriptionScreenTestTags.EXPIRATION_PRICE_FIELD).performTextInput("1.2.3")
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).performClick()

    assertNull(subscriptions.storedSubscriptions.single().price)
  }

  private fun addByDate(label: String, period: Period) {
    val subscriptions = FakeSubscriptionRepository()
    setScreen(subscriptions)
    openAddDialog()
    tag(SubscriptionScreenTestTags.EXPIRATION_DATE_BUTTON).performClick()

    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).assertIsNotEnabled()
    composeRule.onNodeWithText(label).performClick()
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).assertIsEnabled()
    val before = LocalDate.now()
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).performClick()
    val after = LocalDate.now()

    val stored = subscriptions.storedSubscriptions.single().expiresAtEpochMilli
    // Purchase date defaults to today (tolerating a midnight rollover); expiry is that plus period.
    val candidates =
        setOf(before, after).map {
          it.plus(period).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    assertTrue("$label -> $stored not in $candidates", stored in candidates)
    assertNull(subscriptions.storedSubscriptions.single().maxEntries)
  }

  @Test
  fun subscriptionScreen_addByDate_storesPurchaseDatePlusOneMonth() =
      addByDate("1 month", Period.ofMonths(1))

  @Test
  fun subscriptionScreen_addByDate_storesPurchaseDatePlusSixMonths() =
      addByDate("6 months", Period.ofMonths(6))

  @Test
  fun subscriptionScreen_addByDate_storesPurchaseDatePlusOneYear() =
      addByDate("1 year", Period.ofYears(1))

  @Test
  fun subscriptionScreen_switchingBackToNoLimit_dropsDateAndEntries() {
    val subscriptions = FakeSubscriptionRepository()
    setScreen(subscriptions)
    openAddDialog()
    tag(SubscriptionScreenTestTags.EXPIRATION_DATE_BUTTON).performClick()
    composeRule.onNodeWithText("1 year").performClick()
    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_BUTTON).performClick()
    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_FIELD).performTextInput("5")
    tag(SubscriptionScreenTestTags.EXPIRATION_NONE_BUTTON).performClick()

    tag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_FIELD).assertDoesNotExist()
    tag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON).performClick()

    val added = subscriptions.storedSubscriptions.single()
    assertNull(added.expiresAtEpochMilli)
    assertNull(added.maxEntries)
  }

  @Test
  fun subscriptionScreen_purchaseDatePicker_showsTodayAndCanBeConfirmed() {
    val subscriptions = FakeSubscriptionRepository()
    setScreen(subscriptions)
    openAddDialog()
    tag(SubscriptionScreenTestTags.EXPIRATION_DATE_BUTTON).performClick()
    val today = LocalDate.now()
    val todayText =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(today.atStartOfDay(ZoneId.systemDefault()))
    tag(SubscriptionScreenTestTags.EXPIRATION_PURCHASE_DATE_BUTTON)
        .assertTextEquals("Bought on $todayText")

    tag(SubscriptionScreenTestTags.EXPIRATION_PURCHASE_DATE_BUTTON).performClick()
    composeRule.onNodeWithText("OK").performClick()

    composeRule.onNodeWithText("OK").assertDoesNotExist()
    tag(SubscriptionScreenTestTags.EXPIRATION_PURCHASE_DATE_BUTTON)
        .assertTextEquals("Bought on $todayText")
  }

  @Test
  fun subscriptionScreen_purchaseDatePicker_cancelKeepsTheDialogsAndDate() {
    setScreen()
    openAddDialog()
    tag(SubscriptionScreenTestTags.EXPIRATION_DATE_BUTTON).performClick()

    tag(SubscriptionScreenTestTags.EXPIRATION_PURCHASE_DATE_BUTTON).performClick()
    composeRule.onAllNodesWithText("Cancel").onLast().performClick()

    composeRule.onNodeWithText("OK").assertDoesNotExist()
    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertIsDisplayed()
  }

  // ---------- mutual exclusion of dialogs ----------

  @Test
  fun subscriptionScreen_pickingPdf_closesOpenDetailDialog() {
    setScreen(FakeSubscriptionRepository(listOf(newer)))
    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()
    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertIsDisplayed()

    tag(SubscriptionScreenTestTags.ADD_BUTTON).performClick()

    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertDoesNotExist()
    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_openingRow_closesPendingAddDialog() {
    setScreen(FakeSubscriptionRepository(listOf(newer)))
    openAddDialog()

    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()

    tag(SubscriptionScreenTestTags.EXPIRATION_DIALOG).assertDoesNotExist()
    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertIsDisplayed()
  }

  // ---------- navigation ----------

  @Test
  fun subscriptionScreen_bottomBarTabs_navigateToTheirDestinations() {
    val navigation = RecordingNavigation()
    setScreen(navigation = navigation)

    tag(NavigationTestTags.HOME_TAB).performClick()
    tag(NavigationTestTags.HISTORY_TAB).performClick()

    assertEquals(listOf(Screen.Home, Screen.History), navigation.destinations)
  }

  @Test
  fun subscriptionScreen_bottomBarTab_doesNothing_withoutNavigationActions() {
    setScreen(navigation = null)

    tag(NavigationTestTags.HOME_TAB).performClick()

    tag(SubscriptionScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()
  }

  // ---------- PDF rendering ----------

  @Test
  fun subscriptionScreen_rendersEachRowsPdf_asThumbnail() {
    setScreen(FakeSubscriptionRepository(listOf(older, newer)))
    composeRule.waitForIdle()

    assertTrue(renderedUris.contains(older.uri))
    composeRule
        .onAllNodesWithTag(SubscriptionScreenTestTags.THUMBNAIL, useUnmergedTree = true)
        .assertCountEquals(2)
  }

  @Test
  fun subscriptionScreen_detailDialogRendersPreview_ofTheSelectedSubscriptionsPdf() {
    setScreen(FakeSubscriptionRepository(listOf(newer)))
    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithContentDescription("Subscription pass preview").assertIsDisplayed()
  }

  @Test
  fun subscriptionScreen_showsNoPreviewImage_whenPdfCannotBeRendered() {
    renderedBitmap = null
    setScreen(FakeSubscriptionRepository(listOf(newer)))
    tag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM).performClick()
    composeRule.waitForIdle()

    tag(SubscriptionScreenTestTags.DETAIL_DIALOG).assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Subscription pass preview").assertDoesNotExist()
  }
}
