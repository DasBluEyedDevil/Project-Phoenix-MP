package com.devil.phoenixproject.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Theme helper functions for consistent styling across screens.
 */

/**
 * Returns a vertical gradient brush for screen backgrounds.
 * Dark mode: Slate with subtle plum accent in center
 * Light mode: Light with subtle mint wash
 */
@Composable
fun screenBackgroundBrush(): Brush {
    val isDark = isSystemInDarkTheme()
    return if (isDark) {
        Brush.verticalGradient(
            0.0f to Slate900,
            0.5f to HomeButtonColors.AccentPlum.copy(alpha = 0.3f),
            1.0f to Slate900
        )
    } else {
        Brush.verticalGradient(
            0.0f to Slate50,
            0.5f to HomeButtonColors.SecondaryMint.copy(alpha = 0.1f),
            1.0f to Color.White
        )
    }
}
