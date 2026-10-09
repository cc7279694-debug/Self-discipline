package com.guanyi.mirra.data.backup

import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.maintenance.MaintenanceCoordinator
import com.guanyi.mirra.domain.maintenance.PendingEditRegistry
import kotlinx.coroutines.Deferred

/** Captured instances belong to one resource epoch; never resolved from the latest container. */
interface BackupStorageOwner {
    val backupDatabase: MirraDatabase
    val backupPreferences: ManagedPreferences
    val storageGate: StorageMaintenanceGate
    val pendingEdits: PendingEditRegistry
    val storagePaths: RestoreResources
    val startup: Deferred<Unit>
    suspend fun assertRuntimeQuiescent()
    suspend fun closeStorageOwners()
}

interface BackupStorageHost {
    suspend fun requireCurrent(owner: BackupStorageOwner)
    suspend fun setMaintenanceBusy(busy: Boolean)
    suspend fun replace(owner: BackupStorageOwner, candidate: RestoreResources, permit: MaintenanceCoordinator.ExclusivePermit)
    /** Restoration outlives a disappearing Activity/ViewModel; it is NOT fire-and-forget. */
    suspend fun runRestore(block: suspend () -> Unit) = block()
}
