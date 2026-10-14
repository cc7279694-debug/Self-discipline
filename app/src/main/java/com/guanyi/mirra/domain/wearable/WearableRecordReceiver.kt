package com.guanyi.mirra.domain.wearable

data class WearableStoredRecord(val installationId: String, val sessionId: String, val digest: String, val bytes: Int)
data class WearableRecordReceipt(val installationId: String, val sessionId: String, val digest: String, val bytes: Int,
    val alreadyPresent: Boolean)
class WearableRecordStorageException(val code: String) : IllegalStateException(code)

/** Bound to one storage owner. OFFER is a preflight, never a durable receipt. */
interface WearableRecordReceiver {
    suspend fun offer(installationId: String, sessionId: String, digest: String, bytes: Int): WearableStoredRecord?
    /** Only a completed transaction with an exact original-byte comparison returns a receipt. */
    suspend fun persist(canonical: String, digest: String): WearableRecordReceipt
    suspend fun count(): Long
}
