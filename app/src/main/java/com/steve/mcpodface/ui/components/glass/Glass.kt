package com.steve.mcpodface.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangularShape
import com.steve.mcpodface.ui.theme.AppTheme

/*
 * Liquid Glass (PLAN.md §11). Glass is drawn with the Backdrop library: it samples a *backdrop
 * layer* (pixels recorded elsewhere), refracts it through a lens at the edges, blurs it, and adds
 * a specular rim. The one hard rule: a glass element must never sample a layer it is drawn inside.
 *
 *  - The shell records the whole NavHost into a root layer; only the floating tab bar and mini
 *    player sample it (LocalChromeBackdrop).
 *  - GlassPage records a screen's background (e.g. blurred artwork) into a page layer and provides
 *    it as LocalGlassBackdrop, so buttons and chips in the page refract the background.
 *  - GlassScaffold records the scrolling content and its top bar samples that.
 *
 * Without a backdrop (previews, or a glass element outside any GlassPage) glass falls back to a
 * translucent fill with a hairline rim, so nothing ever samples itself.
 */

/** Backdrop that in-page glass (buttons, chips, cards) refracts. Provided by [GlassPage]. */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Backdrop of the whole screen content, for the shell's floating chrome only. */
val LocalChromeBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Space at the bottom of the screen covered by the floating tab bar and mini player (plus the
 * gesture bar). Scrolling content adds this to its contentPadding so its last item clears them.
 */
val LocalChromePadding = compositionLocalOf { PaddingValues(0.dp) }

enum class GlassStyle {
    /** Toolbars, tab bar, mini player, sheets: frosted enough to keep text readable. */
    Regular,

    /** Small controls over artwork: barely frosted, strong lens. */
    Clear,

    /** Primary actions (Play, Subscribe): tinted with the accent. */
    Prominent,
}

/** Tint laid over the refracted backdrop. */
@Composable
fun glassTint(style: GlassStyle, accent: Color = AppTheme.colors.accent): Color {
    val dark = AppTheme.colors.isDark
    return when (style) {
        // Light glass is a faint cool grey rather than pure white, so it still reads over white pages.
        GlassStyle.Regular -> if (dark) Color(0xFF1C1C1E).copy(alpha = 0.55f) else Color(0xFFF2F2F7).copy(alpha = 0.72f)
        GlassStyle.Clear -> if (dark) Color.Black.copy(alpha = 0.18f) else Color(0xFFF2F2F7).copy(alpha = 0.3f)
        GlassStyle.Prominent -> accent.copy(alpha = if (dark) 0.78f else 0.86f)
    }
}

/**
 * Draws [shape]-clipped Liquid Glass behind this element's content, sampling [backdrop]
 * (default: [LocalGlassBackdrop]). [pressed] (0..1) swells the lens and brightens the rim for the
 * springy press feel; [shadow] lifts floating chrome off the content.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    style: GlassStyle = GlassStyle.Regular,
    tint: Color = glassTint(style),
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    pressed: () -> Float = { 0f },
    shadow: Boolean = false,
): Modifier {
    val dark = AppTheme.colors.isDark
    if (backdrop == null || LocalInspectionMode.current) {
        val rim = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.7f)
        return this
            .background(tint.copy(alpha = (tint.alpha + 0.25f).coerceAtMost(0.96f)), shape)
            .border(0.5.dp, rim, shape)
    }
    val blurDp = when (style) {
        GlassStyle.Regular -> 10.dp
        GlassStyle.Clear -> 2.dp
        GlassStyle.Prominent -> 6.dp
    }
    val lensHeight = when (style) {
        GlassStyle.Clear -> 20.dp
        else -> 14.dp
    }
    val supportsLens = shape is RoundedRectangularShape || shape is CornerBasedShape
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blurDp.toPx())
            // The lens needs a rounded shape (it refracts along the corner curve). Rectangular glass,
            // like a full-width top bar, is frosted only.
            if (supportsLens) {
                val p = pressed()
                lens(
                    refractionHeight = (lensHeight * (1f + 0.5f * p)).toPx(),
                    refractionAmount = (lensHeight * 2f * (1f + 0.6f * p)).toPx(),
                    depthEffect = style != GlassStyle.Regular,
                )
            }
        },
        highlight = { Highlight.Default.copy(alpha = (if (dark) 0.55f else 0.9f) + 0.1f * pressed()) },
        // Floating chrome gets a deep soft shadow; every other rounded control a faint one, which is
        // what keeps glass legible over plain backgrounds. Rectangular bars get none.
        shadow = when {
            shadow -> { { Shadow(radius = 20.dp, color = Color.Black.copy(alpha = if (dark) 0.35f else 0.12f)) } }
            supportsLens -> { { Shadow(radius = 10.dp, color = Color.Black.copy(alpha = if (dark) 0.25f else 0.07f)) } }
            else -> { { null } }
        },
        onDrawSurface = { drawRect(tint) },
    )
}

/**
 * A screen whose glass controls refract [background] (default: the plain page colour; pass
 * [ArtworkAmbience] for artwork-washed pages). Content draws on top.
 */
@Composable
fun GlassPage(
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit = {
        Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background))
    },
    content: @Composable BoxScope.() -> Unit,
) {
    val pageBackdrop = rememberLayerBackdrop()
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop), content = background)
        CompositionLocalProvider(LocalGlassBackdrop provides pageBackdrop) {
            content()
        }
    }
}
