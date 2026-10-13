package com.vayana.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** docs/PRODUCT_SPEC.md §6. No component reads `isSystemInDarkTheme()` or checks hardware directly — everything reads this. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class DisplayProfile { STANDARD, E_INK }

/** Color capability is independent of the panel's motion and refresh constraints. */
enum class EinkPalette { MONOCHROME, COLOR }

fun DisplayProfile.isMonochrome(einkPalette: EinkPalette): Boolean =
    this == DisplayProfile.E_INK && einkPalette == EinkPalette.MONOCHROME

/**
 * Chrome drawn as ink on paper: flat surface, outlines, inverse for the selected item. Only for the monochrome E-Ink
 * palette; colour E-Ink keeps Material 3's tonal roles (wallpaper colours too), just opaque. Motion, shadows and
 * refresh stay keyed on [DisplayProfile.E_INK] itself.
 */
@Composable
@ReadOnlyComposable
fun isMonochromeEink(): Boolean = LocalDisplayProfile.current.isMonochrome(LocalEinkPalette.current)

/** Design/DESIGN_SPEC.md §1: softer dark (raised black point) and true-black (AMOLED) are both dark-mode variants. */
enum class DarkVariant { STANDARD, SOFTER, TRUE_BLACK }

enum class MotionSetting { FULL, REDUCED, OFF }

val LocalDisplayProfile = staticCompositionLocalOf { DisplayProfile.STANDARD }
val LocalEinkPalette = staticCompositionLocalOf { EinkPalette.MONOCHROME }
val LocalDarkVariant = staticCompositionLocalOf { DarkVariant.STANDARD }
val LocalMotionSetting = staticCompositionLocalOf { MotionSetting.FULL }
