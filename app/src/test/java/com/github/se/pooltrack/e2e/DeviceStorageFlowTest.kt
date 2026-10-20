package com.github.se.pooltrack.e2e

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.github.se.pooltrack.model.clearPreferencesDataStore
import com.github.se.pooltrack.model.productionPreferencesDataStore
import com.github.se.pooltrack.model.subscription.PassZoomRepository
import com.github.se.pooltrack.model.subscription.PassZoomRepositoryLocal
import com.github.se.pooltrack.model.subscription.PdfFiles
import com.github.se.pooltrack.model.subscription.PdfFilesLocal
import com.github.se.pooltrack.model.subscription.SavedPassZoom
import com.github.se.pooltrack.ui.home.HomeScreenTestTags
import com.github.se.pooltrack.ui.navigation.NavigationTestTags
import com.github.se.pooltrack.ui.subscription.SubscriptionQuickViewScreenTestTags
import com.github.se.pooltrack.ui.subscription.SubscriptionScreenTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric

/**
 * The device-side storage with the real code: where the PDFs are (named after what the document
 * provider says) and how the pass was last zoomed. Only the system document provider is faked.
 */
class DeviceStorageFlowTest : EndToEndTest() {

  /** The system document provider: it answers the name of the PDFs it has. */
  class FakeDocumentsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? =
        when (uri.lastPathSegment) {
          "season.pdf" ->
              MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                addRow(arrayOf<Any?>("Season 2026.pdf"))
              }
          "anonymous.pdf" ->
              MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                addRow(arrayOf<Any?>(null))
              }
          "columnless.pdf" ->
              MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(12L)) }
          "empty.pdf" -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
          else -> null
        }

    override fun getType(uri: Uri): String = "application/pdf"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
  }

  private val application: Application = ApplicationProvider.getApplicationContext()
  private val localPdfFiles by lazy { PdfFilesLocal(application, flowOf("google-uid")) }
  private val localPassZoom by lazy { PassZoomRepositoryLocal(application) }

  override fun pdfFilesForApp(): PdfFiles = localPdfFiles

  override fun passZoomForApp(): PassZoomRepository = localPassZoom

  @Before
  fun registerTheDocumentProvider() {
    Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, "documents")
    // The DataStores are cached per process, so they would leak what an earlier test stored.
    runBlocking {
      clearPreferencesDataStore(
          application,
          "com.github.se.pooltrack.model.subscription.PdfFilesKt",
          "pdfFilesDataStore",
      )
      clearPreferencesDataStore(
          application,
          "com.github.se.pooltrack.model.subscription.PassZoomRepositoryLocalKt",
          "passZoomDataStore",
      )
    }
  }

  private fun addPdf(name: String) {
    pickedPdf = Uri.parse("content://documents/$name")
    click(NavigationTestTags.SUBSCRIPTION_TAB)
    click(SubscriptionScreenTestTags.ADD_BUTTON)
    click(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON)
  }

  /** The device storage answers on its own thread, so a new pass takes a moment to be added. */
  private fun awaitSubscriptions(count: Int) {
    composeRule.waitUntil(timeoutMillis = 5_000) { store.ids("subscriptions").size == count }
    composeRule.waitForIdle()
  }

  @Test
  fun aPickedPdf_namesTheSubscriptionAfterItsFile_andIsRememberedOnTheDevice() {
    launchApp()

    addPdf("season.pdf")
    awaitSubscriptions(1)

    composeRule.onNodeWithText("Season 2026.pdf").assertIsDisplayed()
    val stored = store.ids("subscriptions").single()
    assertEquals(
        mapOf(stored to "content://documents/season.pdf"),
        runBlocking { localPdfFiles.uris.first() },
    )
  }

  @Test
  fun aPdfWhoseNameIsUnknown_isCalledSubscription() {
    launchApp()
    listOf("anonymous.pdf", "columnless.pdf", "empty.pdf", "unknown.pdf").forEach { addPdf(it) }

    awaitSubscriptions(4)
    val names =
        store.ids("subscriptions").map { store.document("subscriptions", it)!!["displayName"] }
    assertEquals(List(4) { "Subscription" }, names)
  }

  @Test
  fun deletingAPass_forgetsItsPdfOnTheDevice() {
    seedSubscription(id = "sub-1")
    launchApp()

    click(NavigationTestTags.SUBSCRIPTION_TAB)
    click(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM)
    click(SubscriptionScreenTestTags.DELETE_BUTTON)
    click(SubscriptionScreenTestTags.CONFIRM_DELETE_BUTTON)

    composeRule.waitUntil(timeoutMillis = 5_000) {
      runBlocking { localPdfFiles.uris.first() }.isEmpty()
    }
  }

  @Test
  fun zoomingThePass_isSavedWhenLeavingIt_andBuildsOnTheSavedZoomNextTime() {
    seedSubscription(id = "sub-1")
    val uri = "content://documents/sub-1.pdf"
    runBlocking { localPassZoom.save(uri, SavedPassZoom(2f, 0f, 0f)) }
    launchApp()

    click(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB)
    waitForTag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE)
    tag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE).performTouchInput {
      pinch(
          start0 = Offset(centerX - 20, centerY),
          end0 = Offset(centerX - 60, centerY),
          start1 = Offset(centerX + 20, centerY),
          end1 = Offset(centerX + 60, centerY),
      )
    }
    composeRule.waitForIdle()
    click(NavigationTestTags.GO_BACK_BUTTON)

    composeRule.waitUntil(timeoutMillis = 5_000) {
      (runBlocking { localPassZoom.load(uri) }?.scale ?: 0f) > 2f
    }
    val saved = runBlocking { localPassZoom.load(uri) }
    assertNotNull(saved)
    assertTrue("zoom ${saved?.scale} did not build on the restored 2x", saved!!.scale > 2f)
  }

  // ---------- a damaged saved zoom ----------

  /**
   * Stores a zoom for the pass of the seeded subscription with the given raw values, then shows it.
   */
  private fun showPassWithSavedZoom(
      scale: Float? = 2f,
      offsetX: Float? = 0f,
      offsetY: Float? = 0f,
      forPass: String = "content://documents/sub-1.pdf",
  ) {
    runBlocking {
      productionPreferencesDataStore(
              application,
              "com.github.se.pooltrack.model.subscription.PassZoomRepositoryLocalKt",
              "passZoomDataStore",
          )
          .edit { prefs ->
            prefs[stringPreferencesKey("pass_uri")] = forPass
            scale?.let { prefs[floatPreferencesKey("scale")] = it }
            offsetX?.let { prefs[floatPreferencesKey("offset_x_fraction")] = it }
            offsetY?.let { prefs[floatPreferencesKey("offset_y_fraction")] = it }
          }
    }
    seedSubscription(id = "sub-1")
    launchApp()
    click(HomeScreenTestTags.OPEN_SUBSCRIPTION_FAB)
    waitForTag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE)
    tag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON).assertIsDisplayed()
  }

  @Test
  fun savedZoomOfAnotherPass_isIgnored() = showPassWithSavedZoom(forPass = "content://other.pdf")

  @Test fun savedZoomWithoutItsScale_isIgnored() = showPassWithSavedZoom(scale = null)

  @Test fun savedZoomWithoutItsHorizontalOffset_isIgnored() = showPassWithSavedZoom(offsetX = null)

  @Test fun savedZoomWithoutItsVerticalOffset_isIgnored() = showPassWithSavedZoom(offsetY = null)

  @Test
  fun savedZoomWithAScaleThatIsNotANumber_isIgnored() = showPassWithSavedZoom(scale = Float.NaN)

  @Test
  fun savedZoomWithAnInfiniteOffset_isIgnored() =
      showPassWithSavedZoom(offsetX = Float.POSITIVE_INFINITY)

  @Test
  fun savedZoomWithAnOffsetThatIsNotANumber_isIgnored() = showPassWithSavedZoom(offsetY = Float.NaN)
}
