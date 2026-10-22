package com.github.se.pooltrack.model.subscription

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Keeps the rendered pass on this device, so it opens at once and without a connection even when
 * its PDF lives in a cloud provider. Only the pass shown last is kept, like the saved zoom: it is a
 * per-device copy that is neither scoped to an account nor backed up.
 */
class PassImageCache(private val directory: File) {

  /** The cached image of the pass at [uri], or `null` if there is none or it can't be read. */
  fun load(uri: String): Bitmap? {
    val file = fileFor(uri)
    if (!file.isFile) return null
    return BitmapFactory.decodeFile(file.path)
  }

  /** Caches [bitmap] as the image of the pass at [uri], replacing any other pass's image. */
  fun save(uri: String, bitmap: Bitmap) {
    try {
      directory.mkdirs()
      val target = fileFor(uri)
      directory.listFiles()?.filter { it != target }?.forEach { it.delete() }
      val temp = File(directory, "${target.name}.tmp")
      temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      if (!temp.renameTo(target)) temp.delete()
    } catch (_: IOException) {
      // A pass that can't be cached is just rendered again next time.
    }
  }

  private fun fileFor(uri: String): File {
    val hash = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
    return File(directory, hash.joinToString("") { "%02x".format(it) } + ".png")
  }
}

/**
 * The first page of the PDF at [uri]: the copy cached on this device if there is one, otherwise
 * rendered from the PDF (and cached for next time). `null` if it has no cached copy and can't be
 * rendered.
 */
fun loadPassImage(context: Context, uri: Uri): Bitmap? {
  val cache = PassImageCache(File(context.filesDir, "pass_image"))
  cache.load(uri.toString())?.let {
    return it
  }
  return renderFirstPdfPage(context, uri)?.also { cache.save(uri.toString(), it) }
}
