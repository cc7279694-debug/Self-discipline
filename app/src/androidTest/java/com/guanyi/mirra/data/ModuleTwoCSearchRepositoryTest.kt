package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.ImageAssetEntity
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.entity.TopicEntity
import com.guanyi.mirra.data.repository.DefaultSearchRepository
import com.guanyi.mirra.data.repository.DefaultImageRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultNoteRepository
import com.guanyi.mirra.data.repository.DefaultStudyWorkflowRepository
import com.guanyi.mirra.data.search.SearchIndexRebuilder
import com.guanyi.mirra.domain.DefaultSearchEngine
import com.guanyi.mirra.data.search.SearchIndexWriter
import com.guanyi.mirra.data.storage.DefaultImageStorageService
import com.guanyi.mirra.domain.IntentExpiryPolicy
import com.guanyi.mirra.domain.RuleBasedSummaryEngine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.database.sqlite.SQLiteException
import com.guanyi.mirra.data.local.model.SearchHitRow

@RunWith(AndroidJUnit4::class)
class ModuleTwoCSearchRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var repository: DefaultSearchRepository
    private lateinit var rebuilder: SearchIndexRebuilder

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MirraDatabase::class.java,
        ).build()
        val engine = DefaultSearchEngine()
        rebuilder = SearchIndexRebuilder(database, engine)
        repository = DefaultSearchRepository(database, engine, rebuilder)
    }

    @After fun tearDown() = database.close()

    @Test fun searchesChineseEnglishMixedCaptionAndTopic() = runTest {
        seed()
        rebuilder.rebuild()

        assertEquals(listOf("note"), repository.search("账户").items.map { it.id })
        assertEquals(listOf("book"), repository.search("kotlin").items.map { it.id })
        val mixed = repository.search("边际 value").items.map { it.id }
        assertEquals(listOf("note"), mixed)
        assertEquals(listOf("note"), repository.search("图像说明").items.map { it.id })
        assertEquals(listOf("topic"), repository.search("认知偏差").items.map { it.id })
    }

    @Test fun noteBodyAndMultipleCaptionsStillYieldOneNoteResult() = runTest {
        seed()
        database.imageAssetDao().insert(ImageAssetEntity("image2", "note", "images/y.jpg", "心理账户图像说明", 1, 1, 1, 5))
        rebuilder.rebuild()

        val result = repository.search("账户")

        assertEquals(1, result.items.count { it.id == "note" })
    }

    @Test fun singleHanAndSyntaxCharactersAreSafeAndGiveHintInsteadOfSqlErrors() = runTest {
        seed()
        rebuilder.rebuild()

        assertTrue(repository.search("心").items.isEmpty())
        assertTrue(repository.search("心").hint?.contains("两个") == true)
        listOf("\"", "(", ")", "*", "AND", "😀").forEach { query ->
            repository.search(query)
        }
    }

    @Test fun repositoryWritesReplaceOldCaptionAndRemoveDeletedNoteDocument() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = DefaultSearchEngine()
        val writer = SearchIndexWriter(database, engine)
        val storage = DefaultImageStorageService(context)
        val items = DefaultLearningItemRepository(database, searchIndexWriter = writer)
        val notes = DefaultNoteRepository(database, storage, searchIndexWriter = writer)
        val images = DefaultImageRepository(database, storage, searchIndexWriter = writer)
        val item = items.create("搜索书名", 100)
        val note = notes.createStandalone(item.id, "正文")
        database.imageAssetDao().insert(ImageAssetEntity("image", note.id, "images/00000000-0000-0000-0000-000000000001.jpg", "旧词语", 1, 1, 1, 3))
        writer.reindexNote(note.id)
        assertEquals(listOf(note.id), repository.search("旧词").items.map { it.id })

        images.updateCaption("image", "新词语")
        assertTrue(repository.search("旧词").items.isEmpty())
        assertEquals(listOf(note.id), repository.search("新词").items.map { it.id })

        notes.delete(note.id)
        assertTrue(repository.search("新词").items.isEmpty())
    }

    @Test fun ftsFailureTriggersAtMostOneRepairAndOneRetry() = runTest {
        seed()
        var attempts = 0
        var repairs = 0
        val recovering = DefaultSearchRepository(
            database,
            DefaultSearchEngine(),
            rebuilder,
            executeSearch = { _, _ ->
                attempts += 1
                if (attempts == 1) throw SQLiteException("broken index")
                listOf(SearchHitRow("NOTE", "note", "心理账户", 2))
            },
            repairIndex = { repairs += 1 },
        )

        assertEquals(listOf("note"), recovering.search("账户").items.map { it.id })
        assertEquals(2, attempts)
        assertEquals(1, repairs)
    }

    @Test fun finishedSessionSummaryIsIndexedByWorkflowTransaction() = runTest {
        val writer = SearchIndexWriter(database, DefaultSearchEngine())
        var now = 100L
        var nextId = 0
        val items = DefaultLearningItemRepository(database, clock = { now }, newId = { "book2" }, searchIndexWriter = writer)
        val workflow = DefaultStudyWorkflowRepository(
            database, RuleBasedSummaryEngine(), IntentExpiryPolicy(),
            clock = { now }, newId = { if (nextId++ == 0) "intent2" else "session2" },
            searchIndexWriter = writer,
        )
        val item = items.create("测试材料", 100)
        val intent = workflow.createIntent(item.id)
        now = 101L
        val session = workflow.startSession(intent.id, 1)
        now = 3_600_101L
        workflow.finishSession(session.id, 10)

        assertEquals(listOf(session.id), repository.search("用时").items.map { it.id })
    }

    private suspend fun seed() {
        database.learningItemDao().insert(
            LearningItemEntity("book", "Kotlin 实战", LearningItemStatus.IN_PROGRESS, 100, 1, null, "", 1, 1, null),
        )
        database.noteDao().insert(
            NoteEntity("note", "book", null, NoteSemanticType.UNDERSTANDING, "心理账户与边际 value", 3, 2, 2),
        )
        database.imageAssetDao().insert(ImageAssetEntity("image", "note", "images/x.jpg", "图像说明", 1, 1, 1, 3))
        database.topicDao().insert(TopicEntity("topic", "认知偏差", 4))
    }
}
