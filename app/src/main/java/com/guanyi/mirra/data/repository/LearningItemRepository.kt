package com.guanyi.mirra.data.repository

import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface LearningItemRepository {
    fun observeAll(): Flow<List<LearningItemEntity>>
    fun observe(id: String): Flow<LearningItemEntity?>
    fun observeMainline(): Flow<LearningItemEntity?>
    suspend fun get(id: String): LearningItemEntity?
    suspend fun create(name: String, totalPages: Int, currentPage: Int = 1): LearningItemEntity
    suspend fun setMainline(id: String)
}

class DefaultLearningItemRepository(
    private val database: MirraDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : LearningItemRepository {
    private val dao = database.learningItemDao()

    override fun observeAll() = dao.observeAll()
    override fun observe(id: String) = dao.observe(id)
    override fun observeMainline() = dao.observeMainline()
    override suspend fun get(id: String) = dao.get(id)

    override suspend fun create(name: String, totalPages: Int, currentPage: Int): LearningItemEntity {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "名称不能为空" }
        require(totalPages > 0) { "总页数必须大于 0" }
        require(currentPage in 1..totalPages) { "当前页必须在书籍范围内" }
        val now = clock()
        return LearningItemEntity(
            id = newId(),
            name = cleanName,
            status = LearningItemStatus.IN_PROGRESS,
            totalPages = totalPages,
            currentPage = currentPage,
            mainlineSlot = null,
            firstAction = "拿起《$cleanName》，翻到第 $currentPage 页。",
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        ).also { dao.insert(it) }
    }

    override suspend fun setMainline(id: String) {
        database.withTransaction {
            checkNotNull(dao.get(id)) { "Learning Item 不存在" }
            val now = clock()
            dao.clearMainline(now)
            check(dao.assignMainline(id, now) == 1) { "设置主线失败" }
        }
    }
}
