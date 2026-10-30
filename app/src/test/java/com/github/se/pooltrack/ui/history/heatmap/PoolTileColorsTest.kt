package com.github.se.pooltrack.ui.history.heatmap

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.se.pooltrack.ui.theme.PoolTrackTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PoolTileColorsTest {

  @get:Rule val composeRule = createComposeRule()

  private data class Resolved(
      val scheme: ColorScheme,
      val emptyTile: Color,
      val grout: Color,
      val lightestFill: Color,
  )

  private fun resolve(darkTheme: Boolean): Resolved {
    lateinit var resolved: Resolved
    composeRule.setContent {
      PoolTrackTheme(darkTheme = darkTheme) {
        resolved =
            Resolved(
                MaterialTheme.colorScheme,
                emptyTileColor(),
                groutColor(),
                heatmapColor(1),
            )
      }
    }
    composeRule.waitForIdle()
    return resolved
  }

  @Test
  fun emptyTile_isAFaintVeilOfTheTextColor_inLightTheme() {
    val resolved = resolve(darkTheme = false)

    assertEquals(resolved.scheme.onSurface.copy(alpha = 0.06f), resolved.emptyTile)
  }

  @Test
  fun emptyTile_isAFaintVeilOfTheTextColor_inDarkTheme() {
    val resolved = resolve(darkTheme = true)

    assertEquals(resolved.scheme.onSurface.copy(alpha = 0.06f), resolved.emptyTile)
  }

  @Test
  fun emptyTile_staysFainterThanADayWithOneEntry() {
    val resolved = resolve(darkTheme = true)

    assertTrue(resolved.emptyTile.alpha < resolved.lightestFill.alpha)
  }

  @Test
  fun grout_isALightTintOfThePrimaryColor() {
    val resolved = resolve(darkTheme = false)

    assertEquals(resolved.scheme.primary.copy(alpha = 0.10f), resolved.grout)
  }
}
