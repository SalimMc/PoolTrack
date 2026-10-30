package com.github.se.pooltrack.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Concentric ripples, as when something just touched the water, drawn in [color] and centred in the
 * space [modifier] gives them. With [spread], they grow out from the centre once when first shown;
 * they never animate forever. Plain shapes in the theme's colors, no images.
 */
@Composable
fun Ripples(color: Color, modifier: Modifier = Modifier, spread: Boolean = false) {
  var shown by remember { mutableStateOf(!spread) }
  LaunchedEffect(Unit) { shown = true }
  val progress by
      animateFloatAsState(
          targetValue = if (shown) 1f else 0f,
          animationSpec = tween(durationMillis = 1_200),
          label = "ripples",
      )
  Canvas(modifier = modifier) { drawRipples(color, progress) }
}

/**
 * The radius of the ring at [fraction] of the full size once the ripples have spread to [progress]
 * (0 to 1): outer rings start later, so they seem to travel outwards.
 */
fun rippleRadius(maxRadius: Float, fraction: Float, progress: Float): Float {
  val delay = (fraction - RIPPLE_RINGS.first().first) * 0.5f
  val ringProgress = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
  return maxRadius * fraction * (0.4f + 0.6f * ringProgress)
}

/** Each ring's size (as a fraction of the full radius) and opacity, innermost first. */
private val RIPPLE_RINGS = listOf(0.35f to 1f, 0.68f to 0.5f, 1f to 0.22f)

private fun DrawScope.drawRipples(color: Color, progress: Float) {
  val maxRadius = size.minDimension / 2f
  RIPPLE_RINGS.forEach { (fraction, alpha) ->
    drawCircle(
        color = color.copy(alpha = color.alpha * alpha * progress.coerceIn(0f, 1f)),
        radius = (rippleRadius(maxRadius, fraction, progress) - 1.dp.toPx()).coerceAtLeast(0f),
        style = Stroke(width = 2.dp.toPx()),
    )
  }
}
