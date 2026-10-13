package com.guanyi.mirra.data.wearable

import androidx.room.withTransaction
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.domain.maintenance.MaintenanceUnavailableException
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.wearable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred

/** One captured Room/gate/startup generation; no reference to a replacement container. */
class WearableCatalogReader(private val database: MirraDatabase, private val gate: StorageMaintenanceGate,
    private val startup: Deferred<Unit>) : WearableCatalogSource {
    override suspend fun read(selectedIds: List<String>) = WearableCatalogProjection.project(items(), selectedIds)
    override suspend fun choices() = items().filter { it.status == LearningItemStatus.IN_PROGRESS }
        .sortedWith(compareByDescending<LearningItemEntity> { it.isMainline }.thenBy { it.id })
        .map { WearableCatalogChoice(it.id, it.name, it.isMainline) }
    private suspend fun items(): List<LearningItemEntity> {
        if (!startup.isCompleted) throw WearableSourceBusy()
        try {
            startup.await()
            return gate.operation { database.withTransaction { database.learningItemDao().listAll() } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: MaintenanceUnavailableException) { throw WearableSourceBusy() }
        catch (_: Exception) { throw WearableSourceUnavailable() }
    }
}
