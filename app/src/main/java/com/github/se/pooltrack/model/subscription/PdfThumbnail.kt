package com.github.se.pooltrack.model.subscription

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Renders the first page of the PDF at [uri] to a [Bitmap], or `null` if it can't be opened or has
 * no pages.
 */
fun renderFirstPdfPage(context: Context, uri: Uri): Bitmap? {
  val pfd =
      try {
        context.contentResolver.openFileDescriptor(uri, "r")
      } catch (_: FileNotFoundException) {
        null
      } catch (_: SecurityException) {
        null
      } ?: return null
  pfd.use {
    PdfRenderer(it).use { renderer ->
      if (renderer.pageCount == 0) return null
      renderer.openPage(0).use { page ->
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
      }
    }
  }
}

/** The longest side, in pixels, an image pass is decoded at; larger photos are scaled down. */
private const val MAX_IMAGE_SIDE = 4096

/**
 * Renders the pass at [uri] to a [Bitmap]: the image itself if it is a PNG/JPEG (e.g. a screenshot
 * of a QR code), otherwise the first page of the PDF. `null` if neither can be opened.
 */
fun renderPassFirstPage(context: Context, uri: Uri): Bitmap? =
    decodeImage(context, uri) ?: renderFirstPdfPage(context, uri)

/** Decodes the image at [uri], or returns `null` if it is not an image or can't be opened. */
private fun decodeImage(context: Context, uri: Uri): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  if (!readImage(context, uri, bounds) || bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
  val options = BitmapFactory.Options().apply { inSampleSize = sample }
  var bitmap: Bitmap? = null
  readImage(context, uri, options) { bitmap = it }
  return bitmap
}

/** Runs [BitmapFactory.decodeStream] on [uri]; `false` if the stream can't be opened or read. */
private fun readImage(
    context: Context,
    uri: Uri,
    options: BitmapFactory.Options,
    onDecoded: (Bitmap?) -> Unit = {},
): Boolean =
    try {
      val input = context.contentResolver.openInputStream(uri)
      if (input == null) {
        false
      } else {
        input.use { onDecoded(BitmapFactory.decodeStream(it, null, options)) }
        true
      }
    } catch (_: IOException) {
      false
    } catch (_: SecurityException) {
      false
    }
