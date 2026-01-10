package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.presentation.components.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.SliderWithButtons
import com.devil.phoenixproject.presentation.components.VideoPlayer
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Set Ready Screen - Focused view for a single exercise/set.
 * Allows parameter adjustments before starting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetReadyScreen(
    navController: NavController,
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository
) {
    val routineFlowState by viewModel.routineFlowState.collectAsState()
    val workoutState by viewModel.workoutState.collectAsState()
    val loadedRoutine by viewModel.loadedRoutine.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val enableVideoPlayback by viewModel.enableVideoPlayback.collectAsState()

    // Get current state
    val setReadyState = routineFlowState as? RoutineFlowState.SetReady
    val routine = loadedRoutine

    // If no state/routine, just return early
    // Don't auto-navigate - the caller handles navigation to avoid double-back issues
    if (setReadyState == null || routine == null) {
        return
    }

    val currentExercise = routine.exercises.getOrNull(setReadyState.exerciseIndex)
    // If exercise is invalid, just return early
    if (currentExercise == null) {
        return
    }

    val isEchoMode = currentExercise.programMode is ProgramMode.Echo
    val isAMRAP = currentExercise.isAMRAP

    // Weight parameters matching RestTimerCard exactly
    val maxWeight = if (weightUnit == WeightUnit.LB) 242f else 110f  // 110kg per cable max
    val weightStep = if (weightUnit == WeightUnit.LB) 0.5f else 0.25f  // Fine-grained like RestTimerCard

    // Navigation state - uses superset-aware helpers from ViewModel
    val canGoPrev = viewModel.hasPreviousStep(setReadyState.exerciseIndex, setReadyState.setIndex)
    val canSkip = viewModel.hasNextStep(setReadyState.exerciseIndex, setReadyState.setIndex)

    // Stop confirmation dialog
    var showStopConfirmation by remember { mutableStateOf(false) }

    // Handle system back button
    BackHandler {
        viewModel.returnToOverview()
        navController.navigateUp()
    }

    // Clear topbar title to allow dynamic title from EnhancedMainScreen
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("")
    }

    // Load video for exercise
    var videoEntity by remember { mutableStateOf<ExerciseVideoEntity?>(null) }
    LaunchedEffect(currentExercise.exercise.id) {
        currentExercise.exercise.id?.let { exerciseId ->
            try {
                val videos = exerciseRepository.getVideos(exerciseId)
                videoEntity = videos.firstOrNull()
            } catch (_: Exception) {
                // Video loading failed
            }
        }
    }

    // Watch for workout state changes to navigate to ActiveWorkout
    // Use popUpTo(RoutineOverview) to maintain clean navigation stack:
    // Stack is always: DailyRoutines -> RoutineOverview -> (SetReady OR ActiveWorkout)
    LaunchedEffect(workoutState) {
        when (workoutState) {
            is WorkoutState.Countdown, is WorkoutState.Active -> {
                navController.navigate(NavigationRoutes.ActiveWorkout.route) {
                    popUpTo(NavigationRoutes.RoutineOverview.route) { inclusive = false }
                }
            }
            else -> {}
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showStopConfirmation = true },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                icon = { Icon(Icons.Default.Close, "Stop") },
                text = { Text("Stop") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                )
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header - Set X of Y
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Set ${setReadyState.setIndex + 1} of ${currentExercise.setReps.size}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        currentExercise.programMode.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Video thumbnail
            if (enableVideoPlayback) {
                VideoPlayer(
                    videoUrl = videoEntity?.videoUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(12.dp))
            }


            // Configuration card - matching RestTimerCard style
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.medium)
                ) {
                    Text(
                        if (isEchoMode) "ECHO SETTINGS" else "SET CONFIGURATION",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )

                    if (isEchoMode) {
                        // Echo Level selector - matching RestTimerCard style
                        SetReadyEchoLevelSelector(
                            selectedLevel = setReadyState.echoLevel ?: EchoLevel.HARD,
                            onLevelChange = { viewModel.updateSetReadyEchoLevel(it) }
                        )

                        // Eccentric Load slider - matching RestTimerCard style
                        SetReadyEccentricLoadSlider(
                            percent = setReadyState.eccentricLoadPercent ?: 100,
                            onPercentChange = { viewModel.updateSetReadyEccentricLoad(it) }
                        )

                        // Reps adjuster for Echo mode too
                        if (!isAMRAP) {
                            SliderWithButtons(
                                value = setReadyState.adjustedReps.toFloat(),
                                onValueChange = { newValue ->
                                    viewModel.updateSetReadyReps(newValue.toInt().coerceIn(1, 50))
                                },
                                valueRange = 1f..50f,
                                step = 1f,
                                label = "Target Reps",
                                formatValue = { it.toInt().toString() }
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Target Reps", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "AMRAP",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else {
                        // Standard mode: Weight + Reps using SliderWithButtons
                        SliderWithButtons(
                            value = setReadyState.adjustedWeight,
                            onValueChange = { newWeight ->
                                viewModel.updateSetReadyWeight(newWeight.coerceIn(0f, maxWeight))
                            },
                            valueRange = 0f..maxWeight,
                            step = weightStep,
                            label = "Weight per cable",
                            formatValue = { viewModel.formatWeight(it, weightUnit) }
                        )

                        if (!isAMRAP) {
                            SliderWithButtons(
                                value = setReadyState.adjustedReps.toFloat(),
                                onValueChange = { newValue ->
                                    viewModel.updateSetReadyReps(newValue.toInt().coerceIn(1, 50))
                                },
                                valueRange = 1f..50f,
                                step = 1f,
                                label = "Target Reps",
                                formatValue = { it.toInt().toString() }
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Target Reps", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "AMRAP",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Navigation buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // PREV button
                OutlinedButton(
                    onClick = { viewModel.setReadyPrev() },
                    enabled = canGoPrev,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
                    Text("PREV")
                }

                // START SET button (primary)
                Button(
                    onClick = {
                        viewModel.ensureConnection(
                            onConnected = { viewModel.startSetFromReady() },
                            onFailed = {}
                        )
                    },
                    modifier = Modifier.weight(2f),
                    enabled = connectionState is ConnectionState.Connected
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text("START SET", fontWeight = FontWeight.Bold)
                }

                // SKIP button
                OutlinedButton(
                    onClick = { viewModel.setReadySkip() },
                    enabled = canSkip,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("SKIP")
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }
    }

    // Stop confirmation dialog
    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text("Exit Routine?") },
            text = { Text("Progress will be saved.") },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmation = false
                        viewModel.exitRoutineFlow()
                        navController.popBackStack(NavigationRoutes.DailyRoutines.route, false)
                    }
                ) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Echo Level selector - Row of 4 buttons matching RestTimerCard style
 */
@Composable
private fun SetReadyEchoLevelSelector(
    selectedLevel: EchoLevel,
    onLevelChange: (EchoLevel) -> Unit
) {
    Column {
        Text(
            text = "ECHO LEVEL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    RoundedCornerShape(Spacing.medium)
                )
                .padding(Spacing.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
        ) {
            EchoLevel.entries.forEach { level ->
                val isSelected = level == selectedLevel

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Spacing.small),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
                    onClick = { onLevelChange(level) }
                ) {
                    Text(
                        text = level.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.small),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Eccentric Load slider matching RestTimerCard style (0-150%)
 */
@Composable
private fun SetReadyEccentricLoadSlider(
    percent: Int,
    onPercentChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ECCENTRIC LOAD",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        Slider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(it.toInt()) },
            valueRange = 0f..150f,
            steps = 29, // 5% increments: 0, 5, 10, ... 150
            modifier = Modifier.fillMaxWidth()
        )
    }
}
