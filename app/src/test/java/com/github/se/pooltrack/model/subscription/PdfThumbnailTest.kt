package com.github.se.pooltrack.model.subscription

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.se.pooltrack.model.ResolverContext
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

// The JVM has no PDF engine, and Robolectric's own PdfRenderer is not usable on this SDK level, so
// the renderer and its page are replaced by small test shadows (see below).
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [FakePdfRenderer::class, FakePdfPage::class])
class PdfThumbnailTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private val uri = Uri.parse("content://docs/pass.pdf")

  private lateinit var resolver: ContentResolver
  private lateinit var context: Context

  @Before
  fun setUp() {
    FakePdfRenderer.reset()
    resolver = mockk()
    context = ResolverContext(RuntimeEnvironment.getApplication(), resolver)
  }

  private fun pdfDescriptor(): ParcelFileDescriptor {
    val file = File(temporaryFolder.root, "pass.pdf")
    file.writeText(MINIMAL_PDF)
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  }

  @Test
  fun renderFirstPdfPage_returnsNull_whenFileCannotBeOpened() {
    every { resolver.openFileDescriptor(uri, "r") } returns null

    assertNull(renderFirstPdfPage(context, uri))
  }

  @Test
  fun renderFirstPdfPage_returnsNull_whenUriIsNotFound() {
    every { resolver.openFileDescriptor(uri, "r") } throws FileNotFoundException("gone")

    assertNull(renderFirstPdfPage(context, uri))
  }

  @Test
  fun renderFirstPdfPage_returnsNull_whenUriPermissionIsRevoked() {
    every { resolver.openFileDescriptor(uri, "r") } throws SecurityException("revoked")

    assertNull(renderFirstPdfPage(context, uri))
  }

  @Test
  fun renderFirstPdfPage_returnsNull_whenPdfHasNoPages() {
    every { resolver.openFileDescriptor(uri, "r") } returns pdfDescriptor()
    FakePdfRenderer.pageCount = 0

    assertNull(renderFirstPdfPage(context, uri))
    assertEquals(1, FakePdfRenderer.closed)
    assertEquals(emptyList<Int>(), FakePdfRenderer.openedPages)
  }

  @Test
  fun renderFirstPdfPage_returnsWhiteBitmapOfFirstPageSize_whenPdfHasPages() {
    every { resolver.openFileDescriptor(uri, "r") } returns pdfDescriptor()

    val bitmap = renderFirstPdfPage(context, uri)

    assertNotNull(bitmap)
    assertEquals(PAGE_WIDTH, bitmap!!.width)
    assertEquals(PAGE_HEIGHT, bitmap.height)
    assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
    assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
    assertEquals(listOf(0), FakePdfRenderer.openedPages)
    assertEquals(listOf(PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY), FakePdfPage.renderModes)
    assertEquals(1, FakePdfPage.closed)
    assertEquals(1, FakePdfRenderer.closed)
  }

  private fun pngBytes(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.BLACK)
    return ByteArrayOutputStream()
        .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        .toByteArray()
  }

  @Test
  fun renderPassFirstPage_returnsTheImage_whenPassIsAnImage() {
    val bytes = pngBytes(12, 8)
    every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }

    val bitmap = renderPassFirstPage(context, uri)

    assertNotNull(bitmap)
    assertEquals(12, bitmap!!.width)
    assertEquals(8, bitmap.height)
    assertEquals(emptyList<Int>(), FakePdfRenderer.openedPages)
  }

  @Test
  fun renderPassFirstPage_scalesDownImagesLargerThanTheLimit() {
    val bytes = pngBytes(5000, 10)
    every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }

    val bitmap = renderPassFirstPage(context, uri)

    assertNotNull(bitmap)
    assertEquals(2500, bitmap!!.width)
  }

  @Test
  fun renderPassFirstPage_fallsBackToPdf_whenImageStreamCannotBeOpened() {
    every { resolver.openInputStream(uri) } throws FileNotFoundException("gone")
    every { resolver.openFileDescriptor(uri, "r") } returns null

    assertNull(renderPassFirstPage(context, uri))
  }

  @Test
  fun renderPassFirstPage_fallsBackToPdf_whenImageStreamIsNull() {
    every { resolver.openInputStream(uri) } returns null
    every { resolver.openFileDescriptor(uri, "r") } returns null

    assertNull(renderPassFirstPage(context, uri))
  }

  @Test
  fun renderPassFirstPage_fallsBackToPdf_whenImagePermissionIsRevoked() {
    every { resolver.openInputStream(uri) } throws SecurityException("revoked")
    every { resolver.openFileDescriptor(uri, "r") } throws SecurityException("revoked")

    assertNull(renderPassFirstPage(context, uri))
  }

  private companion object {
    const val PAGE_WIDTH = 20
    const val PAGE_HEIGHT = 30
    const val MINIMAL_PDF =
        "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 10 10]>>endobj\n" +
            "trailer<</Root 1 0 R>>\n%%EOF\n"
  }
}

@Implements(PdfRenderer::class)
class FakePdfRenderer {
  @Implementation
  fun __constructor__(input: ParcelFileDescriptor) {
    input.close()
  }

  @Implementation fun getPageCount(): Int = pageCount

  @Implementation
  fun openPage(index: Int): PdfRenderer.Page {
    openedPages += index
    return Shadow.newInstanceOf(PdfRenderer.Page::class.java)
  }

  @Implementation
  fun close() {
    closed++
  }

  companion object {
    var pageCount = 1
    val openedPages = mutableListOf<Int>()
    var closed = 0

    fun reset() {
      pageCount = 1
      openedPages.clear()
      closed = 0
      FakePdfPage.reset()
    }
  }
}

@Implements(PdfRenderer.Page::class)
class FakePdfPage {
  @Implementation fun getWidth(): Int = 20

  @Implementation fun getHeight(): Int = 30

  @Implementation
  fun render(destination: Bitmap, destClip: Rect?, transform: Matrix?, renderMode: Int) {
    renderModes += renderMode
  }

  @Implementation
  fun close() {
    closed++
  }

  companion object {
    val renderModes = mutableListOf<Int>()
    var closed = 0

    fun reset() {
      renderModes.clear()
      closed = 0
    }
  }
}
