package com.github.se.pooltrack.ui.history.heatmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * How opaque the primary color is from the lightest filled level to the darkest. The fills are soft
 * tints, never the full color, so the text on top stays in the normal text color and the card stays
 * calm.
 */
data class HeatmapPalette(val minAlpha: Float, val maxAlpha: Float)

/**
 * Days. Most days have at most one visit, so the lightest step is already clearly visible and the
 * range is narrow.
 */
val DayPalette = HeatmapPalette(minAlpha = 0.40f, maxAlpha = 0.65f)

/** Months in the year view, which span one visit to a dozen or more, so the range is wider. */
val YearPalette = HeatmapPalette(minAlpha = 0.15f, maxAlpha = 0.60f)

/** The opacity of the primary color at [level] (1 to [MAX_LEVEL]) in [palette]. */
fun levelAlpha(level: Int, palette: HeatmapPalette): Float =
    palette.minAlpha +
        (palette.maxAlpha - palette.minAlpha) * (level.coerceIn(1, MAX_LEVEL) - 1) / (MAX_LEVEL - 1)

/** The fill at [level], from nothing (0) to the busiest ([MAX_LEVEL]), in [palette]. */
@Composable
fun heatmapColor(level: Int, palette: HeatmapPalette = DayPalette): Color =
    if (level <= 0) Color.Transparent
    else MaterialTheme.colorScheme.primary.copy(alpha = levelAlpha(level, palette))

/** The shape of a day in the calendars: a pool tile with softened corners. */
val PoolTileShape = RoundedCornerShape(6.dp)

/** A day without entries: a bare tile, barely there, so the month still reads as a tiled floor. */
@Composable fun emptyTileColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)

/** The lines between the tiles of a calendar, like the grout between a pool's tiles. */
@Composable fun groutColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)

/** The "Less, five tiles, More" key explaining the colors. */
@Composable
fun HeatmapLegend(modifier: Modifier = Modifier) {
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
    Text(
        text = "Less",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    (0..MAX_LEVEL).forEach { level ->
      Box(
          modifier =
              Modifier.padding(start = 4.dp)
                  .size(12.dp)
                  .background(
                      if (level == 0) emptyTileColor() else heatmapColor(level),
                      RoundedCornerShape(3.dp),
                  )
      )
    }
    Text(
        text = "More",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
  }
}
