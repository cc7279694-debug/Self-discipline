package com.guanyi.mirra.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultNoteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private suspend inline fun <reified T : Throwable> assertNoteSuspendThrows(crossinline block: suspend () -> Unit) {
    try {
        block()
        throw AssertionError("Expected ${T::class.java.simpleName}")
    } catch (throwable: Throwable) {
        if (throwable !is T) throw throwable
    }
}

@RunWith(AndroidJUnit4::class)
class ModuleTwoANoteRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var learningItems: DefaultLearningItemRepository
    private lateinit var notes: DefaultNoteRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
        learningItems = DefaultLearningItemRepository(database, clock = { now })
        notes = DefaultNoteRepository(database, clock = { now })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun standaloneNoteRequiresValidContentAndNeverAdvancesProgress() = runTest {
        val item = learningItems.create("独立笔记", 120, 40)

        val note = notes.createStandalone(
            learningItemId = item.id,
            content = "  回看旧页的理解  ",
            pageNumber = 12,
            semanticType = NoteSemanticType.UNDERSTANDING,
        )

        assertEquals("回看旧页的理解", note.content)
        assertNull(note.sessionId)
        assertEquals(12, note.pageNumber)
        assertEquals(40, learningItems.get(item.id)?.currentPage)
        assertNoteSuspendThrows<IllegalArgumentException> {
            notes.createStandalone(item.id, "   ", NoteSemanticType.QUOTE, null)
        }
        assertNoteSuspendThrows<IllegalStateException> {
            notes.createStandalone("missing", "内容", NoteSemanticType.QUOTE, null)
        }
    }

    @Test
    fun updatePreservesOwnershipSessionAndCreatedAt() = runTest {
        val item = learningItems.create("编辑笔记", 100, 30)
        val original = notes.save(
            learningItemId = item.id,
            sessionId = null,
            content = "原内容",
            pageNumber = 20,
            semanticType = NoteSemanticType.QUOTE,
        )

        now = 2_000L
        val updated = notes.update(
            noteId = original.id,
            content = " 修改后的理解 ",
            semanticType = NoteSemanticType.UNDERSTANDING,
            pageNumber = 10,
        )

        assertEquals(original.learningItemId, updated.learningItemId)
        assertEquals(original.sessionId, updated.sessionId)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(2_000L, updated.updatedAt)
        assertEquals("修改后的理解", updated.content)
        assertEquals(10, updated.pageNumber)
        assertEquals(30, learningItems.get(item.id)?.currentPage)
    }

    @Test
    fun listIsNewestFirstAndSupportsCombinedFilters() = runTest {
        val firstItem = learningItems.create("第一本", 100)
        val secondItem = learningItems.create("第二本", 100)
        notes.createStandalone(firstItem.id, "旧摘录", NoteSemanticType.QUOTE, 2)
        now = 2_000L
        notes.createStandalone(firstItem.id, "新问题", NoteSemanticType.QUESTION, 3)
        now = 3_000L
        notes.createStandalone(secondItem.id, "第二本问题", NoteSemanticType.QUESTION, 4)

        val all = notes.observeAll().first()
        assertEquals(listOf("第二本问题", "新问题", "旧摘录"), all.map { it.note.content })
        assertEquals("第二本", all.first().learningItemName)

        val questionsForFirst = notes.observeAll(
            semanticType = NoteSemanticType.QUESTION,
            learningItemId = firstItem.id,
        ).first()
        assertEquals(listOf("新问题"), questionsForFirst.map { it.note.content })
    }

    @Test
    fun deleteRemovesOnlyRequestedNote() = runTest {
        val item = learningItems.create("删除笔记", 100)
        val keep = notes.createStandalone(item.id, "保留", NoteSemanticType.SUMMARY, null)
        val remove = notes.createStandalone(item.id, "删除", NoteSemanticType.QUESTION, null)

        notes.delete(remove.id)

        assertNull(notes.observe(remove.id).first())
        assertEquals(listOf(keep.id), notes.observeAll().first().map { it.note.id })
        assertNoteSuspendThrows<IllegalStateException> { notes.delete(remove.id) }
    }
}
