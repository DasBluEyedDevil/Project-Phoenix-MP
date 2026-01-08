package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Rest timer picker component for Just Lift mode.
 * Displays selectable chips for common rest intervals between sets.
 * Options: Off (0), 30s, 45s, 60s, 90s, 120s, 180s
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RestTimerPicker(
    currentSeconds: Int,
    onSecondsChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(0, 30, 45, 60, 90, 120, 180)

    Column(modifier = modifier) {
        Text(
            text = "REST TIMER",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            options.forEach { seconds ->
                val isSelected = seconds == currentSeconds
                RestTimerChip(
                    label = formatRestSeconds(seconds),
                    isSelected = isSelected,
                    onClick = { onSecondsChanged(seconds) }
                )
            }
        }
    }
}

/**
 * Individual chip for rest timer selection.
 */
@Composable
private fun RestTimerChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected)
                MaterialTheme.colorScheme.onPrimaryContainer
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

/**
 * Format seconds into human-readable rest time.
 *
 * @param seconds Time in seconds
 * @return Formatted string: "Off" for 0, "30s" for <60, "1m" for 60, "1m 30s" for 90, etc.
 */
private fun formatRestSeconds(seconds: Int): String {
    return when {
        seconds == 0 -> "Off"
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60}m"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}
