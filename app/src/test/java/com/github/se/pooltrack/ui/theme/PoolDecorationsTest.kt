package com.github.se.pooltrack.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PoolDecorationsTest {

  @Test
  fun depthMarkerLabel_startsAtOneMetre_andGoesHalfAMetreDeeper() {
    assertEquals(
        listOf("1.0 m", "1.5 m", "2.0 m", "2.5 m"),
        (0..3).map { depthMarkerLabel(it) },
    )
  }

  @Test
  fun depthMarkerLabel_treatsNegativeIndexAsFirst() {
    assertEquals("1.0 m", depthMarkerLabel(-2))
  }

  @Test
  fun waterSurfaceY_isMeasuredFromTheTop() {
    assertEquals(200f, waterSurfaceY(level = 0f, height = 200f), 0f)
    assertEquals(50f, waterSurfaceY(level = 0.75f, height = 200f), 0f)
    assertEquals(0f, waterSurfaceY(level = 1f, height = 200f), 0f)
  }

  @Test
  fun waterSurfaceY_clampsLevelOutsideZeroToOne() {
    assertEquals(200f, waterSurfaceY(level = -0.5f, height = 200f), 0f)
    assertEquals(0f, waterSurfaceY(level = 3f, height = 200f), 0f)
  }

  @Test
  fun waterPath_isEmpty_withoutWaterOrSpace() {
    assertTrue(waterPath(Size(100f, 100f), 0f, 4f, 40f).isEmpty)
    assertTrue(waterPath(Size(0f, 100f), 0.5f, 4f, 40f).isEmpty)
    assertTrue(waterPath(Size(100f, 0f), 0.5f, 4f, 40f).isEmpty)
  }

  @Test
  fun waterPath_coversTheWidth_fromItsSurfaceDownToTheBottom() {
    val bounds = waterPath(Size(200f, 100f), 0.4f, 5f, 40f).getBounds()

    assertEquals(0f, bounds.left, 0f)
    assertEquals(200f, bounds.right, 0.5f)
    assertEquals(100f, bounds.bottom, 0f)
    // The surface sits at 60, its waves reaching up to half the amplitude above it.
    assertTrue(bounds.top in 55f..60f)
  }

  @Test
  fun waterPath_keepsAWave_whenFull() {
    val bounds = waterPath(Size(200f, 100f), 1f, 5f, 40f).getBounds()

    assertTrue(bounds.top >= 0f)
    assertEquals(100f, bounds.bottom, 0f)
  }

  @Test
  fun waterColumnShape_fillsItsBox_downToTheBottom() {
    val outline =
        WaterColumnShape(amplitude = 3.dp, wavelength = 16.dp)
            .createOutline(Size(40f, 100f), LayoutDirection.Ltr, Density(1f))
    val bounds = (outline as Outline.Generic).path.getBounds()

    assertEquals(0f, bounds.left, 0f)
    assertEquals(100f, bounds.bottom, 0f)
    assertTrue(bounds.top >= 0f && bounds.top <= 6f)
  }

  @Test
  fun entryFloats_marksUsedEntriesFirst_thenTheAvailableOnes() {
    assertEquals(
        listOf(false, false, false, true, true),
        entryFloats(usedCount = 3, maxEntries = 5),
    )
  }

  @Test
  fun entryFloats_clampsTheUsedCount() {
    assertEquals(List(4) { true }, entryFloats(usedCount = -2, maxEntries = 4))
    assertEquals(List(4) { false }, entryFloats(usedCount = 9, maxEntries = 4))
  }

  @Test
  fun entryFloats_isEmpty_withoutEntriesOrForBigPasses() {
    assertEquals(emptyList<Boolean>(), entryFloats(usedCount = 0, maxEntries = 0))
    assertEquals(MAX_ENTRY_FLOATS, entryFloats(0, MAX_ENTRY_FLOATS).size)
    assertEquals(
        emptyList<Boolean>(),
        entryFloats(usedCount = 0, maxEntries = MAX_ENTRY_FLOATS + 1),
    )
  }
}
