package com.guanyi.mirra.data.wearable

import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.domain.maintenance.*
import com.guanyi.mirra.domain.wearable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred

/** Every instance owns one captured generation; callbacks cannot look up a replacement database. */
class WearableSnapshotReader(
    private val database: MirraDatabase,
    private val gate: StorageMaintenanceGate,
    private val startup: Deferred<Unit>,
    private val wallClock: () -> Long,
    private val availability: (String) -> MonitorAvailability,
) : WearableSnapshotSource {
    override suspend fun read(lastSeenSessionId: String?): WearableSnapshot {
        if (!startup.isCompleted) throw WearableSourceBusy()
        try {
            startup.await()
            return gate.operation {
                database.withTransaction {
                    val activeSession = database.sessionDao().getActive()
                    val intent = if (activeSession == null) database.intentDao().getActive() else null
                    // New preparation is current truth; an old followed result cannot hide it.
                    val session = activeSession ?: if (intent == null) lastSeenSessionId?.let { id ->
                        database.sessionDao().get(id)?.takeIf { it.endedAt != null }
                    } else null
                    if (session != null) {
                        val convertedIntent = database.intentDao().get(session.intentId)
                        if (convertedIntent == null || convertedIntent.learningItemId != session.learningItemId ||
                            convertedIntent.outcome != com.guanyi.mirra.data.local.entity.IntentOutcome.CONVERTED ||
                            convertedIntent.convertedAt == null || convertedIntent.activeSlot != null) throw WearableSourceUnavailable()
                    }
                    val item = when {
                        session != null -> database.learningItemDao().get(session.learningItemId)
                        intent != null -> database.learningItemDao().get(intent.learningItemId)
                        else -> database.learningItemDao().getMainline()
                    }
                    val context = session?.let { database.focusDao().getContext(it.id) }
                    val segment = session?.let { database.focusDao().getActiveSegment(it.id) }
                    WearableProjection.project(item, intent, session, context, segment, wallClock(),
                        session?.let { availability(it.id) } ?: MonitorAvailability.UNKNOWN)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: MaintenanceUnavailableException) { throw WearableSourceBusy() }
        catch (_: WearableSourceBusy) { throw WearableSourceBusy() }
        catch (_: Exception) { throw WearableSourceUnavailable() }
    }
}
