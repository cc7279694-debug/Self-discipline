package com.guanyi.mirra.data.repository

import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface NoteRepository {
    fun observeForSession(sessionId: String): Flow<List<NoteEntity>>
    suspend fun save(
        learningItemId: String,
        sessionId: String?,
        content: String,
        pageNumber: Int?,
        semanticType: NoteSemanticType = NoteSemanticType.UNDERSTANDING,
        id: String? = null,
    ): NoteEntity
}

class DefaultNoteRepository(
    private val database: MirraDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : NoteRepository {
    private val dao = database.noteDao()
    override fun observeForSession(sessionId: String) = dao.observeForSession(sessionId)

    override suspend fun save(
        learningItemId: String,
        sessionId: String?,
        content: String,
        pageNumber: Int?,
        semanticType: NoteSemanticType,
        id: String?,
    ): NoteEntity {
        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "笔记内容不能为空" }
        val item = checkNotNull(database.learningItemDao().get(learningItemId)) { "Learning Item 不存在" }
        require(pageNumber == null || pageNumber in 1..item.totalPages) { "页码必须在书籍范围内" }
        if (sessionId != null) {
            val session = checkNotNull(database.sessionDao().get(sessionId)) { "Session 不存在" }
            require(session.learningItemId == learningItemId) { "笔记与 Session 不属于同一学习内容" }
        }
        val now = clock()
        val existingId = id ?: newId()
        val existing = id?.let { dao.get(it) }
        return NoteEntity(
            id = existingId,
            learningItemId = learningItemId,
            sessionId = sessionId,
            semanticType = semanticType,
            content = cleanContent,
            pageNumber = pageNumber,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        ).also { dao.upsert(it) }
    }
}
