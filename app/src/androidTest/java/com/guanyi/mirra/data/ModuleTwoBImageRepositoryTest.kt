package com.guanyi.mirra.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.repository.DefaultImageRepository
import com.guanyi.mirra.data.repository.DefaultLearningItemRepository
import com.guanyi.mirra.data.repository.DefaultNoteRepository
import com.guanyi.mirra.data.storage.CameraTarget
import com.guanyi.mirra.data.storage.CleanupReport
import com.guanyi.mirra.data.storage.ImageStorageService
import com.guanyi.mirra.data.storage.StoredImage
import com.guanyi.mirra.data.storage.TrashedFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModuleTwoBImageRepositoryTest {
    private lateinit var database: MirraDatabase
    private lateinit var storage: FakeImageStorage
    private lateinit var images: DefaultImageRepository
    private lateinit var notes: DefaultNoteRepository
    private lateinit var learningItems: DefaultLearningItemRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MirraDatabase::class.java).build()
        storage = FakeImageStorage(context.cacheDir.resolve("image-repository-test"))
        images = DefaultImageRepository(database, storage, clock = { 1_000L })
        notes = DefaultNoteRepository(database, storage = storage)
        learningItems = DefaultLearningItemRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        storage.root.deleteRecursively()
    }

    @Test
    fun fallbackSelectionIsHardLimitedAndExternalUrisAreNeverPersisted() = runTest {
        val noteId = createNote()
        val result = images.importFromGallery(noteId, (1..25).map { Uri.parse("content://picker/$it") })

        assertEquals(20, result.added.size)
        assertEquals(0, result.failedCount)
        assertEquals(5, result.rejectedCount)
        assertEquals(20, storage.importedUris.size)
        assertTrue(result.added.all { it.localPath.startsWith("images/") && !it.localPath.contains("content:") })
    }

    @Test
    fun eachImageImportSucceedsOrFailsIndependently() = runTest {
        val noteId = createNote()
        storage.failUris += "content://picker/bad"

        val result = images.importFromGallery(
            noteId,
            listOf(Uri.parse("content://picker/1"), Uri.parse("content://picker/bad"), Uri.parse("content://picker/2")),
        )

        assertEquals(2, result.added.size)
        assertEquals(1, result.failedCount)
        assertEquals(2, images.observeForNote(noteId).first().size)
    }

    @Test
    fun captionAndGlobalListComeFromImageNoteAndLearningItemJoin() = runTest {
        val item = learningItems.create("来源书", 100)
        val note = notes.createStandalone(item.id, "来源笔记", pageNumber = 23)
        val image = images.importFromGallery(note.id, listOf(Uri.parse("content://picker/1"))).added.single()

        images.updateCaption(image.id, "  关键图  ")

        val updated = images.observeForNote(note.id).first().single()
        val global = images.observeAll().first().single()
        assertEquals("关键图", updated.caption)
        assertEquals("来源书", global.learningItemName)
        assertEquals(23, global.notePageNumber)
        assertEquals(note.id, global.image.noteId)
    }

    @Test
    fun imageForeignKeyAndUniquePathAreEnforced() = runTest {
        val noteId = createNote()
        val first = images.importFromGallery(noteId, listOf(Uri.parse("content://picker/1"))).added.single()
        storage.fixedPath = first.localPath

        val duplicate = images.importFromGallery(noteId, listOf(Uri.parse("content://picker/2")))
        val missingNote = images.importFromGallery("missing", listOf(Uri.parse("content://picker/3")))

        assertEquals(1, duplicate.failedCount)
        assertEquals(1, missingNote.failedCount)
        assertEquals(1, images.observeForNote(noteId).first().size)
    }

    @Test
    fun databaseInsertFailureDeletesTheNewFinalFile() = runTest {
        val noteId = createNote()
        images = DefaultImageRepository(database, storage, newId = { "same-image-id" })
        images.importFromGallery(noteId, listOf(Uri.parse("content://picker/first")))

        val result = images.importFromGallery(noteId, listOf(Uri.parse("content://picker/second")))

        assertEquals(1, result.failedCount)
        assertEquals(1, storage.deletedFinals.size)
        assertEquals(1, images.observeForNote(noteId).first().size)
    }

    @Test
    fun imageDeleteRestoresFileWhenDatabaseDeleteFails() = runTest {
        val noteId = createNote()
        val image = images.importFromGallery(noteId, listOf(Uri.parse("content://picker/1"))).added.single()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_image_delete BEFORE DELETE ON image_assets BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )

        assertSuspendFails { images.deleteImage(image.id) }

        assertEquals(listOf(image.localPath), storage.restored.map { it.originalPath })
        assertEquals(image.id, images.observeForNote(noteId).first().single().id)
    }

    @Test
    fun fileMoveFailureLeavesDatabaseRowUntouched() = runTest {
        val noteId = createNote()
        val image = images.importFromGallery(noteId, listOf(Uri.parse("content://picker/1"))).added.single()
        storage.failMove = true

        assertSuspendFails { images.deleteImage(image.id) }

        assertEquals(image.id, images.observeForNote(noteId).first().single().id)
    }

    @Test
    fun deletingNoteStagesAndPurgesAllImages() = runTest {
        val noteId = createNote()
        images.importFromGallery(noteId, listOf(Uri.parse("content://picker/1"), Uri.parse("content://picker/2")))

        notes.delete(noteId)

        assertNull(database.noteDao().get(noteId))
        assertTrue(images.observeForNote(noteId).first().isEmpty())
        assertEquals(2, storage.purged.size)
    }

    @Test
    fun deletingNoteRestoresAllImagesWhenDatabaseDeleteFails() = runTest {
        val noteId = createNote()
        images.importFromGallery(noteId, listOf(Uri.parse("content://picker/1"), Uri.parse("content://picker/2")))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_note_delete BEFORE DELETE ON notes BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )

        assertSuspendFails { notes.delete(noteId) }

        assertEquals(2, storage.restored.size)
        assertEquals(2, images.observeForNote(noteId).first().size)
        assertFalse(storage.purged.isNotEmpty())
    }

    @Test
    fun deletingNoteRestoresAlreadyStagedFilesWhenLaterMoveFails() = runTest {
        val noteId = createNote()
        images.importFromGallery(noteId, listOf(Uri.parse("content://picker/1"), Uri.parse("content://picker/2")))
        storage.failMoveAt = 2

        assertSuspendFails { notes.delete(noteId) }

        assertEquals(1, storage.restored.size)
        assertEquals(2, images.observeForNote(noteId).first().size)
    }

    private suspend fun createNote(): String {
        val item = learningItems.create("图片书", 100)
        return notes.createStandalone(item.id, "图片笔记").id
    }
}

private suspend fun assertSuspendFails(block: suspend () -> Unit) {
    try {
        block()
        throw AssertionError("Expected operation to fail")
    } catch (failure: AssertionError) {
        throw failure
    } catch (_: Throwable) {
        // Expected.
    }
}

private class FakeImageStorage(val root: File) : ImageStorageService {
    val importedUris = mutableListOf<String>()
    val failUris = mutableSetOf<String>()
    val deletedFinals = mutableListOf<String>()
    val restored = mutableListOf<TrashedFile>()
    val purged = mutableListOf<TrashedFile>()
    var fixedPath: String? = null
    var failMove = false
    var failMoveAt: Int? = null
    private var moveCalls = 0

    override suspend fun importUri(source: Uri): StoredImage {
        importedUris += source.toString()
        if (source.toString() in failUris) error("broken image")
        val path = fixedPath ?: "images/${UUID.randomUUID()}.jpg"
        return StoredImage(path, 100, 200, 300)
    }

    override suspend fun createCameraTarget() = CameraTarget("camera/test.jpg", Uri.parse("content://camera/test"))
    override suspend fun importCameraTarget(target: CameraTarget) = StoredImage("images/${UUID.randomUUID()}.jpg", 100, 200, 300)
    override suspend fun discardCameraTarget(target: CameraTarget) = Unit
    override suspend fun moveToTrash(localPath: String): TrashedFile {
        moveCalls += 1
        if (failMove || moveCalls == failMoveAt) error("move failed")
        return TrashedFile(localPath, "image-work/trash/${localPath.substringAfterLast('/')}")
    }
    override suspend fun restoreFromTrash(file: TrashedFile) { restored += file }
    override suspend fun purgeTrash(file: TrashedFile) { purged += file }
    override suspend fun deleteFinal(localPath: String) { deletedFinals += localPath }
    override suspend fun cleanup(referencedPaths: Set<String>, now: Long) = CleanupReport()
    override fun resolveFinal(localPath: String) = root.resolve(localPath)
}
