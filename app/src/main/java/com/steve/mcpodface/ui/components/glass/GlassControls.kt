package com.steve.mcpodface.ui.components.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.steve.mcpodface.ui.theme.AppTheme

/** Spring used for every glass press: quick, slightly bouncy, like iOS 26 controls. */
internal val GlassPressSpring = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

/**
 * Press state shared by glass controls: the control swells a little and its lens bulges while held,
 * then springs back. Returns the animated 0..1 press fraction and the modifier to apply.
 */
@Composable
internal fun rememberGlassPress(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Pair<() -> Float, Modifier> {
    val isPressed by interactionSource.collectIsPressedAsState()
    val press by animateFloatAsState(if (isPressed && enabled) 1f else 0f, GlassPressSpring, label = "press")
    val modifier = Modifier.graphicsLayer {
        val s = 1f + 0.06f * press
        scaleX = s
        scaleY = s
    }
    return { press } to modifier
}

/** Capsule glass button. [GlassStyle.Prominent] for the main action on a screen. */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle.Regular,
    enabled: Boolean = true,
    accent: Color = AppTheme.colors.accent,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val (press, pressModifier) = rememberGlassPress(interaction, enabled)
    val haptics = LocalHapticFeedback.current
    val contentColor = if (style == GlassStyle.Prominent) Color.White else AppTheme.colors.textPrimary
    val shape = remember { Capsule() }
    Row(
        modifier = modifier
            .then(pressModifier)
            .glass(shape, style, glassTint(style, accent), pressed = press)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .defaultMinSize(minHeight = 44.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

/** Circular glass icon button (44dp by default, the iOS minimum touch size). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle.Regular,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
    tint: Color? = null,
    accent: Color = AppTheme.colors.accent,
) {
    val interaction = remember { MutableInteractionSource() }
    val (press, pressModifier) = rememberGlassPress(interaction, enabled)
    val haptics = LocalHapticFeedback.current
    val shape = remember { Capsule() }
    val iconTint = tint ?: if (style == GlassStyle.Prominent) Color.White else AppTheme.colors.textPrimary
    Box(
        modifier = modifier
            .size(size)
            .then(pressModifier)
            .glass(shape, style, glassTint(style, accent), pressed = press)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

/** Glass filter chip: clear glass when off, accent glass when selected. */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    accent: Color = AppTheme.colors.accent,
) {
    val interaction = remember { MutableInteractionSource() }
    val (press, pressModifier) = rememberGlassPress(interaction)
    val style = if (selected) GlassStyle.Prominent else GlassStyle.Regular
    val contentColor = if (selected) Color.White else AppTheme.colors.textPrimary
    val shape = remember { Capsule() }
    Row(
        modifier = modifier
            .then(pressModifier)
            .glass(shape, style, glassTint(style, accent), pressed = press)
            .clickable(interaction, indication = null, role = Role.Tab, onClick = onClick)
            .defaultMinSize(minHeight = 36.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) Icon(leadingIcon, null, tint = contentColor, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}
