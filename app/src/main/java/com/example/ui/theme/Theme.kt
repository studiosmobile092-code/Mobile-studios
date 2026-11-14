package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val MobileLabsColorScheme = darkColorScheme(
    primary = ApertureRed,
    onPrimary = StudioText,
    primaryContainer = ApertureRedHi,
    onPrimaryContainer = StudioText,
    secondary = SurfaceLevel2,
    onSecondary = StudioText,
    background = StudioBg,
    onBackground = StudioText,
    surface = SurfaceLevel1,
    onSurface = StudioText,
    surfaceVariant = SurfaceLevel2,
    onSurfaceVariant = StudioTextDim,
    outline = StudioDivider,
    error = StatusCritical,
    onError = StudioText
)

private val MobileLabsShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp)
)

@Composable
fun MobileLabsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MobileLabsColorScheme,
        typography = MobileLabsTypography,
        shapes = MobileLabsShapes,
        content = content
    )
}
