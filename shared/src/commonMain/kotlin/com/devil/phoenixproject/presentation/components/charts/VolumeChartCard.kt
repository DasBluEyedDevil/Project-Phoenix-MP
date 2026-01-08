package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.VolumeDataPoint
import com.devil.phoenixproject.domain.model.VolumePeriod
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.ui.theme.DataColors
import com.devil.phoenixproject.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Volume Chart Card component for displaying workout volume trends.
 * Features:
 * - Header with title and W/M/Y period toggle
 * - Current total volume display with workout count
 * - Animated bar chart showing volume over time
 * - Period comparison showing % change vs previous period
 * - Empty state when no data available
 */
@Composable
fun VolumeChartCard(
    volumeData: List<VolumeDataPoint>,
    selectedPeriod: VolumePeriod,
    onPeriodChange: (VolumePeriod) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium)
        ) {
            // Header with title and period toggle
            VolumeChartHeader(
                selectedPeriod = selectedPeriod,
                onPeriodChange = onPeriodChange
            )

            Spacer(modifier = Modifier.height(Spacing.medium))

            if (volumeData.isEmpty()) {
                VolumeEmptyState()
            } else {
                // Current total volume display
                VolumeStatsRow(volumeData = volumeData)

                Spacer(modifier = Modifier.height(Spacing.medium))

                // Bar chart
                VolumeBarChart(
                    volumeData = volumeData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )

                Spacer(modifier = Modifier.height(Spacing.small))

                // Period comparison
                PeriodComparison(
                    volumeData = volumeData,
                    selectedPeriod = selectedPeriod
                )
            }
        }
    }
}

@Composable
private fun VolumeChartHeader(
    selectedPeriod: VolumePeriod,
    onPeriodChange: (VolumePeriod) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Total Volume",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        SingleChoiceSegmentedButtonRow {
            val periods = VolumePeriod.entries
            periods.forEachIndexed { index, period ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = periods.size),
                    onClick = { onPeriodChange(period) },
                    selected = selectedPeriod == period
                ) {
                    Text(
                        text = when (period) {
                            VolumePeriod.WEEKLY -> "W"
                            VolumePeriod.MONTHLY -> "M"
                            VolumePeriod.YEARLY -> "Y"
                        },
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeStatsRow(volumeData: List<VolumeDataPoint>) {
    val latestData = volumeData.lastOrNull()
    val currentVolume = latestData?.totalVolume ?: 0f
    val workoutCount = latestData?.workoutCount ?: 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column {
            Text(
                text = formatVolume(currentVolume) + " kg",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$workoutCount workout${if (workoutCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VolumeBarChart(
    volumeData: List<VolumeDataPoint>,
    modifier: Modifier = Modifier
) {
    val animationProgress = remember { Animatable(0f) }

    LaunchedEffect(volumeData) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 800,
                easing = EaseInOutCubic
            )
        )
    }

    val maxVolume = volumeData.maxOfOrNull { it.totalVolume }?.coerceAtLeast(1f) ?: 1f
    val barColor = DataColors.Volume
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val chartWidthPx = with(density) { maxWidth.toPx() }
        val chartHeightPx = with(density) { maxHeight.toPx() }
        val paddingBottom = 24.dp
        val paddingBottomPx = with(density) { paddingBottom.toPx() }
        val effectiveHeight = chartHeightPx - paddingBottomPx

        Canvas(modifier = Modifier.fillMaxSize()) {
            val progress = animationProgress.value
            val barCount = volumeData.size.coerceAtLeast(1)
            val barWidth = (chartWidthPx / barCount) * 0.6f
            val barSpacing = (chartWidthPx / barCount) * 0.4f / 2f

            // Draw horizontal grid lines
            for (i in 0..4) {
                val y = effectiveHeight * (1 - i / 4f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(chartWidthPx, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Draw bars (reversed so oldest is on left)
            volumeData.reversed().forEachIndexed { index, data ->
                val normalizedHeight = (data.totalVolume / maxVolume) * effectiveHeight * progress
                val x = barSpacing + index * (barWidth + barSpacing * 2)
                val y = effectiveHeight - normalizedHeight

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, normalizedHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
            }
        }

        // X-axis labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            volumeData.reversed().forEach { data ->
                Text(
                    text = formatPeriodLabel(data),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PeriodComparison(
    volumeData: List<VolumeDataPoint>,
    selectedPeriod: VolumePeriod
) {
    if (volumeData.size < 2) return

    val currentPeriodVolume = volumeData.lastOrNull()?.totalVolume ?: 0f
    val previousPeriodVolume = volumeData.getOrNull(volumeData.size - 2)?.totalVolume ?: 0f

    if (previousPeriodVolume == 0f) return

    val percentChange = ((currentPeriodVolume - previousPeriodVolume) / previousPeriodVolume) * 100
    val isIncrease = percentChange > 0
    val isDecrease = percentChange < 0

    val changeColor = when {
        isIncrease -> Color(0xFF22C55E) // Green
        isDecrease -> Color(0xFFEF4444) // Red
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val changeIcon = when {
        isIncrease -> Icons.AutoMirrored.Filled.TrendingUp
        isDecrease -> Icons.AutoMirrored.Filled.TrendingDown
        else -> Icons.AutoMirrored.Filled.TrendingFlat
    }

    val periodLabel = when (selectedPeriod) {
        VolumePeriod.WEEKLY -> "week"
        VolumePeriod.MONTHLY -> "month"
        VolumePeriod.YEARLY -> "year"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
    ) {
        Icon(
            imageVector = changeIcon,
            contentDescription = if (isIncrease) "Increase" else if (isDecrease) "Decrease" else "No change",
            tint = changeColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "${if (isIncrease) "+" else ""}${percentChange.roundToInt()}% vs previous $periodLabel",
            style = MaterialTheme.typography.bodySmall,
            color = changeColor
        )
    }
}

@Composable
private fun VolumeEmptyState() {
    val emptyStateHeight = ResponsiveDimensions.chartHeight(baseHeight = 200.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(emptyStateHeight),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            Icon(
                imageVector = Icons.Default.BarChart,
                contentDescription = "No data available",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Complete workouts to see your volume trends",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Format volume for display (e.g., 12400 -> "12.4k")
 */
private fun formatVolume(volume: Float): String {
    return when {
        volume >= 1000 -> {
            val thousands = volume / 1000
            val intPart = thousands.toInt()
            val decPart = ((thousands - intPart) * 10).roundToInt()
            if (decPart == 0) "${intPart}k" else "$intPart.${decPart}k"
        }
        else -> volume.roundToInt().toString()
    }
}

/**
 * Format period label for X-axis (e.g., "W3", "Jan", "2024")
 */
private fun formatPeriodLabel(data: VolumeDataPoint): String {
    return when {
        data.periodKey.contains("-W") -> "W${data.period}"
        data.period in 1..12 -> getMonthAbbreviation(data.period)
        else -> data.year.toString()
    }
}

/**
 * Get month abbreviation from month number
 */
private fun getMonthAbbreviation(month: Int): String {
    return when (month) {
        1 -> "Jan"
        2 -> "Feb"
        3 -> "Mar"
        4 -> "Apr"
        5 -> "May"
        6 -> "Jun"
        7 -> "Jul"
        8 -> "Aug"
        9 -> "Sep"
        10 -> "Oct"
        11 -> "Nov"
        12 -> "Dec"
        else -> ""
    }
}
