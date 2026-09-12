package com.guanyi.mirra.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.guanyi.mirra.data.local.entity.NoteTopicCrossRef
import com.guanyi.mirra.data.local.entity.TopicEntity
import com.guanyi.mirra.data.local.model.NoteListItem
import com.guanyi.mirra.data.local.model.TopicListItem
import kotlinx.coroutines.flow.Flow

@Dao
interface TopicDao {
    @Insert suspend fun insert(topic: TopicEntity)

    @Query("SELECT * FROM topics WHERE id = :topicId")
    suspend fun get(topicId: String): TopicEntity?

    @Query("SELECT * FROM topics WHERE name = :normalizedName COLLATE NOCASE LIMIT 1")
    suspend fun getByName(normalizedName: String): TopicEntity?

    @Query("SELECT * FROM topics WHERE id = :topicId")
    fun observe(topicId: String): Flow<TopicEntity?>

    @Query("SELECT * FROM topics ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics ORDER BY name COLLATE NOCASE, id")
    suspend fun listAll(): List<TopicEntity>

    @Query("SELECT * FROM topics WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TopicEntity>

    @Query("""
        SELECT topics.*, COUNT(note_topic_cross_refs.noteId) AS noteCount
        FROM topics LEFT JOIN note_topic_cross_refs
            ON note_topic_cross_refs.topicId = topics.id
        GROUP BY topics.id
        ORDER BY topics.name COLLATE NOCASE, topics.id
    """)
    fun observeAllWithNoteCount(): Flow<List<TopicListItem>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: NoteTopicCrossRef): Long

    @Query("DELETE FROM note_topic_cross_refs WHERE noteId = :noteId AND topicId = :topicId")
    suspend fun deleteCrossRef(noteId: String, topicId: String): Int

    @Query("""
        SELECT topics.* FROM topics
        INNER JOIN note_topic_cross_refs ON note_topic_cross_refs.topicId = topics.id
        WHERE note_topic_cross_refs.noteId = :noteId
        ORDER BY topics.name COLLATE NOCASE, topics.id
    """)
    fun observeForNote(noteId: String): Flow<List<TopicEntity>>

    @Query("""
        SELECT topics.* FROM topics
        INNER JOIN note_topic_cross_refs ON note_topic_cross_refs.topicId = topics.id
        WHERE note_topic_cross_refs.noteId = :noteId
        ORDER BY topics.name COLLATE NOCASE, topics.id
    """)
    suspend fun listForNote(noteId: String): List<TopicEntity>

    @Query("""
        SELECT notes.*, learning_items.name AS learningItemName
        FROM notes
        INNER JOIN note_topic_cross_refs ON note_topic_cross_refs.noteId = notes.id
        INNER JOIN learning_items ON learning_items.id = notes.learningItemId
        WHERE note_topic_cross_refs.topicId = :topicId
        ORDER BY notes.createdAt DESC, notes.id DESC
    """)
    fun observeNotes(topicId: String): Flow<List<NoteListItem>>
}
