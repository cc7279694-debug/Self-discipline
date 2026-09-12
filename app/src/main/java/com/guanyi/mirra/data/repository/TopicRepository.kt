package com.guanyi.mirra.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.NoteTopicCrossRef
import com.guanyi.mirra.data.local.entity.TopicEntity
import com.guanyi.mirra.data.local.model.NoteListItem
import com.guanyi.mirra.data.local.model.TopicListItem
import com.guanyi.mirra.data.search.SearchIndexWriter
import com.guanyi.mirra.domain.TopicNameNormalizer
import com.guanyi.mirra.domain.TopicSuggester
import com.guanyi.mirra.domain.TopicSuggestion
import java.util.UUID
import kotlinx.coroutines.flow.Flow

sealed interface CreateTopicResult {
    val topic: TopicEntity

    data class Created(override val topic: TopicEntity) : CreateTopicResult
    data class AlreadyExists(override val topic: TopicEntity) : CreateTopicResult
}

enum class LinkTopicResult { LINKED, ALREADY_LINKED }
enum class UnlinkTopicResult { UNLINKED, NOT_LINKED }

interface TopicRepository {
    fun observeAll(): Flow<List<TopicListItem>>
    fun observeAllTopics(): Flow<List<TopicEntity>>
    fun observe(topicId: String): Flow<TopicEntity?>
    fun observeForNote(noteId: String): Flow<List<TopicEntity>>
    fun observeNotes(topicId: String): Flow<List<NoteListItem>>
    suspend fun create(name: String): CreateTopicResult
    suspend fun createAndLink(noteId: String, name: String): CreateTopicResult
    suspend fun link(noteId: String, topicId: String): LinkTopicResult
    suspend fun unlink(noteId: String, topicId: String): UnlinkTopicResult
    suspend fun suggestions(noteId: String): List<TopicSuggestion>
}

class DefaultTopicRepository(
    private val database: MirraDatabase,
    private val indexWriter: SearchIndexWriter,
    private val suggester: TopicSuggester = TopicSuggester(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : TopicRepository {
    private val dao = database.topicDao()

    override fun observeAll() = dao.observeAllWithNoteCount()
    override fun observeAllTopics() = dao.observeAll()
    override fun observe(topicId: String) = dao.observe(topicId)
    override fun observeForNote(noteId: String) = dao.observeForNote(noteId)
    override fun observeNotes(topicId: String) = dao.observeNotes(topicId)

    override suspend fun create(name: String): CreateTopicResult = database.withTransaction {
        createInternal(name)
    }

    override suspend fun createAndLink(noteId: String, name: String): CreateTopicResult = database.withTransaction {
        checkNotNull(database.noteDao().get(noteId)) { "Note 不存在" }
        val result = createInternal(name)
        dao.insertCrossRef(NoteTopicCrossRef(noteId, result.topic.id))
        result
    }

    override suspend fun link(noteId: String, topicId: String): LinkTopicResult = database.withTransaction {
        checkNotNull(database.noteDao().get(noteId)) { "Note 不存在" }
        checkNotNull(dao.get(topicId)) { "Topic 不存在" }
        if (dao.insertCrossRef(NoteTopicCrossRef(noteId, topicId)) == -1L) {
            LinkTopicResult.ALREADY_LINKED
        } else {
            LinkTopicResult.LINKED
        }
    }

    override suspend fun unlink(noteId: String, topicId: String): UnlinkTopicResult = database.withTransaction {
        checkNotNull(database.noteDao().get(noteId)) { "Note 不存在" }
        checkNotNull(dao.get(topicId)) { "Topic 不存在" }
        if (dao.deleteCrossRef(noteId, topicId) == 1) UnlinkTopicResult.UNLINKED else UnlinkTopicResult.NOT_LINKED
    }

    override suspend fun suggestions(noteId: String): List<TopicSuggestion> = database.withTransaction {
        val note = checkNotNull(database.noteDao().get(noteId)) { "Note 不存在" }
        suggester.suggest(note.content, dao.listAll(), dao.listForNote(noteId).mapTo(mutableSetOf()) { it.id })
    }

    private suspend fun createInternal(rawName: String): CreateTopicResult {
        val normalized = TopicNameNormalizer.normalize(rawName)
        require(normalized.isNotEmpty()) { "Topic 名称不能为空" }
        dao.getByName(normalized)?.let { return CreateTopicResult.AlreadyExists(it) }
        val topic = TopicEntity(newId(), normalized, clock())
        return try {
            dao.insert(topic)
            indexWriter.reindexTopic(topic.id)
            CreateTopicResult.Created(topic)
        } catch (constraint: SQLiteConstraintException) {
            val existing = dao.getByName(normalized) ?: throw constraint
            CreateTopicResult.AlreadyExists(existing)
        }
    }
}
