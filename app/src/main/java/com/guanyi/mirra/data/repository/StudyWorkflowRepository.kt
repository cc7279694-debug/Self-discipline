package com.guanyi.mirra.data.repository

import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionRiskAppSnapshotEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.model.RecentReadingSnapshot
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.SummaryEngine
import com.guanyi.mirra.data.search.SearchIndexWriter
import com.guanyi.mirra.domain.DefaultSearchEngine
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.model.CloseoutSnapshot
import com.guanyi.mirra.domain.monitoring.BackwardClockCloseoutEvidence

data class MonitoredSessionStartResult(val session: StudySessionEntity, val created: Boolean)

interface StudyWorkflowRepository {
    fun observeActiveIntent(): Flow<StudyIntentEntity?>
    fun observeActiveSession(): Flow<StudySessionEntity?>
    fun observeSession(id: String): Flow<StudySessionEntity?>
    fun observeLatestSummaryForItem(learningItemId: String): Flow<StudySessionEntity?>
    fun observeLatestNormalReading(learningItemId: String): Flow<RecentReadingSnapshot?>
    suspend fun createIntent(learningItemId: String, setAsMainline: Boolean = false): StudyIntentEntity
    suspend fun markTransitioned(intentId: String)
    suspend fun abandonIntent(intentId: String)
    suspend fun startSession(intentId: String, startPage: Int): StudySessionEntity
    suspend fun startMonitoredSession(
        intentId: String, startPage: Int, readyLease: MonitoringReadyLease, proposedSessionId: String,
    ): MonitoredSessionStartResult
    suspend fun findActiveSessionForIntent(intentId: String): StudySessionEntity?
    suspend fun updateCurrentPage(sessionId: String, page: Int)
    suspend fun finishSession(sessionId: String, endPage: Int): StudySessionEntity
    suspend fun beginCloseout(sessionId: String, requestedEndPage: Int, closeoutStartedAt: Long,
        backwardClockEvidence: BackwardClockCloseoutEvidence? = null): CloseoutSnapshot
    suspend fun completeCloseout(sessionId: String): StudySessionEntity
    suspend fun getCloseoutState(sessionId: String): FocusCloseoutState?
    fun observeCloseoutState(sessionId: String): Flow<FocusCloseoutState?>
    suspend fun getCloseoutSnapshot(sessionId: String): CloseoutSnapshot?
    suspend fun recoverInterruptedSession()
}

class DefaultStudyWorkflowRepository(
    private val database: MirraDatabase,
    private val summaryEngine: SummaryEngine,
    private val expiryPolicy: IntentExpiryPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val searchIndexWriter: SearchIndexWriter = SearchIndexWriter(database, DefaultSearchEngine()),
) : StudyWorkflowRepository {
    private val intentDao = database.intentDao()
    private val sessionDao = database.sessionDao()
    private val itemDao = database.learningItemDao()
    private val noteDao = database.noteDao()
    private val focusDao = database.focusDao()

    override suspend fun beginCloseout(sessionId: String, requestedEndPage: Int, closeoutStartedAt: Long,
        backwardClockEvidence: BackwardClockCloseoutEvidence?): CloseoutSnapshot = database.withTransaction {
        val session = checkNotNull(sessionDao.get(sessionId)) { "Session 不存在" }
        val context = checkNotNull(focusDao.getContext(sessionId)) { "Focus Context 不存在" }
        if (context.closeoutState in setOf(FocusCloseoutState.PENDING, FocusCloseoutState.COMPLETED)) {
            return@withTransaction checkNotNull(snapshot(context))
        }
        check(context.closeoutState == FocusCloseoutState.ACTIVE && session.activeSlot == ACTIVE_SLOT &&
            session.endedAt == null && session.endType == null) { "Session 已结束" }
        val item = checkNotNull(itemDao.get(session.learningItemId)) { "Learning Item 不存在" }
        validateEndPage(requestedEndPage, session.currentPage, item.totalPages)
        val active = checkNotNull(focusDao.getActiveSegment(sessionId)) { "Active Segment 不存在" }
        val closedBoundary = focusDao.listSegments(sessionId).filter { it.activeSlot == null }
            .map { checkNotNull(it.endedAt) { "已闭合 Segment 缺少结束时间" } }.maxOrNull()
        check(closedBoundary == null || closedBoundary <= active.startedAt) { "Segment 时间线重叠" }
        val latestBoundary = maxOf(session.startedAt, active.startedAt, closedBoundary ?: session.startedAt)
        val boundary = if (closeoutStartedAt >= latestBoundary) closeoutStartedAt else {
            val proof = backwardClockEvidence
            require(proof != null && proof.sessionId == sessionId &&
                proof.regressionSample.elapsedNowMillis >= proof.lastTrustedSample.elapsedNowMillis &&
                proof.regressionSample.wallNowMillis < proof.lastTrustedSample.wallNowMillis &&
                context.monitoringStatus == MonitoringCoverage.PARTIAL &&
                context.monitoringLostAt == active.startedAt && active.startedAt == proof.durableLossBoundary &&
                active.type == SessionSegmentType.UNMONITORED && active.id == proof.unmonitoredSegmentId &&
                proof.durableLossBoundary > closeoutStartedAt) { "结束时间早于已保存的时间线" }
            maxOf(closeoutStartedAt, latestBoundary)
        }
        when {
            boundary > active.startedAt -> check(focusDao.closeActiveSegment(active.id, sessionId, boundary) == 1)
            boundary == active.startedAt -> check(focusDao.deleteActiveSegment(active.id, sessionId) == 1)
            else -> error("结束边界非法")
        }
        check(focusDao.markCloseoutPending(sessionId, requestedEndPage, boundary) == 1) { "保存结束决定失败" }
        CloseoutSnapshot(sessionId, boundary, requestedEndPage)
    }

    override suspend fun completeCloseout(sessionId: String): StudySessionEntity = database.withTransaction {
        val session = checkNotNull(sessionDao.get(sessionId)) { "Session 不存在" }
        val context = checkNotNull(focusDao.getContext(sessionId)) { "Focus Context 不存在" }
        if (context.closeoutState == FocusCloseoutState.COMPLETED) {
            check(session.endType == SessionEndType.NORMAL && session.endedAt != null && session.activeSlot == null)
            return@withTransaction session
        }
        check(context.closeoutState == FocusCloseoutState.PENDING && session.activeSlot == ACTIVE_SLOT &&
            session.endedAt == null && session.endType == null) { "没有待完成的结束决定" }
        val frozen = checkNotNull(snapshot(context))
        val item = checkNotNull(itemDao.get(session.learningItemId)) { "Learning Item 不存在" }
        validateEndPage(frozen.requestedEndPage, session.currentPage, item.totalPages)
        check(frozen.closeoutStartedAt >= session.startedAt && focusDao.getActiveSegment(sessionId) == null)
        val summary = summaryEngine.create(session.startPage, frozen.requestedEndPage,
            frozen.closeoutStartedAt - session.startedAt, noteDao.countForSession(sessionId))
        check(sessionDao.finish(sessionId, frozen.closeoutStartedAt, frozen.requestedEndPage, SessionEndType.NORMAL, summary) == 1)
        check(itemDao.advanceProgress(item.id, frozen.requestedEndPage, frozen.closeoutStartedAt) == 1)
        searchIndexWriter.reindexSession(sessionId)
        check(focusDao.markCloseoutCompleted(sessionId, frozen.closeoutStartedAt) == 1)
        checkNotNull(sessionDao.get(sessionId))
    }

    override suspend fun getCloseoutState(sessionId: String) = focusDao.getContext(sessionId)?.closeoutState
    override fun observeCloseoutState(sessionId: String) = focusDao.observeContext(sessionId).map { it?.closeoutState }
    override suspend fun getCloseoutSnapshot(sessionId: String) = database.withTransaction {
        focusDao.getContext(sessionId)?.let(::snapshot)
    }

    private fun snapshot(context: SessionFocusContextEntity): CloseoutSnapshot? {
        if (context.closeoutState !in setOf(FocusCloseoutState.PENDING, FocusCloseoutState.COMPLETED)) return null
        return CloseoutSnapshot(context.sessionId,
            checkNotNull(context.closeoutStartedAt) { "结束记录缺少时间" },
            checkNotNull(context.requestedEndPage) { "结束记录缺少页码" })
    }

    private fun validateEndPage(page: Int, currentPage: Int, totalPages: Int) {
        require(page in 1..totalPages) { "结束页必须在书籍范围内" }
        require(page >= currentPage) { "不能低于已经记录的阅读位置" }
    }

    override fun observeActiveIntent() = intentDao.observeActive()
    override fun observeActiveSession() = sessionDao.observeActive()
    override fun observeSession(id: String) = sessionDao.observe(id)
    override fun observeLatestSummaryForItem(learningItemId: String) = sessionDao.observeLatestSummaryForItem(learningItemId)
    override fun observeLatestNormalReading(learningItemId: String) = sessionDao.observeLatestNormalReading(learningItemId)
    override suspend fun findActiveSessionForIntent(intentId: String) = sessionDao.getActiveForIntent(intentId)

    override suspend fun createIntent(
        learningItemId: String,
        setAsMainline: Boolean,
    ): StudyIntentEntity = database.withTransaction {
        val initialItem = checkNotNull(itemDao.get(learningItemId)) { "Learning Item 不存在" }
        check(initialItem.status == LearningItemStatus.IN_PROGRESS) {
            "只有进行中的内容可以开始"
        }
        check(sessionDao.getActive() == null) { "请先结束当前 Session" }
        val now = clock()
        val active = intentDao.getActive()
        if (active != null && expiryPolicy.isExpired(active.createdAt, now)) {
            intentDao.markTimedOut(active.id, now)
        } else if (active != null) {
            return@withTransaction active
        }
        val itemBeforeInsert = checkNotNull(itemDao.get(learningItemId)) { "Learning Item 不存在" }
        check(itemBeforeInsert.status == LearningItemStatus.IN_PROGRESS) {
            "只有进行中的内容可以开始"
        }
        if (setAsMainline) {
            itemDao.clearMainline(now)
            check(itemDao.assignMainline(learningItemId, now) == 1) { "设置主线失败" }
        }
        StudyIntentEntity(
            id = newId(),
            learningItemId = learningItemId,
            createdAt = now,
            transitionedAt = null,
            convertedAt = null,
            endedAt = null,
            outcome = null,
            activeSlot = ACTIVE_SLOT,
        ).also { intentDao.insert(it) }
    }

    override suspend fun markTransitioned(intentId: String) {
        check(intentDao.markTransitioned(intentId, clock()) == 1) { "Intent 已结束或不存在" }
    }

    override suspend fun abandonIntent(intentId: String) {
        database.withTransaction {
            val intent = checkNotNull(intentDao.get(intentId)) { "Intent 不存在" }
            if (intent.outcome == IntentOutcome.ABANDONED && intent.activeSlot == null) {
                return@withTransaction
            }
            check(intent.outcome == null && intent.activeSlot == ACTIVE_SLOT) { "Intent 已结束" }
            check(intentDao.markAbandoned(intentId, clock()) == 1) { "放弃 Intent 失败" }
        }
    }

    override suspend fun startSession(intentId: String, startPage: Int): StudySessionEntity =
        startSessionInternal(intentId, startPage, readyLease = null, proposedSessionId = null).session

    override suspend fun startMonitoredSession(
        intentId: String, startPage: Int, readyLease: MonitoringReadyLease, proposedSessionId: String,
    ): MonitoredSessionStartResult = startSessionInternal(intentId, startPage, readyLease, proposedSessionId)

    private suspend fun startSessionInternal(
        intentId: String, startPage: Int, readyLease: MonitoringReadyLease?, proposedSessionId: String?,
    ): MonitoredSessionStartResult {
        val result = database.withTransaction<MonitoredSessionStartResult?> {
            if (readyLease != null) sessionDao.getActiveForIntent(intentId)?.let {
                return@withTransaction MonitoredSessionStartResult(it, created = false)
            }
            val intent = checkNotNull(intentDao.get(intentId)) { "Intent 不存在" }
            check(intent.activeSlot == ACTIVE_SLOT && intent.outcome == null) { "Intent 已结束" }
            val initialItem = checkNotNull(itemDao.get(intent.learningItemId)) { "Learning Item 不存在" }
            check(initialItem.status == LearningItemStatus.IN_PROGRESS) {
                "只有进行中的内容可以开始"
            }
            val checkedAt = clock()
            if (expiryPolicy.isExpired(intent.createdAt, checkedAt)) {
                intentDao.markTimedOut(intent.id, checkedAt)
                return@withTransaction null
            }
            check(sessionDao.getActive() == null) { "已有进行中的 Session" }
            val item = checkNotNull(itemDao.get(intent.learningItemId)) { "Learning Item 不存在" }
            check(item.status == LearningItemStatus.IN_PROGRESS) {
                "只有进行中的内容可以开始"
            }
            require(startPage in 1..item.totalPages) { "起始页必须在书籍范围内" }
            val startedAt = readyLease?.readyAtWall ?: checkedAt
            if (readyLease != null) {
                require(!proposedSessionId.isNullOrBlank()) { "Session ID 不可为空" }
                require(readyLease.generation.isNotBlank() && readyLease.successfulQueryGeneration > 0) { "监测证据无效" }
                require(startedAt in intent.createdAt..checkedAt) { "READY 时间越过 Intent/当前时间" }
            }
            val session = StudySessionEntity(
                id = proposedSessionId ?: newId(),
                learningItemId = item.id,
                intentId = intent.id,
                startedAt = startedAt,
                stableStartedAt = null,
                endedAt = null,
                startPage = startPage,
                currentPage = startPage,
                endPage = null,
                endType = null,
                generatedSummary = null,
                activeSlot = ACTIVE_SLOT,
            )
            sessionDao.insert(session)
            focusDao.insertContext(
                SessionFocusContextEntity(
                    sessionId = session.id,
                    monitoringStatus = if (readyLease != null) MonitoringCoverage.FULL else MonitoringCoverage.NONE,
                    monitoringLostAt = if (readyLease != null) null else session.startedAt,
                    pollIntervalMillis = if (readyLease != null) 1_000 else 2_000,
                    usageAccessAtStart = readyLease != null,
                    dndAccessAtStart = readyLease?.dndAccessAvailable ?: false,
                    notificationAccessAtStart = readyLease?.notificationVisible ?: false,
                    priorDndInterruptionFilter = null,
                    dndRuleId = null,
                    requestedEndPage = null,
                    closeoutStartedAt = null,
                    lastHeartbeatAt = session.startedAt,
                    createdAt = session.startedAt,
                    updatedAt = session.startedAt,
                ),
            )
            val riskSnapshots = focusDao.listRiskApps().map {
                SessionRiskAppSnapshotEntity(session.id, it.packageName, it.labelSnapshot)
            }
            if (riskSnapshots.isNotEmpty()) focusDao.insertRiskSnapshots(riskSnapshots)
            focusDao.insertSegment(
                SessionSegmentEntity(
                    id = newId(), sessionId = session.id,
                    type = if (readyLease != null) SessionSegmentType.FOCUS else SessionSegmentType.UNMONITORED,
                    startedAt = session.startedAt, endedAt = null, packageName = null, reason = null,
                    plannedEndAt = null, extensionCount = 0, relatedSegmentId = null, activeSlot = ACTIVE_SLOT,
                ),
            )
            check(intentDao.markConverted(intent.id, startedAt) == 1) { "Intent 转换失败" }
            MonitoredSessionStartResult(session, created = true)
        }
        return result ?: error("Intent 已超时")
    }

    override suspend fun updateCurrentPage(sessionId: String, page: Int) {
        database.withTransaction {
            val session = checkNotNull(sessionDao.get(sessionId)) { "Session 不存在" }
            val item = checkNotNull(itemDao.get(session.learningItemId)) { "Learning Item 不存在" }
            require(page in 1..item.totalPages) { "页码必须在书籍范围内" }
            check(sessionDao.advanceCurrentPage(sessionId, page) == 1) { "Session 已结束" }
        }
    }

    override suspend fun finishSession(sessionId: String, endPage: Int): StudySessionEntity =
        database.withTransaction {
            val session = checkNotNull(sessionDao.get(sessionId)) { "Session 不存在" }
            check(session.activeSlot == ACTIVE_SLOT && session.endType == null) { "Session 已结束" }
            val item = checkNotNull(itemDao.get(session.learningItemId)) { "Learning Item 不存在" }
            require(endPage in 1..item.totalPages) { "结束页必须在书籍范围内" }
            val finalPage = maxOf(session.currentPage, endPage)
            val now = clock()
            closeActiveSegmentForSession(session.id, now)
            val noteCount = noteDao.countForSession(sessionId)
            val summary = summaryEngine.create(
                startPage = session.startPage,
                endPage = finalPage,
                durationMillis = now - session.startedAt,
                noteCount = noteCount,
            )
            check(sessionDao.finish(sessionId, now, finalPage, SessionEndType.NORMAL, summary) == 1) {
                "结束 Session 失败"
            }
            check(itemDao.advanceProgress(item.id, finalPage, now) == 1) { "保存阅读进度失败" }
            searchIndexWriter.reindexSession(sessionId)
            checkNotNull(sessionDao.get(sessionId))
        }

    override suspend fun recoverInterruptedSession() {
        database.withTransaction {
            val now = clock()
            sessionDao.getActive()?.let { active ->
                val segment = focusDao.getActiveSegment(active.id)
                val context = focusDao.getContext(active.id)
                // A regressed wall clock cannot truncate facts already durable in Room.
                val recoveryBoundary = maxOf(
                    now,
                    active.startedAt,
                    segment?.startedAt ?: active.startedAt,
                    context?.lastHeartbeatAt ?: active.startedAt,
                    context?.monitoringLostAt ?: active.startedAt,
                )
                closeActiveSegmentForRecovery(active.id, recoveryBoundary)
                sessionDao.finish(
                    id = active.id,
                    endedAt = recoveryBoundary,
                    endPage = active.currentPage,
                    endType = SessionEndType.ABNORMAL,
                    summary = null,
                )
                searchIndexWriter.reindexSession(active.id)
            }
            intentDao.getActive()?.takeIf { expiryPolicy.isExpired(it.createdAt, now) }?.let { expired ->
                intentDao.markTimedOut(expired.id, now)
            }
        }
    }

    private suspend fun closeActiveSegmentForSession(sessionId: String, endedAt: Long) {
        val active = focusDao.getActiveSegment(sessionId) ?: return
        if (endedAt > active.startedAt) {
            check(focusDao.closeActiveSegment(active.id, sessionId, endedAt) == 1) { "关闭 Segment 失败" }
        } else {
            check(focusDao.deleteActiveSegment(active.id, sessionId) == 1) { "清理零时长 Segment 失败" }
        }
    }

    private suspend fun closeActiveSegmentForRecovery(sessionId: String, detectedAt: Long) {
        val active = focusDao.getActiveSegment(sessionId) ?: return
        val context = focusDao.getContext(sessionId)
        val trustedAt = maxOf(active.startedAt, minOf(context?.lastHeartbeatAt ?: active.startedAt, detectedAt))
        if (detectedAt <= active.startedAt) {
            check(focusDao.deleteActiveSegment(active.id, sessionId) == 1)
        } else if (active.type == SessionSegmentType.UNMONITORED || trustedAt == active.startedAt) {
            check(
                focusDao.replaceAndCloseActiveSegment(
                    active.id, sessionId, SessionSegmentType.UNMONITORED, detectedAt,
                ) == 1,
            )
        } else {
            check(focusDao.closeActiveSegment(active.id, sessionId, trustedAt) == 1)
            if (trustedAt < detectedAt) {
                focusDao.insertSegment(
                    SessionSegmentEntity(
                        id = newId(), sessionId = sessionId, type = SessionSegmentType.UNMONITORED,
                        startedAt = trustedAt, endedAt = detectedAt, packageName = null, reason = null,
                        plannedEndAt = null, extensionCount = 0, relatedSegmentId = null, activeSlot = null,
                    ),
                )
            }
        }
        if (context != null) {
            val degraded = if (context.monitoringStatus == MonitoringCoverage.FULL) {
                MonitoringCoverage.PARTIAL
            } else context.monitoringStatus
            check(focusDao.setCoverage(sessionId, degraded, trustedAt, detectedAt) == 1)
        }
    }

    private companion object {
        const val ACTIVE_SLOT = 1
    }
}
