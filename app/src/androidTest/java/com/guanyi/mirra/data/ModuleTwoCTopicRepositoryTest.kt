package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.repository.CreateTopicResult
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultNoteRepository
import com.guanyi.mirra.data.repository.DefaultTopicRepository
import com.guanyi.mirra.data.repository.LinkTopicResult
import com.guanyi.mirra.data.repository.UnlinkTopicResult
import com.guanyi.mirra.data.search.SearchIndexWriter
import com.guanyi.mirra.data.storage.DefaultImageStorageService
import com.guanyi.mirra.domain.DefaultSearchEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleTwoCTopicRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var topics: DefaultTopicRepository
    private lateinit var notes: DefaultNoteRepository
    private lateinit var learningItems: DefaultLearningItemRepository
    private var nextTopicId = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
        val writer = SearchIndexWriter(database, DefaultSearchEngine())
        topics = DefaultTopicRepository(database, writer, clock = { 100L }, newId = { "topic-${nextTopicId++}" })
        learningItems = DefaultLearningItemRepository(database)
        notes = DefaultNoteRepository(database, DefaultImageStorageService(context))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun createNormalizesNameAndReturnsExistingAsciiCaseInsensitiveTopic() = runTest {
        val created = topics.create("  Ｋｏｔｌｉｎ  ") as CreateTopicResult.Created
        val duplicate = topics.create("kotlin") as CreateTopicResult.AlreadyExists

        assertEquals("Kotlin", created.topic.name)
        assertEquals(created.topic.id, duplicate.topic.id)
        assertEquals(1, topics.observeAll().first().size)
    }

    @Test
    fun blankTopicIsRejectedWithoutWriting() = runTest {
        assertSuspendFails { topics.create(" \u3000 ") }
        assertTrue(topics.observeAll().first().isEmpty())
    }

    @Test
    fun manyToManyLinksAreIdempotentAndUnlinkDoesNotDeleteEitherSide() = runTest {
        val item = learningItems.create("书", 100)
        val firstNote = notes.createStandalone(item.id, "心理账户")
        val secondNote = notes.createStandalone(item.id, "另一条")
        val firstTopic = (topics.create("心理") as CreateTopicResult.Created).topic
        val secondTopic = (topics.create("行为") as CreateTopicResult.Created).topic

        assertEquals(LinkTopicResult.LINKED, topics.link(firstNote.id, firstTopic.id))
        assertEquals(LinkTopicResult.ALREADY_LINKED, topics.link(firstNote.id, firstTopic.id))
        assertEquals(LinkTopicResult.LINKED, topics.link(firstNote.id, secondTopic.id))
        assertEquals(LinkTopicResult.LINKED, topics.link(secondNote.id, firstTopic.id))
        assertEquals(2, topics.observeForNote(firstNote.id).first().size)
        assertEquals(2, topics.observeNotes(firstTopic.id).first().size)

        assertEquals(UnlinkTopicResult.UNLINKED, topics.unlink(firstNote.id, firstTopic.id))
        assertEquals(UnlinkTopicResult.NOT_LINKED, topics.unlink(firstNote.id, firstTopic.id))
        assertEquals(firstNote.id, notes.observe(firstNote.id).first()?.id)
        assertEquals(firstTopic.id, topics.observe(firstTopic.id).first()?.id)
    }

    @Test
    fun createAndLinkIsAtomicAndMissingNoteDoesNotCreateTopic() = runTest {
        assertSuspendFails { topics.createAndLink("missing", "孤立") }
        assertTrue(topics.observeAll().first().isEmpty())

        val item = learningItems.create("书", 100)
        val note = notes.createStandalone(item.id, "内容")
        val result = topics.createAndLink(note.id, "主题")
        assertTrue(result is CreateTopicResult.Created)
        assertEquals("主题", topics.observeForNote(note.id).first().single().name)
    }

    @Test
    fun deletingNoteCascadesCrossReferences() = runTest {
        val item = learningItems.create("书", 100)
        val note = notes.createStandalone(item.id, "内容")
        val topic = (topics.create("主题") as CreateTopicResult.Created).topic
        topics.link(note.id, topic.id)

        notes.delete(note.id)

        assertTrue(topics.observeNotes(topic.id).first().isEmpty())
        assertEquals(0, database.query("SELECT COUNT(*) FROM note_topic_cross_refs", null).use {
            it.moveToFirst(); it.getInt(0)
        })
    }

    private suspend fun assertSuspendFails(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected failure")
        } catch (expected: IllegalArgumentException) {
            Unit
        } catch (expected: IllegalStateException) {
            Unit
        }
    }
}
