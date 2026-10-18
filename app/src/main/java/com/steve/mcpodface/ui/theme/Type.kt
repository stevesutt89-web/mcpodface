package com.steve.mcpodface.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System font (follows the Samsung font setting). Weight and size carry the hierarchy, not colour.
// Screen title 28sp bold · body 16sp · secondary 13sp (PLAN.md §11).

private val Base = TextStyle(fontFamily = FontFamily.Default)

val AppTypography = Typography(
    displaySmall = Base.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    /** Large collapsing screen titles. */
    headlineMedium = Base.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = Base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    /** Collapsed top bar title, section headers. */
    titleLarge = Base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    /** Episode and podcast titles in rows. */
    titleMedium = Base.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
    titleSmall = Base.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = Base.copy(fontSize = 14.sp, lineHeight = 20.sp),
    /** Secondary text: podcast name, dates, summaries. */
    bodySmall = Base.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = Base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = Base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelSmall = Base.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)
