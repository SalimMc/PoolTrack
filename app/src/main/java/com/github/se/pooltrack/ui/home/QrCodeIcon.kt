package com.github.se.pooltrack.ui.home

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector

/** A QR-code glyph (three finder squares plus data blocks); material-icons-extended isn't used. */
val QrCodeIcon: ImageVector by lazy {
  materialIcon(name = "QrCode") {
    materialPath(pathFillType = PathFillType.EvenOdd) {
      // Finder squares (outer ring, then inner dot) at top-left, top-right and bottom-left.
      for ((x, y) in listOf(3f to 3f, 15f to 3f, 3f to 15f)) {
        moveTo(x, y)
        horizontalLineToRelative(6f)
        verticalLineToRelative(6f)
        horizontalLineToRelative(-6f)
        close()
        moveTo(x + 2f, y + 2f)
        verticalLineToRelative(2f)
        horizontalLineToRelative(2f)
        verticalLineToRelative(-2f)
        close()
      }
      // Data blocks.
      moveTo(13f, 13f)
      horizontalLineToRelative(3f)
      verticalLineToRelative(3f)
      horizontalLineToRelative(-3f)
      close()
      moveTo(18f, 13f)
      horizontalLineToRelative(3f)
      verticalLineToRelative(3f)
      horizontalLineToRelative(-3f)
      close()
      moveTo(13f, 18f)
      horizontalLineToRelative(3f)
      verticalLineToRelative(3f)
      horizontalLineToRelative(-3f)
      close()
      moveTo(18f, 18f)
      horizontalLineToRelative(3f)
      verticalLineToRelative(3f)
      horizontalLineToRelative(-3f)
      close()
    }
  }
}
