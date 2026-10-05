package com.guanyi.mirra

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.data.local.MIGRATION_1_2
import com.guanyi.mirra.data.local.MIGRATION_2_3
import com.guanyi.mirra.data.local.MIGRATION_3_4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionRiskAppSnapshotEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.data.repository.CreateTopicResult
import com.guanyi.mirra.data.repository.DefaultReadingAnalyticsRepository
import com.guanyi.mirra.data.repository.DefaultReadingRecordRepository
import com.guanyi.mirra.data.search.SearchIndexWriter
import com.guanyi.mirra.di.AppContainer
import com.guanyi.mirra.domain.AnalyticsTimeContext
import com.guanyi.mirra.domain.DefaultSearchEngine
import com.guanyi.mirra.domain.EffectiveEstimateUnavailableReason
import com.guanyi.mirra.domain.EffectiveReadingService
import com.guanyi.mirra.domain.ReadingAnalyticsService
import com.guanyi.mirra.domain.ReadingRecordService
import com.guanyi.mirra.domain.TimelineTrust
import com.guanyi.mirra.navigation.TopLevelDestination
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Catches cleared/re-written history, broken references/media, reset preferences and effective
 * backfill. The two persistent fixtures require explicit opt-in; they are not a real UI/monitoring
 * reading session. Ordinary instrumentation only runs the isolated migration/reopen regression.
 */
@RunWith(AndroidJUnit4::class)
class ModuleThreeDDataPreservationTest {
    @get:Rule val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), MirraDatabase::class.java,
    )

    @Test fun seedPreservationFixture() = runBlocking(Dispatchers.IO) {
        requireDedicatedThreeDScenario("data_seed")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as MirraApplication).container
        container.startup.await()
        val marker = markerFile(context)
        check(!marker.exists() && !File("${marker.path}.bak").exists()) {
            "A preservation marker already exists; retain it and inspect its evidence before reseeding"
        }
        val originalPreferences = readPreferences(container.appPreferencesRepository)
        val database = openInstalledDatabase(context)
        var markerSaved = false
        var primaryFailure: Throwable? = null
        try {
            check(database.sessionDao().getActive() == null && database.intentDao().getActive() == null) {
                "Preservation requires an idle dedicated AVD; existing workflows must not be changed"
            }
            val runId = UUID.randomUUID().toString()
            val token = searchToken(runId)
            val item = container.learningItemRepository.create(
                "Mirra3D preservation $token", 200, 30, "专用数据保留验收动作", false,
            )
            val standalone = container.noteRepository.createStandalone(
                item.id, "$token standalone fixture", NoteSemanticType.UNDERSTANDING, 25,
            )
            val camera = container.imageRepository.createCameraTarget()
            writeJpeg(context.filesDir.resolve(camera.tempPath))
            val image = container.imageRepository.completeCameraImport(standalone.id, camera)
            container.imageRepository.updateCaption(image.id, "$token controlled caption")
            val topic = container.topicRepository.createAndLink(standalone.id, "$token topic")
            check(topic is CreateTopicResult.Created) { "The new fixture topic must not reuse an existing topic" }
            val riskPackage = "com.guanyi.mirra.test.preservation.p" + runId.replace("-", "")
            check(database.focusDao().listRiskApps().none { it.packageName == riskPackage })
            container.focusRepository.replaceRiskApp(riskPackage, OLD_RISK_LABEL)

            // Closed, newly INSERTed legacy-shaped rows. No old row is updated, no READY/monitoring
            // result is fabricated. The literal 20ms/10ms fixture intervals are only test data.
            awaitWallAtLeast(Math.addExact(item.createdAt, 40L))
            val legacyIntentId = UUID.randomUUID().toString()
            val legacySessionId = UUID.randomUUID().toString()
            val trustedIntentId = UUID.randomUUID().toString()
            val trustedSessionId = UUID.randomUUID().toString()
            database.withTransaction {
                insertClosedFixture(database, item.id, legacyIntentId, legacySessionId,
                    item.createdAt, item.createdAt + 20L, 10, 20, "$token legacy record")
                insertClosedFixture(database, item.id, trustedIntentId, trustedSessionId,
                    item.createdAt + 20L, item.createdAt + 40L, 20, 30, "$token trusted old record")
                database.focusDao().insertContext(SessionFocusContextEntity(
                    sessionId = trustedSessionId, snapshotVersion = 1, pollIntervalMillis = 1_000,
                    usageAccessAtStart = true, monitoringStatus = MonitoringCoverage.FULL,
                    monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null,
                    requestedEndPage = null, closeoutStartedAt = null,
                    lastHeartbeatAt = item.createdAt + 40L,
                    createdAt = item.createdAt + 20L, updatedAt = item.createdAt + 40L,
                ))
                database.focusDao().insertSegment(closedSegment(trustedSessionId,
                    SessionSegmentType.FOCUS, item.createdAt + 20L, item.createdAt + 30L))
                database.focusDao().insertSegment(closedSegment(trustedSessionId,
                    SessionSegmentType.BREAK, item.createdAt + 30L, item.createdAt + 40L))
                database.focusDao().insertRiskSnapshots(listOf(
                    SessionRiskAppSnapshotEntity(trustedSessionId, riskPackage, OLD_RISK_LABEL),
                ))
                val index = SearchIndexWriter(database, DefaultSearchEngine())
                index.reindexSession(legacySessionId)
                index.reindexSession(trustedSessionId)
            }

            // The production Workflow Repository commits a normal NONE/UNMONITORED closeout.
            // Calling it directly does not invoke SessionManager's platform DND/FGS callbacks.
            val intent = container.studyWorkflowRepository.createIntent(item.id, false)
            container.studyWorkflowRepository.markTransitioned(intent.id)
            val session = container.studyWorkflowRepository.startSession(intent.id, 30)
            val sessionNote = container.noteRepository.save(
                item.id, session.id, "$token session fixture", 35, NoteSemanticType.SUMMARY,
            )
            container.topicRepository.link(sessionNote.id, topic.topic.id)
            container.studyWorkflowRepository.updateCurrentPage(session.id, 40)
            val endSample = awaitWallAtLeast(Math.addExact(session.startedAt, 1L))
            container.studyWorkflowRepository.beginCloseout(session.id, 40, endSample)
            container.studyWorkflowRepository.completeCloseout(session.id)
            container.focusRepository.replaceRiskApp(riskPackage, CURRENT_RISK_LABEL)
            val fixture = Fixture(runId, item.id, standalone.id, sessionNote.id, image.id,
                topic.topic.id, intent.id, session.id, legacyIntentId, legacySessionId,
                trustedIntentId, trustedSessionId, riskPackage)
            assertFixture(container, database, fixture)
            writePreferences(container.appPreferencesRepository, SEEDED_PREFERENCES)
            assertEquals(SEEDED_PREFERENCES, readPreferences(container.appPreferencesRepository))
            val baseline = captureFrame(database, container, fixture)
            writeMarker(marker, JSONObject().put("formatVersion", 1).put("state", "SEEDED")
                .put("sourceFreeze", SOURCE_FREEZE).put("fixture", fixture.toJson())
                .put("originalPreferences", originalPreferences.toJson())
                .put("baseline", frameJson(baseline)).put("baselineHash", frameHash(baseline)))
            markerSaved = true
            println("MIRRA_3D_DATA_SEED run=$runId baseline=${frameHash(baseline)} persistent=mirra.db")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanup = CleanupFailures(primaryFailure)
            try {
                if (!markerSaved) cleanup.attempt {
                    writePreferences(container.appPreferencesRepository, originalPreferences)
                }
            } finally {
                cleanup.attempt { database.close() }
            }
            cleanup.finish()
        }
    }

    @Test fun assertPreservationFixture() = runBlocking(Dispatchers.IO) {
        requireDedicatedThreeDScenario("data_assert")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as MirraApplication).container
        container.startup.await()
        val marker = markerFile(context)
        val evidence = readMarker(marker)
        check(evidence.getInt("formatVersion") == 1 && evidence.getString("state") == "SEEDED") {
            "An unconsumed seed marker is required; assertion must not silently reseed"
        }
        assertEquals(SOURCE_FREEZE, evidence.getString("sourceFreeze"))
        val fixture = Fixture.fromJson(evidence.getJSONObject("fixture"))
        val original = PreferenceValues.fromJson(evidence.getJSONObject("originalPreferences"))
        val baseline = readFrame(evidence.getJSONObject("baseline"))
        assertEquals(evidence.getString("baselineHash"), frameHash(baseline))
        val database = openInstalledDatabase(context)
        var before: Map<String, String>? = null
        var verified = false
        var primaryFailure: Throwable? = null
        try {
            before = captureFrame(database, container, fixture)
            evidence.put("observedBefore", frameJson(before)).put("observedBeforeHash", frameHash(before))
            assertEquals("All fixture fields, references, files and preferences must survive", baseline, before)
            assertFixture(container, database, fixture)
            val after = captureFrame(database, container, fixture)
            evidence.put("observedAfterRead", frameJson(after)).put("observedAfterReadHash", frameHash(after))
            assertEquals("Record/search/analytics reads must not backfill history", before, after)
            verified = true
            println("MIRRA_3D_DATA_ASSERT run=${fixture.runId} before=${frameHash(before)} afterRead=${frameHash(after)}")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            // Preserve the pre-restoration evidence first. Restoration cannot make a mismatched
            // seeded preference pass. Even a failed assertion restores only these four settings.
            val cleanup = CleanupFailures(primaryFailure)
            try {
                cleanup.attempt {
                    evidence.put("state", if (verified) "VERIFIED_BEFORE_RESTORE" else "ASSERTION_FAILED")
                    writeMarker(marker, evidence)
                }
                cleanup.attempt {
                    writePreferences(container.appPreferencesRepository, original)
                    val restored = readPreferences(container.appPreferencesRepository)
                    evidence.put("restoredPreferencesHash", preferenceHash(restored))
                    assertEquals("Restore the values that existed before this fixture", original, restored)
                    val restoredFrame = captureFrame(database, container, fixture)
                    before?.let { assertEquals("Restoring preferences must not touch business facts/media",
                        it - PREFERENCES_KEY, restoredFrame - PREFERENCES_KEY) }
                    evidence.put("afterRestoreBusinessHash", frameHash(restoredFrame - PREFERENCES_KEY))
                        .put("state", if (verified) "VERIFIED_PREFERENCES_RESTORED" else "FAILED_PREFERENCES_RESTORED")
                    writeMarker(marker, evidence)
                    println("MIRRA_3D_DATA_RESTORE run=${fixture.runId} preferences=${preferenceHash(restored)} business=${frameHash(restoredFrame - PREFERENCES_KEY)}")
                }
            } finally {
                cleanup.attempt { database.close() }
            }
            cleanup.finish()
        }
    }

    @Test fun coverInstallPreservesExistingFactsWithoutEffectiveBackfill() = runBlocking(Dispatchers.IO) {
        // A deterministic migration/reopen proof, not an adb install or production database.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mirra-3d-preservation-${UUID.randomUUID()}.db"
        val oldDatabase = migrationHelper.createDatabase(name, 3)
        val before: Map<String, String>
        try {
            oldDatabase.execSQL("INSERT INTO learning_items VALUES ('old-book','专用旧历史','IN_PROGRESS',200,31,NULL,'',0,3600000,NULL)")
            for (index in 0..2) {
                val start = index * 1_200_000L
                val end = start + 1_200_000L
                oldDatabase.execSQL("INSERT INTO study_intents VALUES (?,?,?,?,?,?,?,NULL)",
                    arrayOf<Any?>("old-i$index", "old-book", start, start, start, start, "CONVERTED"))
                oldDatabase.execSQL("INSERT INTO study_sessions VALUES (?,?,?,?,NULL,?,?,?,?,?,?,NULL)",
                    arrayOf<Any?>("old-s$index", "old-book", "old-i$index", start, end,
                        1 + index * 10, 11 + index * 10, 11 + index * 10, "NORMAL", "旧总结$index"))
            }
            oldDatabase.execSQL("INSERT INTO notes VALUES ('old-note','old-book','old-s0','UNDERSTANDING','旧笔记',5,1000,1000)")
            before = oldTableHashes(oldDatabase)
        } finally { oldDatabase.close() }
        var reopened: MirraDatabase? = null
        try {
            migrationHelper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).close()
            reopened = Room.databaseBuilder(context, MirraDatabase::class.java, name)
                .addMigrations(MIGRATION_3_4).build()
            val database = checkNotNull(reopened)
            assertEquals(before, oldTableHashes(database.openHelper.readableDatabase))
            for (table in FOCUS_TABLES) {
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst()); assertEquals("No historical $table backfill", 0L, it.getLong(0))
                }
            }
            val records = DefaultReadingRecordRepository(database)
            for (index in 0..2) {
                val source = checkNotNull(records.observe("old-s$index").first())
                assertNull(source.context); assertTrue(source.segments.isEmpty()); assertTrue(source.riskSnapshots.isEmpty())
                val projection = ReadingRecordService().project(source)
                assertEquals(1_200_000L, projection.totalDurationMillis)
                assertEquals(10L, projection.pagesRead)
                assertEquals(TimelineTrust.MONITORING_INCOMPLETE, projection.trust)
                assertNull(projection.effectiveFocusMillis)
            }
            val time = AnalyticsTimeContext(Instant.ofEpochMilli(4_600_000L), ZoneId.of("UTC"))
            val analytics = DefaultReadingAnalyticsRepository(database)
            val history = analytics.observeHistory("old-book").first()
            val overall = checkNotNull(ReadingAnalyticsService().selectAnalyticsWindow(history, time))
            assertEquals(3, overall.sessions.size); assertEquals(30L, overall.totalPagesRead)
            assertEquals(Duration.ofHours(1), overall.totalDuration)
            assertEquals(30.0, overall.overallPagesPerHour, 0.0)
            val effectiveSource = analytics.observeEffectiveRecentForItem("old-book", 0L, 4_600_000L).first()
            assertTrue(effectiveSource.contexts.isEmpty()); assertTrue(effectiveSource.segments.isEmpty())
            val estimate = EffectiveReadingService().estimate(checkNotNull(database.learningItemDao().get("old-book")), effectiveSource, time)
            assertNull(estimate.window); assertNull(estimate.remainingEffectiveReadingTime)
            assertEquals(EffectiveEstimateUnavailableReason.INSUFFICIENT_WINDOW, estimate.unavailableReason)
            assertEquals(before, oldTableHashes(database.openHelper.readableDatabase))
            database.close()
            reopened = Room.databaseBuilder(context, MirraDatabase::class.java, name).build()
            assertEquals(before, oldTableHashes(reopened.openHelper.readableDatabase))
            println("MIRRA_3D_OLD_HISTORY isolated=v3-to-v4-and-reopen checksum=${frameHash(before)} noBackfill=true")
        } finally {
            reopened?.close()
            // This exact, newly-created UUID database only. Never delete installed mirra.db/media.
            check(name.startsWith("mirra-3d-preservation-") && name.endsWith(".db"))
            context.deleteDatabase(name)
        }
    }

    private fun openInstalledDatabase(context: Context): MirraDatabase {
        val database = Room.databaseBuilder(context, MirraDatabase::class.java, "mirra.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        assertEquals(context.getDatabasePath("mirra.db").canonicalPath,
            File(checkNotNull(database.openHelper.readableDatabase.path)).canonicalPath)
        assertEquals(4, database.openHelper.readableDatabase.version)
        return database
    }

    private suspend fun assertFixture(container: AppContainer, database: MirraDatabase, fixture: Fixture) {
        fixture.validate()
        val item = checkNotNull(container.learningItemRepository.get(fixture.itemId))
        assertEquals(40, item.currentPage); assertEquals(200, item.totalPages); assertNull(item.mainlineSlot)
        val standalone = checkNotNull(container.noteRepository.observe(fixture.standaloneNoteId).first())
        val sessionNote = checkNotNull(container.noteRepository.observe(fixture.sessionNoteId).first())
        assertEquals(fixture.itemId, standalone.learningItemId); assertNull(standalone.sessionId)
        assertEquals(fixture.normalSessionId, sessionNote.sessionId); assertEquals(35, sessionNote.pageNumber)
        for (noteId in listOf(fixture.standaloneNoteId, fixture.sessionNoteId)) {
            assertEquals(listOf(fixture.topicId), container.topicRepository.observeForNote(noteId).first().map { it.id })
        }
        val image = checkNotNull(container.imageRepository.get(fixture.imageId))
        assertEquals(fixture.standaloneNoteId, image.noteId)
        assertEquals(80, image.width); assertEquals(120, image.height)
        val normal = checkNotNull(container.readingRecordRepository.observe(fixture.normalSessionId).first())
        assertEquals(SessionEndType.NORMAL, normal.session.endType); assertNull(normal.session.activeSlot)
        assertEquals(FocusCloseoutState.COMPLETED, normal.context?.closeoutState)
        assertEquals(1, normal.noteCount); assertNull(container.readingRecordService.project(normal).effectiveFocusMillis)
        val legacy = checkNotNull(container.readingRecordRepository.observe(fixture.legacySessionId).first())
        assertNull(legacy.context); assertTrue(legacy.segments.isEmpty()); assertTrue(legacy.riskSnapshots.isEmpty())
        assertNull(container.readingRecordService.project(legacy).effectiveFocusMillis)
        val trusted = checkNotNull(container.readingRecordRepository.observe(fixture.trustedSessionId).first())
        assertEquals(FocusCloseoutState.ACTIVE, trusted.context?.closeoutState)
        assertEquals(1, trusted.context?.snapshotVersion)
        val projection = container.readingRecordService.project(trusted)
        assertEquals(TimelineTrust.COMPLETE_TRUSTED, projection.trust)
        assertEquals(10L, projection.effectiveFocusMillis); assertEquals(20L, projection.totalDurationMillis)
        assertEquals(OLD_RISK_LABEL, trusted.riskSnapshots.single { it.packageName == fixture.riskPackage }.labelSnapshot)
        assertEquals(CURRENT_RISK_LABEL, database.focusDao().listRiskApps().single { it.packageName == fixture.riskPackage }.labelSnapshot)
        assertEquals(setOf(fixture.normalSessionId, fixture.legacySessionId, fixture.trustedSessionId),
            container.readingAnalyticsRepository.observeHistory(fixture.itemId).first().map { it.sessionId }.toSet())
        val effective = container.readingAnalyticsRepository.observeEffectiveRecentForItem(fixture.itemId, 0L, Long.MAX_VALUE).first()
        assertEquals(listOf(fixture.trustedSessionId),
            container.effectiveReadingService.qualify(effective, container.analyticsTimeProvider.snapshot()).map { it.sessionId })
        val hits = container.searchRepository.search(searchToken(fixture.runId)).items.map { it.id }.toSet()
        assertTrue(hits.containsAll(listOf(fixture.itemId, fixture.standaloneNoteId, fixture.sessionNoteId,
            fixture.topicId, fixture.legacySessionId, fixture.trustedSessionId)))
        assertNull(database.sessionDao().getActive()); assertNull(database.intentDao().getActive())
    }

    private suspend fun captureFrame(database: MirraDatabase, container: AppContainer, fixture: Fixture): Map<String, String> {
        val hashes = database.withTransaction {
            val sqlite = database.openHelper.readableDatabase
            val byItem = listOf(fixture.itemId)
            linkedMapOf(
                "learning_items" to tableHash(sqlite, "SELECT * FROM learning_items WHERE id = ? ORDER BY id", byItem),
                "study_intents" to tableHash(sqlite, "SELECT * FROM study_intents WHERE learningItemId = ? ORDER BY id", byItem),
                "study_sessions" to tableHash(sqlite, "SELECT * FROM study_sessions WHERE learningItemId = ? ORDER BY id", byItem),
                "notes" to tableHash(sqlite, "SELECT * FROM notes WHERE learningItemId = ? ORDER BY id", byItem),
                "image_assets" to tableHash(sqlite, "SELECT a.* FROM image_assets a JOIN notes n ON n.id = a.noteId WHERE n.learningItemId = ? ORDER BY a.id", byItem),
                "topics" to tableHash(sqlite, "SELECT * FROM topics WHERE id = ? ORDER BY id", listOf(fixture.topicId)),
                "note_topic_cross_refs" to tableHash(sqlite, "SELECT r.* FROM note_topic_cross_refs r JOIN notes n ON n.id = r.noteId WHERE n.learningItemId = ? ORDER BY r.noteId, r.topicId", byItem),
                "risk_apps" to tableHash(sqlite, "SELECT * FROM risk_apps WHERE packageName = ? ORDER BY packageName", listOf(fixture.riskPackage)),
                "session_focus_contexts" to tableHash(sqlite, "SELECT c.* FROM session_focus_contexts c JOIN study_sessions s ON s.id = c.sessionId WHERE s.learningItemId = ? ORDER BY c.sessionId", byItem),
                "session_segments" to tableHash(sqlite, "SELECT c.* FROM session_segments c JOIN study_sessions s ON s.id = c.sessionId WHERE s.learningItemId = ? ORDER BY c.id", byItem),
                "session_risk_app_snapshots" to tableHash(sqlite, "SELECT c.* FROM session_risk_app_snapshots c JOIN study_sessions s ON s.id = c.sessionId WHERE s.learningItemId = ? ORDER BY c.sessionId, c.packageName", byItem),
                "focus_events" to tableHash(sqlite, "SELECT c.* FROM focus_events c JOIN study_sessions s ON s.id = c.sessionId WHERE s.learningItemId = ? ORDER BY c.id", byItem),
                // rowid is derived FTS layout, not a business field.
                "search_fts" to tableHash(sqlite, "SELECT entityType, entityId, searchableText, normalizedTokens FROM search_fts WHERE entityId IN (?,?,?,?,?,?,?) ORDER BY entityType, entityId, searchableText, normalizedTokens",
                    listOf(fixture.itemId, fixture.standaloneNoteId, fixture.sessionNoteId, fixture.topicId,
                        fixture.normalSessionId, fixture.legacySessionId, fixture.trustedSessionId)),
            )
        }
        val images = (database.imageAssetDao().listForNote(fixture.standaloneNoteId) +
            database.imageAssetDao().listForNote(fixture.sessionNoteId)).sortedBy { it.id }
        assertEquals(listOf(fixture.imageId), images.map { it.id })
        for (image in images) {
            val file = container.imageRepository.displayFile(image.localPath)
            assertTrue("Referenced image remains a local file", file.isFile)
            assertEquals(image.fileSize, file.length())
            val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "Referenced image must remain decodable" }
            try { assertEquals(image.width, bitmap.width); assertEquals(image.height, bitmap.height) }
            finally { bitmap.recycle() }
            hashes["image_file:${image.id}"] = sha256(file.readBytes())
        }
        hashes[PREFERENCES_KEY] = preferenceHash(readPreferences(container.appPreferencesRepository))
        return hashes.toSortedMap()
    }

    private suspend fun insertClosedFixture(database: MirraDatabase, itemId: String, intentId: String,
        sessionId: String, start: Long, end: Long, startPage: Int, endPage: Int, summary: String) {
        database.intentDao().insert(StudyIntentEntity(intentId, itemId, start, start, start, start, IntentOutcome.CONVERTED, null))
        database.sessionDao().insert(StudySessionEntity(sessionId, itemId, intentId, start, null, end,
            startPage, endPage, endPage, SessionEndType.NORMAL, summary, null))
    }

    private fun closedSegment(sessionId: String, type: SessionSegmentType, start: Long, end: Long) =
        SessionSegmentEntity(UUID.randomUUID().toString(), sessionId, type, start, end, null, null, null,
            relatedSegmentId = null, activeSlot = null)

    private suspend fun awaitWallAtLeast(boundary: Long): Long = withTimeout(5_000) {
        var observed = System.currentTimeMillis()
        while (observed < boundary) { delay(1); observed = System.currentTimeMillis() }
        observed
    }

    private fun oldTableHashes(database: SupportSQLiteDatabase) =
        listOf("learning_items", "study_intents", "study_sessions", "notes").associateWith {
            tableHash(database, "SELECT * FROM $it ORDER BY id", emptyList())
        }

    private fun tableHash(database: SupportSQLiteDatabase, sql: String, arguments: List<String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            database.query(SimpleSQLiteQuery(sql, arguments.toTypedArray())).use { cursor ->
                val columns = cursor.columnNames.indices.sortedBy { cursor.columnNames[it] }
                output.writeInt(columns.size); columns.forEach { writeString(output, cursor.columnNames[it]) }
                output.writeInt(cursor.count)
                while (cursor.moveToNext()) for (column in columns) {
                    val type = cursor.getType(column)
                    output.writeInt(type)
                    when (type) {
                        Cursor.FIELD_TYPE_NULL -> Unit
                        Cursor.FIELD_TYPE_INTEGER -> output.writeLong(cursor.getLong(column))
                        Cursor.FIELD_TYPE_FLOAT -> output.writeDouble(cursor.getDouble(column))
                        Cursor.FIELD_TYPE_STRING -> writeString(output, cursor.getString(column))
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).let { output.writeInt(it.size); output.write(it) }
                        else -> error("Unsupported SQLite field type")
                    }
                }
            }
        }
        return sha256(bytes.toByteArray())
    }

    private fun frameHash(frame: Map<String, String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(frame.size)
            for ((key, value) in frame.toSortedMap()) { writeString(output, key); writeString(output, value) }
        }
        return sha256(bytes.toByteArray())
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size); output.write(bytes)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun frameJson(frame: Map<String, String>) = JSONObject().also { json ->
        for ((key, value) in frame.toSortedMap()) json.put(key, value)
    }

    private fun readFrame(json: JSONObject): Map<String, String> = json.keys().asSequence().associateWith {
        json.getString(it).also { hash -> require(Regex("[0-9a-f]{64}").matches(hash)) }
    }.toSortedMap()

    private fun markerFile(context: Context) = context.noBackupFilesDir
        .resolve("mirra3d-preservation").resolve("manifest.json")

    private fun readMarker(file: File): JSONObject {
        check(file.isFile && file.length() in 1L..65_536L) { "Missing or invalid preservation marker" }
        return JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
    }

    private fun writeMarker(file: File, json: JSONObject) {
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }

    private suspend fun readPreferences(repository: AppPreferencesRepository) = PreferenceValues(
        repository.lastDestination.first().storageValue, repository.themeId.first().storageValue,
        repository.dndEnabled.first(), repository.crossAppInterventionEnabled.first(),
    )

    private suspend fun writePreferences(repository: AppPreferencesRepository, values: PreferenceValues) {
        repository.setLastDestination(TopLevelDestination.entries.single { it.storageValue == values.destination })
        repository.setThemeId(MirraThemeId.entries.single { it.storageValue == values.theme })
        repository.setDndEnabled(values.dnd); repository.setCrossAppInterventionEnabled(values.crossApp)
    }

    private fun preferenceHash(values: PreferenceValues) = frameHash(mapOf(
        "last_destination" to values.destination, "theme_id" to values.theme,
        "dnd_enabled" to values.dnd.toString(), "cross_app_intervention_enabled" to values.crossApp.toString(),
    ))

    private fun writeJpeg(file: File) {
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.RGB_565).apply { eraseColor(Color.WHITE) }
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
        finally { bitmap.recycle() }
    }

    /** Keep the data assertion as the primary cause, while still failing a successful body on cleanup errors. */
    private class CleanupFailures(private val primaryFailure: Throwable?) {
        private var firstFailure = primaryFailure

        suspend fun attempt(action: suspend () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val first = firstFailure
                if (first == null) firstFailure = failure
                else if (first !== failure) first.addSuppressed(failure)
            }
        }

        fun finish() {
            if (primaryFailure == null) firstFailure?.let { throw it }
        }
    }

    private data class PreferenceValues(val destination: String, val theme: String, val dnd: Boolean, val crossApp: Boolean) {
        fun toJson() = JSONObject().put("destination", destination).put("theme", theme).put("dnd", dnd).put("crossApp", crossApp)
        companion object {
            fun fromJson(json: JSONObject) = PreferenceValues(json.getString("destination"), json.getString("theme"),
                json.getBoolean("dnd"), json.getBoolean("crossApp")).also {
                require(TopLevelDestination.entries.any { entry -> entry.storageValue == it.destination })
                require(MirraThemeId.entries.any { entry -> entry.storageValue == it.theme })
            }
        }
    }

    private data class Fixture(val runId: String, val itemId: String, val standaloneNoteId: String,
        val sessionNoteId: String, val imageId: String, val topicId: String, val normalIntentId: String,
        val normalSessionId: String, val legacyIntentId: String, val legacySessionId: String,
        val trustedIntentId: String, val trustedSessionId: String, val riskPackage: String) {
        private fun fields() = linkedMapOf("runId" to runId, "itemId" to itemId,
            "standaloneNoteId" to standaloneNoteId, "sessionNoteId" to sessionNoteId,
            "imageId" to imageId, "topicId" to topicId, "normalIntentId" to normalIntentId,
            "normalSessionId" to normalSessionId, "legacyIntentId" to legacyIntentId,
            "legacySessionId" to legacySessionId, "trustedIntentId" to trustedIntentId,
            "trustedSessionId" to trustedSessionId, "riskPackage" to riskPackage)
        fun validate() {
            fields().filterKeys { it != "riskPackage" }.values.forEach(UUID::fromString)
            require(riskPackage == "com.guanyi.mirra.test.preservation.p${runId.replace("-", "")}")
            require(fields().filterKeys { it != "riskPackage" && it != "runId" }.values.distinct().size == 11)
        }
        fun toJson() = JSONObject().also { json -> fields().forEach { (key, value) -> json.put(key, value) } }
        companion object {
            fun fromJson(json: JSONObject) = Fixture(json.getString("runId"), json.getString("itemId"),
                json.getString("standaloneNoteId"), json.getString("sessionNoteId"), json.getString("imageId"),
                json.getString("topicId"), json.getString("normalIntentId"), json.getString("normalSessionId"),
                json.getString("legacyIntentId"), json.getString("legacySessionId"), json.getString("trustedIntentId"),
                json.getString("trustedSessionId"), json.getString("riskPackage")).also { it.validate() }
        }
    }

    private companion object {
        const val SOURCE_FREEZE = "80ece95cf24918627e57a57bb6a6d94c253528b0"
        const val PREFERENCES_KEY = "app_preferences"
        const val OLD_RISK_LABEL = "Mirra3D controlled snapshot label"
        const val CURRENT_RISK_LABEL = "Mirra3D controlled current label"
        val SEEDED_PREFERENCES = PreferenceValues("knowledge", "night", true, true)
        val FOCUS_TABLES = listOf("risk_apps", "session_focus_contexts", "session_risk_app_snapshots", "session_segments", "focus_events")
        fun searchToken(runId: String) = "mirrapreservation" + runId.replace("-", "")
    }
}
