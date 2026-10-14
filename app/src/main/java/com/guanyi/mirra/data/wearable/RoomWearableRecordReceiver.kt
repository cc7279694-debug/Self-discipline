package com.guanyi.mirra.data.wearable

import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.WearableCompletedRecordEntity
import com.guanyi.mirra.domain.maintenance.MaintenanceUnavailableException
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.wearable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred

/** Captured Room/startup/gate only. No latest-container lookup and no SDK work inside a transaction. */
class RoomWearableRecordReceiver(private val database: MirraDatabase, private val gate: StorageMaintenanceGate,
    private val startup: Deferred<Unit>, private val clock: () -> Long = System::currentTimeMillis) : WearableRecordReceiver {
    private val dao = database.wearableCompletedRecordDao()

    override suspend fun offer(installationId: String, sessionId: String, digest: String, bytes: Int): WearableStoredRecord? {
        requireOffer(installationId, sessionId, digest, bytes)
        return operation {
            val row = dao.get(installationId, sessionId) ?: return@operation null
            verifyStored(row)
            if (row.digest != digest || row.bytes != bytes) throw WearableRecordStorageException("CONFLICT")
            // Even a matching offer needs complete incoming original bytes before ACK.
            WearableStoredRecord(row.installationId, row.sessionId, row.digest, row.bytes)
        }
    }

    override suspend fun persist(canonical: String, digest: String): WearableRecordReceipt {
        val incoming = WearableLocalRecord.parse(canonical, digest)
        return operation {
            val old = dao.get(incoming.installationId, incoming.sessionId)
            if (old != null) {
                verifyStored(old)
                if (old.canonical != incoming.canonical || old.digest != incoming.digest || old.bytes != incoming.bytes)
                    throw WearableRecordStorageException("CONFLICT")
            } else {
                val receivedAt = clock()
                check(receivedAt >= 0) { "Invalid receipt time" }
                val row = WearableCompletedRecordEntity(incoming.installationId, incoming.sessionId,
                    incoming.canonical, incoming.digest, incoming.bytes, receivedAt)
                dao.insert(row)
                check(dao.get(row.installationId, row.sessionId) == row) { "Stored record differs" }
                verifyStored(row)
            }
            WearableRecordReceipt(incoming.installationId, incoming.sessionId, incoming.digest, incoming.bytes, old != null)
        }
    }

    override suspend fun count(): Long = operation { dao.count() }

    private suspend fun verifyStored(row: WearableCompletedRecordEntity) {
        val original = WearableLocalRecord.parse(row.canonical, row.digest)
        check(row.installationId == original.installationId && row.sessionId == original.sessionId &&
            row.bytes == original.bytes && row.receivedAt >= 0) { "Invalid stored record" }
        check(dao.canonicalBytes(row.installationId, row.sessionId)?.contentEquals(original.canonical.toByteArray(Charsets.UTF_8)) == true) {
            "Stored UTF-8 bytes differ"
        }
    }

    private suspend fun <T> operation(block: suspend () -> T): T {
        if (!startup.isCompleted) throw WearableSourceBusy()
        try {
            startup.await()
            // withTransaction returns only after commit, before the caller can produce an ACK.
            return gate.writerOperation { database.withTransaction { block() } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: WearableRecordStorageException) { throw failure }
        catch (_: MaintenanceUnavailableException) { throw WearableSourceBusy() }
        catch (_: Exception) { throw WearableSourceUnavailable() }
    }

    private fun requireOffer(installationId: String, sessionId: String, digest: String, bytes: Int) {
        val ordinal = sessionId.substringAfterLast(':').toLongOrNull()
        if (!installationId.matches(Regex("[A-Za-z0-9_-]{1,64}")) || !sessionId.startsWith("$installationId:") ||
            !sessionId.substringAfterLast(':').matches(Regex("[1-9][0-9]{0,15}")) ||
            ordinal == null || ordinal !in 1L..9_007_199_254_740_991L ||
            sessionId != installationId + ":" + ordinal.toString() ||
            !digest.matches(Regex("[0-9a-f]{64}")) || bytes !in 1..8192)
            throw WearableRecordStorageException("INVALID_RECORD")
    }
}
