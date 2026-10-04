package com.streamvault.core.ui.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Trimmed about a tenth in October 2026: the type scale is already below the Android TV
// guideline (body 18sp), so what read as oversized was the chrome around it, not the words.
data class AppSpacing(
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 14.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 28.dp,
    val xxl: Dp = 36.dp,
    val screenGutter: Dp = 44.dp,
    val railWidth: Dp = 112.dp,
    val sectionGap: Dp = 26.dp,
    val cardGap: Dp = 14.dp,
    val chipGap: Dp = 8.dp,
    val safeTop: Dp = 26.dp,
    val safeBottom: Dp = 26.dp,
    val safeHoriz: Dp = 44.dp
)

val LocalAppSpacing = staticCompositionLocalOf { AppSpacing() }
