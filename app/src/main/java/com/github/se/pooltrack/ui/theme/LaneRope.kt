package com.github.se.pooltrack.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The colors of a lane rope's floats. */
enum class FloatColor {
  RED,
  WHITE,
  BLUE,
}

/** How many floats at each end of a lane rope are red, warning swimmers that the wall is near. */
const val RED_FLOATS_AT_WALLS = 3

/**
 * The color of float [index] out of [count] on a lane rope: red near both walls, then blue and
 * white alternating in between, like a racing lane rope.
 */
fun laneFloatColor(index: Int, count: Int): FloatColor =
    when {
      index < RED_FLOATS_AT_WALLS || index >= count - RED_FLOATS_AT_WALLS -> FloatColor.RED
      index % 2 == 0 -> FloatColor.BLUE
      else -> FloatColor.WHITE
    }

private val RopeRed = Color(0xFFE5484D)
private val RopeWhite = Color(0xFFF2F6F8)
private val RopeBlue = Color(0xFF2E86DE)

private fun FloatColor.toColor(): Color =
    when (this) {
      FloatColor.RED -> RopeRed
      FloatColor.WHITE -> RopeWhite
      FloatColor.BLUE -> RopeBlue
    }

/**
 * A lane rope across the available width: a thin cord threaded through capsule-shaped floats, red
 * near the ends and blue and white in between (see [laneFloatColor]). Plain shapes, nothing
 * animated.
 */
@Composable
fun LaneRope(modifier: Modifier = Modifier, height: Dp = 10.dp) {
  val cord = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
  Canvas(modifier = modifier.fillMaxWidth().height(height)) {
    val floatWidth = size.height * 1.6f
    val gap = size.height * 0.35f
    val count = ((size.width + gap) / (floatWidth + gap)).toInt().coerceAtLeast(1)
    // Centre the row of floats so both ends look the same.
    val used = count * floatWidth + (count - 1) * gap
    val start = (size.width - used) / 2f
    drawLine(
        cord,
        Offset(0f, size.height / 2f),
        Offset(size.width, size.height / 2f),
        strokeWidth = 1.5.dp.toPx(),
    )
    repeat(count) { i ->
      drawRoundRect(
          color = laneFloatColor(i, count).toColor(),
          topLeft = Offset(start + i * (floatWidth + gap), 0f),
          size = Size(floatWidth, size.height),
          cornerRadius = CornerRadius(size.height / 2f),
      )
    }
  }
}
