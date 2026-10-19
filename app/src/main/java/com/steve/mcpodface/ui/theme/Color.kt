package com.steve.mcpodface.ui.theme

import androidx.compose.ui.graphics.Color
import com.steve.mcpodface.data.prefs.AccentColor

// Raw palette (PLAN.md §11). Screens never use these directly: use MaterialTheme.colorScheme
// or AppTheme.colors.

internal object Palette {
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
    val Zinc50 = Color(0xFFFAFAFA)
    val Zinc100 = Color(0xFFF4F4F5)
    val Zinc200 = Color(0xFFE4E4E7)
    val Zinc300 = Color(0xFFD4D4D8)
    val Zinc400 = Color(0xFFA1A1AA)
    val Zinc500 = Color(0xFF71717A)
    val Zinc600 = Color(0xFF52525B)
    val Zinc700 = Color(0xFF3F3F46)
    val Zinc800 = Color(0xFF27272A)
    val Zinc900 = Color(0xFF18181B)
    val Grey950 = Color(0xFF121212)

    // The default accent: a vivid violet (PLAN.md §11, iOS 26 direction). The other presets are in
    // accentPair below.
    val AccentLight = Color(0xFF6B4EFF)
    val AccentDark = Color(0xFF9B8CFF)

    val ErrorLight = Color(0xFFB3261E)
    val ErrorDark = Color(0xFFF2B8B5)
}

/** Tokens Material 3 has no slot for. Read them with AppTheme.colors. */
data class AppColors(
    /** The single accent: active play/progress, selected tab, toggles. */
    val accent: Color,
    val onAccent: Color,
    /** Subtle accent wash, e.g. the selected-tab indicator pill. */
    val accentSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val hairline: Color,
    /** Grouped / secondary surfaces (settings groups, chips, sheets). */
    val surfaceGrouped: Color,
    /** Neutral high-contrast fill for primary buttons (near-black on light, near-white on dark). */
    val buttonPrimary: Color,
    val onButtonPrimary: Color,
    val isDark: Boolean,
)

internal val LightAppColors = AppColors(
    accent = Palette.AccentLight,
    onAccent = Palette.White,
    accentSubtle = Palette.AccentLight.copy(alpha = 0.12f),
    textPrimary = Palette.Zinc900,
    textSecondary = Palette.Zinc500,
    hairline = Palette.Zinc200,
    surfaceGrouped = Palette.Zinc100,
    buttonPrimary = Palette.Zinc900,
    onButtonPrimary = Palette.White,
    isDark = false,
)

internal val DarkAppColors = AppColors(
    accent = Palette.AccentDark,
    onAccent = Palette.Zinc900,
    accentSubtle = Palette.AccentDark.copy(alpha = 0.16f),
    textPrimary = Palette.Zinc100,
    textSecondary = Palette.Zinc400,
    hairline = Palette.Zinc800,
    surfaceGrouped = Palette.Zinc900,
    buttonPrimary = Palette.Zinc100,
    onButtonPrimary = Palette.Zinc900,
    isDark = true,
)

internal fun darkBackground(pureBlack: Boolean) = if (pureBlack) Palette.Black else Palette.Grey950

/** The preset's accent for light mode, then dark mode (Settings → Appearance, PLAN.md §11). */
internal fun accentPair(preset: AccentColor): Pair<Color, Color> = when (preset) {
    AccentColor.VIOLET -> Palette.AccentLight to Palette.AccentDark
    AccentColor.BLUE -> Color(0xFF0A6CFF) to Color(0xFF5AA2FF)
    AccentColor.TEAL -> Color(0xFF00897B) to Color(0xFF4DD0C4)
    AccentColor.GREEN -> Color(0xFF2E9E4F) to Color(0xFF5FD27F)
    AccentColor.ORANGE -> Color(0xFFE8660A) to Color(0xFFFF9F45)
    AccentColor.PINK -> Color(0xFFE0336E) to Color(0xFFFF7AA8)
    AccentColor.GRAPHITE -> Palette.Zinc600 to Palette.Zinc400
}

/** [LightAppColors] or [DarkAppColors] with the accent tokens swapped for [preset]. */
internal fun appColorsFor(dark: Boolean, preset: AccentColor): AppColors {
    val (light, darkAccent) = accentPair(preset)
    return if (dark) {
        DarkAppColors.copy(accent = darkAccent, accentSubtle = darkAccent.copy(alpha = 0.16f))
    } else {
        LightAppColors.copy(accent = light, accentSubtle = light.copy(alpha = 0.12f))
    }
}
