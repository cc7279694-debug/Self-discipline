package com.guanyi.mirra.data.repository

import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.model.NoteListItem
import com.guanyi.mirra.data.storage.ImageStorageService
import com.guanyi.mirra.data.storage.TrashedFile
import com.guanyi.mirra.data.search.SearchDocumentType
import com.guanyi.mirra.data.search.SearchIndexWriter
import com.guanyi.mirra.domain.DefaultSearchEngine
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

interface NoteRepository {
    fun observeForSession(sessionId: String): Flow<List<NoteEntity>>
    fun observe(noteId: String): Flow<NoteEntity?>
    fun observeAll(
        semanticType: NoteSemanticType? = null,
        learningItemId: String? = null,
    ): Flow<List<NoteListItem>>
    suspend fun save(
        learningItemId: String,
        sessionId: String?,
        content: String,
        pageNumber: Int?,
        semanticType: NoteSemanticType = NoteSemanticType.UNDERSTANDING,
        id: String? = null,
    ): NoteEntity
    suspend fun createStandalone(
        learningItemId: String,
        content: String,
        semanticType: NoteSemanticType = NoteSemanticType.UNDERSTANDING,
        pageNumber: Int? = null,
        id: String? = null,
    ): NoteEntity
    suspend fun update(
        noteId: String,
        content: String,
        semanticType: NoteSemanticType,
        pageNumber: Int?,
    ): NoteEntity
    suspend fun delete(noteId: String)
}

class DefaultNoteRepository(
    private val database: MirraDatabase,
    private val storage: ImageStorageService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val searchIndexWriter: SearchIndexWriter = SearchIndexWriter(database, DefaultSearchEngine()),
) : NoteRepository {
    private val dao = database.noteDao()
    override fun observeForSession(sessionId: String) = dao.observeForSession(sessionId)
    override fun observe(noteId: String) = dao.observe(noteId)
    override fun observeAll(semanticType: NoteSemanticType?, learningItemId: String?) =
        dao.observeAll(semanticType, learningItemId)

    override suspend fun save(
        learningItemId: String,
        sessionId: String?,
        content: String,
        pageNumber: Int?,
        semanticType: NoteSemanticType,
        id: String?,
    ): NoteEntity = database.withTransaction {
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
        NoteEntity(
            id = existingId,
            learningItemId = learningItemId,
            sessionId = sessionId,
            semanticType = semanticType,
            content = cleanContent,
            pageNumber = pageNumber,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        ).also {
            dao.upsert(it)
            searchIndexWriter.reindexNote(it.id)
        }
    }

    override suspend fun createStandalone(
        learningItemId: String,
        content: String,
        semanticType: NoteSemanticType,
        pageNumber: Int?,
        id: String?,
    ): NoteEntity = database.withTransaction {
        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "笔记内容不能为空" }
        val item = checkNotNull(database.learningItemDao().get(learningItemId)) { "Learning Item 不存在" }
        require(pageNumber == null || pageNumber in 1..item.totalPages) { "页码必须在书籍范围内" }
        val now = clock()
        NoteEntity(
            id = id ?: newId(),
            learningItemId = learningItemId,
            sessionId = null,
            semanticType = semanticType,
            content = cleanContent,
            pageNumber = pageNumber,
            createdAt = now,
            updatedAt = now,
        ).also {
            dao.insert(it)
            searchIndexWriter.reindexNote(it.id)
        }
    }

    override suspend fun update(
        noteId: String,
        content: String,
        semanticType: NoteSemanticType,
        pageNumber: Int?,
    ): NoteEntity = database.withTransaction {
        val existing = checkNotNull(dao.get(noteId)) { "Note 不存在" }
        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "笔记内容不能为空" }
        val item = checkNotNull(database.learningItemDao().get(existing.learningItemId)) { "Learning Item 不存在" }
        require(pageNumber == null || pageNumber in 1..item.totalPages) { "页码必须在书籍范围内" }
        check(dao.updateContent(noteId, cleanContent, semanticType, pageNumber, clock()) == 1) { "Note 已被删除" }
        searchIndexWriter.reindexNote(noteId)
        checkNotNull(dao.get(noteId))
    }

    override suspend fun delete(noteId: String) {
        checkNotNull(dao.get(noteId)) { "Note 不存在" }
        val images = database.imageAssetDao().listForNote(noteId)
        val staged = mutableListOf<TrashedFile>()
        try {
            images.forEach { image ->
                storage.moveToTrash(image.localPath)?.let(staged::add)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                staged.asReversed().forEach { runCatching { storage.restoreFromTrash(it) } }
            }
            throw failure
        }
        try {
            database.withTransaction {
                checkNotNull(dao.get(noteId)) { "Note 已被删除" }
                val currentImageIds = database.imageAssetDao().listForNote(noteId).map { it.id }
                check(currentImageIds == images.map { it.id }) { "图片列表已变化，请重试" }
                check(dao.delete(noteId) == 1) { "Note 已被删除" }
                searchIndexWriter.remove(SearchDocumentType.NOTE, noteId)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                staged.asReversed().forEach { runCatching { storage.restoreFromTrash(it) } }
            }
            throw failure
        }
        staged.forEach { runCatching { storage.purgeTrash(it) } }
    }
}
