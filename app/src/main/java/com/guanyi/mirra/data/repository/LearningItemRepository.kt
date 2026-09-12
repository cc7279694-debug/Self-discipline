package com.guanyi.mirra.data.repository

import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.search.SearchIndexWriter
import com.guanyi.mirra.domain.DefaultSearchEngine
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface LearningItemRepository {
    fun observeAll(): Flow<List<LearningItemEntity>>
    fun observe(id: String): Flow<LearningItemEntity?>
    fun observeMainline(): Flow<LearningItemEntity?>
    suspend fun get(id: String): LearningItemEntity?
    suspend fun create(
        name: String,
        totalPages: Int,
        currentPage: Int = 1,
        firstAction: String? = null,
        setAsMainline: Boolean = false,
    ): LearningItemEntity
    suspend fun updateFirstAction(id: String, firstAction: String): LearningItemEntity
    suspend fun setMainline(id: String)
    suspend fun pause(id: String): LearningItemEntity
    suspend fun resume(id: String): LearningItemEntity
    suspend fun complete(id: String): LearningItemEntity
}

class DefaultLearningItemRepository(
    private val database: MirraDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val searchIndexWriter: SearchIndexWriter = SearchIndexWriter(database, DefaultSearchEngine()),
) : LearningItemRepository {
    private val dao = database.learningItemDao()
    private val intentDao = database.intentDao()
    private val sessionDao = database.sessionDao()

    override fun observeAll() = dao.observeAll()
    override fun observe(id: String) = dao.observe(id)
    override fun observeMainline() = dao.observeMainline()
    override suspend fun get(id: String) = dao.get(id)

    override suspend fun create(
        name: String,
        totalPages: Int,
        currentPage: Int,
        firstAction: String?,
        setAsMainline: Boolean,
    ): LearningItemEntity = database.withTransaction {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "名称不能为空" }
        require(totalPages > 0) { "总页数必须大于 0" }
        require(currentPage in 1..totalPages) { "当前页必须在书籍范围内" }
        val now = clock()
        if (setAsMainline) dao.clearMainline(now)
        LearningItemEntity(
            id = newId(),
            name = cleanName,
            status = LearningItemStatus.IN_PROGRESS,
            totalPages = totalPages,
            currentPage = currentPage,
            mainlineSlot = if (setAsMainline) 1 else null,
            firstAction = firstAction?.trim().orEmpty(),
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        ).also {
            dao.insert(it)
            searchIndexWriter.reindexLearningItem(it.id)
        }
    }

    override suspend fun updateFirstAction(id: String, firstAction: String): LearningItemEntity =
        database.withTransaction {
            checkNotNull(dao.get(id)) { "Learning Item 不存在" }
            ensureNoActiveWorkflow(id)
            check(dao.updateFirstAction(id, firstAction.trim(), clock()) == 1) { "更新起步动作失败" }
            checkNotNull(dao.get(id))
        }

    override suspend fun setMainline(id: String) {
        database.withTransaction {
            val item = checkNotNull(dao.get(id)) { "Learning Item 不存在" }
            check(item.status == LearningItemStatus.IN_PROGRESS) { "只有进行中的内容可以设为主线" }
            val now = clock()
            dao.clearMainline(now)
            check(dao.assignMainline(id, now) == 1) { "设置主线失败" }
        }
    }

    override suspend fun pause(id: String): LearningItemEntity = database.withTransaction {
        val item = checkNotNull(dao.get(id)) { "Learning Item 不存在" }
        check(item.status == LearningItemStatus.IN_PROGRESS) { "只有进行中的内容可以暂停" }
        ensureNoActiveWorkflow(id)
        check(dao.markPaused(id, clock()) == 1) { "暂停失败，内容状态已变化" }
        checkNotNull(dao.get(id))
    }

    override suspend fun resume(id: String): LearningItemEntity = database.withTransaction {
        val item = checkNotNull(dao.get(id)) { "Learning Item 不存在" }
        check(item.status == LearningItemStatus.PAUSED) {
            if (item.status == LearningItemStatus.COMPLETED) "已完成的内容不能恢复" else "只有暂停的内容可以恢复"
        }
        check(dao.markInProgress(id, clock()) == 1) { "恢复失败，内容状态已变化" }
        checkNotNull(dao.get(id))
    }

    override suspend fun complete(id: String): LearningItemEntity = database.withTransaction {
        val item = checkNotNull(dao.get(id)) { "Learning Item 不存在" }
        check(item.status == LearningItemStatus.IN_PROGRESS) { "只有进行中的内容可以完成" }
        ensureNoActiveWorkflow(id)
        check(dao.markCompleted(id, clock()) == 1) { "完成失败，内容状态已变化" }
        checkNotNull(dao.get(id))
    }

    private suspend fun ensureNoActiveWorkflow(id: String) {
        check(sessionDao.getActiveForLearningItem(id) == null) { "请先结束这本书当前的阅读" }
        check(intentDao.getActiveForLearningItem(id) == null) { "请先取消这本书当前的启动" }
    }
}
