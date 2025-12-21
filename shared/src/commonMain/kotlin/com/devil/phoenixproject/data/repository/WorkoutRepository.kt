package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

/**
 * Stub Workout Repository for compilation
 */
class StubWorkoutRepository : WorkoutRepository {
    override fun getAllSessions() = flowOf(emptyList<WorkoutSession>())
    override suspend fun saveSession(session: WorkoutSession) {}
    override suspend fun deleteSession(sessionId: String) {}
    override suspend fun deleteAllSessions() {}
    override fun getRecentSessions(limit: Int) = flowOf(emptyList<WorkoutSession>())
    override suspend fun getSession(sessionId: String): WorkoutSession? = null

    override fun getAllRoutines() = flowOf(emptyList<Routine>())
    override suspend fun saveRoutine(routine: Routine) {}
    override suspend fun updateRoutine(routine: Routine) {}
    override suspend fun deleteRoutine(routineId: String) {}
    override suspend fun getRoutineById(routineId: String): Routine? = null
    override suspend fun markRoutineUsed(routineId: String) {}

    override fun getAllPersonalRecords() = flowOf(emptyList<PersonalRecordEntity>())
    override suspend fun updatePRIfBetter(exerciseId: String, weightKg: Float, reps: Int, mode: String) {}
    override suspend fun saveMetrics(sessionId: String, metrics: List<com.devil.phoenixproject.domain.model.WorkoutMetric>) {}
    override fun getMetricsForSession(sessionId: String) = flowOf(emptyList<com.devil.phoenixproject.domain.model.WorkoutMetric>())
    override suspend fun getMetricsForSessionSync(sessionId: String) = emptyList<com.devil.phoenixproject.domain.model.WorkoutMetric>()
    override suspend fun getRecentSessionsSync(limit: Int) = emptyList<WorkoutSession>()
    override suspend fun savePhaseStatistics(sessionId: String, stats: com.devil.phoenixproject.domain.model.HeuristicStatistics) {}
    override fun getAllPhaseStatistics() = flowOf(emptyList<PhaseStatisticsData>())
}
