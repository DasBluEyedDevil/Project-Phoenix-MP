package com.devil.phoenixproject.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Represents the size class of the window for responsive layouts.
 * Based on Material 3 window size class breakpoints.
 */
enum class WindowWidthSizeClass {
    /** Phones in portrait (< 600dp) */
    Compact,
    /** Small tablets, phones in landscape (600-840dp) */
    Medium,
    /** Large tablets, desktops (> 840dp) */
    Expanded
}

enum class WindowHeightSizeClass {
    /** Short screens (< 480dp) */
    Compact,
    /** Medium height (480-900dp) */
    Medium,
    /** Tall screens (> 900dp) */
    Expanded
}

data class WindowSizeClass(
    val widthSizeClass: WindowWidthSizeClass,
    val heightSizeClass: WindowHeightSizeClass,
    val widthDp: Dp,
    val heightDp: Dp
) {
    val isTablet: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.Compact

    val isExpandedTablet: Boolean
        get() = widthSizeClass == WindowWidthSizeClass.Expanded
}

/**
 * CompositionLocal for accessing WindowSizeClass throughout the app.
 * Defaults to Compact (phone) if not provided.
 */
val LocalWindowSizeClass = compositionLocalOf {
    WindowSizeClass(
        widthSizeClass = WindowWidthSizeClass.Compact,
        heightSizeClass = WindowHeightSizeClass.Medium,
        widthDp = 400.dp,
        heightDp = 800.dp
    )
}

/**
 * Calculate WindowSizeClass from screen dimensions.
 */
fun calculateWindowSizeClass(widthDp: Dp, heightDp: Dp): WindowSizeClass {
    val widthClass = when {
        widthDp < 600.dp -> WindowWidthSizeClass.Compact
        widthDp < 840.dp -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Expanded
    }

    val heightClass = when {
        heightDp < 480.dp -> WindowHeightSizeClass.Compact
        heightDp < 900.dp -> WindowHeightSizeClass.Medium
        else -> WindowHeightSizeClass.Expanded
    }

    return WindowSizeClass(
        widthSizeClass = widthClass,
        heightSizeClass = heightClass,
        widthDp = widthDp,
        heightDp = heightDp
    )
}
