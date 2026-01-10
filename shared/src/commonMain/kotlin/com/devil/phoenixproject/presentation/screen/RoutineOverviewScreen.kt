package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel

/**
 * Routine Overview Screen - Entry point when starting a routine.
 * Shows a horizontal carousel of exercises with the ability to browse
 * and select where to begin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineOverviewScreen(
    navController: NavController,
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository
) {
    val routineFlowState by viewModel.routineFlowState.collectAsState()
    val completedExercises by viewModel.completedExercises.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    // Get the current routine from flow state
    val routine = when (val state = routineFlowState) {
        is RoutineFlowState.Overview -> state.routine
        else -> null
    }

    if (routine == null) {
        // No routine loaded, navigate back
        LaunchedEffect(Unit) {
            navController.navigateUp()
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = (routineFlowState as? RoutineFlowState.Overview)?.selectedExerciseIndex ?: 0,
        pageCount = { routine.exercises.size }
    )

    // Sync pager with viewmodel
    LaunchedEffect(pagerState.currentPage) {
        viewModel.selectExerciseInOverview(pagerState.currentPage)
    }

    // Stop routine confirmation dialog
    var showStopConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(routine.name) },
                navigationIcon = {
                    IconButton(onClick = { showStopConfirmation = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showStopConfirmation = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Stop Routine")
                    }
                }
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
        ) {
            // Horizontal pager for exercises
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 32.dp),
                pageSpacing = 16.dp
            ) { page ->
                val exercise = routine.exercises[page]
                val isCompleted = completedExercises.contains(page)

                ExerciseOverviewCard(
                    exercise = exercise,
                    exerciseIndex = page,
                    isCompleted = isCompleted,
                    weightUnit = weightUnit,
                    formatWeight = viewModel::formatWeight,
                    onStartExercise = {
                        viewModel.enterSetReady(page, 0)
                        navController.navigate(NavigationRoutes.SetReady.route)
                    }
                )
            }

            // Page indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(routine.exercises.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    val isCompleted = completedExercises.contains(index)

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isSelected) 12.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCompleted -> MaterialTheme.colorScheme.tertiary
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                }
            }

            // Connection status indicator
            if (connectionState !is ConnectionState.Connected) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            "Warning",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Connect to Vitruvian to start exercises",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
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
                        navController.navigateUp()
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

@Composable
private fun ExerciseOverviewCard(
    exercise: RoutineExercise,
    exerciseIndex: Int,
    isCompleted: Boolean,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onStartExercise: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Exercise header
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Exercise ${exerciseIndex + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        exercise.exercise.displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        exercise.exercise.muscleGroups,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Set summary
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${exercise.setReps.size} sets",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))

                    // Show set breakdown
                    exercise.setReps.forEachIndexed { index, reps ->
                        val setWeight = exercise.setWeightsPerCableKg.getOrNull(index)
                            ?: exercise.weightPerCableKg
                        Text(
                            "${reps ?: "AMRAP"} @ ${formatWeight(setWeight, weightUnit)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Mode and cable config
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text(exercise.programMode.displayName) },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                            }
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text(exercise.cableConfig.name) },
                            leadingIcon = {
                                Icon(Icons.Default.Link, null, Modifier.size(18.dp))
                            }
                        )
                    }
                }

                // Start button
                Button(
                    onClick = onStartExercise,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("START EXERCISE", fontWeight = FontWeight.Bold)
                }
            }

            // Completed overlay
            if (isCompleted) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        "Completed",
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}
