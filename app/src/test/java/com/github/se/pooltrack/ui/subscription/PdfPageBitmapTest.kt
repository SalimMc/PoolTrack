package com.github.se.pooltrack.ui.subscription

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PdfPageBitmapTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun pdfMimeTypes_acceptsOnlyPdf() {
    assertArrayEquals(arrayOf("application/pdf"), PDF_MIME_TYPES)
  }

  @Test
  fun loadPdfFirstPage_rendersTheGivenUriOnTheGivenDispatcher() =
      runTest(StandardTestDispatcher()) {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        var renderedUri: String? = null

        val result =
            loadPdfFirstPage(context, "content://pass/1", StandardTestDispatcher(testScheduler)) {
                _,
                uri ->
              renderedUri = uri.toString()
              bitmap
            }

        assertSame(bitmap, result)
        assertEquals("content://pass/1", renderedUri)
      }

  @Test
  fun loadPdfFirstPage_returnsNullWhenRenderingFails() =
      runTest(StandardTestDispatcher()) {
        val result =
            loadPdfFirstPage(context, "content://pass/2", StandardTestDispatcher(testScheduler)) {
                _,
                _ ->
              null
            }

        assertNull(result)
      }
}
