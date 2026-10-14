package com.guanyi.mirra.data.wearable

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.wearable.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearableRecordReceiverTest {
    private lateinit var database: MirraDatabase
    @Before fun open() { database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MirraDatabase::class.java).build() }
    @After fun close() { database.close() }

    @Test fun commitRetainsExactRecordAndDuplicateDoesNotWriteAnotherRowOrChangeReceiptTime() = runTest {
        val receiver = RoomWearableRecordReceiver(database, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit), { 123L })
        val record = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
        assertNull(receiver.offer(record.installationId, record.sessionId, record.digest, record.bytes))
        assertFalse(receiver.persist(record.canonical, record.digest).alreadyPresent)
        val first = database.wearableCompletedRecordDao().get(record.installationId, record.sessionId)!!
        assertEquals(record.canonical, first.canonical)
        assertEquals(record.digest, first.digest)
        assertEquals(123L, first.receivedAt)
        assertTrue(receiver.persist(record.canonical, record.digest).alreadyPresent)
        assertEquals(first, database.wearableCompletedRecordDao().get(record.installationId, record.sessionId))
        assertEquals(1L, receiver.count())
        assertEquals(record.digest, receiver.offer(record.installationId, record.sessionId, record.digest, record.bytes)?.digest)
        val legacyTables = listOf("learning_items", "study_intents", "study_sessions", "notes", "session_segments", "focus_events")
        legacyTables.forEach { table -> database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            assertTrue(it.moveToFirst()); assertEquals("Receiver must not invent phone business facts", 0L, it.getLong(0))
        } }
    }

    @Test fun sameStableIdentityWithDifferentValidatedBytesIsConflictAndOriginalIsImmutable() = runTest {
        val receiver = RoomWearableRecordReceiver(database, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit))
        val first = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
        val changed = WearableLocalRecord.parseCanonical(wearableCompletedFixture().replace("中文与📖", "变更后的书名"))
        receiver.persist(first.canonical, first.digest)
        try { receiver.persist(changed.canonical, changed.digest); fail("Different original bytes were replaced") }
        catch (failure: WearableRecordStorageException) { assertEquals("CONFLICT", failure.code) }
        assertEquals(first.canonical, database.wearableCompletedRecordDao().get(first.installationId, first.sessionId)?.canonical)
        assertEquals(1L, receiver.count())
    }

    @Test fun concurrentIdenticalDeliveriesCommitOneImmutableRow() = runTest {
        val receiver = RoomWearableRecordReceiver(database, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit))
        val record = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
        val outcomes = (1..8).map { async { receiver.persist(record.canonical, record.digest) } }.awaitAll()
        assertEquals(1, outcomes.count { !it.alreadyPresent })
        assertEquals(1L, receiver.count())
    }

    @Test fun unfinishedStartupOrExclusiveMaintenanceCannotPersist() = runTest {
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val record = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
        val busy = RoomWearableRecordReceiver(database, gate, CompletableDeferred())
        try { busy.persist(record.canonical, record.digest); fail("Unfinished startup admitted") } catch (_: WearableSourceBusy) { }
        val receiver = RoomWearableRecordReceiver(database, gate, CompletableDeferred(Unit))
        gate.coordinator.withExclusive(1_000) {
            try { receiver.persist(record.canonical, record.digest); fail("Exclusive admitted") } catch (_: WearableSourceBusy) { }
        }
        assertEquals(0L, receiver.count())
    }

    @Test fun retiredReceiverNeverReopensItsCapturedDatabase() = runTest {
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val receiver = RoomWearableRecordReceiver(database, gate, CompletableDeferred(Unit))
        val record = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
        gate.coordinator.withExclusive(1_000) { gate.retire(it, "record receiver fixture replacement") }
        database.close()
        try { receiver.persist(record.canonical, record.digest); fail("Retired owner admitted") } catch (_: WearableSourceUnavailable) { }
        assertFalse(database.isOpen)
    }

    @Test fun corruptStoredCanonicalNeverReceivesAlreadyImportedReceipt() = runTest {
        val receiver = RoomWearableRecordReceiver(database, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit))
        val record = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
        receiver.persist(record.canonical, record.digest)
        database.openHelper.writableDatabase.execSQL("UPDATE wearable_completed_records SET canonical='broken' WHERE installationId=? AND sessionId=?",
            arrayOf(record.installationId, record.sessionId))
        try { receiver.offer(record.installationId, record.sessionId, record.digest, record.bytes); fail("Corrupt row acknowledged") }
        catch (_: WearableSourceUnavailable) { }
    }

    @Test fun offerRejectsExtraIdentitySeparatorsBeforeLookingUpARecord() = runTest {
        val receiver = RoomWearableRecordReceiver(database, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit))
        val record = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
        listOf("install-a::1", "install-a:x:1", "install-a:01", "install-a:9007199254740992").forEach { sessionId ->
            try { receiver.offer(record.installationId, sessionId, record.digest, record.bytes); fail("Noncanonical offer ID admitted") }
            catch (failure: WearableRecordStorageException) { assertEquals("INVALID_RECORD", failure.code) }
        }
        assertEquals(0L, receiver.count())
    }

    @Test fun realSqliteInsertFailureReturnsNoReceiptAndRollbackCanSafelyRetry() = runTest {
        val receiver = RoomWearableRecordReceiver(database, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit), { 123L })
        val record = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fixture_abort_record_insert BEFORE INSERT ON wearable_completed_records BEGIN SELECT RAISE(ABORT, 'fixture write failure'); END")
        var receipt: WearableRecordReceipt? = null
        try {
            receipt = receiver.persist(record.canonical, record.digest)
            fail("Failed SQLite transaction returned a durable receipt")
        } catch (_: WearableSourceUnavailable) { }
        assertNull(receipt)
        assertEquals(0L, receiver.count())
        assertNull(database.wearableCompletedRecordDao().get(record.installationId, record.sessionId))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fixture_abort_record_insert")
        val retry = receiver.persist(record.canonical, record.digest)
        assertFalse(retry.alreadyPresent)
        assertEquals(record.digest, retry.digest)
        assertEquals(record.bytes, retry.bytes)
        assertEquals(record.canonical, database.wearableCompletedRecordDao().get(record.installationId, record.sessionId)?.canonical)
        assertEquals(1L, receiver.count())
    }

    @Test fun fileBackedRoomReopenRetainsExactCommittedBytesAndDuplicateReceipt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "record-reopen-test-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val path = File(root, "fixture.sqlite").absolutePath
        val first = Room.databaseBuilder(context, MirraDatabase::class.java, path).build()
        var reopened: MirraDatabase? = null
        try {
            val record = WearableLocalRecord.parseCanonical(wearableCompletedFixture())
            val firstReceiver = RoomWearableRecordReceiver(first, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit), { 123L })
            assertFalse(firstReceiver.persist(record.canonical, record.digest).alreadyPresent)
            val committed = first.wearableCompletedRecordDao().get(record.installationId, record.sessionId)!!
            first.close()
            val replacement = Room.databaseBuilder(context, MirraDatabase::class.java, path).build()
            reopened = replacement
            val nextReceiver = RoomWearableRecordReceiver(replacement, StorageMaintenanceGate(leaseScope = backgroundScope), CompletableDeferred(Unit), { 999L })
            assertEquals(1L, nextReceiver.count())
            assertEquals(committed, replacement.wearableCompletedRecordDao().get(record.installationId, record.sessionId))
            assertArrayEquals(record.canonical.toByteArray(Charsets.UTF_8), replacement.wearableCompletedRecordDao().canonicalBytes(record.installationId, record.sessionId))
            assertTrue(nextReceiver.persist(record.canonical, record.digest).alreadyPresent)
            assertEquals(123L, replacement.wearableCompletedRecordDao().get(record.installationId, record.sessionId)?.receivedAt)
            assertEquals(1L, nextReceiver.count())
            assertFalse(first.isOpen)
        } finally {
            reopened?.close()
            first.close()
            check(root.parentFile == context.cacheDir && root.name.startsWith("record-reopen-test-"))
            check(root.deleteRecursively())
        }
    }
}

internal fun wearableCompletedFixture() = """{"schemaVersion":1,"installationId":"install-a","session":{"id":"install-a:1","ordinal":1,"bookSnapshot":{"id":"book-a","title":"中文与📖","startPage":12},"firstActionSnapshot":{"id":"preset-read","text":"翻到第12页，读第一段。"},"confirmedAt":{"wallMs":1000,"ownerId":"owner-a","monotonicMs":null,"monotonicVerified":false},"startAt":{"wallMs":1000,"ownerId":"owner-a","monotonicMs":null,"monotonicVerified":false},"intervals":[{"kind":"FOCUS","start":{"wallMs":1000,"ownerId":"owner-a","monotonicMs":null,"monotonicVerified":false},"end":{"wallMs":61000,"ownerId":"owner-a","monotonicMs":null,"monotonicVerified":false},"plannedMinutes":null}],"finishedAt":{"wallMs":61000,"ownerId":"owner-a","monotonicMs":null,"monotonicVerified":false},"endPage":null}}"""
