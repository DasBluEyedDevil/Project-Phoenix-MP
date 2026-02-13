package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.KmpUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlin.math.ceil

// ===== Data classes that move with DefaultWorkoutSessionManager =====

/**
 * Data class for storing Just Lift session defaults.
 */
data class JustLiftDefaults(
    val weightPerCableKg: Float,
    val weightChangePerRep: Int, // In display units (kg or lbs based on user preference)
    val workoutModeId: Int, // 0=OldSchool, 1=Pump, 10=Echo
    val eccentricLoadPercentage: Int = 100,
    val echoLevelValue: Int = 1, // 0=Hard, 1=Harder, 2=Hardest, 3=Epic
    val stallDetectionEnabled: Boolean = true // Stall detection auto-stop toggle
) {
    /**
     * Convert stored mode ID to ProgramMode
     */
    fun toProgramMode(): ProgramMode = when (workoutModeId) {
        0 -> ProgramMode.OldSchool
        2 -> ProgramMode.Pump
        3 -> ProgramMode.TUT
        4 -> ProgramMode.TUTBeast
        6 -> ProgramMode.EccentricOnly
        10 -> ProgramMode.Echo
        else -> ProgramMode.OldSchool
    }

    /**
     * Get EccentricLoad from stored percentage
     */
    fun getEccentricLoad(): EccentricLoad = when (eccentricLoadPercentage) {
        0 -> EccentricLoad.LOAD_0
        50 -> EccentricLoad.LOAD_50
        75 -> EccentricLoad.LOAD_75
        100 -> EccentricLoad.LOAD_100
        110 -> EccentricLoad.LOAD_110
        120 -> EccentricLoad.LOAD_120
        130 -> EccentricLoad.LOAD_130
        140 -> EccentricLoad.LOAD_140
        150 -> EccentricLoad.LOAD_150
        else -> EccentricLoad.LOAD_100
    }

    /**
     * Get EchoLevel from stored value
     */
    fun getEchoLevel(): EchoLevel = EchoLevel.entries.getOrElse(echoLevelValue) { EchoLevel.HARDER }
}

/**
 * Data class for resumable workout progress information.
 * Used to display progress in the Resume/Restart dialog.
 */
data class ResumableProgressInfo(
    val exerciseName: String,
    val currentSet: Int,
    val totalSets: Int,
    val currentExercise: Int,
    val totalExercises: Int
)

/**
 * Event emitted when a training cycle day is completed after a workout.
 * Consumed by TrainingCyclesScreen to show completion feedback.
 */
data class CycleDayCompletionEvent(
    val dayNumber: Int,
    val dayName: String?,
    val isRotationComplete: Boolean,
    val rotationCount: Int
)

// ===== DefaultWorkoutSessionManager =====

/**
 * Manages the entire workout lifecycle: starting, stopping, pausing, resuming,
 * rep counting, auto-stop, rest timers, routine navigation, superset flow,
 * weight adjustment, Just Lift mode, and training cycles.
 *
 * Extracted from MainViewModel during monolith decomposition (Phase 3).
 */
class DefaultWorkoutSessionManager(
    private val bleRepository: BleRepository,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val repCounter: RepCounterFromMachine,
    private val preferencesManager: PreferencesManager,
    private val gamificationManager: GamificationManager,
    private val trainingCycleRepository: TrainingCycleRepository,
    private val completedSetRepository: CompletedSetRepository,
    private val syncTriggerManager: SyncTriggerManager?,
    private val resolveWeightsUseCase: ResolveRoutineWeightsUseCase,
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope,
    private val _hapticEvents: MutableSharedFlow<HapticEvent> = MutableSharedFlow(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
) : WorkoutStateProvider {

    // ===== Coordinator: Shared state bus for all workout state =====
    val coordinator = WorkoutCoordinator(_hapticEvents)

    // BleConnectionManager is set after construction (circular dependency)
    lateinit var bleConnectionManager: BleConnectionManager

    companion object {
        /** Prefix for temporary single exercise routines to identify them for cleanup */
        const val TEMP_SINGLE_EXERCISE_PREFIX = "temp_single_"
    }

    fun clearCycleDayCompletionEvent() {
        coordinator._cycleDayCompletionEvent.value = null
    }

    // ===== WorkoutStateProvider Implementation =====

    override val isWorkoutActiveForConnectionAlert: Boolean
        get() = when (coordinator._workoutState.value) {
            is WorkoutState.Active, is WorkoutState.Countdown, is WorkoutState.Resting -> true
            else -> false
        }

    // ===== Init Block: Workout-Related Collectors =====

    init {
        Logger.d("DefaultWorkoutSessionManager initialized")

        // Load routines (filter out cycle template routines that shouldn't show in Daily Routines)
        scope.launch {
            workoutRepository.getAllRoutines().collect { routinesList ->
                // Exclude routines created by template cycles (prefixed with cycle_routine_)
                coordinator._routines.value = routinesList.filter { !it.id.startsWith("cycle_routine_") }
            }
        }

        // Import exercises if not already imported
        scope.launch {
            try {
                val result = exerciseRepository.importExercises()
                if (result.isSuccess) {
                    Logger.d { "Exercise library initialized" }
                } else {
                    Logger.e { "Failed to initialize exercise library: ${result.exceptionOrNull()?.message}" }
                }
            } catch (e: Exception) {
                Logger.e(e) { "Error initializing exercise library" }
            }
        }

        // Hook up RepCounter
        repCounter.onRepEvent = { event ->
             scope.launch {
                 when (event.type) {
                     RepType.WORKING_COMPLETED -> {
                         // Check if audio rep count is enabled and rep is within announcement range (1-25)
                         // Use event.workingCount (not coordinator._repCount.value) - the state hasn't been updated yet
                         val prefs = settingsManager.userPreferences.value
                         if (prefs.audioRepCountEnabled && event.workingCount in 1..25) {
                             coordinator._hapticEvents.emit(HapticEvent.REP_COUNT_ANNOUNCED(event.workingCount))
                         } else {
                             coordinator._hapticEvents.emit(HapticEvent.REP_COMPLETED)
                         }
                     }
                     RepType.WARMUP_COMPLETED -> coordinator._hapticEvents.emit(HapticEvent.REP_COMPLETED)
                     RepType.WARMUP_COMPLETE -> coordinator._hapticEvents.emit(HapticEvent.WARMUP_COMPLETE)
                     RepType.WORKOUT_COMPLETE -> {
                         // Note: WORKOUT_COMPLETE sound removed - WORKOUT_END in handleSetCompletion
                         // provides sufficient feedback, and celebration sounds (PR/badge) may also play.
                         // Playing both was causing multiple sounds to fire at once (sound stacking bug).
                         // Issue #182: Trigger set completion immediately on WORKOUT_COMPLETE event.
                         if (coordinator._workoutState.value is WorkoutState.Active) {
                             Logger.d("WORKOUT_COMPLETE event received - triggering immediate set completion")
                             handleSetCompletion()
                         }
                     }
                     else -> {}
                 }
             }
        }

        // Handle activity state collector for auto-start functionality
        // Uses 4-state machine from BLE repo (matches parent repo v0.5.1-beta):
        // WaitingForRest -> SetComplete (armed) -> Moving (intermediate) -> Active (grabbed with velocity)
        scope.launch {
            bleRepository.handleState.collect { activityState ->
                val params = coordinator._workoutParameters.value
                val currentState = coordinator._workoutState.value
                val isIdle = currentState is WorkoutState.Idle
                val isSummaryAndJustLift = currentState is WorkoutState.SetSummary && params.isJustLift

                // Handle auto-START when Idle and waiting for handles
                // Also allow auto-start from SetSummary if in Just Lift mode (interrupting the summary)
                if (params.useAutoStart && (isIdle || isSummaryAndJustLift)) {
                    when (activityState) {
                        HandleState.Grabbed -> {
                            Logger.d("Handles grabbed! Starting auto-start timer (State: ${coordinator._workoutState.value})")
                            startAutoStartTimer()
                        }
                        HandleState.Moving -> {
                            // Moving = position extended but no velocity yet
                            // Don't start countdown yet, but also don't cancel if already running
                            // This allows user to slowly pick up handles without false trigger
                        }
                        HandleState.Released -> {
                            Logger.d("Handles released! Canceling auto-start timer")
                            cancelAutoStartTimer()
                        }
                        HandleState.WaitingForRest -> {
                            cancelAutoStartTimer()
                        }
                    }
                }

                // Handle auto-STOP when Active in Just Lift mode and handles released
                // This starts the countdown timer via checkAutoStop logic (triggered by handle state)
                if (params.isJustLift && currentState is WorkoutState.Active) {
                    if (activityState == HandleState.Released) {
                        Logger.d("Just Lift: Handles RELEASED - starting auto-stop timer")
                        // Do NOT trigger immediately. Let checkAutoStop handle the timer.
                        // We ensure coordinator.autoStopStartTime is set to start the countdown.
                        if (coordinator.autoStopStartTime == null) {
                            coordinator.autoStopStartTime = currentTimeMillis()
                            Logger.d("Auto-stop timer STARTED (Just Lift) - handles released")
                        }
                    } else if (activityState == HandleState.Grabbed || activityState == HandleState.Moving) {
                        // User resumed activity, reset auto-stop timer
                        resetAutoStopTimer()
                    }
                }

                // Track handle activity state for UI
                coordinator.currentHandleState = activityState
            }
        }

        // Issue #98: Deload event collector for firmware-based auto-stop detection
        scope.launch {
            bleRepository.deloadOccurredEvents.collect {
                val params = coordinator._workoutParameters.value
                val currentState = coordinator._workoutState.value

                // Only trigger auto-stop when mode allows it and workout is active.
                // Timed cable exercises are eligible ONLY after warmup completes.
                if (shouldEnableAutoStop(params) && currentState is WorkoutState.Active) {
                    Logger.d("DELOAD_OCCURRED: Machine detected cable release - starting auto-stop timer")

                    val hasMeaningfulRange = repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD)
                    val inGrace = isInAmrapStartupGrace(hasMeaningfulRange)

                    if (coordinator.stallStartTime == null && !inGrace && hasMeaningfulRange) {
                        coordinator.stallStartTime = currentTimeMillis()
                        coordinator.isCurrentlyStalled = true
                        Logger.d("Auto-stop stall timer STARTED via DELOAD_OCCURRED flag")
                    } else if (inGrace) {
                        Logger.d("DELOAD_OCCURRED ignored - in AMRAP startup grace period")
                    } else if (!hasMeaningfulRange) {
                        Logger.d("DELOAD_OCCURRED ignored - no meaningful ROM established yet (warmup incomplete)")
                    }
                }
            }
        }

        // Rep events collector for handling machine rep notifications
        coordinator.repEventsCollectionJob = scope.launch {
            bleRepository.repEvents.collect { notification ->
                val state = coordinator._workoutState.value
                if (state is WorkoutState.Active) {
                    handleRepNotification(notification)
                }
            }
        }

        // CRITICAL: Global metricsFlow collection (matches parent repo)
        // This runs continuously regardless of workout state, enabling:
        // - Position tracking during handle detection phase (before workout starts)
        // - Position bars to update immediately when connected
        // - Continuous position range calibration for auto-stop detection
        coordinator.monitorDataCollectionJob = scope.launch {
            Logger.d("DefaultWorkoutSessionManager") { "Starting global metricsFlow collection..." }
            bleRepository.metricsFlow.collect { metric ->
                coordinator._currentMetric.value = metric
                handleMonitorMetric(metric)
            }
        }

        // Heuristic data collection for Echo mode force feedback (matching parent repo)
        scope.launch {
            bleRepository.heuristicData.collect { stats ->
                if (stats != null && coordinator._workoutState.value is WorkoutState.Active) {
                    // Track maximum force (kgMax) across both phases for Echo mode
                    // kgMax is per-cable force in kg
                    val concentricMax = stats.concentric.kgMax
                    val eccentricMax = stats.eccentric.kgMax
                    val currentMax = maxOf(concentricMax, eccentricMax)

                    // Update live display value for Echo mode
                    coordinator._currentHeuristicKgMax.value = currentMax

                    // Track session maximum for history recording
                    if (currentMax > coordinator.maxHeuristicKgMax) {
                        coordinator.maxHeuristicKgMax = currentMax
                        Logger.v("DefaultWorkoutSessionManager") { "Echo force telemetry: kgMax=$currentMax (concentric=$concentricMax, eccentric=$eccentricMax)" }
                    }
                }
            }
        }
    }

    // ===== Round 1: Pure helpers =====

    fun getRoutineById(routineId: String): Routine? {
        return coordinator._routines.value.find { it.id == routineId }
    }

    /**
     * Look up the PlannedSet ID for the current routine exercise and set index.
     * Returns null if no PlannedSet exists (e.g., Just Lift mode, or no planned sets saved).
     */
    private suspend fun findPlannedSetId(setIndex: Int): String? {
        val routineExercise = getCurrentExercise() ?: return null
        val plannedSets = completedSetRepository.getPlannedSets(routineExercise.id)
        return plannedSets.find { it.setNumber == setIndex }?.id
    }

    /**
     * Check if the given exercise is a bodyweight exercise.
     *
     * Bodyweight = no cable accessories (HANDLES, BAR, ROPE, SHORT_BAR, BELT, STRAPS)
     * in the exercise's equipment list. Non-cable equipment like BENCH is allowed.
     */
    private fun isBodyweightExercise(exercise: RoutineExercise?): Boolean {
        return exercise?.let {
            val isBodyweight = !it.exercise.hasCableAccessory
            Logger.d { "isBodyweightExercise: exercise=${it.exercise.name}, equipment='${it.exercise.equipment}', hasCableAccessory=${it.exercise.hasCableAccessory}, result=$isBodyweight" }
            isBodyweight
        } ?: false
    }

    /**
     * Check if current workout is in single exercise mode.
     */
    private fun isSingleExerciseMode(): Boolean {
        val routine = coordinator._loadedRoutine.value
        return routine == null || routine.id.startsWith(TEMP_SINGLE_EXERCISE_PREFIX)
    }

    /**
     * Calculate enhanced metrics for the set summary display.
     */
    private fun calculateSetSummaryMetrics(
        metrics: List<WorkoutMetric>,
        repCount: Int,
        fallbackWeightKg: Float,
        isEchoMode: Boolean = false,
        warmupRepsCount: Int = 0,
        workingRepsCount: Int = 0,
        baselineLoadA: Float = 0f,
        baselineLoadB: Float = 0f
    ): WorkoutState.SetSummary {
        if (metrics.isEmpty()) {
            return WorkoutState.SetSummary(
                metrics = metrics,
                peakPower = 0f,
                averagePower = 0f,
                repCount = repCount,
                heaviestLiftKgPerCable = fallbackWeightKg
            )
        }

        // Duration from first to last metric
        val durationMs = metrics.last().timestamp - metrics.first().timestamp

        // Subtract baseline cable tension (~4kg/cable) from raw BLE load values.
        // The machine exerts base tension even at rest; without subtraction all stats are inflated.
        val blA = baselineLoadA.coerceAtLeast(0f)
        val blB = baselineLoadB.coerceAtLeast(0f)
        fun adjA(raw: Float) = (raw - blA).coerceAtLeast(0f)
        fun adjB(raw: Float) = (raw - blB).coerceAtLeast(0f)

        // Heaviest lift (max load per cable, baseline-adjusted)
        val heaviestLiftKgPerCable = metrics.maxOf { maxOf(adjA(it.loadA), adjB(it.loadB)) }

        // Total volume = sum of per-cable peaks × reps
        // Previous: avgTotalLoad * repCount — wrong because averaging ALL BLE samples
        // (including rest, handle pickup, cable retraction at ~4kg) massively dilutes the value.
        // Uses actual per-cable peaks for accuracy with asymmetric loading.
        val peakCableA = metrics.maxOf { adjA(it.loadA) }
        val peakCableB = metrics.maxOf { adjB(it.loadB) }
        val totalVolumeKg = (peakCableA + peakCableB) * repCount

        // Separate concentric (velocity > 0) and eccentric (velocity < 0) phases
        val concentricMetrics = metrics.filter { it.velocityA > 10 || it.velocityB > 10 }
        val eccentricMetrics = metrics.filter { it.velocityA < -10 || it.velocityB < -10 }

        // Peak forces per phase (baseline-adjusted)
        val peakConcentricA = concentricMetrics.maxOfOrNull { adjA(it.loadA) } ?: 0f
        val peakConcentricB = concentricMetrics.maxOfOrNull { adjB(it.loadB) } ?: 0f
        val peakEccentricA = eccentricMetrics.maxOfOrNull { adjA(it.loadA) } ?: 0f
        val peakEccentricB = eccentricMetrics.maxOfOrNull { adjB(it.loadB) } ?: 0f

        // Average forces per phase — filter out transition noise.
        // Only include samples where adjusted load > 10% of peak to exclude
        // handle pickup, rest between reps, and cable retraction samples.
        val peakLoadA = metrics.maxOf { adjA(it.loadA) }
        val peakLoadB = metrics.maxOf { adjB(it.loadB) }
        val thresholdA = (peakLoadA * 0.1f).coerceAtLeast(1f)  // Min 1kg to exclude noise on unused cable
        val thresholdB = (peakLoadB * 0.1f).coerceAtLeast(1f)

        val activeConcentricMetrics = concentricMetrics.filter {
            adjA(it.loadA) > thresholdA || adjB(it.loadB) > thresholdB
        }
        val activeEccentricMetrics = eccentricMetrics.filter {
            adjA(it.loadA) > thresholdA || adjB(it.loadB) > thresholdB
        }

        val avgConcentricA = if (activeConcentricMetrics.isNotEmpty())
            activeConcentricMetrics.map { adjA(it.loadA) }.average().toFloat() else 0f
        val avgConcentricB = if (activeConcentricMetrics.isNotEmpty())
            activeConcentricMetrics.map { adjB(it.loadB) }.average().toFloat() else 0f
        val avgEccentricA = if (activeEccentricMetrics.isNotEmpty())
            activeEccentricMetrics.map { adjA(it.loadA) }.average().toFloat() else 0f
        val avgEccentricB = if (activeEccentricMetrics.isNotEmpty())
            activeEccentricMetrics.map { adjB(it.loadB) }.average().toFloat() else 0f

        // Estimate calories: Work = Force × Distance, roughly 4.184 J per calorie
        // Simplified estimate: totalVolume (kg) × 0.5m ROM × 9.81 / 4184
        val estimatedCalories = (totalVolumeKg * 0.5f * 9.81f / 4184f).coerceAtLeast(1f)

        // Legacy power values (baseline-adjusted)
        val peakPower = heaviestLiftKgPerCable
        val averagePower = metrics.map { (adjA(it.loadA) + adjB(it.loadB)) / 2f }.average().toFloat()

        // Echo Mode Phase-Aware Metrics
        var warmupAvgWeightKg = 0f
        var workingAvgWeightKg = 0f
        var burnoutAvgWeightKg = 0f
        var peakWeightKg = 0f
        var burnoutReps = 0

        if (isEchoMode && metrics.size > 10) {
            // Detect phases by analyzing weight progression (baseline-adjusted)
            // Echo mode: weight increases (warmup) -> stabilizes (working) -> decreases (burnout)
            val weightSamples = metrics.map { maxOf(adjA(it.loadA), adjB(it.loadB)) }
            peakWeightKg = weightSamples.maxOrNull() ?: 0f
            val peakThreshold = peakWeightKg * 0.9f  // Within 90% of peak is "working" phase

            // Find peak indices
            val peakIndices = weightSamples.indices.filter { weightSamples[it] >= peakThreshold }

            if (peakIndices.isNotEmpty()) {
                val firstPeakIndex = peakIndices.first()
                val lastPeakIndex = peakIndices.last()

                // Warmup phase: samples before first peak
                val warmupSamples = weightSamples.take(firstPeakIndex)
                warmupAvgWeightKg = if (warmupSamples.isNotEmpty())
                    warmupSamples.average().toFloat() else 0f

                // Working phase: samples around peak
                val workingSamples = weightSamples.subList(firstPeakIndex, (lastPeakIndex + 1).coerceAtMost(weightSamples.size))
                workingAvgWeightKg = if (workingSamples.isNotEmpty())
                    workingSamples.average().toFloat() else peakWeightKg

                // Burnout phase: samples after last peak
                val burnoutSamples = if (lastPeakIndex < weightSamples.lastIndex)
                    weightSamples.drop(lastPeakIndex + 1) else emptyList()
                burnoutAvgWeightKg = if (burnoutSamples.isNotEmpty())
                    burnoutSamples.average().toFloat() else 0f

                // Estimate burnout reps based on weight decline pattern
                // Total reps = warmup + working + burnout
                val totalReps = warmupRepsCount + workingRepsCount
                if (burnoutSamples.isNotEmpty() && totalReps > 0) {
                    // Estimate burnout reps proportionally based on samples
                    val burnoutRatio = burnoutSamples.size.toFloat() / weightSamples.size.toFloat()
                    burnoutReps = (totalReps * burnoutRatio).toInt().coerceAtLeast(0)
                }
            } else {
                // No clear peak - treat all as working phase
                workingAvgWeightKg = weightSamples.average().toFloat()
                peakWeightKg = workingAvgWeightKg
            }
        }

        return WorkoutState.SetSummary(
            metrics = metrics,
            peakPower = peakPower,
            averagePower = averagePower,
            repCount = repCount,
            durationMs = durationMs,
            totalVolumeKg = totalVolumeKg,
            heaviestLiftKgPerCable = heaviestLiftKgPerCable,
            peakForceConcentricA = peakConcentricA,
            peakForceConcentricB = peakConcentricB,
            peakForceEccentricA = peakEccentricA,
            peakForceEccentricB = peakEccentricB,
            avgForceConcentricA = avgConcentricA,
            avgForceConcentricB = avgConcentricB,
            avgForceEccentricA = avgEccentricA,
            avgForceEccentricB = avgEccentricB,
            estimatedCalories = estimatedCalories,
            // Echo Mode Phase Metrics
            isEchoMode = isEchoMode,
            warmupReps = warmupRepsCount,
            workingReps = workingRepsCount,
            burnoutReps = burnoutReps,
            warmupAvgWeightKg = warmupAvgWeightKg,
            workingAvgWeightKg = workingAvgWeightKg,
            burnoutAvgWeightKg = burnoutAvgWeightKg,
            peakWeightKg = peakWeightKg
        )
    }

    /**
     * Collect metric for history recording.
     */
    private fun collectMetricForHistory(metric: WorkoutMetric) {
        coordinator.collectedMetrics.add(metric)
    }

    fun resetForNewWorkout() {
        coordinator._workoutState.value = WorkoutState.Idle
        coordinator._repCount.value = RepCount()
        coordinator._repRanges.value = null  // Clear ROM calibration for new workout
        // Note: Load baseline is NOT reset here - it persists across sets in the same workout session
        // This is intentional since the base tension doesn't change between sets
    }

    /**
     * Manually recapture load baseline (tare function).
     */
    fun recaptureLoadBaseline() {
        coordinator._currentMetric.value?.let { metric ->
            coordinator._loadBaselineA.value = metric.loadA
            coordinator._loadBaselineB.value = metric.loadB
            Logger.d("DefaultWorkoutSessionManager") { "LOAD BASELINE: Manually recaptured loadA=${metric.loadA}kg, loadB=${metric.loadB}kg" }
        }
    }

    /**
     * Reset load baseline to zero (disable baseline subtraction).
     */
    fun resetLoadBaseline() {
        coordinator._loadBaselineA.value = 0f
        coordinator._loadBaselineB.value = 0f
        Logger.d("DefaultWorkoutSessionManager") { "LOAD BASELINE: Reset to 0 (disabled)" }
    }

    /**
     * Reset auto-stop timer without resetting the triggered flag.
     */
    private fun resetAutoStopTimer() {
        coordinator.autoStopStartTime = null
        if (!coordinator.autoStopTriggered && !coordinator.isCurrentlyStalled) {
            coordinator._autoStopState.value = AutoStopUiState()
        }
    }

    /**
     * Reset stall detection timer.
     */
    private fun resetStallTimer() {
        coordinator.stallStartTime = null
        coordinator.isCurrentlyStalled = false
        // Only reset UI if position-based detection isn't active
        if (coordinator.autoStopStartTime == null && !coordinator.autoStopTriggered) {
            coordinator._autoStopState.value = AutoStopUiState()
        }
    }

    /**
     * Fully reset auto-stop state for a new workout/set.
     */
    private fun resetAutoStopState() {
        coordinator.autoStopStartTime = null
        coordinator.autoStopTriggered = false
        coordinator.autoStopStopRequested = false
        coordinator.stallStartTime = null
        coordinator.isCurrentlyStalled = false
        coordinator._autoStopState.value = AutoStopUiState()
    }

    /**
     * Issue #204: Returns true if we're in the startup grace period for auto-stop modes.
     */
    private fun isInAmrapStartupGrace(hasMeaningfulRange: Boolean): Boolean {
        // Grace period applies to AMRAP and Just Lift modes
        // This prevents premature auto-stop before user grabs handles
        // (Issue: Exercise was ending in ~2 seconds if handles weren't grabbed immediately)
        val params = coordinator._workoutParameters.value
        if (!params.isAMRAP && !params.isJustLift) return false

        // If meaningful range established, user has started exercising - no grace needed
        if (hasMeaningfulRange) return false

        // coordinator.workoutStartTime == 0 means we're in the race window between Active state
        // and coordinator.workoutStartTime assignment - treat as "in grace" to be safe
        if (coordinator.workoutStartTime == 0L) return true

        val elapsed = currentTimeMillis() - coordinator.workoutStartTime
        return elapsed < WorkoutCoordinator.AMRAP_STARTUP_GRACE_MS
    }

    /**
     * Whether auto-stop logic should run for the current mode/state.
     */
    private fun shouldEnableAutoStop(params: WorkoutParameters): Boolean {
        val timedCableReadyForAutoStop = coordinator.isCurrentTimedCableExercise && coordinator._repCount.value.isWarmupComplete
        return params.isJustLift || params.isAMRAP || timedCableReadyForAutoStop
    }

    /**
     * Request auto-stop (thread-safe, only triggers once).
     */
    private fun requestAutoStop() {
        if (coordinator.autoStopStopRequested) return
        coordinator.autoStopStopRequested = true
        triggerAutoStop()
    }

    /**
     * Trigger auto-stop and handle set completion.
     */
    private fun triggerAutoStop() {
        Logger.d("triggerAutoStop() called")
        coordinator.autoStopTriggered = true

        // Update UI state
        // UI completion state should reflect mode, not re-check warmup gate here.
        // Warmup gating happens before requestAutoStop/triggerAutoStop can be reached.
        if (coordinator._workoutParameters.value.isJustLift || coordinator._workoutParameters.value.isAMRAP || coordinator.isCurrentTimedCableExercise) {
            coordinator._autoStopState.value = coordinator._autoStopState.value.copy(
                progress = 1f,
                secondsRemaining = 0,
                isActive = true
            )
        } else {
            coordinator._autoStopState.value = AutoStopUiState()
        }

        // Handle set completion
        handleSetCompletion()
    }

    // ===== Round 2: Superset navigation =====

    /**
     * Get all exercises in the same superset as the current exercise.
     */
    private fun getCurrentSupersetExercises(): List<RoutineExercise> {
        val routine = coordinator._loadedRoutine.value ?: return emptyList()
        val currentExercise = getCurrentExercise() ?: return emptyList()
        val supersetId = currentExercise.supersetId ?: return emptyList()

        return routine.exercises
            .filter { it.supersetId == supersetId }
            .sortedBy { it.orderInSuperset }
    }

    /**
     * Check if the current exercise is part of a superset.
     */
    private fun isInSuperset(): Boolean {
        return getCurrentExercise()?.supersetId != null
    }

    /**
     * Get the next exercise index in the superset rotation.
     */
    private fun getNextSupersetExerciseIndex(): Int? {
        val routine = coordinator._loadedRoutine.value ?: return null
        val currentExercise = getCurrentExercise() ?: return null
        val supersetId = currentExercise.supersetId ?: return null

        val supersetExercises = getCurrentSupersetExercises()
        val currentPositionInSuperset = supersetExercises.indexOf(currentExercise)

        if (currentPositionInSuperset < supersetExercises.size - 1) {
            // More exercises in this superset cycle
            val nextSupersetExercise = supersetExercises[currentPositionInSuperset + 1]
            return routine.exercises.indexOf(nextSupersetExercise)
        }

        return null // End of superset cycle
    }

    /**
     * Get the first exercise in the current superset.
     */
    private fun getFirstSupersetExerciseIndex(): Int? {
        val routine = coordinator._loadedRoutine.value ?: return null
        val supersetExercises = getCurrentSupersetExercises()
        if (supersetExercises.isEmpty()) return null

        return routine.exercises.indexOf(supersetExercises.first())
    }

    /**
     * Check if we're at the end of a superset cycle.
     */
    private fun isAtEndOfSupersetCycle(): Boolean {
        val currentExercise = getCurrentExercise() ?: return false
        if (currentExercise.supersetId == null) return false

        val supersetExercises = getCurrentSupersetExercises()
        return currentExercise == supersetExercises.lastOrNull()
    }

    /**
     * Get the superset rest time.
     */
    private fun getSupersetRestSeconds(): Int {
        val routine = coordinator._loadedRoutine.value ?: return 10
        val supersetId = getCurrentExercise()?.supersetId ?: return 10
        return routine.supersets.find { it.id == supersetId }?.restBetweenSeconds ?: 10
    }

    /**
     * Find the next exercise after the current one (or after the current superset).
     */
    private fun findNextExerciseAfterCurrent(): Int? {
        val routine = coordinator._loadedRoutine.value ?: return null
        val currentExercise = getCurrentExercise() ?: return null
        val currentSupersetId = currentExercise.supersetId

        // If in a superset, find the first exercise after the superset
        if (currentSupersetId != null) {
            val supersetExerciseIndices = routine.exercises
                .mapIndexedNotNull { index, ex ->
                    if (ex.supersetId == currentSupersetId) index else null
                }
            val lastSupersetIndex = supersetExerciseIndices.maxOrNull() ?: coordinator._currentExerciseIndex.value
            val nextIndex = lastSupersetIndex + 1
            return if (nextIndex < routine.exercises.size) nextIndex else null
        }

        // Not in a superset - just go to next index
        val nextIndex = coordinator._currentExerciseIndex.value + 1
        return if (nextIndex < routine.exercises.size) nextIndex else null
    }

    /**
     * Determine the next step (Exercise Index, Set Index) in the workout sequence.
     */
    private fun getNextStep(routine: Routine, currentExIndex: Int, currentSetIndex: Int): Pair<Int, Int>? {
        val currentExercise = routine.exercises.getOrNull(currentExIndex) ?: return null

        // 1. Superset Logic - interleaved progression (A1 -> B1 -> A2 -> B2)
        if (currentExercise.supersetId != null) {
            val supersetExercises = routine.exercises
                .filter { it.supersetId == currentExercise.supersetId }
                .sortedBy { it.orderInSuperset }

            val currentSupersetPos = supersetExercises.indexOf(currentExercise)

            // A. Check for next exercise in the SAME set cycle
            for (i in (currentSupersetPos + 1) until supersetExercises.size) {
                val nextEx = supersetExercises[i]
                if (currentSetIndex < nextEx.setReps.size) {
                    val nextExIndex = routine.exercises.indexOf(nextEx)
                    return nextExIndex to currentSetIndex
                }
            }

            // B. Check for the NEXT set cycle - loop back to first exercise with next set
            val nextSetIndex = currentSetIndex + 1
            for (ex in supersetExercises) {
                if (nextSetIndex < ex.setReps.size) {
                    val nextExIndex = routine.exercises.indexOf(ex)
                    return nextExIndex to nextSetIndex
                }
            }

            // C. Superset Complete -> Move to next exercise after superset
            val maxIndex = supersetExercises.maxOf { routine.exercises.indexOf(it) }
            val nextExIndex = maxIndex + 1
            if (nextExIndex < routine.exercises.size) {
                return nextExIndex to 0
            }
            return null
        }

        // 2. Standard Linear Logic
        if (currentSetIndex < currentExercise.setReps.size - 1) {
            return currentExIndex to (currentSetIndex + 1)
        } else if (currentExIndex < routine.exercises.size - 1) {
            return (currentExIndex + 1) to 0
        }

        return null
    }

    /**
     * Determine the previous step (Exercise Index, Set Index) in the workout sequence.
     */
    private fun getPreviousStep(routine: Routine, currentExIndex: Int, currentSetIndex: Int): Pair<Int, Int>? {
        val currentExercise = routine.exercises.getOrNull(currentExIndex) ?: return null

        // 1. Superset Logic - interleaved progression
        if (currentExercise.supersetId != null) {
            val supersetExercises = routine.exercises
                .filter { it.supersetId == currentExercise.supersetId }
                .sortedBy { it.orderInSuperset }

            val currentSupersetPos = supersetExercises.indexOf(currentExercise)

            // A. Check for previous exercise in SAME set cycle
            for (i in (currentSupersetPos - 1) downTo 0) {
                val prevEx = supersetExercises[i]
                if (currentSetIndex < prevEx.setReps.size) {
                    val prevExIndex = routine.exercises.indexOf(prevEx)
                    return prevExIndex to currentSetIndex
                }
            }

            // B. Check for PREVIOUS set cycle - find last exercise that has prevSetIndex
            val prevSetIndex = currentSetIndex - 1
            if (prevSetIndex >= 0) {
                for (i in supersetExercises.indices.reversed()) {
                    val prevEx = supersetExercises[i]
                    if (prevSetIndex < prevEx.setReps.size) {
                        val prevExIndex = routine.exercises.indexOf(prevEx)
                        return prevExIndex to prevSetIndex
                    }
                }
            }

            // C. Start of Superset -> Go to previous exercise before superset
            val minIndex = supersetExercises.minOf { routine.exercises.indexOf(it) }
            val prevExIndex = minIndex - 1
            if (prevExIndex >= 0) {
                val prevEx = routine.exercises[prevExIndex]
                return prevExIndex to (prevEx.setReps.size - 1)
            }
            return null
        }

        // 2. Standard Linear Logic
        if (currentSetIndex > 0) {
            return currentExIndex to (currentSetIndex - 1)
        } else if (currentExIndex > 0) {
            val prevEx = routine.exercises[currentExIndex - 1]
            return (currentExIndex - 1) to (prevEx.setReps.size - 1)
        }

        return null
    }

    /**
     * Check if there is a next step in the routine from the given position.
     */
    fun hasNextStep(exerciseIndex: Int, setIndex: Int): Boolean {
        val routine = coordinator._loadedRoutine.value ?: return false
        return getNextStep(routine, exerciseIndex, setIndex) != null
    }

    /**
     * Check if there is a previous step in the routine from the given position.
     */
    fun hasPreviousStep(exerciseIndex: Int, setIndex: Int): Boolean {
        val routine = coordinator._loadedRoutine.value ?: return false
        return getPreviousStep(routine, exerciseIndex, setIndex) != null
    }

    /**
     * Calculate the name of the next exercise/set for display during rest.
     */
    private fun calculateNextExerciseName(
        isSingleExercise: Boolean,
        currentExercise: RoutineExercise?,
        routine: Routine?
    ): String {
        if (isSingleExercise || currentExercise == null) {
            return currentExercise?.exercise?.name ?: "Next Set"
        }

        if (routine == null) return "Next Set"

        // Use getNextStep for superset-aware navigation (fixes Issue #193)
        val nextStep = getNextStep(routine, coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
        if (nextStep == null) {
            return "Routine Complete"
        }

        val (nextExIndex, nextSetIndex) = nextStep
        val nextExercise = routine.exercises.getOrNull(nextExIndex)

        return if (nextExercise != null) {
            "${nextExercise.exercise.name} - Set ${nextSetIndex + 1}"
        } else {
            "Routine Complete"
        }
    }

    /**
     * Check if current exercise is the last one in the routine.
     */
    private fun calculateIsLastExercise(
        isSingleExercise: Boolean,
        currentExercise: RoutineExercise?,
        routine: Routine?
    ): Boolean {
        if (isSingleExercise) {
            // For single exercise, check if this is the last set
            return coordinator._currentSetIndex.value >= (currentExercise?.setReps?.size ?: 1) - 1
        }

        // Check if last exercise in routine
        val isLastExerciseInRoutine = coordinator._currentExerciseIndex.value >= (routine?.exercises?.size ?: 1) - 1
        val isLastSetInExercise = coordinator._currentSetIndex.value >= (currentExercise?.setReps?.size ?: 1) - 1

        return isLastExerciseInRoutine && isLastSetInExercise
    }

    // ===== Round 3: Routine CRUD and navigation =====

    fun saveRoutine(routine: Routine) {
        scope.launch { workoutRepository.saveRoutine(routine) }
    }

    fun updateRoutine(routine: Routine) {
        scope.launch { workoutRepository.updateRoutine(routine) }
    }

    fun deleteRoutine(routineId: String) {
        scope.launch { workoutRepository.deleteRoutine(routineId) }
    }

    /**
     * Batch delete multiple routines (for multi-select feature)
     */
    fun deleteRoutines(routineIds: Set<String>) {
        scope.launch {
            routineIds.forEach { id ->
                workoutRepository.deleteRoutine(id)
            }
        }
    }

    /**
     * Resolve PR percentage weights to absolute values for all exercises in a routine.
     */
    private suspend fun resolveRoutineWeights(routine: Routine): Routine {
        val resolvedExercises = routine.exercises.map { exercise ->
            if (exercise.usePercentOfPR) {
                val resolved = resolveWeightsUseCase(exercise, exercise.programMode)
                if (resolved.fallbackReason != null) {
                    Logger.w { "PR weight fallback for ${exercise.exercise.name}: ${resolved.fallbackReason}" }
                } else if (resolved.isFromPR) {
                    Logger.d { "Resolved ${exercise.exercise.name} weight from PR: ${resolved.percentOfPR}% of ${resolved.usedPR}kg = ${resolved.baseWeight}kg" }
                }
                exercise.copy(
                    weightPerCableKg = resolved.baseWeight,
                    setWeightsPerCableKg = resolved.setWeights
                )
            } else {
                exercise
            }
        }
        return routine.copy(exercises = resolvedExercises)
    }

    /**
     * Internal function to load a routine after weights have been resolved.
     */
    private fun loadRoutineInternal(routine: Routine) {
        coordinator._loadedRoutine.value = routine
        coordinator._currentExerciseIndex.value = 0
        coordinator._currentSetIndex.value = 0
        coordinator._skippedExercises.value = emptySet()
        coordinator._completedExercises.value = emptySet()

        // Issue #222 diagnostic: Reset bodyweight counter for new routine
        coordinator.bodyweightSetsCompletedInRoutine = 0
        // Issue #222 v8: Reset transition flag for new routine
        coordinator.previousExerciseWasBodyweight = false

        // Reset workout state to Idle when loading a routine
        // This fixes the bug where stale Resting state persists from a previous workout
        coordinator._workoutState.value = WorkoutState.Idle

        // Load parameters from first exercise (matching parent repo behavior)
        val firstExercise = routine.exercises[0]
        val firstSetReps = firstExercise.setReps.firstOrNull() // Can be null for AMRAP sets
        // Get per-set weight for first set, falling back to exercise default
        val firstSetWeight = firstExercise.setWeightsPerCableKg.getOrNull(0)
            ?: firstExercise.weightPerCableKg

        // Only bodyweight exercises should have warmupReps = 0
        val isFirstBodyweight = isBodyweightExercise(firstExercise)

        // Issue #188: Trace routine loading with println for reliable logcat output
        println("Issue188-Load: ╔══════════════════════════════════════════════════════════════")
        println("Issue188-Load: ║ LOADING ROUTINE: ${routine.name}")
        println("Issue188-Load: ╠══════════════════════════════════════════════════════════════")
        println("Issue188-Load: ║ First exercise: ${firstExercise.exercise.displayName}")
        println("Issue188-Load: ║ setReps list: ${firstExercise.setReps}")
        println("Issue188-Load: ║ firstSetReps (firstOrNull): $firstSetReps")
        println("Issue188-Load: ║ isAMRAP field on exercise: ${firstExercise.isAMRAP}")
        println("Issue188-Load: ║ progressionKg: ${firstExercise.progressionKg}kg")
        println("Issue188-Load: ║ weightPerCableKg: ${firstSetWeight}kg")
        println("Issue188-Load: ║ programMode: ${firstExercise.programMode.displayName}")
        println("Issue188-Load: ╚══════════════════════════════════════════════════════════════")

        // Issue #203: Fallback to exercise-level isAMRAP flag for legacy ExerciseEditDialog compatibility
        // Legacy "Last set AMRAP" only applies when we're on the last set (set index 0 for single-set exercises)
        val isFirstSetLastSet = firstExercise.setReps.size <= 1
        val firstIsAMRAP = firstSetReps == null || (firstExercise.isAMRAP && isFirstSetLastSet)

        val params = WorkoutParameters(
            programMode = firstExercise.programMode,
            echoLevel = firstExercise.echoLevel,
            eccentricLoad = firstExercise.eccentricLoad,
            reps = firstSetReps ?: 0, // AMRAP sets have null reps, use 0 as placeholder
            weightPerCableKg = firstSetWeight,
            progressionRegressionKg = firstExercise.progressionKg,
            isJustLift = false,  // CRITICAL: Routines are NOT just lift mode
            useAutoStart = false,
            stopAtTop = settingsManager.stopAtTop.value,
            warmupReps = if (isFirstBodyweight) 0 else Constants.DEFAULT_WARMUP_REPS,
            isAMRAP = firstIsAMRAP, // Issue #203: Check both per-set (null reps) and exercise-level flag
            selectedExerciseId = firstExercise.exercise.id,
            stallDetectionEnabled = firstExercise.stallDetectionEnabled
        )

        // Issue #188: Log computed params
        println("Issue188-Load: ║ COMPUTED WorkoutParameters:")
        println("Issue188-Load: ║   isAMRAP=${params.isAMRAP} (from firstSetReps == null || exercise.isAMRAP)")
        println("Issue188-Load: ║   reps=${params.reps}")
        println("Issue188-Load: ║   progressionRegressionKg=${params.progressionRegressionKg}kg")
        updateWorkoutParameters(params)
    }

    fun loadRoutine(routine: Routine) {
        if (routine.exercises.isEmpty()) {
            Logger.w { "Cannot load routine with no exercises" }
            return
        }

        // Launch coroutine to resolve PR percentage weights before loading
        scope.launch {
            val resolvedRoutine = resolveRoutineWeights(routine)
            loadRoutineInternal(resolvedRoutine)
        }
    }

    fun loadRoutineById(routineId: String) {
        val routine = coordinator._routines.value.find { it.id == routineId }
        if (routine != null) {
            clearCycleContext()  // Ensure non-cycle workouts don't update cycle progress
            loadRoutine(routine)
        }
    }

    /**
     * Enter routine overview mode.
     */
    fun enterRoutineOverview(routine: Routine) {
        scope.launch {
            val resolvedRoutine = resolveRoutineWeights(routine)
            coordinator._loadedRoutine.value = resolvedRoutine
            coordinator._currentExerciseIndex.value = 0
            coordinator._currentSetIndex.value = 0
            coordinator._skippedExercises.value = emptySet()
            coordinator._completedExercises.value = emptySet()
            coordinator._workoutState.value = WorkoutState.Idle
            coordinator._routineFlowState.value = RoutineFlowState.Overview(
                routine = resolvedRoutine,
                selectedExerciseIndex = 0
            )
        }
    }

    /**
     * Navigate to specific exercise in overview carousel.
     */
    fun selectExerciseInOverview(index: Int) {
        val state = coordinator._routineFlowState.value
        if (state is RoutineFlowState.Overview && index in state.routine.exercises.indices) {
            coordinator._routineFlowState.value = state.copy(selectedExerciseIndex = index)
        }
    }

    /**
     * Enter set-ready state for specific exercise and set.
     */
    fun enterSetReady(exerciseIndex: Int, setIndex: Int) {
        val routine = coordinator._loadedRoutine.value ?: return
        val exercise = routine.exercises.getOrNull(exerciseIndex) ?: return

        coordinator._currentExerciseIndex.value = exerciseIndex
        coordinator._currentSetIndex.value = setIndex

        // Get weight for this set
        val setWeight = exercise.setWeightsPerCableKg.getOrNull(setIndex)
            ?: exercise.weightPerCableKg
        // Issue #129: Check raw value for AMRAP before fallback
        val rawSetReps = exercise.setReps.getOrNull(setIndex)
        val setReps = rawSetReps ?: exercise.reps

        coordinator._routineFlowState.value = RoutineFlowState.SetReady(
            exerciseIndex = exerciseIndex,
            setIndex = setIndex,
            adjustedWeight = setWeight,
            adjustedReps = setReps,
            echoLevel = if (exercise.programMode is ProgramMode.Echo) exercise.echoLevel else null,
            eccentricLoadPercent = if (exercise.programMode is ProgramMode.Echo) exercise.eccentricLoad.percentage else null
        )

        // Issue #129: Determine if this specific set is AMRAP (null reps = AMRAP)
        val isSetAmrap = rawSetReps == null
        Logger.d { "enterSetReady: exercise=${exercise.exercise.name}, set=$setIndex, isAMRAP=$isSetAmrap, stallDetection=${exercise.stallDetectionEnabled}" }

        // Update workout parameters for this set
        // Issue #209: Explicitly set isJustLift=false and useAutoStart=false
        // These may have been set to true from a previous Just Lift session,
        // and .copy() preserves existing values. Routines must not inherit Just Lift behavior.
        coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
            programMode = exercise.programMode,
            weightPerCableKg = setWeight,
            reps = setReps,
            echoLevel = exercise.echoLevel,
            eccentricLoad = exercise.eccentricLoad,
            selectedExerciseId = exercise.exercise.id,
            stallDetectionEnabled = exercise.stallDetectionEnabled,
            isAMRAP = isSetAmrap,  // Issue #129: Set per-set AMRAP flag
            progressionRegressionKg = exercise.progressionKg,  // Issue #188: Include progression from exercise
            isJustLift = false,  // Issue #209: Routines are NOT just lift mode
            useAutoStart = false  // Issue #209: Routines don't use auto-start
        )
    }

    /**
     * Enter SetReady state with pre-adjusted weight and reps from the overview screen.
     */
    fun enterSetReadyWithAdjustments(exerciseIndex: Int, setIndex: Int, adjustedWeight: Float, adjustedReps: Int) {
        val routine = coordinator._loadedRoutine.value ?: return
        val exercise = routine.exercises.getOrNull(exerciseIndex) ?: return

        coordinator._currentExerciseIndex.value = exerciseIndex
        coordinator._currentSetIndex.value = setIndex

        coordinator._routineFlowState.value = RoutineFlowState.SetReady(
            exerciseIndex = exerciseIndex,
            setIndex = setIndex,
            adjustedWeight = adjustedWeight,
            adjustedReps = adjustedReps,
            echoLevel = if (exercise.programMode is ProgramMode.Echo) exercise.echoLevel else null,
            eccentricLoadPercent = if (exercise.programMode is ProgramMode.Echo) exercise.eccentricLoad.percentage else null
        )

        // Issue #129: Check raw value for AMRAP - null reps in setReps list = AMRAP
        val rawSetReps = exercise.setReps.getOrNull(setIndex)
        val isSetAmrap = rawSetReps == null
        Logger.d { "enterSetReadyWithAdjustments: exercise=${exercise.exercise.name}, set=$setIndex, isAMRAP=$isSetAmrap, stallDetection=${exercise.stallDetectionEnabled}" }

        // Update workout parameters with adjusted values
        // Issue #209: Explicitly set isJustLift=false and useAutoStart=false
        // These may have been set to true from a previous Just Lift session,
        // and .copy() preserves existing values. Routines must not inherit Just Lift behavior.
        coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
            programMode = exercise.programMode,
            weightPerCableKg = adjustedWeight,
            reps = adjustedReps,
            echoLevel = exercise.echoLevel,
            eccentricLoad = exercise.eccentricLoad,
            selectedExerciseId = exercise.exercise.id,
            stallDetectionEnabled = exercise.stallDetectionEnabled,
            isAMRAP = isSetAmrap,  // Issue #129: Set per-set AMRAP flag
            progressionRegressionKg = exercise.progressionKg,  // Issue #188: Include progression from exercise
            isJustLift = false,  // Issue #209: Routines are NOT just lift mode
            useAutoStart = false  // Issue #209: Routines don't use auto-start
        )
    }

    /**
     * Update weight in set-ready state.
     */
    fun updateSetReadyWeight(weight: Float) {
        val state = coordinator._routineFlowState.value
        if (state is RoutineFlowState.SetReady && weight >= 0f) {
            coordinator._routineFlowState.value = state.copy(adjustedWeight = weight)
            coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(weightPerCableKg = weight)
        }
    }

    /**
     * Update reps in set-ready state.
     */
    fun updateSetReadyReps(reps: Int) {
        val state = coordinator._routineFlowState.value
        if (state is RoutineFlowState.SetReady && reps >= 1) {
            coordinator._routineFlowState.value = state.copy(adjustedReps = reps)
            coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(reps = reps)
        }
    }

    /**
     * Update echo level in set-ready state for Echo mode.
     */
    fun updateSetReadyEchoLevel(level: EchoLevel) {
        val state = coordinator._routineFlowState.value
        if (state is RoutineFlowState.SetReady) {
            coordinator._routineFlowState.value = state.copy(echoLevel = level)
            coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(echoLevel = level)
        }
    }

    /**
     * Update eccentric load percentage in set-ready state for Echo mode.
     */
    fun updateSetReadyEccentricLoad(percent: Int) {
        // Defensive clamping: Machine hardware limit is 150% eccentric load
        val safePercent = percent.coerceIn(0, 150)
        val state = coordinator._routineFlowState.value
        if (state is RoutineFlowState.SetReady) {
            coordinator._routineFlowState.value = state.copy(eccentricLoadPercent = safePercent)
            val load = EccentricLoad.entries.minByOrNull { kotlin.math.abs(it.percentage - safePercent) }
                ?: EccentricLoad.LOAD_100
            coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(eccentricLoad = load)
        }
    }

    /**
     * Start the set from set-ready state.
     */
    fun startSetFromReady() {
        val state = coordinator._routineFlowState.value
        if (state !is RoutineFlowState.SetReady) return

        // Issue #XXX: Full reset before starting to ensure no stale state
        // This is critical when user presses back during workout and returns to SetReady
        repCounter.reset()
        coordinator._repCount.value = RepCount()
        coordinator._repRanges.value = null
        resetAutoStopState()

        // Apply the adjusted values to workout parameters
        // Issue #209: Explicitly set isJustLift=false as a safety net
        // (enterSetReady/enterSetReadyWithAdjustments should have already set this,
        // but this ensures it's reset even if called from an unexpected path)
        coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
            weightPerCableKg = state.adjustedWeight,
            reps = state.adjustedReps,
            isJustLift = false  // Issue #209: Routines are NOT just lift mode
        )

        // Start the workout directly (skip countdown since user already configured on SetReady)
        startWorkout(skipCountdown = true)
    }

    /**
     * Return to routine overview from set-ready.
     */
    fun returnToOverview() {
        val routine = coordinator._loadedRoutine.value ?: return
        coordinator._routineFlowState.value = RoutineFlowState.Overview(
            routine = routine,
            selectedExerciseIndex = coordinator._currentExerciseIndex.value
        )
    }

    /**
     * Exit routine flow and return to routines list.
     */
    fun exitRoutineFlow() {
        coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
        coordinator._loadedRoutine.value = null
        coordinator._workoutState.value = WorkoutState.Idle
        coordinator.routineStartTime = 0  // Issue #195: Reset for next routine
    }

    /**
     * Show routine complete screen.
     */
    fun showRoutineComplete() {
        val routine = coordinator._loadedRoutine.value ?: return
        // Issue #195: Use coordinator.routineStartTime (set on first set) for total duration,
        // not coordinator.workoutStartTime (reset each set)
        val duration = if (coordinator.routineStartTime > 0) {
            currentTimeMillis() - coordinator.routineStartTime
        } else {
            0L
        }
        coordinator._routineFlowState.value = RoutineFlowState.Complete(
            routineName = routine.name,
            totalSets = routine.exercises.sumOf { it.setReps.size },
            totalExercises = routine.exercises.size,
            totalDurationMs = duration
        )
    }

    fun clearLoadedRoutine() {
        coordinator._loadedRoutine.value = null
        clearCycleContext()
        coordinator.routineStartTime = 0  // Issue #195: Reset for next routine
    }

    fun getCurrentExercise(): RoutineExercise? {
        val routine = coordinator._loadedRoutine.value ?: return null
        return routine.exercises.getOrNull(coordinator._currentExerciseIndex.value)
    }

    /**
     * Check if there's resumable progress for a specific routine.
     */
    fun hasResumableProgress(routineId: String): Boolean {
        val loaded = coordinator._loadedRoutine.value ?: return false
        if (loaded.id != routineId) return false
        // Check if we have any progress (beyond the initial state)
        if (coordinator._currentSetIndex.value > 0 || coordinator._currentExerciseIndex.value > 0) {
            // Validate that indices are still valid for the routine
            val exercise = loaded.exercises.getOrNull(coordinator._currentExerciseIndex.value) ?: return false
            return coordinator._currentSetIndex.value < exercise.setReps.size
        }
        return false
    }

    /**
     * Get information about resumable progress for display in dialog.
     */
    fun getResumableProgressInfo(): ResumableProgressInfo? {
        val routine = coordinator._loadedRoutine.value ?: return null
        val exercise = routine.exercises.getOrNull(coordinator._currentExerciseIndex.value) ?: return null
        return ResumableProgressInfo(
            exerciseName = exercise.exercise.displayName,
            currentSet = coordinator._currentSetIndex.value + 1,  // 1-based for display
            totalSets = exercise.setReps.size,
            currentExercise = coordinator._currentExerciseIndex.value + 1,  // 1-based for display
            totalExercises = routine.exercises.size
        )
    }

    /**
     * Internal helper to perform the actual exercise navigation.
     */
    private fun navigateToExerciseInternal(routine: Routine, index: Int) {
        // Navigate to new exercise
        coordinator._currentExerciseIndex.value = index
        coordinator._currentSetIndex.value = 0

        // Load new exercise parameters
        val exercise = routine.exercises[index]
        val setReps = exercise.setReps.getOrNull(0)
        val setWeight = exercise.setWeightsPerCableKg.getOrNull(0) ?: exercise.weightPerCableKg

        coordinator._workoutParameters.update { params ->
            params.copy(
                programMode = exercise.programMode,
                echoLevel = exercise.echoLevel,
                eccentricLoad = exercise.eccentricLoad,
                reps = setReps ?: exercise.reps,
                weightPerCableKg = setWeight,
                progressionRegressionKg = exercise.progressionKg,
                warmupReps = 3,  // Routines use default 3 warmup reps (machine expects this)
                selectedExerciseId = exercise.exercise.id
            )
        }

        // Reset workout state
        coordinator._workoutState.value = WorkoutState.Idle
        coordinator._repCount.value = RepCount()
        repCounter.reset()

        Logger.i("DefaultWorkoutSessionManager") { "Jumped to exercise $index: ${exercise.exercise.name}" }
    }

    fun advanceToNextExercise() {
        val routine = coordinator._loadedRoutine.value ?: return
        val nextIndex = coordinator._currentExerciseIndex.value + 1
        if (nextIndex < routine.exercises.size) {
            jumpToExercise(nextIndex)
        }
    }

    /**
     * Navigate to a specific exercise in the routine.
     */
    fun jumpToExercise(index: Int) {
        val routine = coordinator._loadedRoutine.value ?: return
        if (index < 0 || index >= routine.exercises.size) return

        // Issue #125: Block exercise navigation during Active state - machine must be stopped first
        // This matches official app behavior and prevents BLE command collisions that crash the machine
        if (coordinator._workoutState.value is WorkoutState.Active) {
            Logger.w("DefaultWorkoutSessionManager") { "Cannot jump to exercise $index while workout is Active - stop workout first" }
            // Issue #172: Provide user feedback when navigation is blocked
            scope.launch {
                coordinator._userFeedbackEvents.emit("Stop the current set first")
            }
            return
        }

        // Save current exercise progress if we have any reps
        val currentRepCount = coordinator._repCount.value
        if (currentRepCount.workingReps > 0 && coordinator._workoutState.value !is WorkoutState.Completed) {
            // Mark as completed if we did some work
            coordinator._completedExercises.update { it + coordinator._currentExerciseIndex.value }
            Logger.d("DefaultWorkoutSessionManager") { "Saving progress for exercise ${coordinator._currentExerciseIndex.value}: ${currentRepCount.workingReps} reps" }
        } else if (coordinator._workoutState.value !is WorkoutState.Completed) {
            // Mark as skipped if no reps done
            coordinator._skippedExercises.update { it + coordinator._currentExerciseIndex.value }
            Logger.d("DefaultWorkoutSessionManager") { "Skipping exercise ${coordinator._currentExerciseIndex.value}" }
        }

        // Cancel any active timers
        coordinator.restTimerJob?.cancel()
        coordinator.bodyweightTimerJob?.cancel()
        // Issue #192: Clear timed exercise countdown display
        coordinator._timedExerciseRemainingSeconds.value = null
        resetAutoStopState()

        // Issue #172: Async navigation with proper BLE cleanup to ensure machine is in BASELINE mode
        // This matches official app behavior which requires explicit stop before mode transitions
        scope.launch {
            try {
                // Issue #205: First clear any fault state with official StopPacket (0x50)
                // This command clears blinking orange/red lights from mode transition faults
                // The StopPacket is a "soft stop" that releases tension and clears fault states
                bleRepository.sendStopCommand()
                delay(100)  // Allow fault clear to process

                // Then full reset with RESET command (0x0A) which also stops polling
                // This ensures machine is in BASELINE mode before mode transition
                bleRepository.stopWorkout()
                delay(150)  // Settling time for machine to process

                Logger.d("DefaultWorkoutSessionManager") { "BLE stop sequence sent before navigation to exercise $index" }
            } catch (e: Exception) {
                Logger.w(e) { "Stop command before navigation failed (non-fatal): ${e.message}" }
                // Continue anyway - the stop may have succeeded partially
            }

            navigateToExerciseInternal(routine, index)
            // Auto-start the next exercise with countdown (Issue #93 fix)
            startWorkout(skipCountdown = false)
        }
    }

    /**
     * Skip the current exercise and move to the next one.
     */
    fun skipCurrentExercise() {
        val routine = coordinator._loadedRoutine.value ?: return
        val nextIndex = coordinator._currentExerciseIndex.value + 1
        if (nextIndex < routine.exercises.size) {
            // Mark current as skipped (even if we had some progress)
            coordinator._skippedExercises.update { it + coordinator._currentExerciseIndex.value }
            jumpToExercise(nextIndex)
        }
    }

    /**
     * Go back to the previous exercise in the routine.
     */
    fun goToPreviousExercise() {
        val prevIndex = coordinator._currentExerciseIndex.value - 1
        if (prevIndex >= 0) {
            jumpToExercise(prevIndex)
        }
    }

    /**
     * Check if current exercise can go back (not first in routine).
     */
    fun canGoBack(): Boolean {
        return coordinator._loadedRoutine.value != null && coordinator._currentExerciseIndex.value > 0
    }

    /**
     * Check if current exercise can skip forward (not last in routine).
     */
    fun canSkipForward(): Boolean {
        val routine = coordinator._loadedRoutine.value ?: return false
        return coordinator._currentExerciseIndex.value < routine.exercises.size - 1
    }

    /**
     * Get list of exercise names in current routine (for navigation display).
     */
    fun getRoutineExerciseNames(): List<String> {
        return coordinator._loadedRoutine.value?.exercises?.map { it.exercise.name } ?: emptyList()
    }

    /**
     * Log RPE (Rate of Perceived Exertion) for the current set.
     */
    fun logRpeForCurrentSet(rpe: Int) {
        coordinator._currentSetRpe.value = rpe
        Logger.d("DefaultWorkoutSessionManager") { "RPE logged for current set: $rpe" }
    }

    /**
     * Navigate to previous set/exercise in set-ready.
     */
    fun setReadyPrev() {
        val state = coordinator._routineFlowState.value
        if (state !is RoutineFlowState.SetReady) return
        val routine = coordinator._loadedRoutine.value ?: return

        getPreviousStep(routine, state.exerciseIndex, state.setIndex)?.let { (exIdx, setIdx) ->
            enterSetReady(exIdx, setIdx)
        }
    }

    /**
     * Skip to next set/exercise in set-ready.
     */
    fun setReadySkip() {
        val state = coordinator._routineFlowState.value
        if (state !is RoutineFlowState.SetReady) return
        val routine = coordinator._loadedRoutine.value ?: return

        getNextStep(routine, state.exerciseIndex, state.setIndex)?.let { (exIdx, setIdx) ->
            enterSetReady(exIdx, setIdx)
        }
    }

    // ===== Round 4: Superset CRUD =====

    /**
     * Create a new superset in a routine.
     */
    suspend fun createSuperset(
        routineId: String,
        name: String? = null,
        exercises: List<RoutineExercise> = emptyList()
    ): Superset {
        val routine = getRoutineById(routineId) ?: throw IllegalArgumentException("Routine not found")
        val existingColors = routine.supersets.map { it.colorIndex }.toSet()
        val colorIndex = SupersetColors.next(existingColors)
        val supersetCount = routine.supersets.size
        val autoName = name ?: "Superset ${'A' + supersetCount}"
        val orderIndex = routine.getItems().maxOfOrNull { it.orderIndex }?.plus(1) ?: 0

        val superset = Superset(
            id = generateSupersetId(),
            routineId = routineId,
            name = autoName,
            colorIndex = colorIndex,
            restBetweenSeconds = 10,
            orderIndex = orderIndex
        )

        // Save routine with new superset
        val updatedSupersets = routine.supersets + superset
        val updatedExercises = exercises.mapIndexed { index, exercise ->
            exercise.copy(supersetId = superset.id, orderInSuperset = index)
        } + routine.exercises.filter { it.id !in exercises.map { e -> e.id } }

        val updatedRoutine = routine.copy(supersets = updatedSupersets, exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)

        return superset
    }

    /**
     * Update superset properties (name, rest time, color).
     */
    suspend fun updateSuperset(routineId: String, superset: Superset) {
        val routine = getRoutineById(routineId) ?: return
        val updatedSupersets = routine.supersets.map {
            if (it.id == superset.id) superset else it
        }
        val updatedRoutine = routine.copy(supersets = updatedSupersets)
        workoutRepository.updateRoutine(updatedRoutine)
    }

    /**
     * Delete a superset. Exercises become standalone.
     */
    suspend fun deleteSuperset(routineId: String, supersetId: String) {
        val routine = getRoutineById(routineId) ?: return
        val updatedSupersets = routine.supersets.filter { it.id != supersetId }
        // Clear superset reference from exercises
        val updatedExercises = routine.exercises.map { exercise ->
            if (exercise.supersetId == supersetId) {
                exercise.copy(supersetId = null, orderInSuperset = 0)
            } else {
                exercise
            }
        }
        val updatedRoutine = routine.copy(supersets = updatedSupersets, exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)
    }

    /**
     * Move an exercise into a superset.
     */
    suspend fun addExerciseToSuperset(routineId: String, exerciseId: String, supersetId: String) {
        val routine = getRoutineById(routineId) ?: return
        val superset = routine.supersets.find { it.id == supersetId } ?: return
        val currentExercisesInSuperset = routine.exercises.filter { it.supersetId == supersetId }
        val newOrderInSuperset = currentExercisesInSuperset.maxOfOrNull { it.orderInSuperset }?.plus(1) ?: 0

        val updatedExercises = routine.exercises.map { exercise ->
            if (exercise.id == exerciseId) {
                exercise.copy(supersetId = supersetId, orderInSuperset = newOrderInSuperset)
            } else {
                exercise
            }
        }
        val updatedRoutine = routine.copy(exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)
    }

    /**
     * Remove an exercise from a superset (becomes standalone).
     */
    suspend fun removeExerciseFromSuperset(routineId: String, exerciseId: String) {
        val routine = getRoutineById(routineId) ?: return
        val updatedExercises = routine.exercises.map { exercise ->
            if (exercise.id == exerciseId) {
                exercise.copy(supersetId = null, orderInSuperset = 0)
            } else {
                exercise
            }
        }
        val updatedRoutine = routine.copy(exercises = updatedExercises)
        workoutRepository.updateRoutine(updatedRoutine)
    }

    // ===== Round 5: Weight adjustment =====

    /**
     * Send weight update command to the machine.
     * This resends the workout command with updated weight.
     */
    private suspend fun sendWeightUpdateToMachine(weightKg: Float) {
        try {
            val params = coordinator._workoutParameters.value

            // Create and send updated workout command
            val command = if (!params.isEchoMode) {
                BlePacketFactory.createWorkoutCommand(
                    params.programMode,
                    weightKg,
                    params.reps
                )
            } else {
                // For Echo mode, weight is dynamic based on position - no update needed
                return
            }

            bleRepository.sendWorkoutCommand(command)
            Logger.d("Weight update sent to machine: $weightKg kg")
        } catch (e: Exception) {
            Logger.e(e) { "Failed to send weight update: ${e.message}" }
        }
    }

    /**
     * Adjust the weight during an active workout or rest period.
     * This updates the workout parameters and optionally sends the updated weight to the machine.
     *
     * @param newWeightKg The new weight per cable in kg
     * @param sendToMachine Whether to send the command to the machine immediately
     */
    fun adjustWeight(newWeightKg: Float, sendToMachine: Boolean = true) {
        val clampedWeight = newWeightKg.coerceIn(0f, 110f) // Max 110kg per cable (220kg total)

        Logger.d("DefaultWorkoutSessionManager: Adjusting weight to $clampedWeight kg (sendToMachine=$sendToMachine)")

        // Issue #108/#180: Track if user adjusts weight during Idle, Resting, or SetSummary
        val currentState = coordinator._workoutState.value
        if (currentState is WorkoutState.Idle ||
            currentState is WorkoutState.Resting ||
            currentState is WorkoutState.SetSummary) {
            coordinator._userAdjustedWeightDuringRest = true
            Logger.d("DefaultWorkoutSessionManager: User adjusted weight in ${currentState::class.simpleName} - will preserve on next set")
        }

        // Update workout parameters
        coordinator._workoutParameters.update { params ->
            params.copy(weightPerCableKg = clampedWeight)
        }

        // If workout is active, send updated weight to machine
        if (sendToMachine && coordinator._workoutState.value is WorkoutState.Active) {
            scope.launch {
                sendWeightUpdateToMachine(clampedWeight)
            }
        }
    }

    /**
     * Increment weight by a specific amount.
     */
    fun incrementWeight(amount: Float = 0.5f) {
        val currentWeight = coordinator._workoutParameters.value.weightPerCableKg
        adjustWeight(currentWeight + amount)
    }

    /**
     * Decrement weight by a specific amount.
     */
    fun decrementWeight(amount: Float = 0.5f) {
        val currentWeight = coordinator._workoutParameters.value.weightPerCableKg
        adjustWeight(currentWeight - amount)
    }

    /**
     * Set weight to a specific preset value.
     */
    fun setWeightPreset(presetWeightKg: Float) {
        adjustWeight(presetWeightKg)
    }

    /**
     * Get the last used weight for a specific exercise.
     */
    suspend fun getLastWeightForExercise(exerciseId: String): Float? {
        return workoutRepository.getAllSessions()
            .first()
            .filter { it.exerciseId == exerciseId }
            .sortedByDescending { it.timestamp }
            .firstOrNull()
            ?.weightPerCableKg
    }

    /**
     * Get the PR weight for a specific exercise.
     */
    suspend fun getPrWeightForExercise(exerciseId: String): Float? {
        return workoutRepository.getAllPersonalRecords()
            .first()
            .filter { it.exerciseId == exerciseId }
            .maxOfOrNull { it.weightPerCableKg }
    }

    // ===== Round 6: Just Lift =====

    /**
     * Enable handle detection for auto-start functionality.
     * When connected, the machine monitors handle grip to auto-start workout.
     *
     * Made idempotent to prevent iOS race condition where multiple LaunchedEffects
     * could call this and reset the state machine mid-grab.
     */
    fun enableHandleDetection() {
        val now = currentTimeMillis()
        if (now - coordinator.handleDetectionEnabledTimestamp < coordinator.HANDLE_DETECTION_DEBOUNCE_MS) {
            Logger.d("DefaultWorkoutSessionManager: Handle detection already enabled recently, skipping (idempotent)")
            return
        }
        coordinator.handleDetectionEnabledTimestamp = now
        Logger.d("DefaultWorkoutSessionManager: Enabling handle detection for auto-start")
        bleRepository.enableHandleDetection(true)
    }

    /**
     * Disable handle detection.
     * Called when leaving Just Lift mode or when handle detection is no longer needed.
     */
    fun disableHandleDetection() {
        Logger.d("DefaultWorkoutSessionManager: Disabling handle detection")
        bleRepository.enableHandleDetection(false)
    }

    /**
     * Prepare for Just Lift mode by resetting workout state while preserving weight.
     * Called when entering Just Lift screen with non-Idle state.
     * Matches parent repo behavior: resets state if needed, sets parameters, enables handle detection.
     */
    fun prepareForJustLift() {
        scope.launch {
            val currentState = coordinator._workoutState.value
            val currentWeight = coordinator._workoutParameters.value.weightPerCableKg
            Logger.d("prepareForJustLift: BEFORE - weight=$currentWeight kg")

            if (currentState !is WorkoutState.Idle) {
                Logger.d("Preparing for Just Lift: Resetting from ${currentState::class.simpleName} to Idle")
                resetForNewWorkout()
                coordinator._workoutState.value = WorkoutState.Idle
            } else {
                Logger.d("Just Lift already in Idle state, ensuring auto-start is enabled")
            }

            // Set parameters first before enabling handle detection
            coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
                isJustLift = true,
                useAutoStart = true,
                selectedExerciseId = null  // Clear exercise selection for Just Lift
            )

            // Enable handle detection - auto-start triggers when user grabs handles
            enableHandleDetection()
            val newWeight = coordinator._workoutParameters.value.weightPerCableKg
            Logger.d("prepareForJustLift: AFTER - weight=$newWeight kg")
            Logger.d("Just Lift ready: State=Idle, AutoStart=enabled, waiting for handle grab")
        }
    }

    /**
     * Get saved Just Lift defaults.
     * Returns default object if none saved. Converts from preferences format to viewmodel format.
     */
    suspend fun getJustLiftDefaults(): JustLiftDefaults {
        val prefsDefaults = preferencesManager.getJustLiftDefaults()
        // Convert from preferences format (Float weightChangePerRep) to viewmodel format (Int)
        return JustLiftDefaults(
            weightPerCableKg = prefsDefaults.weightPerCableKg,
            weightChangePerRep = kotlin.math.round(prefsDefaults.weightChangePerRep).toInt(),
            workoutModeId = prefsDefaults.workoutModeId,
            eccentricLoadPercentage = prefsDefaults.eccentricLoadPercentage,
            echoLevelValue = prefsDefaults.echoLevelValue,
            stallDetectionEnabled = prefsDefaults.stallDetectionEnabled
        )
    }

    /**
     * Save Just Lift defaults for next session.
     * Converts from viewmodel format to preferences format.
     */
    fun saveJustLiftDefaults(defaults: JustLiftDefaults) {
        scope.launch {
            // Convert from viewmodel format (Int weightChangePerRep) to preferences format (Float)
            val prefsDefaults = com.devil.phoenixproject.data.preferences.JustLiftDefaults(
                weightPerCableKg = defaults.weightPerCableKg,
                weightChangePerRep = defaults.weightChangePerRep.toFloat(),
                workoutModeId = defaults.workoutModeId,
                eccentricLoadPercentage = defaults.eccentricLoadPercentage,
                echoLevelValue = defaults.echoLevelValue,
                stallDetectionEnabled = defaults.stallDetectionEnabled
            )
            preferencesManager.saveJustLiftDefaults(prefsDefaults)
            Logger.d("saveJustLiftDefaults: weight=${defaults.weightPerCableKg}kg, mode=${defaults.workoutModeId}")
        }
    }

    /**
     * Save Just Lift defaults after completing a Just Lift workout.
     * Called from saveWorkoutSession when isJustLift is true.
     */
    private suspend fun saveJustLiftDefaultsFromWorkout() {
        val params = coordinator._workoutParameters.value
        if (!params.isJustLift) return

        val eccentricLoadPct = if (params.isEchoMode) params.eccentricLoad.percentage else 100
        val echoLevelVal = if (params.isEchoMode) params.echoLevel.levelValue else 2

        try {
            val defaults = com.devil.phoenixproject.data.preferences.JustLiftDefaults(
                workoutModeId = params.programMode.modeValue,
                weightPerCableKg = params.weightPerCableKg.coerceAtLeast(0.1f),
                weightChangePerRep = params.progressionRegressionKg,
                eccentricLoadPercentage = eccentricLoadPct,
                echoLevelValue = echoLevelVal,
                stallDetectionEnabled = params.stallDetectionEnabled
            )
            preferencesManager.saveJustLiftDefaults(defaults)
            Logger.d { "Saved Just Lift defaults: mode=${params.programMode.modeValue}, weight=${params.weightPerCableKg}kg" }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to save Just Lift defaults: ${e.message}" }
        }
    }

    /**
     * Get saved Single Exercise defaults for a specific exercise.
     * Returns null if no defaults have been saved yet.
     */
    suspend fun getSingleExerciseDefaults(exerciseId: String): com.devil.phoenixproject.data.preferences.SingleExerciseDefaults? {
        return preferencesManager.getSingleExerciseDefaults(exerciseId)
    }

    /**
     * Save Single Exercise defaults for a specific exercise.
     */
    fun saveSingleExerciseDefaults(defaults: com.devil.phoenixproject.data.preferences.SingleExerciseDefaults) {
        scope.launch {
            preferencesManager.saveSingleExerciseDefaults(defaults)
            Logger.d("saveSingleExerciseDefaults: exerciseId=${defaults.exerciseId}")
        }
    }

    /**
     * Save Single Exercise defaults after completing a single exercise workout
     * Called from saveWorkoutSession when in Single Exercise mode (temp routine)
     */
    private suspend fun saveSingleExerciseDefaultsFromWorkout() {
        val routine = coordinator._loadedRoutine.value ?: return

        // Only save for temp single exercise routines, not for regular routines
        if (!routine.id.startsWith(TEMP_SINGLE_EXERCISE_PREFIX)) return

        val currentExercise = routine.exercises.getOrNull(coordinator._currentExerciseIndex.value) ?: return
        val exerciseId = currentExercise.exercise.id ?: return

        val isEchoExercise = currentExercise.programMode == ProgramMode.Echo
        val eccentricLoadPct = if (isEchoExercise) currentExercise.eccentricLoad.percentage else 100
        val echoLevelVal = if (isEchoExercise) currentExercise.echoLevel.levelValue else 1

        try {
            val setReps = currentExercise.setReps.ifEmpty { listOf(10) }
            val numSets = setReps.size

            // Normalize setWeightsPerCableKg to match setReps size (or be empty)
            val normalizedSetWeights = when {
                currentExercise.setWeightsPerCableKg.isEmpty() -> emptyList()
                currentExercise.setWeightsPerCableKg.size == numSets -> currentExercise.setWeightsPerCableKg
                else -> emptyList() // Reset if invalid size
            }

            // Normalize setRestSeconds to match setReps size (or be empty)
            val normalizedSetRest = when {
                currentExercise.setRestSeconds.isEmpty() -> emptyList()
                currentExercise.setRestSeconds.size == numSets -> currentExercise.setRestSeconds
                else -> emptyList() // Reset if invalid size
            }

            val defaults = com.devil.phoenixproject.data.preferences.SingleExerciseDefaults(
                exerciseId = exerciseId,
                setReps = setReps,
                weightPerCableKg = currentExercise.weightPerCableKg.coerceAtLeast(0f),
                setWeightsPerCableKg = normalizedSetWeights,
                progressionKg = currentExercise.progressionKg.coerceIn(-50f, 50f),
                setRestSeconds = normalizedSetRest,
                workoutModeId = currentExercise.programMode.modeValue,
                eccentricLoadPercentage = eccentricLoadPct,
                echoLevelValue = echoLevelVal,
                duration = currentExercise.duration?.takeIf { it > 0 } ?: 0,
                isAMRAP = currentExercise.isAMRAP,
                perSetRestTime = currentExercise.perSetRestTime
            )
            preferencesManager.saveSingleExerciseDefaults(defaults)
            Logger.d { "Saved Single Exercise defaults for ${currentExercise.exercise.name}" }
        } catch (e: IllegalArgumentException) {
            Logger.e(e) { "Failed to save Single Exercise defaults - validation error" }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to save Single Exercise defaults: ${e.message}" }
        }
    }

    // ===== Round 7: Training cycles =====

    /**
     * Load a routine from a training cycle context.
     * This tracks the cycle and day so we can mark the day as completed when the workout finishes.
     */
    fun loadRoutineFromCycle(routineId: String, cycleId: String, dayNumber: Int) {
        val routine = coordinator._routines.value.find { it.id == routineId }
        if (routine != null) {
            coordinator.activeCycleId = cycleId
            coordinator.activeCycleDayNumber = dayNumber
            Logger.d { "Loading routine from cycle: cycleId=$cycleId, dayNumber=$dayNumber" }
            loadRoutine(routine)
        }
    }

    /**
     * Clear the active cycle context (e.g., when starting a non-cycle workout).
     */
    fun clearCycleContext() {
        coordinator.activeCycleId = null
        coordinator.activeCycleDayNumber = null
    }

    /**
     * Update cycle progress when a workout is completed from a training cycle.
     * Marks the day as completed and advances to the next day.
     * If the user completes a day ahead of the current day, marks skipped days as missed.
     */
    private suspend fun updateCycleProgressIfNeeded() {
        val cycleId = coordinator.activeCycleId ?: return
        val dayNumber = coordinator.activeCycleDayNumber ?: return

        // Clear cycle context immediately to prevent race conditions
        coordinator.activeCycleId = null
        coordinator.activeCycleDayNumber = null

        try {
            val cycle = trainingCycleRepository.getCycleById(cycleId)
            val progress = trainingCycleRepository.getCycleProgress(cycleId)

            if (cycle != null && progress != null) {
                // Use the CycleProgress model method which handles:
                // - Adding the day to completedDays set
                // - Marking any skipped days as missed (in missedDays set)
                // - Advancing to the next day
                // - Handling rotation (reset sets when cycling back to Day 1)
                val updated = progress.markDayCompleted(dayNumber, cycle.days.size)
                trainingCycleRepository.updateCycleProgress(updated)

                // Emit completion event for UI feedback
                val completedDay = cycle.days.find { it.dayNumber == dayNumber }
                val isRotationComplete = updated.rotationCount > progress.rotationCount
                coordinator._cycleDayCompletionEvent.value = CycleDayCompletionEvent(
                    dayNumber = dayNumber,
                    dayName = completedDay?.name,
                    isRotationComplete = isRotationComplete,
                    rotationCount = updated.rotationCount
                )

                Logger.d { "Cycle progress updated: day $dayNumber completed, now on day ${updated.currentDayNumber}" +
                    if (isRotationComplete) " (rotation ${updated.rotationCount} complete!)" else "" }
            }
        } catch (e: Exception) {
            Logger.e(e) { "Error updating cycle progress: ${e.message}" }
        }
    }

    // ===== Round 8: Core workout lifecycle =====

    /**
     * Handle rep notification from the machine.
     * Updates rep counter and position ranges for visualization.
     *
     * Note: Skip processing for timed/duration-based exercises to avoid
     * incorrect ROM calibration and potential crashes (Issue #41).
     */
    private fun handleRepNotification(notification: RepNotification) {
        // Skip rep processing for bodyweight exercises - they use duration, not rep counting
        if (coordinator._isCurrentExerciseBodyweight.value) {
            return
        }

        val currentPositions = coordinator._currentMetric.value
        val rawPosA = currentPositions?.positionA ?: 0f
        val rawPosB = currentPositions?.positionB ?: 0f

        // Use raw per-cable positions for rep range tracking to ensure danger zone
        // checks work correctly with the UI's raw position display.
        // Rep counter internally handles single-cable exercises by recording
        // positions only for cables with meaningful values (> 0).

        // Use machine's ROM and Set counters directly (official app method)
        // Position values are in mm (Issue #197)
        // CRITICAL: Pass isLegacyFormat to ensure correct counting method (Issue #123)
        // Samsung devices send 6-byte legacy format where repsSetCount=0, requiring
        // processLegacy() which tracks topCounter increments instead of repsSetCount
        // Issue #210: Pass repsRomTotal/repsSetTotal for warmup sync verification
        repCounter.process(
            repsRomCount = notification.repsRomCount,
            repsRomTotal = notification.repsRomTotal,  // Issue #210: Machine's warmup target
            repsSetCount = notification.repsSetCount,
            repsSetTotal = notification.repsSetTotal,  // Issue #210: Machine's working target
            up = notification.topCounter,
            down = notification.completeCounter,
            posA = rawPosA,
            posB = rawPosB,
            isLegacyFormat = notification.isLegacyFormat
        )

        // Issue #163: Update phase tracking for animated rep counter
        // Use max of both positions for direction detection (handles single-cable exercises)
        repCounter.updatePhaseFromPosition(rawPosA, rawPosB)

        // Update rep count and ranges for UI
        coordinator._repCount.value = repCounter.getRepCount()
        coordinator._repRanges.value = repCounter.getRepRanges()
    }

    /**
     * Handle monitor metric data (matches parent repo logic).
     *
     * This is called on every metric from the machine, regardless of workout state.
     * It handles:
     * - Pre-workout position tracking (during handle detection phase)
     * - Active workout position tracking for Just Lift and AMRAP modes
     * - Auto-stop detection for Just Lift and AMRAP modes
     */
    private fun handleMonitorMetric(metric: WorkoutMetric) {
        val params = coordinator._workoutParameters.value
        val state = coordinator._workoutState.value

        // CRITICAL: Track positions during handle detection phase (before workout starts)
        // This builds up min/max ranges for hasMeaningfulRange() auto-stop detection
        // useAutoStart is true when in Just Lift mode and waiting for handles
        if (params.useAutoStart && state is WorkoutState.Idle) {
            repCounter.updatePositionRangesContinuously(metric.positionA, metric.positionB)
            coordinator._repRanges.value = repCounter.getRepRanges()
        }

        if (state is WorkoutState.Active) {
            // Collect metrics for history (moved from monitorWorkout)
            collectMetricForHistory(metric)

            // CRITICAL: In Just Lift/AMRAP modes, we must track positions continuously
            // because no rep events fire to establish min/max ranges.
            // This enables hasMeaningfulRange() to return true for auto-stop detection.
            // For standard workouts, we rely on rep-based tracking (recordTopPosition/recordBottomPosition)
            // which uses sliding window averaging for better accuracy (matches parent repo).
            // Issue #221: Debug logging for position tracking condition
            Logger.d { "Issue221: handleMonitorMetric Active - isJustLift=${params.isJustLift}, isAMRAP=${params.isAMRAP}, isTimedCable=$coordinator.isCurrentTimedCableExercise, posA=${metric.positionA}, posB=${metric.positionB}" }
            if (params.isJustLift || params.isAMRAP || coordinator.isCurrentTimedCableExercise) {
                Logger.d { "Issue221: Calling updatePositionRangesContinuously" }
                repCounter.updatePositionRangesContinuously(metric.positionA, metric.positionB)
            }

            // Issue #163: Update phase tracking for animated rep counter
            repCounter.updatePhaseFromPosition(metric.positionA, metric.positionB)
            coordinator._repCount.value = repCounter.getRepCount()

            // Update rep ranges for position bar ROM visualization
            coordinator._repRanges.value = repCounter.getRepRanges()

            // Just Lift / AMRAP / Duration Cable Auto-Stop
            // Always call checkAutoStop for position-based detection.
            // Stall (velocity) detection inside is gated by stallDetectionEnabled.
            // Duration cable exercises auto-stop when user puts handles down (like AMRAP).
            // Issue #203: Debug logging to track auto-stop check conditions
            if (shouldEnableAutoStop(params)) {
                Logger.d { "Issue203 DEBUG: checkAutoStop called - isJustLift=${params.isJustLift}, isAMRAP=${params.isAMRAP}, isTimedCable=$coordinator.isCurrentTimedCableExercise, setIndex=${coordinator._currentSetIndex.value}" }
                checkAutoStop(metric)
            } else {
                resetAutoStopTimer()
            }

            // Standard Auto-Stop (rep target reached)
            if (repCounter.shouldStopWorkout()) {
                handleSetCompletion()
            }
        } else {
            resetAutoStopTimer()
        }
    }

    /**
     * Check if auto-stop should be triggered based on velocity stall detection OR position-based detection.
     * Called on every metric update during workout.
     *
     * Two detection methods (Issue #204, #214):
     *
     * 1. VELOCITY-BASED STALL DETECTION (primary):
     *    - Triggers when velocity < 25 mm/s for 5 seconds while handles are in use
     *    - Prevents false triggers during controlled eccentric movements
     *
     * 2. POSITION-BASED DETECTION (secondary):
     *    - Triggers when handles in danger zone AND appear released for 2.5 seconds
     *    - Original logic kept as safety backup
     */
    private fun checkAutoStop(metric: WorkoutMetric) {
        // Don't check if workout isn't active
        if (coordinator._workoutState.value !is WorkoutState.Active) {
            resetAutoStopTimer()
            resetStallTimer()
            return
        }

        // Defensive guard: timed cable exercises should never auto-stop before warmup completes.
        if (coordinator.isCurrentTimedCableExercise && !coordinator._repCount.value.isWarmupComplete) {
            resetAutoStopTimer()
            resetStallTimer()
            return
        }

        val hasMeaningfulRange = repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD)
        val params = coordinator._workoutParameters.value

        // ===== 1. VELOCITY-BASED STALL DETECTION (Issue #204, #214, #216, #198) =====
        // Only run if stallDetectionEnabled is true (user preference in Settings)
        if (params.stallDetectionEnabled) {
            // Two-tier hysteresis matching official app (<2.5 stalled, >10 moving):
            // - Below LOW threshold (<2.5): start/continue stall timer
            // - Above HIGH threshold (>10): reset stall timer (clear movement)
            // - Between LOW and HIGH (>=2.5 and <=10): maintain current state (prevents toggling)

            // Get max velocity (use absolute values for comparison)
            val maxVelocity = maxOf(kotlin.math.abs(metric.velocityA), kotlin.math.abs(metric.velocityB))
            val isDefinitelyStalled = maxVelocity < WorkoutCoordinator.STALL_VELOCITY_LOW
            val isDefinitelyMoving = maxVelocity > WorkoutCoordinator.STALL_VELOCITY_HIGH

            // Issue #198: Check if handles are actively being used
            val maxPosition = maxOf(metric.positionA, metric.positionB)
            val isActivelyUsing = maxPosition > WorkoutCoordinator.STALL_MIN_POSITION || hasMeaningfulRange
            val handlesAtRest = maxPosition < WorkoutCoordinator.HANDLE_REST_THRESHOLD  // Position < 2.5mm = dropped

            // Hysteresis state machine
            val inGrace = isInAmrapStartupGrace(hasMeaningfulRange)
            if (isDefinitelyStalled && (isActivelyUsing || handlesAtRest) && coordinator.stallStartTime == null && !inGrace && hasMeaningfulRange) {
                // Velocity below LOW threshold - start stall timer
                coordinator.stallStartTime = currentTimeMillis()
                coordinator.isCurrentlyStalled = true
            } else if (isDefinitelyMoving && coordinator.stallStartTime != null) {
                // Velocity above HIGH threshold - clear movement detected, reset timer
                resetStallTimer()
            }
            // else: velocity in hysteresis band (2.5-10.0) - maintain current timer state

            // If timer is running (regardless of current velocity zone), check progress and update UI
            val startTime = coordinator.stallStartTime
            if (startTime != null) {
                val stallElapsed = (currentTimeMillis() - startTime) / 1000f

                // Trigger auto-stop after 5 seconds of no movement
                if (stallElapsed >= WorkoutCoordinator.STALL_DURATION_SECONDS && !coordinator.autoStopTriggered) {
                    requestAutoStop()
                    return
                }

                // Update UI with stall progress (always update when timer is active)
                if (stallElapsed >= 1.0f) { // Only show after 1 second of stall
                    val progress = (stallElapsed / WorkoutCoordinator.STALL_DURATION_SECONDS).coerceIn(0f, 1f)
                    val remaining = (WorkoutCoordinator.STALL_DURATION_SECONDS - stallElapsed).coerceAtLeast(0f)

                    coordinator._autoStopState.value = AutoStopUiState(
                        isActive = true,
                        progress = progress,
                        secondsRemaining = ceil(remaining).toInt()
                    )
                }
            }
        } else {
            // Stall detection disabled - reset stall timer to avoid stale state
            resetStallTimer()
        }

        // ===== 2. POSITION-BASED DETECTION =====
        val maxPosition = maxOf(metric.positionA, metric.positionB)
        val handlesCompletelyAtRest = maxPosition < WorkoutCoordinator.HANDLE_REST_THRESHOLD  // Both cables < 2.5mm

        // Handle the "handles at rest" case - this should auto-stop even without ROM
        val inGraceForPositionBased = isInAmrapStartupGrace(repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD))
        if (handlesCompletelyAtRest && !inGraceForPositionBased) {
            // Handles at rest (< 2.5mm) - start/continue timer regardless of ROM
            val startTime = coordinator.autoStopStartTime ?: run {
                coordinator.autoStopStartTime = currentTimeMillis()
                currentTimeMillis()
            }

            val elapsed = (currentTimeMillis() - startTime) / 1000f

            // Only update UI if stall detection isn't already showing (stall takes priority)
            if (!coordinator.isCurrentlyStalled) {
                val progress = (elapsed / WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS).coerceIn(0f, 1f)
                val remaining = (WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS - elapsed).coerceAtLeast(0f)

                coordinator._autoStopState.value = AutoStopUiState(
                    isActive = true,
                    progress = progress,
                    secondsRemaining = ceil(remaining).toInt()
                )
            }

            // Trigger auto-stop if timer expired
            if (elapsed >= WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS && !coordinator.autoStopTriggered) {
                requestAutoStop()
            }
            return
        } else if (handlesCompletelyAtRest && inGraceForPositionBased) {
            Logger.v("AutoStop: Handles at rest but in startup grace period - waiting")
            resetAutoStopTimer()
        } else {
            // User is moving or not at rest - reset position-based timer
            resetAutoStopTimer()
        }

        val inDangerZone = repCounter.isInDangerZone(metric.positionA, metric.positionB, WorkoutCoordinator.MIN_RANGE_THRESHOLD)
        val repRanges = repCounter.getRepRanges()

        // Check if cable appears to be released (position at rest OR near minimum)
        var cableAppearsReleased = false

        // Check cable A
        repRanges.minPosA?.let { minA ->
            repRanges.maxPosA?.let { maxA ->
                val rangeA = maxA - minA
                if (rangeA > WorkoutCoordinator.MIN_RANGE_THRESHOLD) {
                    val thresholdA = minA + (rangeA * 0.05f)
                    val cableAInDanger = metric.positionA <= thresholdA
                    val cableAReleased = metric.positionA < WorkoutCoordinator.HANDLE_REST_THRESHOLD ||
                            (metric.positionA - minA) < 10
                    if (cableAInDanger && cableAReleased) {
                        cableAppearsReleased = true
                    }
                }
            }
        }

        // Check cable B (if not already released)
        if (!cableAppearsReleased) {
            repRanges.minPosB?.let { minB ->
                repRanges.maxPosB?.let { maxB ->
                    val rangeB = maxB - minB
                    if (rangeB > WorkoutCoordinator.MIN_RANGE_THRESHOLD) {
                        val thresholdB = minB + (rangeB * 0.05f)
                        val cableBInDanger = metric.positionB <= thresholdB
                        val cableBReleased = metric.positionB < WorkoutCoordinator.HANDLE_REST_THRESHOLD ||
                                (metric.positionB - minB) < 10
                        if (cableBInDanger && cableBReleased) {
                            cableAppearsReleased = true
                        }
                    }
                }
            }
        }

        // Trigger position-based auto-stop countdown if in danger zone AND cable appears released
        if (inDangerZone && cableAppearsReleased) {
            val startTime = coordinator.autoStopStartTime ?: run {
                coordinator.autoStopStartTime = currentTimeMillis()
                currentTimeMillis()
            }

            val elapsed = (currentTimeMillis() - startTime) / 1000f

            // Only update UI if stall detection isn't already showing (stall takes priority)
            if (!coordinator.isCurrentlyStalled) {
                val progress = (elapsed / WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS).coerceIn(0f, 1f)
                val remaining = (WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS - elapsed).coerceAtLeast(0f)

                coordinator._autoStopState.value = AutoStopUiState(
                    isActive = true,
                    progress = progress,
                    secondsRemaining = ceil(remaining).toInt()
                )
            }

            // Trigger auto-stop if timer expired
            if (elapsed >= WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS && !coordinator.autoStopTriggered) {
                requestAutoStop()
            }
        } else {
            // User resumed activity, reset position-based timer
            resetAutoStopTimer()
        }
    }

    /**
     * Start the auto-start countdown timer (configurable via user preferences, default 5 seconds).
     * When user grabs handles while in Idle or SetSummary state, this starts
     * a countdown and automatically begins the workout.
     */
    private fun startAutoStartTimer() {
        // Don't start if already running or not in appropriate state
        if (coordinator.autoStartJob != null) return
        val currentState = coordinator._workoutState.value
        if (currentState !is WorkoutState.Idle && currentState !is WorkoutState.SetSummary) {
            return
        }

        coordinator.autoStartJob = scope.launch {
            // Countdown with visible progress (configurable seconds)
            val countdownSeconds = settingsManager.userPreferences.value.autoStartCountdownSeconds
            for (i in countdownSeconds downTo 1) {
                coordinator._autoStartCountdown.value = i
                delay(1000)
            }
            coordinator._autoStartCountdown.value = null

            // FINAL GUARD: Verify conditions still valid before starting workout
            // Fixes iOS race condition where cancel() is called but coroutine proceeds
            // due to cooperative cancellation timing

            // Check if coroutine was cancelled during countdown
            if (coordinator.autoStartJob?.isActive != true) {
                Logger.d("Auto-start aborted: job cancelled during countdown")
                return@launch
            }

            val currentHandle = bleRepository.handleState.value
            if (currentHandle != HandleState.Grabbed && currentHandle != HandleState.Moving) {
                Logger.d("Auto-start aborted: handles no longer grabbed (state=$currentHandle)")
                return@launch
            }

            val params = coordinator._workoutParameters.value
            if (!params.useAutoStart) {
                Logger.d("Auto-start aborted: autoStart disabled in parameters")
                return@launch
            }

            val state = coordinator._workoutState.value
            if (state !is WorkoutState.Idle && state !is WorkoutState.SetSummary) {
                Logger.d("Auto-start aborted: workout state changed (state=$state)")
                return@launch
            }

            // Auto-start the workout in Just Lift mode
            Logger.d { "Issue221: Auto-start timer complete - params.isJustLift=${params.isJustLift}, params.useAutoStart=${params.useAutoStart}" }
            Logger.d { "Issue221: Starting workout with isJustLiftMode=true (auto-start implies Just Lift mode)" }
            startWorkout(skipCountdown = true, isJustLiftMode = true)
        }
    }

    /**
     * Cancel the auto-start countdown timer.
     * Called when user releases handles before countdown completes.
     */
    private fun cancelAutoStartTimer() {
        coordinator.autoStartJob?.cancel()
        coordinator.autoStartJob = null
        coordinator._autoStartCountdown.value = null
    }

    /**
     * Save workout session to database and check for personal records.
     */
    private suspend fun saveWorkoutSession() {
        val sessionId = coordinator.currentSessionId ?: return
        val params = coordinator._workoutParameters.value
        val warmup = coordinator._repCount.value.warmupReps
        val working = coordinator._repCount.value.workingReps
        val duration = currentTimeMillis() - coordinator.workoutStartTime

        // Take a snapshot of metrics to avoid ConcurrentModificationException
        // (metrics are being collected on another coroutine)
        val metricsSnapshot = coordinator.collectedMetrics.toList()

        // Calculate actual measured weight from metrics (baseline-adjusted)
        val blA = coordinator._loadBaselineA.value.coerceAtLeast(0f)
        val blB = coordinator._loadBaselineB.value.coerceAtLeast(0f)
        val measuredPerCableKg = if (metricsSnapshot.isNotEmpty()) {
            metricsSnapshot.maxOf { maxOf(it.loadA - blA, it.loadB - blB).coerceAtLeast(0f) }
        } else {
            params.weightPerCableKg
        }

        // Get exercise name for display (avoids DB lookups when viewing history)
        val exerciseName = params.selectedExerciseId?.let { exerciseId ->
            exerciseRepository.getExerciseById(exerciseId)?.name
        }

        // Calculate summary metrics for persistence
        val summary = calculateSetSummaryMetrics(
            metrics = metricsSnapshot,
            repCount = working,
            fallbackWeightKg = params.weightPerCableKg,
            isEchoMode = params.isEchoMode,
            warmupRepsCount = warmup,
            workingRepsCount = working,
            baselineLoadA = coordinator._loadBaselineA.value,
            baselineLoadB = coordinator._loadBaselineB.value
        )

        val session = WorkoutSession(
            id = sessionId,
            timestamp = coordinator.workoutStartTime,
            mode = params.programMode.displayName,
            reps = params.reps,
            weightPerCableKg = measuredPerCableKg,
            progressionKg = params.progressionRegressionKg,
            duration = duration,
            totalReps = working,
            warmupReps = warmup,
            workingReps = working,
            isJustLift = params.isJustLift,
            stopAtTop = params.stopAtTop,
            exerciseId = params.selectedExerciseId,
            exerciseName = exerciseName,
            routineSessionId = coordinator.currentRoutineSessionId,
            routineName = coordinator.currentRoutineName,
            // Set Summary Metrics (v0.2.1+)
            peakForceConcentricA = summary.peakForceConcentricA,
            peakForceConcentricB = summary.peakForceConcentricB,
            peakForceEccentricA = summary.peakForceEccentricA,
            peakForceEccentricB = summary.peakForceEccentricB,
            avgForceConcentricA = summary.avgForceConcentricA,
            avgForceConcentricB = summary.avgForceConcentricB,
            avgForceEccentricA = summary.avgForceEccentricA,
            avgForceEccentricB = summary.avgForceEccentricB,
            heaviestLiftKg = summary.heaviestLiftKgPerCable,
            totalVolumeKg = summary.totalVolumeKg,
            estimatedCalories = summary.estimatedCalories,
            warmupAvgWeightKg = if (params.isEchoMode) summary.warmupAvgWeightKg else null,
            workingAvgWeightKg = if (params.isEchoMode) summary.workingAvgWeightKg else null,
            burnoutAvgWeightKg = if (params.isEchoMode) summary.burnoutAvgWeightKg else null,
            peakWeightKg = if (params.isEchoMode) summary.peakWeightKg else null,
            rpe = coordinator._currentSetRpe.value
        )

        workoutRepository.saveSession(session)

        // Trigger sync after workout saved
        scope.launch {
            syncTriggerManager?.onWorkoutCompleted()
        }

        if (metricsSnapshot.isNotEmpty()) {
            workoutRepository.saveMetrics(sessionId, metricsSnapshot)
        }

        Logger.d("Saved workout session: $sessionId with ${metricsSnapshot.size} metrics")

        // Save CompletedSet record for set-level tracking
        var completedSetId: String? = null
        if (params.selectedExerciseId != null && working > 0) {
            val setIndex = coordinator._currentSetIndex.value
            val setId = generateUUID()
            completedSetId = setId
            val matchedPlannedSetId = findPlannedSetId(setIndex)
            val completedSet = CompletedSet(
                id = setId,
                sessionId = sessionId,
                plannedSetId = matchedPlannedSetId,
                setNumber = setIndex,
                setType = if (params.isAMRAP) SetType.AMRAP else SetType.STANDARD,
                actualReps = working,
                actualWeightKg = measuredPerCableKg,
                loggedRpe = coordinator._currentSetRpe.value,
                isPr = false,
                completedAt = currentTimeMillis()
            )
            completedSetRepository.saveCompletedSet(completedSet)
            Logger.d("Saved CompletedSet: set #$setIndex, ${working} reps @ ${measuredPerCableKg}kg${if (matchedPlannedSetId != null) " (linked to PlannedSet)" else ""}")
        }

        // PR checking and badge awarding - delegated to GamificationManager
        val hasPR = gamificationManager.processPostSaveEvents(
            exerciseId = params.selectedExerciseId,
            workingReps = working,
            measuredWeightKg = measuredPerCableKg,
            programMode = params.programMode,
            isJustLift = params.isJustLift,
            isEchoMode = params.isEchoMode
        )

        // Mark the CompletedSet as a PR if personal record was broken
        if (hasPR && completedSetId != null) {
            completedSetRepository.markAsPr(completedSetId)
            Logger.d("Marked CompletedSet $completedSetId as PR")
        }

        // Save exercise defaults for next time (only for Just Lift and Single Exercise modes)
        // Routines have their own saved configuration and should not interfere with these defaults
        if (params.isJustLift) {
            saveJustLiftDefaultsFromWorkout()
        } else if (isSingleExerciseMode()) {
            saveSingleExerciseDefaultsFromWorkout()
        }

        // Update training cycle progress if this workout was started from a cycle
        updateCycleProgressIfNeeded()
    }

    /**
     * Handle automatic set completion (when rep target is reached via auto-stop).
     * This is DIFFERENT from user manually stopping.
     */
    private fun handleSetCompletion() {
        if (coordinator.setCompletionInProgress) {
            Logger.d("handleSetCompletion: already in progress - ignoring")
            return
        }
        coordinator.setCompletionInProgress = true
        // Issue #151: Cancel any running duration timer immediately to prevent double-completion
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        // Issue #192: Clear timed exercise countdown display
        coordinator._timedExerciseRemainingSeconds.value = null

        scope.launch {
            val params = coordinator._workoutParameters.value
            val isJustLift = params.isJustLift

            Logger.d("handleSetCompletion: isJustLift=$isJustLift")

            // Reset timed workout flag
            coordinator.isCurrentWorkoutTimed = false
            coordinator.isCurrentTimedCableExercise = false
            coordinator._isCurrentExerciseBodyweight.value = false

            // Track if this was a bodyweight exercise (for UI decisions like skipping summary)
            val currentExercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
            val wasBodyweight = isBodyweightExercise(currentExercise)

            // Issue #222 diagnostic: Track bodyweight sets completed
            if (wasBodyweight) {
                coordinator.bodyweightSetsCompletedInRoutine++
                println("Issue222: Bodyweight set #$coordinator.bodyweightSetsCompletedInRoutine completed (exercise=${currentExercise?.exercise?.name})")
            }

            // Issue #222 v8: Track if this was bodyweight for transition detection in next startWorkout()
            coordinator.previousExerciseWasBodyweight = wasBodyweight
            Logger.d { "Issue #222 v8: Set coordinator.previousExerciseWasBodyweight=$wasBodyweight" }

            if (!wasBodyweight) {
                bleRepository.stopWorkout()
                Logger.d("handleSetCompletion: Called stopWorkout() (auto-complete)")
            } else {
                Logger.d("handleSetCompletion: Skipping BLE stop (bodyweight exercise)")
            }
            coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)

            // Save session
            saveWorkoutSession()

            // Calculate metrics for summary
            val completedReps = coordinator._repCount.value.workingReps
            val warmupReps = coordinator._repCount.value.warmupReps
            val metricsList = coordinator.collectedMetrics.toList()

            // Calculate enhanced metrics for summary
            val summary = calculateSetSummaryMetrics(
                metrics = metricsList,
                repCount = completedReps,
                fallbackWeightKg = params.weightPerCableKg,
                isEchoMode = params.isEchoMode,
                warmupRepsCount = warmupReps,
                workingRepsCount = completedReps,
                baselineLoadA = coordinator._loadBaselineA.value,
                baselineLoadB = coordinator._loadBaselineB.value
            )

            Logger.d("Set summary: heaviest=${summary.heaviestLiftKgPerCable}kg, reps=$completedReps, duration=${summary.durationMs}ms")

            // Handle based on workout mode
            // Get user's summary countdown preference: -1 = Off (skip), 0 = Unlimited, 5-30 = auto-advance
            val summaryCountdownSeconds = settingsManager.userPreferences.value.summaryCountdownSeconds
            val skipSummary = summaryCountdownSeconds < 0
            val summaryDelayMs = if (skipSummary) 0L else summaryCountdownSeconds * 1000L

            // Issue #222: Bodyweight exercises have nothing to summarize (no weight, no metrics)
            // Always skip summary for bodyweight regardless of user preference
            val skipSummaryForBodyweight = wasBodyweight
            val effectiveSkipSummary = skipSummary || skipSummaryForBodyweight

            Logger.d("handleSetCompletion: summaryCountdownSeconds=$summaryCountdownSeconds, skipSummary=$skipSummary, wasBodyweight=$wasBodyweight, effectiveSkipSummary=$effectiveSkipSummary, isJustLift=$isJustLift, isAMRAP=${params.isAMRAP}")

            // Show set summary (unless user has summary set to "Off" OR it's a bodyweight exercise)
            if (!effectiveSkipSummary) {
                Logger.d("handleSetCompletion: Setting state to SetSummary (effectiveSkipSummary=false)")
                coordinator._workoutState.value = summary
            } else {
                Logger.d("handleSetCompletion: Skipping SetSummary state (effectiveSkipSummary=true, wasBodyweight=$wasBodyweight)")
            }

            if (isJustLift) {
                // Just Lift mode: Auto-advance to next set after showing summary
                Logger.d("Just Lift: IMMEDIATE reset for next set (while showing summary)")

                // 1. Reset logical state immediately
                repCounter.reset()
                resetAutoStopState()

                // 2. Restart monitor polling to clear machine fault state (red lights)
                bleRepository.restartMonitorPolling()

                // 3. Re-enable machine detection (enables auto-start for next set)
                enableHandleDetection()
                bleRepository.enableJustLiftWaitingMode()

                Logger.d("Just Lift: Machine armed & ready. summaryCountdownSeconds=$summaryCountdownSeconds, skipSummary=$skipSummary")

                // 4. Handle summary based on user preference
                if (skipSummary) {
                    // Summary is "Off" (-1): Skip summary entirely, immediately ready for next set
                    Logger.d("Just Lift: Summary OFF - skipping summary, immediately ready")
                    resetForNewWorkout() // Ensures clean state
                    coordinator._workoutState.value = WorkoutState.Idle
                } else if (summaryDelayMs > 0) {
                    // Show summary for configured duration (5-30s), then auto-transition
                    delay(summaryDelayMs)

                    // Transition UI to Idle (only if we haven't already started a new set)
                    if (coordinator._workoutState.value is WorkoutState.SetSummary) {
                        Logger.d("Just Lift: Summary complete, UI transitioning to Idle")
                        resetForNewWorkout() // Ensures clean state
                        coordinator._workoutState.value = WorkoutState.Idle
                    } else {
                        Logger.d("Just Lift: Summary interrupted by user action (state is ${coordinator._workoutState.value})")
                    }
                } else {
                    // Summary is "Unlimited" (0): Show summary, wait for user action
                    Logger.d("Just Lift: Summary Unlimited - waiting for user action")
                }
            } else if (params.isAMRAP) {
                // AMRAP mode: Auto-advance to rest timer and next set (like Just Lift)
                Logger.d("AMRAP: Auto-advancing to rest timer")

                // Reset logical state for next set
                repCounter.reset()
                resetAutoStopState()

                // Restart monitor polling to clear machine fault state (red lights)
                bleRepository.restartMonitorPolling()

                // Enable handle detection for auto-start during rest
                enableHandleDetection()
                bleRepository.enableJustLiftWaitingMode()

                Logger.d("AMRAP: Machine armed & ready. summaryCountdownSeconds=$summaryCountdownSeconds, skipSummary=$skipSummary")

                // Handle summary based on user preference
                if (skipSummary) {
                    // Summary is "Off" (-1): Skip summary entirely, proceed to rest timer
                    Logger.d("AMRAP: Summary OFF - skipping summary, proceeding to rest timer")
                    startRestTimer()
                } else if (summaryDelayMs > 0) {
                    // Show summary for configured duration (5-30s), then auto-advance
                    delay(summaryDelayMs)

                    // Auto-start rest timer if we haven't already started a new set
                    if (coordinator._workoutState.value is WorkoutState.SetSummary) {
                        startRestTimer()
                    }
                } else {
                    // Summary is "Unlimited" (0): Show summary, wait for user action
                    Logger.d("AMRAP: Summary Unlimited - waiting for user action")
                }
            } else {
                // Routine/Program mode (includes Single Exercise)
                Logger.d("Routine/SingleExercise mode: skipSummary=$skipSummary, effectiveSkipSummary=$effectiveSkipSummary, wasBodyweight=$wasBodyweight, summaryCountdownSeconds=$summaryCountdownSeconds")
                if (effectiveSkipSummary) {
                    Logger.d("Routine mode: Summary skipped (effectiveSkipSummary=true, wasBodyweight=$wasBodyweight) - calling startRestTimer()")

                    // Reset logical state for next set
                    repCounter.reset()
                    resetAutoStopState()

                    Logger.d("Routine mode: Parent-aligned - no polling restart/auto-start during rest")

                    // Proceed to rest timer (which handles 0 rest time correctly now)
                    startRestTimer()
                } else {
                    Logger.d("Routine mode: Waiting for UI countdown or user action to proceed from summary")
                }
            }
        }
    }

    fun updateWorkoutParameters(params: WorkoutParameters) {
        // Issue #170/#180: Track if user edits parameters during Idle, Resting, or SetSummary
        val currentState = coordinator._workoutState.value
        if (currentState is WorkoutState.Idle ||
            currentState is WorkoutState.Resting ||
            currentState is WorkoutState.SetSummary) {
            coordinator._userAdjustedWeightDuringRest = true
            Logger.d("updateWorkoutParameters: User edited params in ${currentState::class.simpleName} - will preserve on transition")
        }
        coordinator._workoutParameters.value = params
    }

    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false) {
        Logger.d { "startWorkout called: skipCountdown=$skipCountdown, isJustLiftMode=$isJustLiftMode" }
        Logger.d { "startWorkout: loadedRoutine=${coordinator._loadedRoutine.value?.name}, params=${coordinator._workoutParameters.value}" }

        // Reset stopWorkout guard for new workout (Issue #97)
        coordinator.stopWorkoutInProgress = false
        coordinator.setCompletionInProgress = false
        // Parent parity: reset auto-stop state at start of every workout
        resetAutoStopState()
        // Reset skip countdown flag for new workout
        coordinator.skipCountdownRequested = skipCountdown

        // Cancel any previous workout job
        coordinator.workoutJob?.cancel()

        // CRITICAL: Set Initializing state IMMEDIATELY (before launching coroutine)
        coordinator._workoutState.value = WorkoutState.Initializing

        coordinator.workoutJob = scope.launch {
            val params = coordinator._workoutParameters.value

            // Check for bodyweight or timed exercise
            val currentExercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
            val isBodyweight = isBodyweightExercise(currentExercise)
            val exerciseDuration = currentExercise?.duration?.takeIf { it > 0 }
            val bodyweightDuration = if (isBodyweight) exerciseDuration else null

            // Track if this is a timed cable exercise (not bodyweight, but has duration)
            val isTimedCableExercise = !isBodyweight && exerciseDuration != null
            coordinator.isCurrentWorkoutTimed = exerciseDuration != null
            coordinator.isCurrentTimedCableExercise = isTimedCableExercise
            coordinator._isCurrentExerciseBodyweight.value = isBodyweight

            // Issue #227: Detailed logging to trace exercise type detection
            Logger.d { "Issue227: startWorkout exercise type detection:" }
            Logger.d { "  - Exercise: ${currentExercise?.exercise?.name}" }
            Logger.d { "  - Equipment: '${currentExercise?.exercise?.equipment}'" }
            Logger.d { "  - Weight: ${currentExercise?.weightPerCableKg}kg" }
            Logger.d { "  - Duration: ${exerciseDuration}s" }
            Logger.d { "  - isBodyweight: $isBodyweight" }
            Logger.d { "  - isTimedCableExercise: $isTimedCableExercise" }

            // Issue #222: For ALL bodyweight exercises, skip machine commands
            if (isBodyweight) {
                val effectiveDuration = bodyweightDuration ?: 30  // Default 30s for legacy data
                Logger.d("Starting bodyweight exercise: ${currentExercise?.exercise?.name} for ${effectiveDuration}s (bodyweightDuration=${bodyweightDuration})")

                Logger.d("DefaultWorkoutSessionManager") { "Issue #222 v6: Bodyweight start - keeping existing polling state (matching parent repo)" }

                repCounter.reset()
                repCounter.configure(
                    warmupTarget = 0,
                    workingTarget = 0,
                    isJustLift = false,
                    stopAtTop = params.stopAtTop,
                    isAMRAP = false
                )
                coordinator._repCount.value = RepCount()

                // Countdown (can be skipped via coordinator.skipCountdownRequested flag)
                if (!coordinator.skipCountdownRequested) {
                    for (i in 5 downTo 1) {
                        if (coordinator.skipCountdownRequested) break
                        coordinator._workoutState.value = WorkoutState.Countdown(i)
                        delay(1000)
                    }
                }

                // Start timer
                coordinator._workoutState.value = WorkoutState.Active
                coordinator.workoutStartTime = currentTimeMillis()
                // Issue #195: Track routine start separately - only set on first set
                if (coordinator._loadedRoutine.value != null && coordinator.routineStartTime == 0L) {
                    coordinator.routineStartTime = coordinator.workoutStartTime
                }
                coordinator.currentSessionId = KmpUtils.randomUUID()
                coordinator.collectedMetrics.clear()  // Clear metrics from previous workout
                coordinator._hapticEvents.emit(HapticEvent.WORKOUT_START)

                // Bodyweight timer - auto-complete after duration with countdown display
                coordinator.bodyweightTimerJob?.cancel()
                coordinator.bodyweightTimerJob = scope.launch {
                    coordinator._timedExerciseRemainingSeconds.value = effectiveDuration
                    for (remaining in effectiveDuration downTo 1) {
                        coordinator._timedExerciseRemainingSeconds.value = remaining
                        delay(1000L)
                    }
                    coordinator._timedExerciseRemainingSeconds.value = 0
                    handleSetCompletion()
                }

                return@launch
            }

            // Normal cable-based exercise
            if (coordinator.previousExerciseWasBodyweight) {
                coordinator.previousExerciseWasBodyweight = false
            }

            val effectiveWarmupReps = Constants.DEFAULT_WARMUP_REPS
            val effectiveParams = if (params.warmupReps != effectiveWarmupReps) {
                Logger.d("DefaultWorkoutSessionManager") { "Issue #222: Forcing warmupReps=$effectiveWarmupReps for cable exercise (was ${params.warmupReps})" }
                val updated = params.copy(warmupReps = effectiveWarmupReps)
                coordinator._workoutParameters.value = updated
                updated
            } else {
                params
            }

            // Issue #222 diagnostic: Log state when starting cable exercise
            println("Issue222: CABLE WORKOUT STARTING - DIAGNOSTIC STATE")
            println("Issue222: Bodyweight sets completed this routine: $coordinator.bodyweightSetsCompletedInRoutine")
            println("Issue222: Current exercise index: ${coordinator._currentExerciseIndex.value}")
            println("Issue222: Current set index: ${coordinator._currentSetIndex.value}")
            println("Issue222: isEchoMode: ${effectiveParams.isEchoMode}")
            println("Issue222: programMode: ${effectiveParams.programMode}")

            // Issue #188: Comprehensive workout parameters dump for debugging
            println("Issue188: PRE-BLE WORKOUT PARAMETERS")
            println("Issue188: Mode: ${effectiveParams.programMode.displayName}")
            println("Issue188: Weight: ${effectiveParams.weightPerCableKg}kg per cable")
            println("Issue188: Reps: ${effectiveParams.reps} (isAMRAP=${effectiveParams.isAMRAP})")
            Logger.d { "Issue203 DEBUG: Starting workout - setReps=${currentExercise?.setReps}, currentSetIndex=${coordinator._currentSetIndex.value}, isAMRAP=${effectiveParams.isAMRAP}" }
            println("Issue188: Warmup: ${effectiveParams.warmupReps}")
            println("Issue188: Progression: ${effectiveParams.progressionRegressionKg}kg per rep")
            println("Issue188: isJustLift: ${effectiveParams.isJustLift}")
            println("Issue188: isEchoMode: ${effectiveParams.isEchoMode}")
            println("Issue188: echoLevel: ${effectiveParams.echoLevel.displayName}")
            println("Issue188: eccentricLoad: ${effectiveParams.eccentricLoad.percentage}%")
            println("Issue188: stopAtTop: ${effectiveParams.stopAtTop}")
            println("Issue188: stallDetection: ${effectiveParams.stallDetectionEnabled}")

            // Duration cable exercises should behave like AMRAP to the machine
            val bleParams = if (isTimedCableExercise) {
                Logger.d { "Duration cable: overriding isAMRAP=true for BLE command (prevents machine rep limit)" }
                effectiveParams.copy(isAMRAP = true)
            } else {
                effectiveParams
            }

            // 1. Build Command - Use full 96-byte PROGRAM params (matches parent repo)
            val command = if (bleParams.isEchoMode) {
                // 32-byte Echo control frame
                BlePacketFactory.createEchoControl(
                    level = bleParams.echoLevel,
                    warmupReps = bleParams.warmupReps,
                    targetReps = bleParams.reps,
                    isJustLift = isJustLiftMode || bleParams.isJustLift,
                    isAMRAP = bleParams.isAMRAP,
                    eccentricPct = bleParams.eccentricLoad.percentage
                )
            } else {
                // Full 96-byte program frame with mode profile, weight, progression
                BlePacketFactory.createProgramParams(bleParams)
            }
            Logger.d { "Built ${command.size}-byte workout command for ${bleParams.programMode}" }

            // 2. Reset State (prepare app state during countdown)
            coordinator.currentSessionId = KmpUtils.randomUUID()
            coordinator._repCount.value = RepCount()
            // Issue #213: Reset Echo mode force telemetry
            coordinator._currentHeuristicKgMax.value = 0f
            // For Just Lift mode, preserve position ranges built during handle detection
            if (isJustLiftMode) {
                repCounter.resetCountsOnly()
            } else {
                repCounter.reset()
            }
            repCounter.configure(
                warmupTarget = effectiveParams.warmupReps,
                workingTarget = if (isTimedCableExercise) 0 else effectiveParams.reps,
                isJustLift = isJustLiftMode,
                stopAtTop = effectiveParams.stopAtTop,
                isAMRAP = if (isTimedCableExercise) true else effectiveParams.isAMRAP
            )

            // Log timed cable exercise detection
            if (isTimedCableExercise) {
                Logger.d { "Starting TIMED cable exercise: ${currentExercise?.exercise?.name} for ${exerciseDuration}s (no ROM calibration)" }
            }

            // 3. Countdown (skipped for Just Lift auto-start, can be skipped mid-way via coordinator.skipCountdownRequested)
            if (!coordinator.skipCountdownRequested && !isJustLiftMode) {
                for (i in 5 downTo 1) {
                    if (coordinator.skipCountdownRequested) break
                    coordinator._workoutState.value = WorkoutState.Countdown(i)
                    delay(1000)
                }
            }

            // 4. Send CONFIG command AFTER countdown completes
            try {
                bleRepository.sendWorkoutCommand(command)
                Logger.i { "CONFIG command sent: ${command.size} bytes for ${effectiveParams.programMode}" }
                val preview = command.take(16).joinToString(" ") { it.toUByte().toString(16).padStart(2, '0').uppercase() }
                Logger.d { "Config preview: $preview ..." }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to send config command" }
                bleConnectionManager.setConnectionError("Failed to send command: ${e.message}")
                return@launch
            }

            // 5. Send START command (may be vestigial - CONFIG appears to activate the machine)
            if (!effectiveParams.isEchoMode) {
                delay(100)  // Brief delay between CONFIG and START
                try {
                    val startCommand = BlePacketFactory.createStartCommand()
                    bleRepository.sendWorkoutCommand(startCommand)
                    Logger.i { "START command sent (0x03)" }
                } catch (e: Exception) {
                    Logger.e(e) { "Failed to send START command" }
                    bleConnectionManager.setConnectionError("Failed to start workout: ${e.message}")
                    return@launch
                }
            }

            // Start active workout polling now that we're ready
            bleRepository.startActiveWorkoutPolling()

            // 7. Start Monitoring
            coordinator._workoutState.value = WorkoutState.Active
            coordinator.workoutStartTime = currentTimeMillis()
            // Issue #195: Track routine start separately - only set on first set
            if (coordinator._loadedRoutine.value != null && coordinator.routineStartTime == 0L) {
                coordinator.routineStartTime = coordinator.workoutStartTime
            }
            coordinator.collectedMetrics.clear()  // Clear metrics from previous workout
            coordinator._hapticEvents.emit(HapticEvent.WORKOUT_START)

            // For timed cable exercises, start auto-complete timer with countdown display
            if (isTimedCableExercise && exerciseDuration != null) {
                coordinator.bodyweightTimerJob?.cancel()
                coordinator.bodyweightTimerJob = scope.launch {
                    // Wait for warmup to complete before starting the duration countdown.
                    if (effectiveParams.warmupReps > 0) {
                        Logger.d { "Duration cable: waiting for ${effectiveParams.warmupReps} warmup reps before starting ${exerciseDuration}s timer" }
                        coordinator._repCount.first { it.isWarmupComplete }
                        Logger.d { "Duration cable: warmup complete, starting ${exerciseDuration}s duration timer" }
                    }

                    // Now start the duration countdown
                    coordinator._timedExerciseRemainingSeconds.value = exerciseDuration
                    for (remaining in exerciseDuration downTo 1) {
                        coordinator._timedExerciseRemainingSeconds.value = remaining
                        delay(1000L)
                    }
                    coordinator._timedExerciseRemainingSeconds.value = 0
                    handleSetCompletion()
                }
            }

            // Set initial baseline position for position bars calibration
            coordinator._currentMetric.value?.let { metric ->
                repCounter.setInitialBaseline(metric.positionA, metric.positionB)
                coordinator._repRanges.value = repCounter.getRepRanges()
                Logger.d("DefaultWorkoutSessionManager") { "POSITION BASELINE: Set initial baseline posA=${metric.positionA}, posB=${metric.positionB}" }

                // Capture load baseline for base tension subtraction
                coordinator._loadBaselineA.value = metric.loadA
                coordinator._loadBaselineB.value = metric.loadB
                Logger.d("DefaultWorkoutSessionManager") { "LOAD BASELINE: Set initial baseline loadA=${metric.loadA}kg, loadB=${metric.loadB}kg" }
            }
        }
    }

    /**
     * Skip the countdown and immediately transition to Active state.
     * Called when user clicks "Skip Countdown" button during countdown phase.
     */
    fun skipCountdown() {
        coordinator.skipCountdownRequested = true
        Logger.d { "skipCountdown: Countdown skip requested" }
    }

    /**
     * Stop the current workout, save session, and optionally reset state.
     *
     * @param exitingWorkout When true, sets state to Idle instead of SetSummary.
     */
    fun stopWorkout(exitingWorkout: Boolean = false) {
        // Guard against race condition: handleMonitorMetric() can call this multiple times
        // before the coroutine completes and changes state (Issue #97)
        if (coordinator.stopWorkoutInProgress) return
        coordinator.stopWorkoutInProgress = true

        // Capture this before entering coroutine
        val shouldExitToIdle = exitingWorkout

        // Cancel any running workout job (countdown or active workout)
        coordinator.workoutJob?.cancel()
        coordinator.workoutJob = null

        // Issue #151: Cancel any running duration timer to prevent it from firing
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        // Issue #192: Clear timed exercise countdown display
        coordinator._timedExerciseRemainingSeconds.value = null

        // Cancel any running rest timer
        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null

        scope.launch {
            // Reset timed workout flag
            coordinator.isCurrentWorkoutTimed = false
            coordinator.isCurrentTimedCableExercise = false
            coordinator._isCurrentExerciseBodyweight.value = false

             // Manual stop: match parent behavior (skip BLE stop for bodyweight)
             val currentExercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
             val isBodyweight = isBodyweightExercise(currentExercise)
             println("Issue222 TRACE: manual stop -> isBodyweight=$isBodyweight, exitingWorkout=$shouldExitToIdle")
             if (!isBodyweight) {
                 // Send RESET command (0x0A) to fully stop workout on machine
                 println("Issue222 TRACE: manual stop -> calling bleRepository.stopWorkout()")
                 bleRepository.stopWorkout()
             } else {
                 println("Issue222 TRACE: manual stop -> skipping BLE stop (bodyweight)")
                 Logger.d("Manual stop: bodyweight exercise - skipping BLE stop (parent-aligned)")
             }
             coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)

             val params = coordinator._workoutParameters.value
             val repCount = coordinator._repCount.value
             val isJustLift = params.isJustLift

             // CRITICAL: Just Lift mode - immediately restart polling to clear machine fault state
             if (isJustLift) {
                 Logger.d("Just Lift: Restarting monitor polling to clear machine fault state")
                 bleRepository.restartMonitorPolling()
             }

             // Get exercise name for display
             val exerciseName = params.selectedExerciseId?.let { exerciseId ->
                 exerciseRepository.getExerciseById(exerciseId)?.name
             }

             // Calculate summary metrics for persistence and display
             val metrics = coordinator.collectedMetrics.toList()
             val summary = calculateSetSummaryMetrics(
                 metrics = metrics,
                 repCount = repCount.totalReps,
                 fallbackWeightKg = params.weightPerCableKg,
                 isEchoMode = params.isEchoMode,
                 warmupRepsCount = repCount.warmupReps,
                 workingRepsCount = repCount.workingReps,
                 baselineLoadA = coordinator._loadBaselineA.value,
                 baselineLoadB = coordinator._loadBaselineB.value
             )

             val session = WorkoutSession(
                 timestamp = coordinator.workoutStartTime,
                 mode = params.programMode.displayName,
                 reps = params.reps,
                 weightPerCableKg = params.weightPerCableKg,
                 totalReps = repCount.totalReps,
                 workingReps = repCount.workingReps,
                 warmupReps = repCount.warmupReps,
                 duration = currentTimeMillis() - coordinator.workoutStartTime,
                 isJustLift = isJustLift,
                 exerciseId = params.selectedExerciseId,
                 exerciseName = exerciseName,
                 routineSessionId = coordinator.currentRoutineSessionId,
                 routineName = coordinator.currentRoutineName,
                 // Set Summary Metrics (v0.2.1+)
                 peakForceConcentricA = summary.peakForceConcentricA,
                 peakForceConcentricB = summary.peakForceConcentricB,
                 peakForceEccentricA = summary.peakForceEccentricA,
                 peakForceEccentricB = summary.peakForceEccentricB,
                 avgForceConcentricA = summary.avgForceConcentricA,
                 avgForceConcentricB = summary.avgForceConcentricB,
                 avgForceEccentricA = summary.avgForceEccentricA,
                 avgForceEccentricB = summary.avgForceEccentricB,
                 heaviestLiftKg = summary.heaviestLiftKgPerCable,
                 totalVolumeKg = summary.totalVolumeKg,
                 estimatedCalories = summary.estimatedCalories,
                 warmupAvgWeightKg = if (params.isEchoMode) summary.warmupAvgWeightKg else null,
                 workingAvgWeightKg = if (params.isEchoMode) summary.workingAvgWeightKg else null,
                 burnoutAvgWeightKg = if (params.isEchoMode) summary.burnoutAvgWeightKg else null,
                 peakWeightKg = if (params.isEchoMode) summary.peakWeightKg else null,
                 rpe = coordinator._currentSetRpe.value
             )
             workoutRepository.saveSession(session)

             // Save CompletedSet record for set-level tracking (manual stop path)
             var completedSetId: String? = null
             if (params.selectedExerciseId != null && repCount.workingReps > 0) {
                 val setIndex = coordinator._currentSetIndex.value
                 val setId = generateUUID()
                 completedSetId = setId
                 val matchedPlannedSetId = findPlannedSetId(setIndex)
                 val completedSet = CompletedSet(
                     id = setId,
                     sessionId = session.id,
                     plannedSetId = matchedPlannedSetId,
                     setNumber = setIndex,
                     setType = if (params.isAMRAP) SetType.AMRAP else SetType.STANDARD,
                     actualReps = repCount.workingReps,
                     actualWeightKg = params.weightPerCableKg,
                     loggedRpe = coordinator._currentSetRpe.value,
                     isPr = false,
                     completedAt = currentTimeMillis()
                 )
                 completedSetRepository.saveCompletedSet(completedSet)
                 Logger.d("Saved CompletedSet (manual stop): set #$setIndex, ${repCount.workingReps} reps${if (matchedPlannedSetId != null) " (linked to PlannedSet)" else ""}")
             }

             // PR checking and badge awarding (manual stop path)
             val hasPR = gamificationManager.processPostSaveEvents(
                 exerciseId = params.selectedExerciseId,
                 workingReps = repCount.workingReps,
                 measuredWeightKg = params.weightPerCableKg,
                 programMode = params.programMode,
                 isJustLift = isJustLift,
                 isEchoMode = params.isEchoMode
             )

             // Mark the CompletedSet as a PR if personal record was broken
             if (hasPR && completedSetId != null) {
                 completedSetRepository.markAsPr(completedSetId)
                 Logger.d("Marked CompletedSet $completedSetId as PR (manual stop)")
             }

             // Trigger sync after workout saved
             scope.launch {
                 syncTriggerManager?.onWorkoutCompleted()
             }

             // Save exercise defaults for next time (only for Just Lift and Single Exercise modes)
             if (isJustLift) {
                 saveJustLiftDefaultsFromWorkout()
             } else if (isSingleExerciseMode()) {
                 saveSingleExerciseDefaultsFromWorkout()
             }

             // Set final state based on how we're stopping
             if (shouldExitToIdle) {
                 // User is exiting the workout screen - reset to Idle to allow editing
                 coordinator._workoutState.value = WorkoutState.Idle
                 coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
                 coordinator._loadedRoutine.value = null
                 coordinator.routineStartTime = 0  // Issue #195: Reset for next routine
             } else {
                 // Normal stop - show summary so user can see workout results
                 coordinator._workoutState.value = summary
             }
        }
    }

    /**
     * Stop the current workout and return to SetReady for the current set.
     * Used when user presses back during Active/Resting/Countdown states.
     * Preserves routine context but resets workout state for a fresh start.
     */
    fun stopAndReturnToSetReady() {
        // Cancel any running jobs
        coordinator.workoutJob?.cancel()
        coordinator.workoutJob = null
        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null

        scope.launch {
            // Send RESET command to machine
            bleRepository.stopWorkout()

            // Reset state for fresh start
            repCounter.reset()  // Full reset - clear all counters and ROM ranges
            coordinator._repCount.value = RepCount()
            coordinator._repRanges.value = null
            resetAutoStopState()
            coordinator._workoutState.value = WorkoutState.Idle

            // Navigate to SetReady for CURRENT set (not next)
            val routine = coordinator._loadedRoutine.value
            if (routine != null) {
                enterSetReady(coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
            }

            Logger.d { "stopAndReturnToSetReady: Reset to SetReady for exercise=${coordinator._currentExerciseIndex.value}, set=${coordinator._currentSetIndex.value}" }
        }
    }

    fun pauseWorkout() {
        if (coordinator._workoutState.value is WorkoutState.Active) {
            // Cancel collection jobs to prevent stale data during pause
            coordinator.monitorDataCollectionJob?.cancel()
            coordinator.repEventsCollectionJob?.cancel()

            coordinator._workoutState.value = WorkoutState.Paused
            Logger.d { "DefaultWorkoutSessionManager: Workout paused, collection jobs cancelled" }
        }
    }

    fun resumeWorkout() {
        if (coordinator._workoutState.value is WorkoutState.Paused) {
            coordinator._workoutState.value = WorkoutState.Active

            // Restart collection jobs
            restartCollectionJobs()
            Logger.d { "DefaultWorkoutSessionManager: Workout resumed, collection jobs restarted" }
        }
    }

    private fun restartCollectionJobs() {
        // Restart monitor data collection
        coordinator.monitorDataCollectionJob = scope.launch {
            Logger.d("DefaultWorkoutSessionManager") { "Restarting global metricsFlow collection after resume..." }
            bleRepository.metricsFlow.collect { metric ->
                coordinator._currentMetric.value = metric
                handleMonitorMetric(metric)
            }
        }

        // Restart rep events collection
        coordinator.repEventsCollectionJob = scope.launch {
            bleRepository.repEvents.collect { notification ->
                val state = coordinator._workoutState.value
                if (state is WorkoutState.Active) {
                    handleRepNotification(notification)
                }
            }
        }
    }

    /**
     * Start the rest timer between sets.
     * Counts down and either auto-starts next set (if autoplay enabled) or waits for user.
     */
    private fun startRestTimer() {
        coordinator.restTimerJob?.cancel()

        coordinator.restTimerJob = scope.launch {
            val routine = coordinator._loadedRoutine.value
            val currentExercise = routine?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)

            // Load preset weights for the current exercise
            val exerciseId = currentExercise?.exercise?.id ?: coordinator._workoutParameters.value.selectedExerciseId
            if (exerciseId != null) {
                val lastWeight = getLastWeightForExercise(exerciseId)
                val prWeight = getPrWeightForExercise(exerciseId)
                coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
                    lastUsedWeightKg = lastWeight,
                    prWeightKg = prWeight
                )
            }

            val completedSetIndex = coordinator._currentSetIndex.value

            // Issue #222: Use getNextStep() to determine the ACTUAL next exercise/set
            val nextStep = if (routine != null) {
                getNextStep(routine, coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
            } else null
            val nextExerciseFromStep = if (nextStep != null && routine != null) {
                routine.exercises.getOrNull(nextStep.first)
            } else null
            val nextSetIdxFromStep = nextStep?.second

            // Determine rest duration
            val isInSupersetTransition = isInSuperset() && !isAtEndOfSupersetCycle()
            val isStillInSupersetWorkout = isInSuperset() && nextExerciseFromStep != null &&
                nextExerciseFromStep.supersetId == currentExercise?.supersetId
            val restDuration = if (isInSupersetTransition || isStillInSupersetWorkout) {
                getSupersetRestSeconds().coerceAtLeast(5) // Min 5s for superset transitions
            } else {
                currentExercise?.getRestForSet(completedSetIndex) ?: 90
            }
            val autoplay = settingsManager.autoplayEnabled.value
            val isSingleExercise = isSingleExerciseMode()

            Logger.d("startRestTimer: restDuration=$restDuration, autoplay=$autoplay, isSingleExercise=$isSingleExercise, summaryCountdownSeconds=${settingsManager.userPreferences.value.summaryCountdownSeconds}")

            // Handle 0 rest time: skip rest timer entirely and advance immediately
            if (restDuration == 0) {
                Logger.d { "Rest duration is 0 - skipping rest timer, advancing immediately (no BLE stop - already sent at set end)" }
                if (isSingleExerciseMode()) {
                    advanceToNextSetInSingleExercise()
                } else {
                    startNextSetOrExercise()
                }
                return@launch
            }

            // Determine superset label for display
            val supersetLabel = if (isInSupersetTransition || isStillInSupersetWorkout) {
                val supersetIds = routine?.supersets?.map { it.id } ?: emptyList()
                val groupIndex = supersetIds.indexOf(currentExercise?.supersetId)
                if (groupIndex >= 0) "Superset ${('A' + groupIndex)}" else "Superset"
            } else null

            // Issue #94: Calculate correct set/total for "UP NEXT" display
            val isLastSetOfCurrentExercise = coordinator._currentSetIndex.value >= (currentExercise?.setReps?.size ?: 1) - 1
            val isLastExerciseOverall = calculateIsLastExercise(isSingleExercise, currentExercise, routine)
            val isTransitioningToNextExercise = isLastSetOfCurrentExercise && !isLastExerciseOverall && !isSingleExercise

            val nextExercise = nextExerciseFromStep

            // Issue #170/#203/#222: Update workoutParameters with NEXT exercise/set settings when transitioning
            val exerciseForNextSet = nextExerciseFromStep ?: currentExercise
            val nextExerciseIsBodyweight = isBodyweightExercise(exerciseForNextSet)

            if (exerciseForNextSet != null && !nextExerciseIsBodyweight) {
                val nextSetIdx = nextSetIdxFromStep ?: (completedSetIndex + 1)

                // Guard: Only update if there IS a next set (not past the last set)
                val hasNextSet = nextSetIdx < exerciseForNextSet.setReps.size
                if (hasNextSet) {
                    val nextSetReps = exerciseForNextSet.setReps.getOrNull(nextSetIdx)
                    val nextSetWeight = exerciseForNextSet.setWeightsPerCableKg.getOrNull(nextSetIdx)
                        ?: exerciseForNextSet.weightPerCableKg
                    val isNextSetLastSet = nextSetIdx >= exerciseForNextSet.setReps.size - 1
                    val nextIsAMRAP = nextSetReps == null || (exerciseForNextSet.isAMRAP && isNextSetLastSet)

                    coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
                        weightPerCableKg = nextSetWeight,
                        reps = nextSetReps ?: 0,
                        programMode = exerciseForNextSet.programMode,
                        echoLevel = exerciseForNextSet.echoLevel,
                        eccentricLoad = exerciseForNextSet.eccentricLoad,
                        progressionRegressionKg = exerciseForNextSet.progressionKg,
                        selectedExerciseId = exerciseForNextSet.exercise.id,
                        isAMRAP = nextIsAMRAP,
                        stallDetectionEnabled = exerciseForNextSet.stallDetectionEnabled,
                        warmupReps = if (nextExerciseIsBodyweight) 0 else Constants.DEFAULT_WARMUP_REPS
                    )
                    Logger.d { "startRestTimer: Issue #203 - Updated params for next set: ${exerciseForNextSet.exercise.name}, setIdx=$nextSetIdx, isAMRAP=$nextIsAMRAP, nextSetReps=$nextSetReps" }
                }
            } else if (nextExerciseIsBodyweight) {
                Logger.d { "startRestTimer: Issue #222 - Skipping params update for bodyweight exercise: ${nextExerciseFromStep?.exercise?.name}" }
            }

            // Calculate display values for the rest timer
            val displaySetIndex = nextSetIdxFromStep ?: (coordinator._currentSetIndex.value + 1)
            val displayTotalSets = nextExerciseFromStep?.setReps?.size ?: currentExercise?.setReps?.size ?: 0

            // Countdown using elapsed-time calculation to prevent drift
            val startTime = currentTimeMillis()
            val endTimeMs = startTime + (restDuration * 1000L)

            while (currentTimeMillis() < endTimeMs && isActive) {
                val remainingMs = endTimeMs - currentTimeMillis()
                val remainingSeconds = (remainingMs / 1000L).toInt().coerceAtLeast(0)

                val nextName = calculateNextExerciseName(isSingleExercise, currentExercise, routine)

                coordinator._workoutState.value = WorkoutState.Resting(
                    restSecondsRemaining = remainingSeconds,
                    nextExerciseName = nextName,
                    isLastExercise = isLastExerciseOverall,
                    currentSet = displaySetIndex,
                    totalSets = displayTotalSets,
                    isSupersetTransition = isInSupersetTransition || isStillInSupersetWorkout,
                    supersetLabel = supersetLabel
                )

                delay(100) // Update 10x per second for smooth display
            }

            if (autoplay) {
                Logger.d("DefaultWorkoutSessionManager") { "autoplay rest complete: advancing to next set (no BLE stop - already sent at set end)" }
                if (isSingleExercise) {
                    advanceToNextSetInSingleExercise()
                } else {
                    startNextSetOrExercise()
                }
            } else {
                // Stay in resting state with 0 seconds - user must manually start
                coordinator._workoutState.value = WorkoutState.Resting(
                    restSecondsRemaining = 0,
                    nextExerciseName = calculateNextExerciseName(isSingleExercise, currentExercise, routine),
                    isLastExercise = isLastExerciseOverall,
                    currentSet = displaySetIndex,
                    totalSets = displayTotalSets,
                    isSupersetTransition = isInSupersetTransition || isStillInSupersetWorkout,
                    supersetLabel = supersetLabel
                )
            }
        }
    }

    /**
     * Advance to the next set within a single exercise (non-routine mode).
     */
    private fun advanceToNextSetInSingleExercise() {
        val routine = coordinator._loadedRoutine.value
        if (routine == null) {
            // No routine loaded - complete the workout
            coordinator._workoutState.value = WorkoutState.Completed
            coordinator._currentSetIndex.value = 0
            coordinator._currentExerciseIndex.value = 0
            repCounter.reset()
            resetAutoStopState()
            return
        }
        val currentExercise = routine.exercises.getOrNull(coordinator._currentExerciseIndex.value) ?: return

        if (coordinator._currentSetIndex.value < currentExercise.setReps.size - 1) {
            coordinator._currentSetIndex.value++
            val targetReps = currentExercise.setReps[coordinator._currentSetIndex.value]
            val currentParams = coordinator._workoutParameters.value

            // Issue #108/#170: Preserve user-adjusted params, otherwise use preset
            val setWeight = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.weightPerCableKg
            } else {
                currentExercise.setWeightsPerCableKg.getOrNull(coordinator._currentSetIndex.value)
                    ?: currentExercise.weightPerCableKg
            }
            val setReps = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.reps
            } else {
                targetReps ?: 0
            }
            coordinator._userAdjustedWeightDuringRest = false // Reset flag after use

            // Issue #203: Fallback to exercise-level isAMRAP flag for legacy ExerciseEditDialog compatibility
            val isLastSet = coordinator._currentSetIndex.value >= currentExercise.setReps.size - 1
            val nextIsAMRAP = targetReps == null || (currentExercise.isAMRAP && isLastSet)

            coordinator._workoutParameters.value = currentParams.copy(
                reps = setReps,
                weightPerCableKg = setWeight,
                isAMRAP = nextIsAMRAP,
                stallDetectionEnabled = currentExercise.stallDetectionEnabled,
                progressionRegressionKg = currentExercise.progressionKg  // Issue #110: Reset to prevent stale values
            )
            Logger.d { "advanceToNextSetInSingleExercise: Issue #203 - setIdx=${coordinator._currentSetIndex.value}, isAMRAP=$nextIsAMRAP" }

            repCounter.resetCountsOnly()
            resetAutoStopState()
            startWorkout(skipCountdown = true)
        } else {
            // All sets complete
            coordinator._workoutState.value = WorkoutState.Completed
            coordinator._loadedRoutine.value = null
            coordinator.routineStartTime = 0  // Issue #195: Reset for next routine
            coordinator._currentSetIndex.value = 0
            coordinator._currentExerciseIndex.value = 0
            repCounter.reset()
            resetAutoStopState()
        }
    }

    /**
     * Start workout or enter SetReady based on autoplay preference.
     * When autoplay is ON, starts the workout immediately.
     * When autoplay is OFF, transitions to SetReady screen for manual control.
     */
    private fun startWorkoutOrSetReady() {
        val autoplay = settingsManager.autoplayEnabled.value
        if (autoplay) {
            // Autoplay ON: start workout immediately
            startWorkout(skipCountdown = true)
        } else {
            // Autoplay OFF: go to SetReady screen for manual control
            enterSetReady(coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
        }
    }

    /**
     * Progress to the next set or exercise in a routine.
     * Issue #156: Refactored to use getNextStep() for unified superset-aware navigation.
     */
    private fun startNextSetOrExercise() {
        val currentState = coordinator._workoutState.value
        if (currentState is WorkoutState.Completed) return
        if (currentState !is WorkoutState.Resting &&
            currentState !is WorkoutState.SetSummary &&
            currentState !is WorkoutState.Active) return

        // Issue #151: Cancel any stale duration timer from previous exercise
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        // Issue #192: Clear timed exercise countdown display
        coordinator._timedExerciseRemainingSeconds.value = null

        val routine = coordinator._loadedRoutine.value ?: return

        // Issue #156: Use getNextStep() for unified navigation logic
        val nextStep = getNextStep(routine, coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)

        Logger.d { "startNextSetOrExercise: current=(${coordinator._currentExerciseIndex.value}, ${coordinator._currentSetIndex.value}), nextStep=$nextStep" }

        if (nextStep != null) {
            val (nextExIdx, nextSetIdx) = nextStep
            val nextExercise = routine.exercises[nextExIdx]

            // Determine if we're changing exercises (for counter reset behavior)
            val isChangingExercise = nextExIdx != coordinator._currentExerciseIndex.value

            // Update indices
            coordinator._currentExerciseIndex.value = nextExIdx
            coordinator._currentSetIndex.value = nextSetIdx

            // Issue #108/#170: Handle user-adjusted parameters during rest
            val nextSetReps = nextExercise.setReps.getOrNull(nextSetIdx)
            val currentParams = coordinator._workoutParameters.value

            val nextSetWeight = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.weightPerCableKg
            } else {
                nextExercise.setWeightsPerCableKg.getOrNull(nextSetIdx)
                    ?: nextExercise.weightPerCableKg
            }
            val nextReps = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.reps
            } else {
                nextSetReps ?: 0
            }
            val nextEchoLevel = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.echoLevel
            } else {
                nextExercise.echoLevel
            }
            val nextEccentricLoad = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.eccentricLoad
            } else {
                nextExercise.eccentricLoad
            }
            coordinator._userAdjustedWeightDuringRest = false // Reset flag after use

            // Only bodyweight exercises should have warmupReps = 0
            val nextIsBodyweight = isBodyweightExercise(nextExercise)

            // Issue #203: Fallback to exercise-level isAMRAP flag
            val isNextSetLastSet = nextSetIdx >= nextExercise.setReps.size - 1
            val nextIsAMRAP = nextSetReps == null || (nextExercise.isAMRAP && isNextSetLastSet)

            coordinator._workoutParameters.value = currentParams.copy(
                weightPerCableKg = nextSetWeight,
                reps = nextReps,
                programMode = nextExercise.programMode,
                echoLevel = nextEchoLevel,
                eccentricLoad = nextEccentricLoad,
                progressionRegressionKg = nextExercise.progressionKg,
                selectedExerciseId = nextExercise.exercise.id,
                isAMRAP = nextIsAMRAP,
                stallDetectionEnabled = nextExercise.stallDetectionEnabled,
                warmupReps = if (nextIsBodyweight) 0 else Constants.DEFAULT_WARMUP_REPS
            )
            Logger.d { "startNextSetOrExercise: Issue #203 - progressionKg=${nextExercise.progressionKg}kg for ${nextExercise.exercise.displayName}, isBodyweight=$nextIsBodyweight, isAMRAP=$nextIsAMRAP" }

            // Use full reset when changing exercises, counts-only reset for same exercise
            if (isChangingExercise) {
                repCounter.reset()
            } else {
                repCounter.resetCountsOnly()
            }
            resetAutoStopState()
            startWorkoutOrSetReady()
        } else {
            // Routine complete
            Logger.d { "startNextSetOrExercise: No more steps - showing routine complete" }
            showRoutineComplete()
            coordinator._workoutState.value = WorkoutState.Idle
            coordinator._currentSetIndex.value = 0
            coordinator._currentExerciseIndex.value = 0
            coordinator.currentRoutineSessionId = null
            coordinator.currentRoutineName = null
            repCounter.reset()
            resetAutoStopState()
        }
    }

    /**
     * Skip the current rest timer and immediately start the next set/exercise.
     */
    fun skipRest() {
        if (coordinator._workoutState.value is WorkoutState.Resting) {
            coordinator.restTimerJob?.cancel()
            coordinator.restTimerJob = null

            Logger.d("DefaultWorkoutSessionManager") { "skipRest: advancing to next set (no BLE stop - already sent at set end)" }

            if (isSingleExerciseMode()) {
                advanceToNextSetInSingleExercise()
            } else {
                startNextSetOrExercise()
            }
        }
    }

    /**
     * Manually trigger starting the next set when autoplay is disabled.
     * Called from UI when user taps "Start Next Set" button.
     */
    fun startNextSet() {
        val state = coordinator._workoutState.value
        if (state is WorkoutState.Resting && state.restSecondsRemaining == 0) {
            Logger.d("DefaultWorkoutSessionManager") { "startNextSet: advancing (no BLE stop - already sent at set end)" }

            if (isSingleExerciseMode()) {
                advanceToNextSetInSingleExercise()
            } else {
                startNextSetOrExercise()
            }
        }
    }

    /**
     * Proceed from set summary to next step.
     * Called when user clicks "Done" button on set summary screen,
     * or when autoplay countdown finishes.
     */
    fun proceedFromSummary() {
        scope.launch {
            val routine = coordinator._loadedRoutine.value
            val autoplay = settingsManager.autoplayEnabled.value

            // Issue #209: If we have a loaded routine, force isJustLift = false
            val isJustLift = if (routine != null) {
                coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(isJustLift = false)
                false
            } else {
                coordinator._workoutParameters.value.isJustLift
            }

            Logger.d { "proceedFromSummary: routine=${routine?.name ?: "NULL"}, isJustLift=$isJustLift, autoplay=$autoplay" }
            Logger.d { "  currentExerciseIndex=${coordinator._currentExerciseIndex.value}, currentSetIndex=${coordinator._currentSetIndex.value}" }

            // Check if routine is complete (for routine mode, not Just Lift)
            if (routine != null && !isJustLift) {
                val currentExercise = routine.exercises.getOrNull(coordinator._currentExerciseIndex.value)
                val isLastSetOfExercise = coordinator._currentSetIndex.value >= (currentExercise?.setReps?.size ?: 1) - 1

                // Mark exercise as completed if this was the last set of THIS exercise
                if (isLastSetOfExercise) {
                    coordinator._completedExercises.value = coordinator._completedExercises.value + coordinator._currentExerciseIndex.value
                }

                // Check if there are ANY more steps using superset-aware navigation
                val nextStep = getNextStep(routine, coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)

                // If no more steps in the entire routine, show completion screen
                if (nextStep == null) {
                    Logger.d { "proceedFromSummary: No more steps - showing routine complete" }
                    showRoutineComplete()
                    return@launch
                }

                // Autoplay OFF: go directly to SetReady for manual control (no rest timer)
                if (!autoplay) {
                    Logger.d { "proceedFromSummary: Autoplay OFF - going to SetReady for next step" }
                    val (nextExIdx, nextSetIdx) = nextStep

                    // Advance to next step
                    coordinator._currentExerciseIndex.value = nextExIdx
                    coordinator._currentSetIndex.value = nextSetIdx

                    // Clear RPE for next set
                    coordinator._currentSetRpe.value = null

                    // Get next exercise and update parameters
                    val nextExercise = routine.exercises[nextExIdx]
                    val nextSetWeight = nextExercise.setWeightsPerCableKg.getOrNull(nextSetIdx)
                        ?: nextExercise.weightPerCableKg
                    val nextSetReps = nextExercise.setReps.getOrNull(nextSetIdx)
                    val isNextSetLastSet = nextSetIdx >= nextExercise.setReps.size - 1
                    val nextIsAMRAP = nextSetReps == null || (nextExercise.isAMRAP && isNextSetLastSet)

                    coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
                        weightPerCableKg = nextSetWeight,
                        reps = nextSetReps ?: 0,
                        programMode = nextExercise.programMode,
                        echoLevel = nextExercise.echoLevel,
                        eccentricLoad = nextExercise.eccentricLoad,
                        progressionRegressionKg = nextExercise.progressionKg,
                        selectedExerciseId = nextExercise.exercise.id,
                        isAMRAP = nextIsAMRAP,
                        stallDetectionEnabled = nextExercise.stallDetectionEnabled
                    )
                    Logger.d { "proceedFromSummary: Issue #203 - Updated params for next set: ${nextExercise.exercise.name}, setIdx=$nextSetIdx, isAMRAP=$nextIsAMRAP" }

                    // Reset counters for next set
                    repCounter.resetCountsOnly()
                    resetAutoStopState()

                    // Navigate to SetReady screen
                    enterSetReady(nextExIdx, nextSetIdx)
                    return@launch
                }
            }

            // Check if there are more sets or exercises remaining (for rest timer logic)
            val hasMoreSets = routine?.let {
                val currentExercise = it.exercises.getOrNull(coordinator._currentExerciseIndex.value)
                val isAMRAPExercise = currentExercise?.isAMRAP == true

                if (isAMRAPExercise) {
                    true // AMRAP always has "more sets" - user decides when to move on
                } else {
                    currentExercise != null && coordinator._currentSetIndex.value < currentExercise.setReps.size - 1
                }
            } ?: false

            val hasMoreExercises = routine?.let {
                coordinator._currentExerciseIndex.value < it.exercises.size - 1
            } ?: false

            // Single Exercise mode (not Just Lift, includes temp routines from SingleExerciseScreen)
            val isSingleExercise = isSingleExerciseMode() && !isJustLift
            // Show rest timer if autoplay ON and more sets/exercises remaining
            val shouldShowRestTimer = (hasMoreSets || hasMoreExercises) && !isJustLift

            Logger.d { "proceedFromSummary: hasMoreSets=$hasMoreSets, hasMoreExercises=$hasMoreExercises" }
            Logger.d { "  isSingleExercise=$isSingleExercise, shouldShowRestTimer=$shouldShowRestTimer" }

            // Clear RPE for next set
            coordinator._currentSetRpe.value = null

            // Show rest timer if there are more sets/exercises (autoplay ON path)
            if (shouldShowRestTimer) {
                Logger.d { "proceedFromSummary: Starting rest timer..." }
                startRestTimer()
            } else {
                Logger.d { "proceedFromSummary: No rest timer - marking as completed/idle" }
                repCounter.reset()
                resetAutoStopState()

                // Auto-reset for Just Lift mode to enable immediate restart
                if (isJustLift) {
                    Logger.d { "Just Lift mode: Auto-resetting to Idle" }
                    resetForNewWorkout()
                    coordinator._workoutState.value = WorkoutState.Idle
                    enableHandleDetection()
                    bleRepository.enableJustLiftWaitingMode()
                    Logger.d { "Just Lift mode: Ready for next exercise" }
                } else {
                    coordinator._workoutState.value = WorkoutState.Completed
                }
            }
        }
    }

    // ===== Cleanup =====

    fun cleanup() {
        coordinator.monitorDataCollectionJob?.cancel()
        coordinator.autoStartJob?.cancel()
        coordinator.restTimerJob?.cancel()
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.repEventsCollectionJob?.cancel()
        coordinator.workoutJob?.cancel()
    }
}
