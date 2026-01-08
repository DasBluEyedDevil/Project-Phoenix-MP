package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.VolumeDataPoint
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlinx.coroutines.flow.Flow

/**
 * Personal record entity - stub for database
 */
data class PersonalRecordEntity(
    val id: Long = 0,
    val exerciseId: String,
    val weightPerCableKg: Float,
    val reps: Int,
    val timestamp: Long,
    val workoutMode: String
)

/**
 * Workout Repository interface.
 * Implemented by SqlDelightWorkoutRepository for type-safe database operations.
 */
interface WorkoutRepository {
    // Workout sessions
    fun getAllSessions(): Flow<List<WorkoutSession>>
    suspend fun saveSession(session: WorkoutSession)
    suspend fun deleteSession(sessionId: String)
    suspend fun deleteAllSessions()

    /**
     * Get recent workout sessions
     * @param limit Maximum number of sessions to return
     */
    fun getRecentSessions(limit: Int = 10): Flow<List<WorkoutSession>>

    /**
     * Get a specific workout session by ID
     */
    suspend fun getSession(sessionId: String): WorkoutSession?

    // Routines
    fun getAllRoutines(): Flow<List<Routine>>
    suspend fun saveRoutine(routine: Routine)
    suspend fun updateRoutine(routine: Routine)
    suspend fun deleteRoutine(routineId: String)
    suspend fun getRoutineById(routineId: String): Routine?

    /**
     * Mark routine as used (updates lastUsed and increments useCount)
     */
    suspend fun markRoutineUsed(routineId: String)

    // Personal records
    fun getAllPersonalRecords(): Flow<List<PersonalRecordEntity>>
    suspend fun updatePRIfBetter(exerciseId: String, weightKg: Float, reps: Int, mode: String)

    // Metrics storage
    suspend fun saveMetrics(sessionId: String, metrics: List<com.devil.phoenixproject.domain.model.WorkoutMetric>)

    /**
     * Get metrics for a workout session
     */
    fun getMetricsForSession(sessionId: String): Flow<List<com.devil.phoenixproject.domain.model.WorkoutMetric>>

    /**
     * Get metrics for a workout session synchronously (for export)
     */
    suspend fun getMetricsForSessionSync(sessionId: String): List<com.devil.phoenixproject.domain.model.WorkoutMetric>

    /**
     * Get recent workout sessions synchronously (for export)
     */
    suspend fun getRecentSessionsSync(limit: Int = 10): List<WorkoutSession>

    // Phase Statistics (heuristic data from machine)
    /**
     * Save phase statistics for a workout session
     */
    suspend fun savePhaseStatistics(sessionId: String, stats: com.devil.phoenixproject.domain.model.HeuristicStatistics)

    /**
     * Get all phase statistics
     */
    fun getAllPhaseStatistics(): Flow<List<PhaseStatisticsData>>

    // Volume aggregation for analytics
    /**
     * Get weekly volume aggregation data
     * @param limit Maximum number of weeks to return (default 12)
     */
    suspend fun getWeeklyVolume(limit: Int = 12): List<VolumeDataPoint>

    /**
     * Get monthly volume aggregation data
     * @param limit Maximum number of months to return (default 12)
     */
    suspend fun getMonthlyVolume(limit: Int = 12): List<VolumeDataPoint>

    /**
     * Get yearly volume aggregation data
     * @param limit Maximum number of years to return (default 5)
     */
    suspend fun getYearlyVolume(limit: Int = 5): List<VolumeDataPoint>
}

/**
 * Phase statistics data class for repository layer
 */
data class PhaseStatisticsData(
    val id: Long = 0,
    val sessionId: String,
    val concentricKgAvg: Float,
    val concentricKgMax: Float,
    val concentricVelAvg: Float,
    val concentricVelMax: Float,
    val concentricWattAvg: Float,
    val concentricWattMax: Float,
    val eccentricKgAvg: Float,
    val eccentricKgMax: Float,
    val eccentricVelAvg: Float,
    val eccentricVelMax: Float,
    val eccentricWattAvg: Float,
    val eccentricWattMax: Float,
    val timestamp: Long
)

