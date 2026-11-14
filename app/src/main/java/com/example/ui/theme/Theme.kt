package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val MobileLabsColorScheme = darkColorScheme(
    primary = ObsSelectionBlue,
    onPrimary = StudioText,
    primaryContainer = ApertureRed,
    onPrimaryContainer = StudioText,
    secondary = ObsControlBtn,
    onSecondary = StudioText,
    background = ObsWindowBg,
    onBackground = StudioText,
    surface = ObsHeaderBg,
    onSurface = StudioText,
    surfaceVariant = ObsWellBg,
    onSurfaceVariant = StudioTextDim,
    outline = ObsBorder,
    error = StatusCritical,
    onError = StudioText
)

private val MobileLabsShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp)
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
