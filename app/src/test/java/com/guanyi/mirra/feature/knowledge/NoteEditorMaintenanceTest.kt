package com.guanyi.mirra.feature.knowledge

import androidx.lifecycle.viewModelScope
import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.repository.*
import com.guanyi.mirra.domain.maintenance.PendingEditRegistry
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorMaintenanceTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<NoteEditorViewModel>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private inline fun <reified T> unsupported(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as T

    private inner class Fixture {
        val registry = PendingEditRegistry()
        val stored = mutableListOf<NoteEntity>()
        val captions = mutableMapOf<String, String?>()
        var failCaption = false
        var barrier: CompletableDeferred<Unit>? = null
        val notes = object : NoteRepository by unsupported() {
            override fun observe(noteId: String) = flowOf(NoteEntity(noteId, "book", null,
                NoteSemanticType.UNDERSTANDING, "persisted", 2, 1_000, 1_000))
            override suspend fun update(noteId: String, content: String, semanticType: NoteSemanticType,
                pageNumber: Int?): NoteEntity {
                barrier?.await()
                return NoteEntity(noteId, "book", null, semanticType, content, pageNumber, 1_000, 2_000)
                    .also { stored += it }
            }
        }
        val images = object : ImageRepository by unsupported() {
            override suspend fun updateCaption(imageId: String, caption: String?) {
                if (failCaption) error("caption failed")
                captions[imageId] = caption
            }
        }
        val items = object : LearningItemRepository by unsupported() {
            override fun observeAll() = flowOf(emptyList<LearningItemEntity>())
        }
        val topics = object : TopicRepository by unsupported() {
            override fun observeAllTopics() = flowOf(emptyList<TopicEntity>())
        }
        fun vm() = NoteEditorViewModel("note", null, notes, images, items, topics, pendingEdits = registry)
            .also { models += it }
    }

    @Test fun dirtyBodyPageAndCaptionFlushBeforeDebounceAndFrozenChangesAreRejected() = runTest(dispatcher) {
        val f = Fixture(); val vm = f.vm(); runCurrent()
        vm.changeContent("latest private note"); vm.changePage("42"); vm.changeCaption("image", "latest caption")
        val frozen = f.registry.freezeAndFlush()
        assertEquals("latest private note", f.stored.single().content)
        assertEquals(42, f.stored.single().pageNumber)
        assertEquals("latest caption", f.captions["image"])
        vm.changeContent("late body"); vm.changePage("99"); vm.changeCaption("image", "late caption")
        assertEquals("latest private note", vm.content); assertEquals("42", vm.pageText)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, f.stored.size); assertEquals("latest caption", f.captions["image"])
        frozen.release()
        vm.changeContent("after backup"); advanceTimeBy(501); runCurrent()
        assertEquals("after backup", f.stored.last().content)
    }

    @Test fun captionFailureRejectsFreezeAndPreservesLatestCaptionForRetry() = runTest(dispatcher) {
        val f = Fixture(); val vm = f.vm(); runCurrent()
        vm.changeCaption("image", "must survive failure"); f.failCaption = true
        val failure = runCatching { f.registry.freezeAndFlush() }.exceptionOrNull()
        assertNotNull(failure); assertTrue(f.registry.acceptingEdits.value)
        assertNull(f.captions["image"])
        f.failCaption = false
        val frozen = f.registry.freezeAndFlush()
        assertEquals("must survive failure", f.captions["image"])
        frozen.release()
    }

    @Test fun startedAutosaveIsAwaitedThenLatestTextIsSavedWithoutCancellingDurableWrite() = runTest(dispatcher) {
        val f = Fixture(); val vm = f.vm(); runCurrent()
        f.barrier = CompletableDeferred()
        vm.changeContent("old in-flight"); advanceTimeBy(501); runCurrent()
        vm.changeContent("latest before freeze")
        val freezing = async { f.registry.freezeAndFlush() }; runCurrent()
        assertFalse(freezing.isCompleted)
        f.barrier!!.complete(Unit); runCurrent()
        val frozen = freezing.await()
        assertEquals(listOf("old in-flight", "latest before freeze"), f.stored.map { it.content })
        frozen.retire()
        vm.changeContent("stale callback"); vm.flushDraft(); advanceTimeBy(1_000); runCurrent()
        assertEquals("latest before freeze", vm.content)
        assertEquals(2, f.stored.size)
    }
}
