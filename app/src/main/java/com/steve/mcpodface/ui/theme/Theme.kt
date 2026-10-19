package com.steve.mcpodface.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.steve.mcpodface.data.prefs.ThemeMode

private val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/** Access extra colour tokens: `AppTheme.colors.hairline`. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

// M3 restyled to neutral: primary = the accent (so switches, sliders, progress and checkboxes pick it
// up), every container is a neutral grey, and surfaceTint is transparent so there is no tonal
// elevation. Filled buttons must use AppButtonDefaults.primaryColors() for the neutral look.

private fun lightScheme(): ColorScheme {
    val c = LightAppColors
    return lightColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = Palette.Zinc100,
        onPrimaryContainer = Palette.Zinc900,
        inversePrimary = Palette.AccentDark,
        secondary = Palette.Zinc700,
        onSecondary = Palette.White,
        secondaryContainer = Palette.Zinc100,
        onSecondaryContainer = Palette.Zinc900,
        tertiary = Palette.Zinc700,
        onTertiary = Palette.White,
        tertiaryContainer = Palette.Zinc100,
        onTertiaryContainer = Palette.Zinc900,
        background = Palette.White,
        onBackground = c.textPrimary,
        surface = Palette.White,
        onSurface = c.textPrimary,
        surfaceVariant = Palette.Zinc100,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = Palette.Zinc900,
        inverseOnSurface = Palette.Zinc100,
        error = Palette.ErrorLight,
        onError = Palette.White,
        errorContainer = Palette.Zinc100,
        onErrorContainer = Palette.ErrorLight,
        outline = Palette.Zinc300,
        outlineVariant = c.hairline,
        scrim = Palette.Black,
        surfaceBright = Palette.White,
        surfaceDim = Palette.Zinc100,
        surfaceContainerLowest = Palette.White,
        surfaceContainerLow = Palette.Zinc50,
        surfaceContainer = Palette.Zinc100,
        surfaceContainerHigh = Palette.Zinc100,
        surfaceContainerHighest = Palette.Zinc200,
    )
}

private fun darkScheme(pureBlack: Boolean): ColorScheme {
    val c = DarkAppColors
    val bg = darkBackground(pureBlack)
    return darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = Palette.Zinc900,
        onPrimaryContainer = Palette.Zinc100,
        inversePrimary = Palette.AccentLight,
        secondary = Palette.Zinc300,
        onSecondary = Palette.Zinc900,
        secondaryContainer = Palette.Zinc900,
        onSecondaryContainer = Palette.Zinc100,
        tertiary = Palette.Zinc300,
        onTertiary = Palette.Zinc900,
        tertiaryContainer = Palette.Zinc900,
        onTertiaryContainer = Palette.Zinc100,
        background = bg,
        onBackground = c.textPrimary,
        surface = bg,
        onSurface = c.textPrimary,
        surfaceVariant = Palette.Zinc900,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = Palette.Zinc100,
        inverseOnSurface = Palette.Zinc900,
        error = Palette.ErrorDark,
        onError = Palette.Zinc900,
        errorContainer = Palette.Zinc900,
        onErrorContainer = Palette.ErrorDark,
        outline = Palette.Zinc700,
        outlineVariant = c.hairline,
        scrim = Palette.Black,
        surfaceBright = Palette.Zinc800,
        surfaceDim = bg,
        surfaceContainerLowest = bg,
        surfaceContainerLow = Palette.Zinc900,
        surfaceContainer = Palette.Zinc900,
        surfaceContainerHigh = Palette.Zinc900,
        surfaceContainerHighest = Palette.Zinc800,
    )
}

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    pureBlack: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (dark) {
                dynamicDarkColorScheme(context).let {
                    if (pureBlack) it.copy(background = Palette.Black, surface = Palette.Black) else it
                }
            } else {
                dynamicLightColorScheme(context)
            }
        }
        dark -> darkScheme(pureBlack)
        else -> lightScheme()
    }
    val appColors = when {
        dynamicColor -> (if (dark) DarkAppColors else LightAppColors).copy(
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
            accentSubtle = scheme.primary.copy(alpha = 0.14f),
        )
        dark -> DarkAppColors
        else -> LightAppColors
    }

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
        ) {
            // Text and icons outside any Material Surface (e.g. the floating glass mini player) would
            // otherwise default to black, which vanishes on dark glass.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
