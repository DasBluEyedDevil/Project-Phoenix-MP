package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.data.sync.CustomExerciseSyncDto
import com.devil.phoenixproject.data.sync.EarnedBadgeSyncDto
import com.devil.phoenixproject.data.sync.GamificationStatsSyncDto
import com.devil.phoenixproject.data.sync.IdMappings
import com.devil.phoenixproject.data.sync.PersonalRecordSyncDto
import com.devil.phoenixproject.data.sync.RoutineSyncDto
import com.devil.phoenixproject.data.sync.WorkoutSessionSyncDto

/**
 * Repository interface for sync operations.
 * Provides methods to get local changes for push and merge remote changes from pull.
 */
interface SyncRepository {

    // === Push Operations (get local changes) ===

    /**
     * Get workout sessions modified since the given timestamp
     */
    suspend fun getSessionsModifiedSince(timestamp: Long): List<WorkoutSessionSyncDto>

    /**
     * Get personal records modified since the given timestamp
     */
    suspend fun getPRsModifiedSince(timestamp: Long): List<PersonalRecordSyncDto>

    /**
     * Get routines modified since the given timestamp
     */
    suspend fun getRoutinesModifiedSince(timestamp: Long): List<RoutineSyncDto>

    /**
     * Get custom exercises modified since the given timestamp
     */
    suspend fun getCustomExercisesModifiedSince(timestamp: Long): List<CustomExerciseSyncDto>

    /**
     * Get earned badges modified since the given timestamp
     */
    suspend fun getBadgesModifiedSince(timestamp: Long): List<EarnedBadgeSyncDto>

    /**
     * Get current gamification stats for sync
     */
    suspend fun getGamificationStatsForSync(): GamificationStatsSyncDto?

    // === ID Mapping (after push) ===

    /**
     * Update server IDs after successful push
     */
    suspend fun updateServerIds(mappings: IdMappings)

    // === Pull Operations (merge remote changes) ===

    /**
     * Merge sessions from server (upsert with conflict resolution)
     */
    suspend fun mergeSessions(sessions: List<WorkoutSessionSyncDto>)

    /**
     * Merge personal records from server
     */
    suspend fun mergePRs(records: List<PersonalRecordSyncDto>)

    /**
     * Merge routines from server
     */
    suspend fun mergeRoutines(routines: List<RoutineSyncDto>)

    /**
     * Merge custom exercises from server
     */
    suspend fun mergeCustomExercises(exercises: List<CustomExerciseSyncDto>)

    /**
     * Merge badges from server
     */
    suspend fun mergeBadges(badges: List<EarnedBadgeSyncDto>)

    /**
     * Merge gamification stats from server
     */
    suspend fun mergeGamificationStats(stats: GamificationStatsSyncDto?)
}
