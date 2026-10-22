package com.github.se.pooltrack.model.subscription

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassImageCacheTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var directory: File
  private lateinit var cache: PassImageCache

  @Before
  fun setUp() {
    directory = File(temporaryFolder.root, "pass_image")
    cache = PassImageCache(directory)
  }

  private fun bitmap(width: Int = 4, height: Int = 6) =
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

  @Test
  fun load_returnsNull_whenNothingWasSaved() {
    assertNull(cache.load("content://docs/a.pdf"))
  }

  @Test
  fun load_returnsTheSavedImage() {
    cache.save("content://docs/a.pdf", bitmap(4, 6))

    val loaded = cache.load("content://docs/a.pdf")

    assertNotNull(loaded)
    assertEquals(4, loaded!!.width)
    assertEquals(6, loaded.height)
  }

  @Test
  fun load_returnsNull_forAnotherPass() {
    cache.save("content://docs/a.pdf", bitmap())

    assertNull(cache.load("content://docs/b.pdf"))
  }

  @Test
  fun save_keepsOnlyTheLatestPass() {
    cache.save("content://docs/a.pdf", bitmap())
    cache.save("content://docs/b.pdf", bitmap())

    assertNull(cache.load("content://docs/a.pdf"))
    assertNotNull(cache.load("content://docs/b.pdf"))
    assertEquals(1, directory.listFiles()!!.size)
  }
}
