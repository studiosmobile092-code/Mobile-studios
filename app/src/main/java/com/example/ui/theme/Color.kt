package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Classic OBS Studio Dark Palette (1:1 faithful to OBS Studio desktop UI) + Mobile Labs Aperture Red
val ApertureRed = Color(0xFF720000)
val ApertureRedHi = Color(0xFFA30000)

val ObsWindowBg = Color(0xFF2B2B30)       // Main OBS workspace matte dark charcoal
val ObsHeaderBg = Color(0xFF36363D)       // Dock title bar & menu bar
val ObsWellBg = Color(0xFF1D1D22)         // Recessed list/mixer well background
val ObsControlBtn = Color(0xFF3C3C44)     // Flat OBS rectangular control button
val ObsControlBtnPressed = Color(0xFF4A4A54)
val ObsBorder = Color(0xFF484852)         // Crisp 1dp OBS panel/input border
val ObsSelectionBlue = Color(0xFF2E5EA8)  // Classic OBS scene/source selection blue
val ObsSliderBlue = Color(0xFF4F87D8)     // Classic OBS mixer slider track highlight

// Compatibility aliases used across secondary dialogs
val StudioBg = ObsWindowBg
val SurfaceLevel1 = ObsHeaderBg
val SurfaceLevel2 = ObsControlBtn
val StudioDivider = ObsBorder
val StudioText = Color(0xFFF0F0F2)
val StudioTextDim = Color(0xFFA8A8B2)

val StatusOk = Color(0xFF2EBD59)
val StatusWarn = Color(0xFFF5A524)
val StatusCritical = Color(0xFFFF4D4F)

// Classic OBS Audio Meter Zones
val MeterGreen = Color(0xFF27C24C)
val MeterYellow = Color(0xFFF2B624)
val MeterRed = Color(0xFFE83A3D)
val MeterTrack = Color(0xFF141418)
