package com.steve.mcpodface.ui.components.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.steve.mcpodface.ui.theme.AppTheme

data class GlassTab(val label: String, val icon: ImageVector)

/**
 * Floating Liquid Glass tab bar (PLAN.md §11): a capsule hovering over the content, with a lens
 * that springs to the selected tab. Samples [LocalChromeBackdrop], so it must sit outside the
 * shell's recorded content.
 */
@Composable
fun GlassTabBar(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backdrop = LocalChromeBackdrop.current
    val haptics = LocalHapticFeedback.current
    val barShape = remember { Capsule() }
    val lensShape = remember { Capsule() }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .glass(barShape, GlassStyle.Regular, backdrop = backdrop, shadow = true)
            .padding(4.dp),
    ) {
        val itemWidth = maxWidth / tabs.size.coerceAtLeast(1)
        val lensX by animateDpAsState(
            targetValue = itemWidth * selectedIndex.coerceAtLeast(0),
            animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
            label = "lens",
        )
        if (selectedIndex >= 0) {
            Box(
                Modifier
                    .offset(x = lensX)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .glass(lensShape, GlassStyle.Clear, tint = AppTheme.colors.accent.copy(alpha = 0.14f), backdrop = backdrop),
            )
        }
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val color by animateColorAsState(
                    if (selected) AppTheme.colors.accent else AppTheme.colors.textPrimary.copy(alpha = 0.72f),
                    label = "tabColor",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) {
                            if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(index)
                        },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                    Text(
                        tab.label,
                        color = color,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}
