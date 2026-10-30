package com.github.se.pooltrack.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/*
 * The "Grand bain" touches of Home: light reflections, the lines of a pool floor, depth markers, a
 * water level, water columns and an entry counter made of floats. They are all drawn with plain
 * shapes in the theme's colors (no images), so they cost next to nothing and follow the light and
 * dark themes. None of them animates forever.
 */

/** Vertical distance between two depth markers along a scrolling screen. */
val DEPTH_MARKER_SPACING: Dp = 220.dp

/** Width to keep free at the end of a screen that shows [poolDepthMarkers]. */
val DEPTH_MARKER_GUTTER: Dp = 40.dp

/**
 * The depth written on the [index]th marker (0 for the first one), like on a pool's wall: `1.0 m`,
 * `1.5 m`, `2.0 m` and so on, half a metre deeper each time.
 */
fun depthMarkerLabel(index: Int): String =
    String.format(Locale.ROOT, "%.1f m", 1.0 + 0.5 * index.coerceAtLeast(0))

/**
 * The y of the crest line of a wave whose water fills [level] (0 to 1) of [height]: 0 is the top,
 * [height] the bottom.
 */
fun waterSurfaceY(level: Float, height: Float): Float = height * (1f - level.coerceIn(0f, 1f))

/**
 * A closed path filling [size] with water up to [level], its surface a gentle wave of the given
 * [amplitude] and [wavelength] (in pixels). An empty path once [level] is 0.
 */
fun waterPath(size: Size, level: Float, amplitude: Float, wavelength: Float): Path {
  val path = Path()
  if (level <= 0f || size.width <= 0f || size.height <= 0f) return path
  val surface = waterSurfaceY(level, size.height)
  // A full pool keeps a little wave rather than a flat top, so it still reads as water.
  val crest = if (level >= 1f) amplitude else surface
  path.moveTo(0f, size.height)
  path.lineTo(0f, crest)
  var x = 0f
  val half = wavelength / 2f
  var up = true
  while (x < size.width) {
    val controlY = if (up) crest - amplitude else crest + amplitude
    path.quadraticTo(x + half / 2f, controlY, x + half, crest)
    x += half
    up = !up
  }
  path.lineTo(size.width, size.height)
  path.close()
  return path
}

/**
 * Faint wavy light reflections, like the ones dancing at the bottom of a pool, drawn behind the
 * content in [color].
 */
fun Modifier.poolCaustics(color: Color): Modifier = drawBehind {
  val spacing = 160.dp.toPx()
  val amplitude = 10.dp.toPx()
  val wavelength = 140.dp.toPx()
  var y = 60.dp.toPx()
  var row = 0
  while (y < size.height) {
    val path = Path()
    // Each line is shifted sideways a bit so they don't look stacked.
    var x = -wavelength * (row % 3) / 3f
    path.moveTo(x, y)
    var up = true
    while (x < size.width) {
      val half = wavelength / 2f
      path.quadraticTo(x + half / 2f, if (up) y - amplitude else y + amplitude, x + half, y)
      x += half
      up = !up
    }
    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    y += spacing
    row++
  }
}

/**
 * The dark lane lines painted on a pool floor, seen through the water: [lanes] horizontal stripes
 * across the space, each ending in the "T" that tells swimmers the wall is coming.
 */
fun Modifier.poolFloorLines(color: Color, lanes: Int = 2): Modifier = drawBehind {
  val stripe = 6.dp.toPx()
  val margin = 28.dp.toPx()
  val tHeight = 22.dp.toPx()
  for (lane in 1..lanes) {
    val y = size.height * lane / (lanes + 1)
    drawRect(color, Offset(margin, y - stripe / 2f), Size(size.width - margin * 2, stripe))
    // The T bars at both ends.
    drawRect(color, Offset(margin - stripe, y - tHeight / 2f), Size(stripe, tHeight))
    drawRect(color, Offset(size.width - margin, y - tHeight / 2f), Size(stripe, tHeight))
  }
}

/**
 * Depth markers along the end edge, like the tiles on a pool's wall: [depthMarkerLabel] every
 * [DEPTH_MARKER_SPACING], deeper as the screen scrolls down. Keep [DEPTH_MARKER_GUTTER] free at the
 * end of the content so they don't sit on top of it.
 */
@Composable
fun Modifier.poolDepthMarkers(): Modifier {
  val measurer = rememberTextMeasurer()
  val background = MaterialTheme.colorScheme.primaryContainer
  val textColor = MaterialTheme.colorScheme.onPrimaryContainer
  val style = TextStyle(fontSize = 10.sp, color = textColor)
  return drawBehind {
    val spacing = DEPTH_MARKER_SPACING.toPx()
    val padX = 5.dp.toPx()
    val padY = 2.dp.toPx()
    var y = 24.dp.toPx()
    var index = 0
    while (y < size.height) {
      val layout = measurer.measure(depthMarkerLabel(index), style)
      val tagWidth = layout.size.width + padX * 2
      val left = size.width - tagWidth
      drawRoundRect(
          color = background,
          topLeft = Offset(left, y),
          size = Size(tagWidth + padX, layout.size.height + padY * 2),
          cornerRadius = CornerRadius(4.dp.toPx()),
      )
      drawText(layout, topLeft = Offset(left + padX, y + padY))
      y += spacing
      index++
    }
  }
}

/**
 * Water filling [level] (0 to 1) of the space, with a wavy surface. It rises once to [level] when
 * first shown, then only moves when [level] changes.
 */
@Composable
fun WaterLevel(level: Float, color: Color, modifier: Modifier = Modifier) {
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { shown = true }
  val animated by
      animateFloatAsState(
          targetValue = if (shown) level.coerceIn(0f, 1f) else 0f,
          animationSpec = tween(durationMillis = 900),
          label = "waterLevel",
      )
  Canvas(modifier = modifier) {
    drawPath(
        waterPath(size, animated, amplitude = 4.dp.toPx(), wavelength = 56.dp.toPx()),
        color,
    )
  }
}

/** A column of water: square at the bottom, its top a small wave. Used for the weekly bars. */
class WaterColumnShape(
    private val amplitude: Dp = 3.dp,
    private val wavelength: Dp = 16.dp,
) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
      Outline.Generic(
          waterPath(
              size,
              level = 1f,
              amplitude = with(density) { amplitude.toPx() },
              wavelength = with(density) { wavelength.toPx() },
          )
      )
}

/** The most floats an entry counter shows; bigger passes are only shown as text. */
const val MAX_ENTRY_FLOATS = 30

/**
 * Which floats of an entry counter are still available, oldest entries first: `true` for each of
 * the [maxEntries] entries not used yet, `false` for the [usedCount] used ones. At most
 * [MAX_ENTRY_FLOATS] floats are drawn; past that, the counter is left out.
 */
fun entryFloats(usedCount: Int, maxEntries: Int): List<Boolean> {
  if (maxEntries <= 0 || maxEntries > MAX_ENTRY_FLOATS) return emptyList()
  val used = usedCount.coerceIn(0, maxEntries)
  return List(maxEntries) { it >= used }
}

/**
 * A pass's entries as the floats of a lane rope: a solid float for each entry still available, a
 * hollow one for each used. Draws nothing for passes bigger than [MAX_ENTRY_FLOATS].
 */
@Composable
fun EntryFloats(
    usedCount: Int,
    maxEntries: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
  val floats = entryFloats(usedCount, maxEntries)
  if (floats.isEmpty()) return
  Canvas(modifier = modifier.fillMaxWidth().height(12.dp)) {
    val gap = 3.dp.toPx()
    val floatWidth = (size.width - gap * (floats.size - 1)) / floats.size
    val radius = CornerRadius(size.height / 2f)
    val stroke = 1.5.dp.toPx()
    floats.forEachIndexed { i, available ->
      val left = i * (floatWidth + gap)
      if (available) {
        drawRoundRect(color, Offset(left, 0f), Size(floatWidth, size.height), radius)
      } else {
        drawRoundRect(
            color.copy(alpha = 0.5f),
            Offset(left + stroke / 2f, stroke / 2f),
            Size(floatWidth - stroke, size.height - stroke),
            radius,
            style = Stroke(stroke),
        )
      }
    }
  }
}
