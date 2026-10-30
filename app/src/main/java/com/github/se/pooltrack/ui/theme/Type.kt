package com.github.se.pooltrack.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Material's defaults, made more confident where numbers and titles are shown: heavier, a bit
// tighter headings, and roomier small labels. The system font is kept, so nothing is downloaded.
private val Base = Typography()

val Typography =
    Typography(
        displayMedium =
            Base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        displaySmall =
            Base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge =
            Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp,
            ),
        labelMedium = Base.labelMedium.copy(letterSpacing = 0.6.sp),
        labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    )
