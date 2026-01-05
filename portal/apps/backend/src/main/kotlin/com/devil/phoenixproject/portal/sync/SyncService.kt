package com.devil.phoenixproject.portal.sync

import com.devil.phoenixproject.portal.db.*
import com.devil.phoenixproject.portal.models.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class SyncService {

    fun getStatus(userId: UUID): SyncStatusResponse = transaction {
        val user = Users.select { Users.id eq userId }.singleOrNull()
            ?: throw IllegalStateException("User not found")

        val lastSync = user[Users.lastSyncAt]?.toEpochMilliseconds()
        val subscriptionStatus = user[Users.subscriptionStatus]
        val subscriptionExpires = user[Users.subscriptionExpiresAt]?.toString()

        // Count pending changes (simplified - just count total records)
        val pendingChanges = 0 // TODO: implement actual pending count

        SyncStatusResponse(
            lastSync = lastSync,
            pendingChanges = pendingChanges,
            subscriptionStatus = subscriptionStatus,
            subscriptionExpiresAt = subscriptionExpires
        )
    }

    fun push(userId: UUID, request: SyncPushRequest): SyncPushResponse = transaction {
        val now = Clock.System.now()
        val deviceUuid = UUID.fromString(request.deviceId)

        // Register/update device
        registerDevice(userId, deviceUuid, request.deviceName, request.platform, now)

        val idMappings = IdMappings(
            sessions = pushSessions(userId, deviceUuid, request.sessions, now),
            records = pushRecords(userId, deviceUuid, request.records, now),
            phaseStats = pushPhaseStats(userId, deviceUuid, request.phaseStats, now),
            routines = pushRoutines(userId, deviceUuid, request.routines, now),
            supersets = pushSupersets(userId, deviceUuid, request.supersets, now),
            routineExercises = pushRoutineExercises(userId, deviceUuid, request.routineExercises, now),
            exercises = pushExercises(userId, deviceUuid, request.exercises, now),
            badges = pushBadges(userId, deviceUuid, request.badges, now)
        )

        // Update gamification stats if provided
        request.gamificationStats?.let { pushGamificationStats(userId, deviceUuid, it, now) }

        // Update user's last sync time
        Users.update({ Users.id eq userId }) {
            it[lastSyncAt] = now
        }

        SyncPushResponse(
            syncTime = now.toEpochMilliseconds(),
            idMappings = idMappings
        )
    }

    fun pull(userId: UUID, request: SyncPullRequest): SyncPullResponse = transaction {
        val since = Instant.fromEpochMilliseconds(request.lastSync)
        val now = Clock.System.now()
        val deviceUuid = UUID.fromString(request.deviceId)

        SyncPullResponse(
            syncTime = now.toEpochMilliseconds(),
            sessions = pullSessions(userId, deviceUuid, since),
            records = pullRecords(userId, deviceUuid, since),
            phaseStats = pullPhaseStats(userId, deviceUuid, since),
            routines = pullRoutines(userId, deviceUuid, since),
            routineExercises = pullRoutineExercises(userId, deviceUuid, since),
            supersets = pullSupersets(userId, deviceUuid, since),
            exercises = pullExercises(userId, deviceUuid, since),
            badges = pullBadges(userId, deviceUuid, since),
            gamificationStats = pullGamificationStats(userId)
        )
    }

    // === Private helper functions ===

    private fun registerDevice(userId: UUID, deviceId: UUID, name: String?, platform: String, now: Instant) {
        val existing = SyncDevices.select { SyncDevices.deviceId eq deviceId }.singleOrNull()
        if (existing == null) {
            SyncDevices.insert {
                it[SyncDevices.userId] = userId
                it[SyncDevices.deviceId] = deviceId
                it[deviceName] = name
                it[SyncDevices.platform] = platform
                it[lastSyncAt] = now
                it[createdAt] = now
            }
        } else {
            SyncDevices.update({ SyncDevices.deviceId eq deviceId }) {
                it[lastSyncAt] = now
                if (name != null) it[deviceName] = name
            }
        }
    }

    private fun pushSessions(userId: UUID, deviceId: UUID, sessions: List<WorkoutSessionDto>, now: Instant): Map<String, String> {
        val mappings = mutableMapOf<String, String>()

        for (session in sessions) {
            val clientUuid = UUID.fromString(session.clientId)
            val existing = WorkoutSessions.select { WorkoutSessions.clientId eq clientUuid }.singleOrNull()

            if (existing == null) {
                val serverId = UUID.randomUUID()
                WorkoutSessions.insert {
                    it[id] = serverId
                    it[WorkoutSessions.userId] = userId
                    it[WorkoutSessions.clientId] = clientUuid
                    it[WorkoutSessions.deviceId] = deviceId
                    it[timestamp] = session.timestamp
                    it[mode] = session.mode
                    it[targetReps] = session.targetReps
                    it[weightPerCableKg] = session.weightPerCableKg
                    it[progressionKg] = session.progressionKg
                    it[duration] = session.duration
                    it[totalReps] = session.totalReps
                    it[warmupReps] = session.warmupReps
                    it[workingReps] = session.workingReps
                    it[isJustLift] = session.isJustLift
                    it[stopAtTop] = session.stopAtTop
                    it[eccentricLoad] = session.eccentricLoad
                    it[echoLevel] = session.echoLevel
                    it[exerciseId] = session.exerciseId
                    it[exerciseName] = session.exerciseName
                    it[routineSessionId] = session.routineSessionId
                    it[routineName] = session.routineName
                    it[safetyFlags] = session.safetyFlags
                    it[deloadWarningCount] = session.deloadWarningCount
                    it[romViolationCount] = session.romViolationCount
                    it[spotterActivations] = session.spotterActivations
                    it[peakForceConcentricA] = session.peakForceConcentricA
                    it[peakForceConcentricB] = session.peakForceConcentricB
                    it[peakForceEccentricA] = session.peakForceEccentricA
                    it[peakForceEccentricB] = session.peakForceEccentricB
                    it[avgForceConcentricA] = session.avgForceConcentricA
                    it[avgForceConcentricB] = session.avgForceConcentricB
                    it[avgForceEccentricA] = session.avgForceEccentricA
                    it[avgForceEccentricB] = session.avgForceEccentricB
                    it[heaviestLiftKg] = session.heaviestLiftKg
                    it[totalVolumeKg] = session.totalVolumeKg
                    it[estimatedCalories] = session.estimatedCalories
                    it[warmupAvgWeightKg] = session.warmupAvgWeightKg
                    it[workingAvgWeightKg] = session.workingAvgWeightKg
                    it[burnoutAvgWeightKg] = session.burnoutAvgWeightKg
                    it[peakWeightKg] = session.peakWeightKg
                    it[rpe] = session.rpe
                    it[deletedAt] = session.deletedAt?.let { ts -> Instant.fromEpochMilliseconds(ts) }
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                mappings[session.clientId] = serverId.toString()
            } else {
                // Update existing - only if updatedAt is newer
                val existingUpdatedAt = existing[WorkoutSessions.updatedAt]
                val incomingUpdatedAt = Instant.fromEpochMilliseconds(session.updatedAt)

                if (incomingUpdatedAt > existingUpdatedAt) {
                    WorkoutSessions.update({ WorkoutSessions.clientId eq clientUuid }) {
                        it[deletedAt] = session.deletedAt?.let { ts -> Instant.fromEpochMilliseconds(ts) }
                        it[updatedAt] = now
                    }
                }
                mappings[session.clientId] = existing[WorkoutSessions.id].toString()
            }
        }

        return mappings
    }

    // Placeholder implementations for other entity types
    private fun pushRecords(userId: UUID, deviceId: UUID, records: List<PersonalRecordDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushPhaseStats(userId: UUID, deviceId: UUID, stats: List<PhaseStatisticsDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushRoutines(userId: UUID, deviceId: UUID, routines: List<RoutineDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushSupersets(userId: UUID, deviceId: UUID, supersets: List<SupersetDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushRoutineExercises(userId: UUID, deviceId: UUID, exercises: List<RoutineExerciseDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushExercises(userId: UUID, deviceId: UUID, exercises: List<CustomExerciseDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushBadges(userId: UUID, deviceId: UUID, badges: List<EarnedBadgeDto>, now: Instant): Map<String, String> = emptyMap() // TODO
    private fun pushGamificationStats(userId: UUID, deviceId: UUID, stats: GamificationStatsDto, now: Instant) {} // TODO

    private fun pullSessions(userId: UUID, deviceId: UUID, since: Instant): List<WorkoutSessionDto> = emptyList() // TODO
    private fun pullRecords(userId: UUID, deviceId: UUID, since: Instant): List<PersonalRecordDto> = emptyList() // TODO
    private fun pullPhaseStats(userId: UUID, deviceId: UUID, since: Instant): List<PhaseStatisticsDto> = emptyList() // TODO
    private fun pullRoutines(userId: UUID, deviceId: UUID, since: Instant): List<RoutineDto> = emptyList() // TODO
    private fun pullRoutineExercises(userId: UUID, deviceId: UUID, since: Instant): List<RoutineExerciseDto> = emptyList() // TODO
    private fun pullSupersets(userId: UUID, deviceId: UUID, since: Instant): List<SupersetDto> = emptyList() // TODO
    private fun pullExercises(userId: UUID, deviceId: UUID, since: Instant): List<CustomExerciseDto> = emptyList() // TODO
    private fun pullBadges(userId: UUID, deviceId: UUID, since: Instant): List<EarnedBadgeDto> = emptyList() // TODO
    private fun pullGamificationStats(userId: UUID): GamificationStatsDto? = null // TODO
}
