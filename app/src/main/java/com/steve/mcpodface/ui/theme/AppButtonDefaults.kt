package com.steve.mcpodface.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable

/** Primary buttons are high-contrast neutral, never the accent (PLAN.md §11). */
object AppButtonDefaults {
    @Composable
    fun primaryColors(): ButtonColors {
        val c = AppTheme.colors
        return ButtonDefaults.buttonColors(
            containerColor = c.buttonPrimary,
            contentColor = c.onButtonPrimary,
            disabledContainerColor = c.surfaceGrouped,
            disabledContentColor = c.textSecondary,
        )
    }
}
