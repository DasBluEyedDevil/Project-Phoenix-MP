package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.cycle.AddDaySheet
import com.devil.phoenixproject.presentation.components.cycle.ProgressionSettingsSheet
import com.devil.phoenixproject.presentation.components.cycle.SwipeableCycleItem
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// UI State for the new playlist-style editor
data class CycleEditorState(
    val cycleName: String = "",
    val description: String = "",
    val items: List<CycleItem> = emptyList(),
    val progression: CycleProgression? = null,
    val currentRotation: Int = 0,
    val showAddDaySheet: Boolean = false,
    val showProgressionSheet: Boolean = false,
    val editingItemIndex: Int? = null // For changing routine on a workout day
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleEditorScreen(
    cycleId: String,
    navController: androidx.navigation.NavController,
    viewModel: MainViewModel,
    routines: List<Routine>,
    initialDayCount: Int? = null
) {
    val repository: TrainingCycleRepository = koinInject()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var state by remember { mutableStateOf(CycleEditorState()) }
    var hasInitialized by remember { mutableStateOf(false) }
    var recentRoutineIds by remember { mutableStateOf<List<String>>(emptyList()) }

    // Track deleted items for undo
    var lastDeletedItem by remember { mutableStateOf<Pair<Int, CycleItem>?>(null) }

    // Load data
    LaunchedEffect(cycleId, initialDayCount) {
        if (!hasInitialized) {
            if (cycleId != "new") {
                val cycle = repository.getCycleById(cycleId)
                val progress = repository.getCycleProgress(cycleId)
                val progression = repository.getCycleProgression(cycleId)
                val items = repository.getCycleItems(cycleId)

                if (cycle != null) {
                    state = state.copy(
                        cycleName = cycle.name,
                        description = cycle.description ?: "",
                        items = items,
                        progression = progression ?: CycleProgression.default(cycleId),
                        currentRotation = progress?.rotationCount ?: 0
                    )
                }
            } else {
                val dayCount = initialDayCount ?: 3
                val items = (1..dayCount).map { dayNum ->
                    CycleItem.Rest(
                        id = generateUUID(),
                        dayNumber = dayNum,
                        note = "Rest"
                    )
                }
                state = state.copy(
                    cycleName = "New Cycle",
                    items = items,
                    progression = CycleProgression.default("temp")
                )
            }
            hasInitialized = true
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val list = state.items.toMutableList()
        val moved = list.removeAt(from.index)
        list.add(to.index, moved)
        // Renumber days
        val renumbered = list.mapIndexed { i, item ->
            when (item) {
                is CycleItem.Workout -> item.copy(dayNumber = i + 1)
                is CycleItem.Rest -> item.copy(dayNumber = i + 1)
            }
        }
        state = state.copy(items = renumbered)
    }

    // Save function
    fun saveCycle() {
        scope.launch {
            try {
                val cycleIdToUse = if (cycleId == "new") generateUUID() else cycleId

                // Convert CycleItems back to CycleDays
                val days = state.items.map { item ->
                    when (item) {
                        is CycleItem.Workout -> CycleDay.create(
                            id = item.id,
                            cycleId = cycleIdToUse,
                            dayNumber = item.dayNumber,
                            name = item.routineName,
                            routineId = item.routineId,
                            isRestDay = false
                        )
                        is CycleItem.Rest -> CycleDay.restDay(
                            id = item.id,
                            cycleId = cycleIdToUse,
                            dayNumber = item.dayNumber,
                            name = item.note
                        )
                    }
                }

                val cycle = TrainingCycle.create(
                    id = cycleIdToUse,
                    name = state.cycleName.ifBlank { "Unnamed Cycle" },
                    description = state.description.ifBlank { null },
                    days = days,
                    isActive = false
                )

                if (cycleId == "new") {
                    repository.saveCycle(cycle)
                } else {
                    repository.updateCycle(cycle)
                }

                // Save progression if configured
                state.progression?.let { prog ->
                    repository.saveCycleProgression(prog.copy(cycleId = cycleIdToUse))
                }

                // Navigate to review screen
                navController.navigate(NavigationRoutes.CycleReview.createRoute(cycleIdToUse))
            } catch (e: Exception) {
                Logger.e(e) { "Failed to save training cycle" }
                snackbarHostState.showSnackbar("Failed to save cycle. Please try again.")
            }
        }
    }

    // Add day functions
    fun addWorkoutDay(routine: Routine) {
        val newItem = CycleItem.Workout(
            id = generateUUID(),
            dayNumber = state.items.size + 1,
            routineId = routine.id,
            routineName = routine.name,
            exerciseCount = routine.exercises.size
        )
        state = state.copy(items = state.items + newItem)

        // Track recent routines
        recentRoutineIds = (listOf(routine.id) + recentRoutineIds).distinct().take(3)
    }

    fun addRestDay() {
        val newItem = CycleItem.Rest(
            id = generateUUID(),
            dayNumber = state.items.size + 1,
            note = "Rest"
        )
        state = state.copy(items = state.items + newItem)
    }

    fun deleteItem(index: Int) {
        val item = state.items[index]
        lastDeletedItem = index to item

        val newList = state.items.toMutableList().apply { removeAt(index) }
        val renumbered = newList.mapIndexed { i, it ->
            when (it) {
                is CycleItem.Workout -> it.copy(dayNumber = i + 1)
                is CycleItem.Rest -> it.copy(dayNumber = i + 1)
            }
        }
        state = state.copy(items = renumbered)

        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Day ${item.dayNumber} removed",
                actionLabel = "UNDO",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                lastDeletedItem?.let { (idx, deletedItem) ->
                    val list = state.items.toMutableList()
                    list.add(idx.coerceAtMost(list.size), deletedItem)
                    val renumberedList = list.mapIndexed { i, it ->
                        when (it) {
                            is CycleItem.Workout -> it.copy(dayNumber = i + 1)
                            is CycleItem.Rest -> it.copy(dayNumber = i + 1)
                        }
                    }
                    state = state.copy(items = renumberedList)
                }
            }
            lastDeletedItem = null
        }
    }

    fun duplicateItem(index: Int) {
        val item = state.items[index]
        val duplicate = when (item) {
            is CycleItem.Workout -> item.copy(id = generateUUID(), dayNumber = index + 2)
            is CycleItem.Rest -> item.copy(id = generateUUID(), dayNumber = index + 2)
        }
        val newList = state.items.toMutableList().apply { add(index + 1, duplicate) }
        val renumbered = newList.mapIndexed { i, it ->
            when (it) {
                is CycleItem.Workout -> it.copy(dayNumber = i + 1)
                is CycleItem.Rest -> it.copy(dayNumber = i + 1)
            }
        }
        state = state.copy(items = renumbered)
    }

    fun changeRoutine(index: Int, routine: Routine) {
        val item = state.items[index]
        if (item is CycleItem.Workout) {
            val updated = item.copy(
                routineId = routine.id,
                routineName = routine.name,
                exerciseCount = routine.exercises.size
            )
            val newList = state.items.toMutableList().apply { set(index, updated) }
            state = state.copy(items = newList, editingItemIndex = null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        TextField(
                            value = state.cycleName,
                            onValueChange = { state = state.copy(cycleName = it) },
                            placeholder = { Text("Cycle Name") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                            ),
                            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            singleLine = true
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { saveCycle() }) {
                        Text("Review", fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { state = state.copy(showAddDaySheet = true) }
            ) {
                Icon(Icons.Default.Add, "Add Day")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Description field
            OutlinedTextField(
                value = state.description,
                onValueChange = { state = state.copy(description = it) },
                label = { Text("Description (optional)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )

            // Cycle length header with progression settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CYCLE LENGTH: ${state.items.size} days",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { state = state.copy(showProgressionSheet = true) }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Progression Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Day list or empty state
            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No days added yet.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Build your cycle by adding workout or rest days.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { state = state.copy(showAddDaySheet = true) }
                            ) {
                                Text("+ Add Workout")
                            }
                            OutlinedButton(onClick = { addRestDay() }) {
                                Text("+ Add Rest")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                        ReorderableItem(reorderState, key = item.id) { isDragging ->
                            SwipeableCycleItem(
                                item = item,
                                onDelete = { deleteItem(index) },
                                onDuplicate = { duplicateItem(index) },
                                onTap = {
                                    if (item is CycleItem.Workout) {
                                        state = state.copy(editingItemIndex = index)
                                    }
                                },
                                dragModifier = Modifier.draggableHandle()
                            )
                        }
                    }
                    // Bottom padding for FAB
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }

    // Add Day Sheet
    if (state.showAddDaySheet) {
        AddDaySheet(
            routines = routines,
            recentRoutineIds = recentRoutineIds,
            onSelectRoutine = { routine ->
                addWorkoutDay(routine)
            },
            onAddRestDay = { addRestDay() },
            onDismiss = { state = state.copy(showAddDaySheet = false) }
        )
    }

    // Progression Settings Sheet
    if (state.showProgressionSheet) {
        state.progression?.let { prog ->
            ProgressionSettingsSheet(
                progression = prog,
                currentRotation = state.currentRotation,
                onSave = { newProgression ->
                    state = state.copy(progression = newProgression)
                },
                onDismiss = { state = state.copy(showProgressionSheet = false) }
            )
        }
    }

    // Edit routine sheet (reuse AddDaySheet in edit mode)
    state.editingItemIndex?.let { index ->
        val item = state.items.getOrNull(index)
        if (item is CycleItem.Workout) {
            AddDaySheet(
                routines = routines,
                recentRoutineIds = recentRoutineIds,
                onSelectRoutine = { routine ->
                    changeRoutine(index, routine)
                },
                onAddRestDay = { /* Not applicable in edit mode */ },
                onDismiss = { state = state.copy(editingItemIndex = null) }
            )
        }
    }
}
