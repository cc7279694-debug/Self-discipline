package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.guanyi.mirra.data.local.entity.FocusEventEntity
import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.RiskAppEntity
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionRiskAppSnapshotEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import kotlinx.coroutines.flow.Flow

data class PackageConfirmationCount(val packageName: String, val confirmations: Int)

@Dao
interface FocusDao {
    @Query("""UPDATE session_focus_contexts SET closeoutState = 'PENDING',
        requestedEndPage = :requestedEndPage, closeoutStartedAt = :at, updatedAt = :at
        WHERE sessionId = :sessionId AND closeoutState = 'ACTIVE'""")
    suspend fun markCloseoutPending(sessionId: String, requestedEndPage: Int, at: Long): Int

    @Query("""UPDATE session_focus_contexts SET closeoutState = 'COMPLETED', updatedAt = :at
        WHERE sessionId = :sessionId AND closeoutState = 'PENDING'""")
    suspend fun markCloseoutCompleted(sessionId: String, at: Long): Int

    @Query("""UPDATE session_segments SET plannedEndAt = plannedEndAt + :extensionMillis,
        extensionCount = extensionCount + 1 WHERE id = :id AND sessionId = :sessionId
        AND activeSlot = 1 AND type = 'TEMPORARY_ALLOWANCE' AND extensionCount = 0
        AND plannedEndAt > :at""")
    suspend fun extendAllowance(id: String, sessionId: String, at: Long, extensionMillis: Long): Int

    @Query("SELECT packageName, COUNT(*) AS confirmations FROM focus_events WHERE sessionId = :sessionId AND packageName IS NOT NULL AND type = 'RISK_APP_CONFIRMED' GROUP BY packageName")
    suspend fun riskConfirmationCounts(sessionId: String): List<PackageConfirmationCount>

    @Query("SELECT * FROM session_segments WHERE id = :id AND sessionId = :sessionId LIMIT 1")
    suspend fun getSegment(sessionId: String, id: String): SessionSegmentEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM focus_events WHERE id = :id AND sessionId = :sessionId)")
    suspend fun eventExists(sessionId: String, id: String): Boolean

    @Query("SELECT * FROM focus_events WHERE sessionId = :sessionId AND type = 'RISK_APP_CONFIRMED' ORDER BY occurredAt DESC, id DESC LIMIT 1")
    suspend fun latestRiskConfirmation(sessionId: String): FocusEventEntity?

    @Query("SELECT MAX(occurredAt) FROM focus_events WHERE sessionId = :sessionId")
    suspend fun latestEventOccurredAt(sessionId: String): Long?

    @Query("""UPDATE session_focus_contexts SET priorDndInterruptionFilter = :prior,
        dndRuleId = :ruleId, dndLifecycle = 'NOT_APPLIED', dndAccessAtStart = 1, updatedAt = :at
        WHERE sessionId = :sessionId""")
    suspend fun prepareDnd(sessionId: String, prior: Int?, ruleId: String?, at: Long): Int

    @Query("UPDATE session_focus_contexts SET dndLifecycle = :lifecycle, updatedAt = :at WHERE sessionId = :sessionId")
    suspend fun setDndLifecycle(sessionId: String, lifecycle: DndLifecycle, at: Long): Int

    @Query("""SELECT c.* FROM session_focus_contexts c JOIN study_sessions s ON s.id = c.sessionId
        WHERE (s.activeSlot IS NULL OR c.closeoutState = 'PENDING') AND (c.dndRuleId IS NOT NULL OR c.priorDndInterruptionFilter IS NOT NULL)
          AND c.dndLifecycle != 'RELEASED'""")
    suspend fun listDndRecoveryContexts(): List<SessionFocusContextEntity>
    @Insert suspend fun insertContext(context: SessionFocusContextEntity)
    @Insert suspend fun insertSegment(segment: SessionSegmentEntity)
    @Insert suspend fun insertEvent(event: FocusEventEntity)
    @Insert suspend fun insertRiskSnapshots(snapshots: List<SessionRiskAppSnapshotEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRiskApp(app: RiskAppEntity)

    @Query("DELETE FROM risk_apps WHERE packageName = :packageName")
    suspend fun deleteRiskApp(packageName: String): Int

    @Query("SELECT * FROM risk_apps ORDER BY labelSnapshot COLLATE NOCASE, packageName")
    fun observeRiskApps(): Flow<List<RiskAppEntity>>

    @Query("SELECT * FROM risk_apps ORDER BY packageName")
    suspend fun listRiskApps(): List<RiskAppEntity>

    @Query("SELECT packageName FROM session_risk_app_snapshots WHERE sessionId = :sessionId")
    suspend fun listSessionRiskPackages(sessionId: String): List<String>

    @Query("SELECT * FROM session_focus_contexts WHERE sessionId = :sessionId")
    suspend fun getContext(sessionId: String): SessionFocusContextEntity?

    @Query("SELECT * FROM session_focus_contexts WHERE sessionId = :sessionId")
    fun observeContext(sessionId: String): Flow<SessionFocusContextEntity?>

    @Query("SELECT * FROM session_segments WHERE sessionId = :sessionId AND activeSlot = 1 LIMIT 1")
    suspend fun getActiveSegment(sessionId: String): SessionSegmentEntity?

    @Query("SELECT * FROM session_segments WHERE sessionId = :sessionId AND activeSlot = 1 LIMIT 1")
    fun observeActiveSegment(sessionId: String): Flow<SessionSegmentEntity?>

    @Query("SELECT * FROM session_segments WHERE sessionId = :sessionId ORDER BY startedAt, id")
    suspend fun listSegments(sessionId: String): List<SessionSegmentEntity>

    @Query("SELECT * FROM session_segments WHERE sessionId = :sessionId ORDER BY startedAt, id")
    fun observeSegments(sessionId: String): Flow<List<SessionSegmentEntity>>

    @Query("SELECT * FROM session_focus_contexts WHERE sessionId IN (:sessionIds)")
    fun observeContextsForSessions(sessionIds: List<String>): Flow<List<SessionFocusContextEntity>>

    @Query("SELECT * FROM session_segments WHERE sessionId IN (:sessionIds) ORDER BY sessionId, startedAt, id")
    fun observeSegmentsForSessions(sessionIds: List<String>): Flow<List<SessionSegmentEntity>>

    @Query("SELECT * FROM focus_events WHERE sessionId = :sessionId ORDER BY occurredAt, id")
    suspend fun listEvents(sessionId: String): List<FocusEventEntity>

    @Query("SELECT COUNT(*) FROM focus_events WHERE sessionId = :sessionId AND type = :type AND packageName = :packageName AND occurredAt = :at")
    suspend fun countMatchingEvent(sessionId: String, type: com.guanyi.mirra.data.local.entity.FocusEventType,
        packageName: String, at: Long): Int

    @Query("""
        UPDATE session_segments SET endedAt = :endedAt, activeSlot = NULL
        WHERE id = :id AND sessionId = :sessionId AND activeSlot = 1
          AND startedAt < :endedAt
    """)
    suspend fun closeActiveSegment(id: String, sessionId: String, endedAt: Long): Int

    @Query("""
        UPDATE session_segments SET type = :type, endedAt = :endedAt, activeSlot = NULL
        WHERE id = :id AND sessionId = :sessionId AND activeSlot = 1
          AND startedAt < :endedAt
    """)
    suspend fun replaceAndCloseActiveSegment(
        id: String,
        sessionId: String,
        type: SessionSegmentType,
        endedAt: Long,
    ): Int

    @Query("""
        UPDATE session_segments SET type = :type, packageName = NULL, reason = NULL,
            plannedEndAt = NULL, relatedSegmentId = NULL
        WHERE id = :id AND sessionId = :sessionId AND activeSlot = 1
    """)
    suspend fun changeActiveSegmentType(id: String, sessionId: String, type: SessionSegmentType): Int

    @Query("""UPDATE session_segments SET type = :type, packageName = :packageName,
        reason = NULL, plannedEndAt = NULL, extensionCount = 0, relatedSegmentId = NULL
        WHERE id = :id AND sessionId = :sessionId AND activeSlot = 1""")
    suspend fun changeActiveSegmentToRisk(id: String, sessionId: String, type: SessionSegmentType,
        packageName: String): Int

    @Query("DELETE FROM session_segments WHERE id = :id AND sessionId = :sessionId AND activeSlot = 1")
    suspend fun deleteActiveSegment(id: String, sessionId: String): Int

    @Query("""
        UPDATE session_focus_contexts
        SET monitoringStatus = :coverage,
            monitoringLostAt = CASE WHEN monitoringLostAt IS NULL THEN :lostAt ELSE monitoringLostAt END,
            updatedAt = :updatedAt
        WHERE sessionId = :sessionId
    """)
    suspend fun setCoverage(
        sessionId: String,
        coverage: MonitoringCoverage,
        lostAt: Long?,
        updatedAt: Long,
    ): Int

    @Query("""
        UPDATE session_focus_contexts SET lastHeartbeatAt = :at, updatedAt = :at
        WHERE sessionId = :sessionId AND lastHeartbeatAt <= :at
    """)
    suspend fun updateHeartbeat(sessionId: String, at: Long): Int

    @Query("""
        UPDATE study_sessions SET stableStartedAt = :at
        WHERE id = :sessionId AND activeSlot = 1 AND stableStartedAt IS NULL
          AND startedAt <= :at
    """)
    suspend fun markStableStarted(sessionId: String, at: Long): Int
}
