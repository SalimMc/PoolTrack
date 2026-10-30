package com.github.se.pooltrack.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneRopeTest {

  @Test
  fun laneFloatColor_isRedNearBothWalls_andBlueWhiteBetween() {
    val colors = (0 until 10).map { laneFloatColor(it, 10) }

    assertEquals(
        listOf(
            FloatColor.RED,
            FloatColor.RED,
            FloatColor.RED,
            FloatColor.WHITE,
            FloatColor.BLUE,
            FloatColor.WHITE,
            FloatColor.BLUE,
            FloatColor.RED,
            FloatColor.RED,
            FloatColor.RED,
        ),
        colors,
    )
  }

  @Test
  fun laneFloatColor_isAllRed_forAShortRope() {
    assertTrue((0 until 5).all { laneFloatColor(it, 5) == FloatColor.RED })
  }
}
