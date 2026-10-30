package com.github.se.pooltrack.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipplesTest {

  @Test
  fun rippleRadius_growsWithProgress_upToTheRingSize() {
    assertEquals(40f, rippleRadius(maxRadius = 100f, fraction = 1f, progress = 0f), 0.01f)
    assertEquals(100f, rippleRadius(maxRadius = 100f, fraction = 1f, progress = 1f), 0.01f)
    assertEquals(35f, rippleRadius(maxRadius = 100f, fraction = 0.35f, progress = 1f), 0.01f)
  }

  @Test
  fun rippleRadius_startsTheOuterRingsLater() {
    val inner = rippleRadius(100f, 0.35f, 0.3f) / 35f
    val outer = rippleRadius(100f, 1f, 0.3f) / 100f

    assertTrue(inner > outer)
  }
}
