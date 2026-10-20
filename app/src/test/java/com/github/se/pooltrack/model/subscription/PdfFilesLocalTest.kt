package com.github.se.pooltrack.model.subscription

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import com.github.se.pooltrack.model.ResolverContext
import com.github.se.pooltrack.model.clearPreferencesDataStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PdfFilesLocalTest {

  private val currentUid = MutableStateFlow<String?>("user-1")

  private lateinit var appContext: Context
  private lateinit var resolver: ContentResolver
  private lateinit var pdfFiles: PdfFilesLocal

  @Before
  fun setUp() = runTest {
    appContext = RuntimeEnvironment.getApplication()
    clearPreferencesDataStore(
        appContext,
        "com.github.se.pooltrack.model.subscription.PdfFilesKt",
        "pdfFilesDataStore",
    )
    resolver = mockk(relaxed = true)
    pdfFiles = PdfFilesLocal(ResolverContext(appContext, resolver), currentUid)
  }

  @Test
  fun uris_isEmpty_whenNoPdfWasSaved() = runTest {
    assertEquals(emptyMap<String, String>(), pdfFiles.uris.first())
  }

  @Test
  fun save_remembersThePdfOfThatSubscription() = runTest {
    pdfFiles.save("a", "content://docs/a.pdf")
    pdfFiles.save("b", "content://docs/b.pdf")

    assertEquals(
        mapOf("a" to "content://docs/a.pdf", "b" to "content://docs/b.pdf"),
        pdfFiles.uris.first(),
    )
  }

  @Test
  fun save_replacesThePdf_ofTheSameSubscription() = runTest {
    pdfFiles.save("a", "content://docs/old.pdf")
    pdfFiles.save("a", "content://docs/new.pdf")

    assertEquals(mapOf("a" to "content://docs/new.pdf"), pdfFiles.uris.first())
  }

  @Test
  fun remove_forgetsOnlyThatPdf() = runTest {
    pdfFiles.save("a", "content://docs/a.pdf")
    pdfFiles.save("b", "content://docs/b.pdf")

    pdfFiles.remove("a")

    assertEquals(mapOf("b" to "content://docs/b.pdf"), pdfFiles.uris.first())
  }

  @Test
  fun uris_areKeptPerAccount() = runTest {
    pdfFiles.save("a", "content://docs/a.pdf")

    currentUid.value = "user-2"
    assertEquals(emptyMap<String, String>(), pdfFiles.uris.first())

    currentUid.value = "user-1"
    assertEquals(mapOf("a" to "content://docs/a.pdf"), pdfFiles.uris.first())
  }

  @Test
  fun uris_isEmpty_andSaveFails_whenNobodyIsSignedIn() = runTest {
    currentUid.value = null

    assertEquals(emptyMap<String, String>(), pdfFiles.uris.first())
    assertThrows(IllegalStateException::class.java) {
      kotlinx.coroutines.runBlocking { pdfFiles.save("a", "content://docs/a.pdf") }
    }
  }

  @Test
  fun keepReadable_takesAPersistableReadPermission() {
    pdfFiles.keepReadable("content://docs/a.pdf")

    verify { resolver.takePersistableUriPermission(Uri.parse("content://docs/a.pdf"), any()) }
  }

  @Test
  fun displayNameOf_isTheNameTheContentProviderReports() {
    answer(
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("pass.pdf")) }
    )

    assertEquals("pass.pdf", pdfFiles.displayNameOf("content://docs/a.pdf"))
  }

  @Test
  fun displayNameOf_fallsBackToAGenericLabel_whenThereIsNoName() {
    answer(MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)))

    assertEquals("Subscription", pdfFiles.displayNameOf("content://docs/a.pdf"))
  }

  private fun answer(cursor: MatrixCursor) {
    every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
  }
}
