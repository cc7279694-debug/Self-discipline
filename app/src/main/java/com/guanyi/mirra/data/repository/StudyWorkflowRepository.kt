package com.guanyi.mirra.data.repository

import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.model.RecentReadingSnapshot
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.SummaryEngine
import com.guanyi.mirra.data.search.SearchIndexWriter
import com.guanyi.mirra.domain.DefaultSearchEngine
import java.util.UUID
import kotlinx.coroutines.flow.Flow

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
    suspend fun updateCurrentPage(sessionId: String, page: Int)
    suspend fun finishSession(sessionId: String, endPage: Int): StudySessionEntity
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

    override fun observeActiveIntent() = intentDao.observeActive()
    override fun observeActiveSession() = sessionDao.observeActive()
    override fun observeSession(id: String) = sessionDao.observe(id)
    override fun observeLatestSummaryForItem(learningItemId: String) = sessionDao.observeLatestSummaryForItem(learningItemId)
    override fun observeLatestNormalReading(learningItemId: String) = sessionDao.observeLatestNormalReading(learningItemId)

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

    override suspend fun startSession(intentId: String, startPage: Int): StudySessionEntity {
        val session = database.withTransaction<StudySessionEntity?> {
            val intent = checkNotNull(intentDao.get(intentId)) { "Intent 不存在" }
            check(intent.activeSlot == ACTIVE_SLOT && intent.outcome == null) { "Intent 已结束" }
            val initialItem = checkNotNull(itemDao.get(intent.learningItemId)) { "Learning Item 不存在" }
            check(initialItem.status == LearningItemStatus.IN_PROGRESS) {
                "只有进行中的内容可以开始"
            }
            val now = clock()
            if (expiryPolicy.isExpired(intent.createdAt, now)) {
                intentDao.markTimedOut(intent.id, now)
                return@withTransaction null
            }
            check(sessionDao.getActive() == null) { "已有进行中的 Session" }
            val item = checkNotNull(itemDao.get(intent.learningItemId)) { "Learning Item 不存在" }
            check(item.status == LearningItemStatus.IN_PROGRESS) {
                "只有进行中的内容可以开始"
            }
            require(startPage in 1..item.totalPages) { "起始页必须在书籍范围内" }
            val session = StudySessionEntity(
                id = newId(),
                learningItemId = item.id,
                intentId = intent.id,
                startedAt = now,
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
            check(intentDao.markConverted(intent.id, now) == 1) { "Intent 转换失败" }
            session
        }
        return session ?: error("Intent 已超时")
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
                sessionDao.finish(
                    id = active.id,
                    endedAt = now,
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

    private companion object {
        const val ACTIVE_SLOT = 1
    }
}
