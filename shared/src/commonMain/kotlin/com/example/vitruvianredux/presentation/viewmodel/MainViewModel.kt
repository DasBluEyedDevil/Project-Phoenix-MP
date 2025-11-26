package com.example.vitruvianredux.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vitruvianredux.data.preferences.PreferencesManager
import com.example.vitruvianredux.data.preferences.UserPreferences
import com.example.vitruvianredux.data.repository.AutoStopUiState
import com.example.vitruvianredux.data.repository.BleRepository
import com.example.vitruvianredux.data.repository.ExerciseRepository
import com.example.vitruvianredux.data.repository.HandleState
import com.example.vitruvianredux.data.repository.PersonalRecordRepository
import com.example.vitruvianredux.data.repository.ScannedDevice
import com.example.vitruvianredux.data.repository.WorkoutRepository
import co.touchlab.kermit.Logger
import com.example.vitruvianredux.domain.model.*
import com.example.vitruvianredux.domain.usecase.RepCounterFromMachine
import com.example.vitruvianredux.util.BlePacketFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Sealed class hierarchy for workout history items
 */
sealed class HistoryItem {
    abstract val timestamp: Long
}

data class SingleSessionHistoryItem(val session: WorkoutSession) : HistoryItem() {
    override val timestamp: Long = session.timestamp
}

data class GroupedRoutineHistoryItem(
    val routineSessionId: String,
    val routineName: String,
    val sessions: List<WorkoutSession>,
    val totalDuration: Long,
    val totalReps: Int,
    val exerciseCount: Int,
    override val timestamp: Long
) : HistoryItem()

data class TopBarAction(
    val icon: Any, // Placeholder for ImageVector
    val description: String,
    val onClick: () -> Unit
)

class MainViewModel constructor(
    private val bleRepository: BleRepository,
    private val workoutRepository: WorkoutRepository,
    val exerciseRepository: ExerciseRepository,
    val personalRecordRepository: PersonalRecordRepository,
    private val repCounter: RepCounterFromMachine,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = bleRepository.connectionState

    private val _workoutState = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val workoutState: StateFlow<WorkoutState> = _workoutState.asStateFlow()

    private val _currentMetric = MutableStateFlow<WorkoutMetric?>(null)
    val currentMetric: StateFlow<WorkoutMetric?> = _currentMetric.asStateFlow()

    private val _workoutParameters = MutableStateFlow(
        WorkoutParameters(
            workoutType = WorkoutType.Program(ProgramMode.OldSchool),
            reps = 10,
            weightPerCableKg = 10f,
            progressionRegressionKg = 0f,
            isJustLift = false,
            stopAtTop = false,
            warmupReps = 3
        )
    )
    val workoutParameters: StateFlow<WorkoutParameters> = _workoutParameters.asStateFlow()

    private val _repCount = MutableStateFlow(RepCount())
    val repCount: StateFlow<RepCount> = _repCount.asStateFlow()

    private val _repRanges = MutableStateFlow<com.example.vitruvianredux.domain.usecase.RepRanges?>(null)
    val repRanges: StateFlow<com.example.vitruvianredux.domain.usecase.RepRanges?> = _repRanges.asStateFlow()

    private val _autoStopState = MutableStateFlow(AutoStopUiState())
    val autoStopState: StateFlow<AutoStopUiState> = _autoStopState.asStateFlow()

    private val _autoStartCountdown = MutableStateFlow<Int?>(null)
    val autoStartCountdown: StateFlow<Int?> = _autoStartCountdown.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _workoutHistory = MutableStateFlow<List<WorkoutSession>>(emptyList())
    val workoutHistory: StateFlow<List<WorkoutSession>> = _workoutHistory.asStateFlow()

    // Top Bar Title State
    private val _topBarTitle = MutableStateFlow("Vitruvian Project Phoenix")
    val topBarTitle: StateFlow<String> = _topBarTitle.asStateFlow()

    fun updateTopBarTitle(title: String) {
        _topBarTitle.value = title
    }

    // Top Bar Actions State
    private val _topBarActions = MutableStateFlow<List<TopBarAction>>(emptyList())
    val topBarActions: StateFlow<List<TopBarAction>> = _topBarActions.asStateFlow()

    fun setTopBarActions(actions: List<TopBarAction>) {
        _topBarActions.value = actions
    }

    fun clearTopBarActions() {
        _topBarActions.value = emptyList()
    }

    // Top Bar Back Action Override
    private val _topBarBackAction = MutableStateFlow<(() -> Unit)?>(null)
    val topBarBackAction: StateFlow<(() -> Unit)?> = _topBarBackAction.asStateFlow()

    fun setTopBarBackAction(action: () -> Unit) {
        _topBarBackAction.value = action
    }

    fun clearTopBarBackAction() {
        _topBarBackAction.value = null
    }

    // PR Celebration Events
    private val _prCelebrationEvent = MutableSharedFlow<PRCelebrationEvent>()
    val prCelebrationEvent: SharedFlow<PRCelebrationEvent> = _prCelebrationEvent.asSharedFlow()

    // User preferences
    val userPreferences: StateFlow<UserPreferences> = preferencesManager.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

    val weightUnit: StateFlow<WeightUnit> = userPreferences
        .map { it.weightUnit }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WeightUnit.KG)

    val stopAtTop: StateFlow<Boolean> = userPreferences
        .map { it.stopAtTop }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val enableVideoPlayback: StateFlow<Boolean> = userPreferences
        .map { it.enableVideoPlayback }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val autoplayEnabled: StateFlow<Boolean> = userPreferences
        .map { it.autoplayEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Feature 4: Routine Management
    private val _routines = MutableStateFlow<List<Routine>>(emptyList())
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    private val _loadedRoutine = MutableStateFlow<Routine?>(null)
    val loadedRoutine: StateFlow<Routine?> = _loadedRoutine.asStateFlow()

    private val _currentExerciseIndex = MutableStateFlow(0)
    val currentExerciseIndex: StateFlow<Int> = _currentExerciseIndex.asStateFlow()

    private val _currentSetIndex = MutableStateFlow(0)
    val currentSetIndex: StateFlow<Int> = _currentSetIndex.asStateFlow()

    // Weekly Programs
    val weeklyPrograms: StateFlow<List<com.example.vitruvianredux.data.local.WeeklyProgramWithDays>> =
        workoutRepository.getAllPrograms()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val activeProgram: StateFlow<com.example.vitruvianredux.data.local.WeeklyProgramWithDays?> =
        workoutRepository.getActiveProgram()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = null
            )

    // Personal Records
    @Suppress("unused")
    val personalBests: StateFlow<List<com.example.vitruvianredux.data.repository.PersonalRecordEntity>> =
        workoutRepository.getAllPersonalRecords()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    // ========== Stats for HomeScreen ==========

    val allWorkoutSessions: StateFlow<List<WorkoutSession>> =
        workoutRepository.getAllSessions()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val groupedWorkoutHistory: StateFlow<List<HistoryItem>> = allWorkoutSessions.map { sessions ->
        val groupedByRoutine = sessions.filter { it.routineSessionId != null }
            .groupBy { it.routineSessionId!! }
            .map { (id, sessionList) ->
                GroupedRoutineHistoryItem(
                    routineSessionId = id,
                    routineName = sessionList.first().routineName ?: "Unnamed Routine",
                    sessions = sessionList.sortedBy { it.timestamp },
                    totalDuration = sessionList.sumOf { it.duration },
                    totalReps = sessionList.sumOf { it.totalReps },
                    exerciseCount = sessionList.mapNotNull { it.exerciseId }.distinct().count(),
                    timestamp = sessionList.minOf { it.timestamp }
                )
            }
        val singleSessions = sessions.filter { it.routineSessionId == null }
            .map { SingleSessionHistoryItem(it) }

        (groupedByRoutine + singleSessions).sortedByDescending { it.timestamp }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPersonalRecords: StateFlow<List<PersonalRecord>> =
        personalRecordRepository.getAllPRsGrouped()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val completedWorkouts: StateFlow<Int?> = allWorkoutSessions.map { sessions ->
        sessions.size.takeIf { it > 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val workoutStreak: StateFlow<Int?> = MutableStateFlow(null)

    val progressPercentage: StateFlow<Int?> = allWorkoutSessions.map { sessions ->
        if (sessions.size < 2) return@map null
        val latest = sessions[0]
        val previous = sessions[1]
        val latestVol = (latest.weightPerCableKg * 2) * latest.totalReps
        val prevVol = (previous.weightPerCableKg * 2) * previous.totalReps
        if (prevVol <= 0f) return@map null
        ((latestVol - prevVol) / prevVol * 100).toInt()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isWorkoutSetupDialogVisible = MutableStateFlow(false)
    val isWorkoutSetupDialogVisible: StateFlow<Boolean> = _isWorkoutSetupDialogVisible.asStateFlow()

    private val _isAutoConnecting = MutableStateFlow(false)
    val isAutoConnecting: StateFlow<Boolean> = _isAutoConnecting.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private var _pendingConnectionCallback: (() -> Unit)? = null

    private val _hapticEvents = MutableSharedFlow<HapticEvent>(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val hapticEvents: SharedFlow<HapticEvent> = _hapticEvents.asSharedFlow()

    private val _connectionLostDuringWorkout = MutableStateFlow(false)
    val connectionLostDuringWorkout: StateFlow<Boolean> = _connectionLostDuringWorkout.asStateFlow()

    private var currentSessionId: String? = null
    private var workoutStartTime: Long = 0
    private val collectedMetrics = mutableListOf<WorkoutMetric>()

    private var currentRoutineSessionId: String? = null
    private var currentRoutineName: String? = null

    private var autoStopStartTime: Long? = null
    private var connectionJob: Job? = null
    private var monitorDataCollectionJob: Job? = null

    init {
        Logger.d("MainViewModel initialized")

        // Load recent history
        viewModelScope.launch {
            workoutRepository.getAllSessions().collect { sessions ->
                _workoutHistory.value = sessions.take(20)
            }
        }

        // Load routines
        viewModelScope.launch {
            workoutRepository.getAllRoutines().collect { routinesList ->
                _routines.value = routinesList
            }
        }
        
        // Hook up RepCounter
        repCounter.onRepEvent = { event ->
             viewModelScope.launch {
                 when (event.type) {
                     RepType.WORKING_COMPLETED -> _hapticEvents.emit(HapticEvent.REP_COMPLETED)
                     RepType.WARMUP_COMPLETED -> _hapticEvents.emit(HapticEvent.REP_COMPLETED)
                     RepType.WARMUP_COMPLETE -> _hapticEvents.emit(HapticEvent.WARMUP_COMPLETE)
                     RepType.WORKOUT_COMPLETE -> _hapticEvents.emit(HapticEvent.WORKOUT_COMPLETE)
                     else -> {}
                 }
             }
        }
    }

    fun startScanning() {
        viewModelScope.launch { bleRepository.startScanning() }
    }

    fun stopScanning() {
        viewModelScope.launch { bleRepository.stopScanning() }
    }

    fun connectToDevice(deviceAddress: String) {
        // Handled by ensureConnection mostly, but direct connect stub
    }

    fun disconnect() {
        viewModelScope.launch { bleRepository.disconnect() }
    }

    fun updateWorkoutParameters(params: WorkoutParameters) {
        _workoutParameters.value = params
    }

    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false) {
        val connection = connectionState.value
        if (connection !is ConnectionState.Connected) {
            _connectionError.value = "Not connected to device"
            return
        }

        viewModelScope.launch {
            // 1. Build Command
            val params = _workoutParameters.value
            val command = if (params.workoutType is WorkoutType.Program) {
                BlePacketFactory.createWorkoutCommand(
                    params.workoutType,
                    params.weightPerCableKg
                )
            } else {
                 val echo = params.workoutType as WorkoutType.Echo
                 BlePacketFactory.createEchoCommand(echo.level.levelValue, echo.eccentricLoad.percentage)
            }

            // 2. Send Command
            try {
                bleRepository.sendWorkoutCommand(command)
                Logger.d { "Workout command sent: $params" }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to send command" }
                _connectionError.value = "Failed to send command: ${e.message}"
                return@launch
            }

            // 3. Reset State
            _repCount.value = RepCount()
            repCounter.reset()
            repCounter.configure(
                warmupTarget = params.warmupReps,
                workingTarget = params.reps,
                isJustLift = isJustLiftMode,
                stopAtTop = params.stopAtTop,
                isAMRAP = params.isAMRAP
            )
            
            // 4. Countdown
            if (!skipCountdown && !isJustLiftMode) {
                for (i in 5 downTo 1) {
                    _workoutState.value = WorkoutState.Countdown(i)
                    delay(1000)
                }
            }
            
            // 5. Start Monitoring
            _workoutState.value = WorkoutState.Active
            workoutStartTime = currentTimeMillis()
            _hapticEvents.emit(HapticEvent.WORKOUT_START)
            monitorWorkout(isJustLiftMode)
        }
    }

    private fun monitorWorkout(isJustLift: Boolean) {
        monitorDataCollectionJob?.cancel()
        monitorDataCollectionJob = viewModelScope.launch {
             bleRepository.metricsFlow.collect { metric ->
                 _currentMetric.value = metric
                 
                 repCounter.process(
                     repsRomCount = 0, // TODO: Extract from packet
                     repsSetCount = 0, // TODO: Extract from packet
                     posA = metric.positionA,
                     posB = metric.positionB
                 )
                 
                 _repCount.value = repCounter.getRepCount()
                 
                 // Just Lift Auto-Stop
                 if (isJustLift && repCounter.hasMeaningfulRange()) {
                     // Implementation for Just Lift auto-stop logic would go here
                     // using repCounter.isInDangerZone(...)
                 }

                 // Standard Auto-Stop
                 if (repCounter.shouldStopWorkout()) {
                     stopWorkout()
                 }
             }
        }
    }

    fun stopWorkout() {
        viewModelScope.launch {
             bleRepository.sendWorkoutCommand(BlePacketFactory.createStopCommand())
             monitorDataCollectionJob?.cancel()
             _hapticEvents.emit(HapticEvent.WORKOUT_END)
             
             val params = _workoutParameters.value
             val repCount = _repCount.value
             
             val session = WorkoutSession(
                 timestamp = workoutStartTime,
                 mode = params.workoutType.displayName,
                 reps = params.reps,
                 weightPerCableKg = params.weightPerCableKg,
                 totalReps = repCount.totalReps,
                 workingReps = repCount.workingReps,
                 warmupReps = repCount.warmupReps,
                 duration = currentTimeMillis() - workoutStartTime,
                 isJustLift = params.isJustLift
             )
             workoutRepository.saveSession(session)
             
             // Show Summary
             val metrics = emptyList<WorkoutMetric>() // TODO: collectedMetrics
             _workoutState.value = WorkoutState.SetSummary(
                 metrics = metrics,
                 peakPower = 0f,
                 averagePower = 0f,
                 repCount = repCount.totalReps
             )
        }
    }

    fun pauseWorkout() {
        _workoutState.value = WorkoutState.Paused
    }

    fun resumeWorkout() {
        _workoutState.value = WorkoutState.Active
    }

    fun skipRest() {
        // TODO: Cancel rest timer job
        proceedFromSummary()
    }

    fun setWeightUnit(unit: WeightUnit) {
        viewModelScope.launch { preferencesManager.setWeightUnit(unit) }
    }

    fun setStopAtTop(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setStopAtTop(enabled) }
    }

    fun setEnableVideoPlayback(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setEnableVideoPlayback(enabled) }
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setAutoplayEnabled(enabled) }
    }

    fun setColorScheme(schemeIndex: Int) {
        viewModelScope.launch { bleRepository.setColorScheme(schemeIndex) }
    }

    fun deleteAllWorkouts() {
        viewModelScope.launch { workoutRepository.deleteAllSessions() }
    }

    fun clearConnectionError() {
        _connectionError.value = null
    }

    fun cancelAutoConnecting() {
        _isAutoConnecting.value = false
        _connectionError.value = null
        connectionJob?.cancel()
        connectionJob = null
        viewModelScope.launch { bleRepository.stopScanning() }
    }

    fun ensureConnection(onConnected: () -> Unit, onFailed: () -> Unit = {}) {
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            try {
                if (connectionState.value is ConnectionState.Connected) {
                    onConnected()
                    return@launch
                }
                
                _isAutoConnecting.value = true
                _connectionError.value = null
                bleRepository.startScanning()
                
                // Wait for device scan (Simplified auto-connect to first device for V1)
                val device = withTimeoutOrNull(10000) {
                    bleRepository.scannedDevices.filter { it.isNotEmpty() }.map { it.first() }.firstOrNull()
                }
                
                if (device != null) {
                    bleRepository.connect(device)
                    // Wait for connection
                    val connected = withTimeoutOrNull(10000) {
                        connectionState.filter { it is ConnectionState.Connected }.firstOrNull()
                    }
                    
                    if (connected != null) {
                        _isAutoConnecting.value = false
                        onConnected()
                    } else {
                        _connectionError.value = "Connection failed"
                        _isAutoConnecting.value = false
                    }
                } else {
                    _connectionError.value = "No device found"
                    _isAutoConnecting.value = false
                    bleRepository.stopScanning()
                }
            } catch (e: Exception) {
                _connectionError.value = "Error: ${e.message}"
                _isAutoConnecting.value = false
            }
        }
    }

    fun proceedFromSummary() {
        _workoutState.value = WorkoutState.Idle
    }

    fun resetForNewWorkout() {
        _workoutState.value = WorkoutState.Idle
        _repCount.value = RepCount()
    }

    fun advanceToNextExercise() {
        // TODO
    }

    fun deleteWorkout(sessionId: String) {
        viewModelScope.launch { workoutRepository.deleteSession(sessionId) }
    }

    fun kgToDisplay(kg: Float, unit: WeightUnit): Float =
        when (unit) {
            WeightUnit.KG -> kg
            WeightUnit.LB -> kg * 2.20462f
        }

    fun displayToKg(display: Float, unit: WeightUnit): Float =
        when (unit) {
            WeightUnit.KG -> display
            WeightUnit.LB -> display / 2.20462f
        }

    fun formatWeight(kg: Float, unit: WeightUnit): String {
        val value = kgToDisplay(kg, unit)
        return "%.1f".format(value)
    }

    fun saveRoutine(routine: Routine) {
        viewModelScope.launch { workoutRepository.saveRoutine(routine) }
    }

    fun updateRoutine(routine: Routine) {
        viewModelScope.launch { workoutRepository.updateRoutine(routine) }
    }

    fun deleteRoutine(routineId: String) {
        viewModelScope.launch { workoutRepository.deleteRoutine(routineId) }
    }

    fun loadRoutine(routine: Routine) {
        _loadedRoutine.value = routine
        _currentExerciseIndex.value = 0
        _currentSetIndex.value = 0
    }

    fun loadRoutineById(routineId: String) {
        val routine = _routines.value.find { it.id == routineId }
        if (routine != null) {
            loadRoutine(routine)
        }
    }

    fun clearLoadedRoutine() {
        _loadedRoutine.value = null
    }

    fun getCurrentExercise(): RoutineExercise? {
        val routine = _loadedRoutine.value ?: return null
        return routine.exercises.getOrNull(_currentExerciseIndex.value)
    }
}
