package com.devil.phoenixproject.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic colors for data visualization (charts, graphs).
 * Designed to be colorblind-safe with distinct luminance values.
 * These do NOT change with light/dark mode.
 */
object DataColors {
    /** Training volume trends - Blue */
    val Volume = Color(0xFF3B82F6)

    /** Intensity/effort metrics - Amber */
    val Intensity = Color(0xFFF59E0B)

    /** Heart rate / cardio data - Red (use with icons for accessibility) */
    val HeartRate = Color(0xFFEF4444)

    /** Time-based metrics - Emerald */
    val Duration = Color(0xFF10B981)

    /** Strength PRs / 1RM estimates - Violet */
    val OneRepMax = Color(0xFF8B5CF6)

    /** Power output / wattage - Cyan */
    val Power = Color(0xFF06B6D4)
}
