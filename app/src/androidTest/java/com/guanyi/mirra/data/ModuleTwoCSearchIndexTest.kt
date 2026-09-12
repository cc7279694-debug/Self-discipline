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
import com.guanyi.mirra.data.local.entity.SearchFtsEntity
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.entity.TopicEntity
import com.guanyi.mirra.data.search.SearchDocumentType
import com.guanyi.mirra.data.search.SearchIndexRebuilder
import com.guanyi.mirra.domain.DefaultSearchEngine
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@RunWith(AndroidJUnit4::class)
class ModuleTwoCSearchIndexTest {
    private lateinit var database: MirraDatabase
    private lateinit var rebuilder: SearchIndexRebuilder

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MirraDatabase::class.java,
        ).build()
        rebuilder = SearchIndexRebuilder(database, DefaultSearchEngine())
    }

    @After fun tearDown() = database.close()

    @Test fun rebuildIsIdempotentAndIncludesAllSupportedSources() = runTest {
        seedBusinessData()

        rebuilder.rebuild()
        val first = database.searchFtsDao().dump().toSet()
        rebuilder.rebuild()
        val second = database.searchFtsDao().dump().toSet()

        assertEquals(first, second)
        assertEquals(4, second.size)
        assertTrue(second.single { it.entityType == "NOTE" }.searchableText.contains("图片说明"))
        assertTrue(second.any { it.entityType == "SESSION" && it.searchableText == "本次阅读 10–20 页" })
    }

    @Test fun lightweightCheckRepairsMissingAndDuplicateRows() = runTest {
        seedBusinessData()
        rebuilder.rebuild()
        database.searchFtsDao().delete(SearchDocumentType.TOPIC.name, "topic")
        assertTrue(rebuilder.ensureConsistent().rebuilt)

        database.searchFtsDao().insert(
            SearchFtsEntity("NOTE", "note", "重复", "重复"),
        )
        assertTrue(rebuilder.ensureConsistent().rebuilt)
        assertEquals(4, database.searchFtsDao().count())
    }

    @Test fun equalCountStaleContentIsRecoveredByExplicitRebuildWithoutChangingBusinessData() = runTest {
        seedBusinessData()
        rebuilder.rebuild()
        val businessBefore = database.noteDao().listAll()
        database.searchFtsDao().delete(SearchDocumentType.NOTE.name, "note")
        database.searchFtsDao().insert(SearchFtsEntity("NOTE", "note", "陈旧内容", "陈旧 内容"))

        assertTrue(!rebuilder.ensureConsistent().rebuilt)
        rebuilder.rebuild()

        assertEquals(businessBefore, database.noteDao().listAll())
        val note = database.searchFtsDao().dump().single { it.entityType == "NOTE" }
        assertTrue(note.searchableText.contains("心理账户"))
        assertTrue(!note.searchableText.contains("陈旧内容"))
    }

    @Test fun fiveThousandDocumentSearchHonorsGlobalLimitOffMainThread() = runTest {
        database.searchFtsDao().insertAll(
            List(5_000) { index -> SearchFtsEntity("TOPIC", "topic-$index", "测试 $index", "测试 $index") },
        )
        lateinit var hits: List<com.guanyi.mirra.data.local.model.SearchHitRow>
        val elapsed = measureTimeMillis {
            hits = withContext(Dispatchers.IO) { database.searchFtsDao().search("\"测试\"", 60) }
        }
        Log.i("MirraSearchSmoke", "5000-document query took ${elapsed}ms on API 37")
        assertEquals(60, hits.size)
    }

    private suspend fun seedBusinessData() {
        database.learningItemDao().insert(
            LearningItemEntity("book", "思考快与慢", LearningItemStatus.IN_PROGRESS, 500, 20, 1, "翻到第20页", 1, 1, null),
        )
        database.intentDao().insert(StudyIntentEntity("intent", "book", 1, 2, 2, 2, com.guanyi.mirra.data.local.entity.IntentOutcome.CONVERTED, null))
        database.sessionDao().insert(
            StudySessionEntity("session", "book", "intent", 2, null, 62, 10, 20, 20, SessionEndType.NORMAL, "本次阅读 10–20 页", null),
        )
        database.noteDao().insert(
            NoteEntity("note", "book", "session", NoteSemanticType.UNDERSTANDING, "心理账户", 20, 3, 3),
        )
        database.imageAssetDao().insert(ImageAssetEntity("image", "note", "images/x.jpg", "图片说明", 1, 1, 1, 4))
        database.topicDao().insert(TopicEntity("topic", "行为经济学", 5))
    }
}
