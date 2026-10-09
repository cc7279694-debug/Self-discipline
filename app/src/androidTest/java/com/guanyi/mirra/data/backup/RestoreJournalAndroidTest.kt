package com.guanyi.mirra.data.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.FocusEventEntity
import com.guanyi.mirra.data.local.entity.FocusEventType
import com.guanyi.mirra.data.local.entity.ImageAssetEntity
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.entity.NoteTopicCrossRef
import com.guanyi.mirra.data.local.entity.RiskAppEntity
import com.guanyi.mirra.data.local.entity.SearchFtsEntity
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionRiskAppSnapshotEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.entity.TopicEntity
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.data.preferences.PortableAppPreferencesSnapshot
import com.guanyi.mirra.data.preferences.PreferenceSnapshotValue
import com.guanyi.mirra.data.preferences.StoredPreferenceValue
import com.guanyi.mirra.domain.DefaultSearchEngine
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android storage in synthetic cache roots; Error injection is not a physical power cut. */
@RunWith(AndroidJUnit4::class)
class RestoreJournalAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun everyDurableBoundaryRecoversOneCompleteAndroidGeneration() = runBlocking {
        var oldSelections = 0
        var newSelections = 0
        RESTORE_ANDROID_BOUNDARIES.forEach { boundary ->
            val fixture = RestoreAndroidFixture(context, restoreAndroidTestRoot(context, "restore-journal-android", UUID.randomUUID().toString()))
            try {
                fixture.seed()
                val oldFrame = restoreAndroidResourceFrame(fixture.live)
                val newFrame = restoreAndroidResourceFrame(fixture.candidate)
                val crashed = fixture.journal { if (it == boundary) throw RestoreInjectedDeath() }
                try {
                    crashed.apply(crashed.prepare(fixture.candidate))
                    fail("Expected injected death at $boundary")
                } catch (_: RestoreInjectedDeath) {
                    // Bypass the normal Exception compensation path, as an abruptly lost caller does.
                }
                assertTrue("Recovery decision must survive $boundary", crashed.hasPendingRecovery())
                val restarted = fixture.journal()
                restarted.recoverBeforeOpeningStores()
                val committed = boundary in RESTORE_ANDROID_COMMITTED_BOUNDARIES
                assertEquals("All three resource bytes must select the same generation at $boundary", if (committed) newFrame else oldFrame, restoreAndroidResourceFrame(fixture.live))
                fixture.assertGeneration(if (committed) RestoreTestGeneration.NEW else RestoreTestGeneration.OLD)
                assertFalse(restarted.hasPendingRecovery())
                if (committed) newSelections++ else oldSelections++
                println("MIRRA_4C_ANDROID_JOURNAL boundary=$boundary selected=${if (committed) "new" else "old"} resources=3 tables=12 unknownOldTypes=8")
            } finally { fixture.removeSyntheticRoot() }
        }
        assertEquals(9, oldSelections)
        assertEquals(2, newSelections)
        println("MIRRA_4C_ANDROID_JOURNAL_MATRIX boundaries=11 old=9 new=2 realRoom=true realDataStore=true androidFsync=true physicalPowerCut=false")
    }

    @Test fun completedSwitchRetainsCandidateBytesAndClearsDecision() = runBlocking {
        val fixture = RestoreAndroidFixture(context, restoreAndroidTestRoot(context, "restore-journal-android", UUID.randomUUID().toString()))
        try {
            fixture.seed()
            val expected = restoreAndroidResourceFrame(fixture.candidate)
            val subject = fixture.journal()
            subject.apply(subject.prepare(fixture.candidate))
            assertFalse(subject.hasPendingRecovery())
            assertEquals(expected, restoreAndroidResourceFrame(fixture.live))
            fixture.assertGeneration(RestoreTestGeneration.NEW)
        } finally { fixture.removeSyntheticRoot() }
    }

    @Test fun finalValidationFailureRestoresOriginalBytesAndAllUnknownPreferenceTypes() = runBlocking {
        val fixture = RestoreAndroidFixture(context, restoreAndroidTestRoot(context, "restore-journal-android", UUID.randomUUID().toString()))
        try {
            fixture.seed()
            val original = restoreAndroidResourceFrame(fixture.live)
            val subject = RestoreJournal(fixture.journalRoot, fixture.live, androidDurableFiles(), verify = {
                verifyClosedDatabase(it)
                if (it.database == fixture.live.database) throw IOException("Injected final Android validation failure")
            })
            try {
                subject.apply(subject.prepare(fixture.candidate))
                fail("Validation failure must refuse the candidate")
            } catch (_: IOException) {
                // The ordinary failure path must also restore every original preference key/type.
            }
            assertEquals(original, restoreAndroidResourceFrame(fixture.live))
            assertFalse(subject.hasPendingRecovery())
            fixture.assertGeneration(RestoreTestGeneration.OLD)
        } finally { fixture.removeSyntheticRoot() }
    }

    @Test fun thousandNoteSnapshotArchiveAndJournalRoundTripRetainsAllTwelveTables() = runBlocking {
        val fixture = RestoreAndroidFixture(context, restoreAndroidTestRoot(context, "restore-journal-android", UUID.randomUUID().toString()))
        try {
            fixture.seed(noteCount = 1_000)
            val source = fixture.openDatabase(fixture.live)
            val snapshot = try {
                BackupDatabaseSnapshot(context).capture(source, fixture.live.images, File(fixture.root, "snapshot"))
            } finally { source.close() }
            assertEquals(12, snapshot.tableCounts.size)
            assertEquals(1_000L, snapshot.tableCounts.getValue("notes"))
            assertTrue(snapshot.tableCounts.filterKeys { it != "notes" }.values.all { it == 1L })
            val archive = File(fixture.root, "synthetic-backup.zip")
            BackupArchive().create(snapshot, archive, restoreTestPortablePreferences(RestoreTestGeneration.OLD), 60_002)
            val validated = BackupArchive().validateAndExtract(archive, File(fixture.root, "extracted"))
            val clean = BackupDatabaseSnapshot(context).validateAndReconstruct(validated, File(fixture.root, "reconstructed"))
            assertEquals(snapshot.tableCounts, clean.tableCounts)
            assertArrayEquals(File(fixture.live.images, RESTORE_TEST_IMAGE).readBytes(), File(clean.imagesDirectory, RESTORE_TEST_IMAGE).readBytes())
            val candidate = RestoreResources(clean.databaseFile, File(clean.root, "preferences.preferences_pb"), clean.imagesDirectory)
            ManagedPreferences(candidate.preferences).let { owner ->
                try { owner.writePortable(validated.portablePrefs) } finally { owner.close() }
            }
            val journal = fixture.journal()
            journal.apply(journal.prepare(candidate))
            fixture.assertGeneration(RestoreTestGeneration.OLD, noteCount = 1_000, includeOldUnknownKeys = false)
            assertFalse(journal.hasPendingRecovery())
            println("MIRRA_4C_ANDROID_LARGE_ROUNDTRIP notes=1000 tables=12 jpeg=1 chineseSearch=true formalArchive=true")
        } finally { fixture.removeSyntheticRoot() }
    }
}

internal val RESTORE_ANDROID_COMMITTED_BOUNDARIES = setOf("COMMITTED", "CLEANUP_PENDING")
internal val RESTORE_ANDROID_BOUNDARIES = listOf(
    "PREPARED", "SWITCHING", "database:removed", "database:published",
    "preferences:removed", "preferences:published", "images:removed", "images:published",
    "VERIFIED", "COMMITTED", "CLEANUP_PENDING",
)
internal const val RESTORE_TEST_IMAGE = "11223344-5566-7788-99aa-bbccddeeff00.jpg"
internal class RestoreInjectedDeath : Error("Synthetic abrupt restore caller loss")
internal enum class RestoreTestGeneration(val token: String, val text: String) { OLD("old", "原始中文"), NEW("new", "候选中文") }

/** Only these two cache families are accepted. No installed database, preferences or image path is used. */
internal fun restoreAndroidTestRoot(context: Context, family: String, run: String): File {
    require(family in setOf("restore-journal-android", "restore-process-fixture"))
    require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    val cache = context.cacheDir.canonicalFile
    val parent = File(cache, family)
    val root = File(parent, run)
    require(root.canonicalFile == root.absoluteFile && root.path.startsWith(cache.path + File.separator))
    return root
}

internal fun restoreTestPortablePreferences(generation: RestoreTestGeneration) = PortableAppPreferencesSnapshot(
    PreferenceSnapshotValue(if (generation == RestoreTestGeneration.OLD) TopLevelDestination.Knowledge else TopLevelDestination.Profile, true),
    PreferenceSnapshotValue(if (generation == RestoreTestGeneration.OLD) MirraThemeId.NIGHT else MirraThemeId.MONO, true),
    PreferenceSnapshotValue(generation == RestoreTestGeneration.OLD, generation == RestoreTestGeneration.OLD),
    PreferenceSnapshotValue(generation == RestoreTestGeneration.NEW, generation == RestoreTestGeneration.NEW),
)

/** Compare closed byte identities before constructing Room/DataStore, which may create their sidecars. */
internal fun restoreAndroidResourceFrame(resources: RestoreResources): Map<String, String> {
    fun sha(file: File): String {
        require(file.isFile && file.length() in 1..8_388_608 && file.canonicalFile == file.absoluteFile)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    val images = checkNotNull(resources.images.listFiles()).sortedBy { it.name }
    require(images.size == 1 && images.single().name == RESTORE_TEST_IMAGE)
    return linkedMapOf("database" to sha(resources.database), "preferences" to sha(resources.preferences), "images/$RESTORE_TEST_IMAGE" to sha(images.single()))
}

internal class RestoreAndroidFixture(private val context: Context, val root: File) {
    private val files = androidDurableFiles()
    val live = paths("live")
    val candidate = paths("candidate")
    val journalRoot = File(root, "journal")
    private fun paths(name: String): RestoreResources {
        val directory = File(root, name)
        return RestoreResources(File(directory, "database.sqlite"), File(directory, "preferences.preferences_pb"), File(directory, "images"))
    }
    fun journal(effect: (String) -> Unit = {}) = RestoreJournal(journalRoot, live, files, ::verifyClosedDatabase, effect)
    fun openDatabase(resources: RestoreResources): MirraDatabase = Room.databaseBuilder(context, MirraDatabase::class.java, resources.database.absolutePath)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()

    suspend fun seed(noteCount: Int = 1) {
        require(noteCount in 1..1_000)
        check(!root.exists()) { "Never overwrite an existing restore evidence fixture" }
        files.directory(root)
        seedGeneration(live, RestoreTestGeneration.OLD, noteCount)
        seedGeneration(candidate, RestoreTestGeneration.NEW, noteCount)
    }

    private suspend fun seedGeneration(resources: RestoreResources, generation: RestoreTestGeneration, noteCount: Int) {
        files.directory(checkNotNull(resources.database.parentFile))
        files.directory(resources.images)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            bitmap.eraseColor(if (generation == RestoreTestGeneration.OLD) 0xff2350a0.toInt() else 0xffb06030.toInt())
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }.toByteArray()
        } finally { bitmap.recycle() }
        val image = File(resources.images, RESTORE_TEST_IMAGE)
        files.write(image, jpeg)
        val token = generation.token
        val database = openDatabase(resources)
        try {
            database.withTransaction {
            database.learningItemDao().insert(LearningItemEntity("$token-book", "${generation.text}学习", LearningItemStatus.IN_PROGRESS, 320, 42, 1, "拿起书", 1, 60_002, null))
            database.intentDao().insert(StudyIntentEntity("$token-intent", "$token-book", 1, 1, 2, 2, IntentOutcome.CONVERTED, null))
            database.sessionDao().insert(StudySessionEntity("$token-session", "$token-book", "$token-intent", 2, null, 60_002, 40, 42, 42, SessionEndType.NORMAL, "${generation.text}总结", null))
            repeat(noteCount) { index ->
                val id = restoreTestNoteId(generation, index)
                val content = "${generation.text}笔记 $index"
                database.noteDao().insert(NoteEntity(id, "$token-book", "$token-session", NoteSemanticType.UNDERSTANDING, content, 35, 2, 60_002))
                val document = DefaultSearchEngine().buildDocument(listOf(content))
                database.searchFtsDao().insert(SearchFtsEntity("NOTE", id, document.searchableText, document.normalizedTokens))
            }
            database.imageAssetDao().insert(ImageAssetEntity("$token-image", restoreTestNoteId(generation, 0), "images/$RESTORE_TEST_IMAGE", "${generation.text}图注", 8, 8, jpeg.size.toLong(), 2))
            database.topicDao().insert(TopicEntity("$token-topic", "${generation.text}主题", 2))
            database.topicDao().insertCrossRef(NoteTopicCrossRef(restoreTestNoteId(generation, 0), "$token-topic"))
            database.focusDao().upsertRiskApp(RiskAppEntity("com.example.$token", "${generation.text}风险", 1, 2))
            database.focusDao().insertContext(SessionFocusContextEntity(
                sessionId = "$token-session", monitoringStatus = MonitoringCoverage.FULL, monitoringLostAt = null,
                priorDndInterruptionFilter = null, dndRuleId = null, dndLifecycle = DndLifecycle.NOT_APPLIED,
                closeoutState = FocusCloseoutState.COMPLETED, requestedEndPage = 42, closeoutStartedAt = 60_002,
                lastHeartbeatAt = 60_002, createdAt = 2, updatedAt = 60_002,
            ))
            database.focusDao().insertRiskSnapshots(listOf(SessionRiskAppSnapshotEntity("$token-session", "com.example.$token", "${generation.text}历史风险")))
            database.focusDao().insertSegment(SessionSegmentEntity("$token-segment", "$token-session", SessionSegmentType.FOCUS, 2, 60_002, null, null, null, 0, null, null))
            database.focusDao().insertEvent(FocusEventEntity("$token-event", "$token-session", FocusEventType.USER_PRESENT, 3, null, "$token-segment", null))
            }
        } finally { database.close() }
        val preferences = ManagedPreferences(resources.preferences)
        try {
            preferences.writePortable(restoreTestPortablePreferences(generation))
            if (generation == RestoreTestGeneration.OLD) preferences.dataStore.edit {
                it[booleanPreferencesKey("unknown_boolean")] = true
                it[floatPreferencesKey("unknown_float")] = 1.25f
                it[doublePreferencesKey("unknown_double")] = 2.5
                it[intPreferencesKey("unknown_int")] = 7
                it[longPreferencesKey("unknown_long")] = 9_000_000_000L
                it[stringPreferencesKey("unknown_string")] = "保留全部旧偏好"
                it[stringSetPreferencesKey("unknown_set")] = setOf("中文", "exact")
                it[byteArrayPreferencesKey("unknown_bytes")] = byteArrayOf(1, -2, 3)
            }
            assertEquals(restoreTestPortablePreferences(generation), preferences.repository.readStrictSnapshot().portable)
        } finally { preferences.close() }
        // Journal receives no live storage owners; explicitly establish file and parent durability.
        java.io.RandomAccessFile(resources.database, "rw").use { it.fd.sync() }
        java.io.RandomAccessFile(resources.preferences, "rw").use { it.fd.sync() }
        files.syncDirectory(checkNotNull(resources.database.parentFile))
        verifyClosedDatabase(resources)
    }

    suspend fun assertGeneration(generation: RestoreTestGeneration, noteCount: Int = 1, includeOldUnknownKeys: Boolean = generation == RestoreTestGeneration.OLD) {
        verifyClosedDatabase(live)
        val database = openDatabase(live)
        try {
            val raw = database.openHelper.readableDatabase
            AUTHORITATIVE_TABLES.forEach { table ->
                raw.query("SELECT COUNT(*) FROM `$table`").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("Preserve $table", if (table == "notes") noteCount.toLong() else 1L, it.getLong(0))
                }
            }
            assertEquals("${generation.text}学习", database.learningItemDao().get("${generation.token}-book")?.name)
            assertEquals("${generation.text}笔记 0", database.noteDao().get(restoreTestNoteId(generation, 0))?.content)
            assertEquals("${generation.text}笔记 ${noteCount - 1}", database.noteDao().get(restoreTestNoteId(generation, noteCount - 1))?.content)
            val query = checkNotNull(DefaultSearchEngine().buildQuery(generation.text)).expression
            raw.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM search_fts WHERE search_fts MATCH ? AND entityType='NOTE'", arrayOf(query))).use {
                assertTrue(it.moveToFirst()); assertEquals(noteCount.toLong(), it.getLong(0))
            }
            val asset = database.imageAssetDao().listAll().single()
            assertEquals("${generation.token}-image", asset.id)
            assertEquals(restoreTestNoteId(generation, 0), asset.noteId)
            assertEquals("images/$RESTORE_TEST_IMAGE", asset.localPath)
            assertEquals(File(live.images, RESTORE_TEST_IMAGE).length(), asset.fileSize)
            val decoded = checkNotNull(BitmapFactory.decodeFile(File(live.images, RESTORE_TEST_IMAGE).path))
            try { assertEquals(asset.width, decoded.width); assertEquals(asset.height, decoded.height) } finally { decoded.recycle() }
        } finally { database.close() }
        val preferences = ManagedPreferences(live.preferences)
        try {
            val snapshot = preferences.repository.readStrictSnapshot()
            assertEquals(restoreTestPortablePreferences(generation), snapshot.portable)
            val values = snapshot.original.values
            val unknownKeys = values.keys.filter { it.startsWith("unknown_") }.toSet()
            if (includeOldUnknownKeys) {
                assertEquals(setOf("unknown_boolean", "unknown_float", "unknown_double", "unknown_int", "unknown_long", "unknown_string", "unknown_set", "unknown_bytes"), unknownKeys)
                assertEquals(StoredPreferenceValue.BooleanValue(true), values["unknown_boolean"])
                assertEquals(StoredPreferenceValue.FloatValue(1.25f), values["unknown_float"])
                assertEquals(StoredPreferenceValue.DoubleValue(2.5), values["unknown_double"])
                assertEquals(StoredPreferenceValue.IntValue(7), values["unknown_int"])
                assertEquals(StoredPreferenceValue.LongValue(9_000_000_000L), values["unknown_long"])
                assertEquals(StoredPreferenceValue.StringValue("保留全部旧偏好"), values["unknown_string"])
                assertEquals(setOf("中文", "exact"), (values["unknown_set"] as StoredPreferenceValue.StringSetValue).value)
                assertArrayEquals(byteArrayOf(1, -2, 3), (values["unknown_bytes"] as StoredPreferenceValue.ByteArrayValue).value)
            } else assertTrue(unknownKeys.isEmpty())
            assertFalse("Absent keys must not be synthesized", values.containsKey("unknown_missing"))
            assertEquals(if (includeOldUnknownKeys) 11 else 3, values.size)
        } finally { preferences.close() }
    }

    fun removeSyntheticRoot() {
        val parent = checkNotNull(root.parentFile)
        require(parent.name in setOf("restore-journal-android", "restore-process-fixture"))
        require(parent.canonicalFile.parentFile == context.cacheDir.canonicalFile && root.canonicalFile == root.absoluteFile)
        if (root.exists()) files.deleteOwned(root, parent)
        if (parent.isDirectory) files.syncDirectory(parent)
    }
}

private fun restoreTestNoteId(generation: RestoreTestGeneration, index: Int) = "${generation.token}-note-${index.toString().padStart(4, '0')}"
