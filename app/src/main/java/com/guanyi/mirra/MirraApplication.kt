package com.guanyi.mirra

import android.app.Application
import com.guanyi.mirra.di.AppContainer
import com.guanyi.mirra.di.DefaultAppContainer
import com.guanyi.mirra.di.checkpointAndCloseDatabase
import com.guanyi.mirra.di.startStorageBootstrap
import androidx.room.Room
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.MIGRATION_1_2
import com.guanyi.mirra.data.local.MIGRATION_2_3
import com.guanyi.mirra.data.local.MIGRATION_3_4
import com.guanyi.mirra.platform.focus.MonitoringPlatformRuntime
import com.guanyi.mirra.data.backup.*
import com.guanyi.mirra.domain.maintenance.MaintenanceCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArraySet

enum class StoragePresentation { OPEN, BUSY, SWITCHING, BLOCKED }
data class AppStorageState(
    val phase: StoragePresentation,
    val container: AppContainer? = null,
    val statusMessage: String? = null,
)

class MirraApplication : Application(), BackupStorageHost {
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restoreSerial = Mutex()
    private val activityReaders = CopyOnWriteArraySet<MainActivity>()
    private val mutableStorageState = MutableStateFlow(AppStorageState(StoragePresentation.SWITCHING))
    val storageState = mutableStorageState.asStateFlow()
    /** Retain partial construction too: a failed constructor cannot abandon a DataStore owner. */
    private class StorageGeneration {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var database: MirraDatabase? = null
        var preferences: ManagedPreferences? = null
        var runtime: MonitoringPlatformRuntime? = null
        var selected: DefaultAppContainer? = null
    }
    private val storageGenerations = mutableSetOf<StorageGeneration>()
    private var currentRuntime: MonitoringPlatformRuntime? = null
    val monitoringPlatform get() = checkNotNull(currentRuntime) { "Storage bootstrap is not ready" }
    val monitoringPlatformOrNull get() = currentRuntime
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Before Room, DataStore, runtime reconciliation or orphan cleanup is constructed.
        RestoreFileAccess.blocked = true
        val journal by lazy { installedRestoreJournal(applicationContext) }
        startStorageBootstrap(
            scope = maintenanceScope,
            serial = restoreSerial,
            pendingRecovery = { journal.hasPendingRecovery() },
            recover = { journal.recoverBeforeOpeningStores() },
            construct = ::constructSelectedGeneration,
            open = { openSelectedGeneration(it, StoragePresentation.OPEN) },
            close = ::closeStorageGenerations,
            blocked = ::showStorageBlocked,
        )
    }

    private fun constructSelectedGeneration(): StorageGeneration {
        val generation = StorageGeneration()
        storageGenerations += generation
        val paths = installedRestoreResources(applicationContext)
        val database = Room.databaseBuilder(applicationContext, MirraDatabase::class.java, paths.database.absolutePath)
            .openHelperFactory(com.guanyi.mirra.data.maintenance.EpochOpenHelperFactory())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        generation.database = database
        val preferences = ManagedPreferences(paths.preferences)
        generation.preferences = preferences
        val runtime = MonitoringPlatformRuntime(applicationContext)
        generation.runtime = runtime
        val selected = DefaultAppContainer(applicationContext, runtime, this, paths, preferences, database, generation.scope)
        generation.selected = selected
        // Keep the existing startup contract available, while presentation and native access stay closed.
        container = selected
        return generation
    }

    private suspend fun openSelectedGeneration(
        generation: StorageGeneration,
        presentation: StoragePresentation,
        statusMessage: String? = null,
    ) {
        val selected = checkNotNull(generation.selected)
        selected.startup.await()
        withContext(Dispatchers.Main.immediate) {
            currentRuntime = checkNotNull(generation.runtime)
            RestoreFileAccess.blocked = false
            mutableStorageState.value = AppStorageState(presentation, selected, statusMessage)
        }
    }

    private suspend fun closeStorageGenerations() = withContext(NonCancellable) {
        for (generation in storageGenerations.toList()) {
            val selected = generation.selected
            if (selected != null) selected.closeStorageOwners()
            else {
                // Construction can fail after any one of these owners has been created.
                generation.runtime?.closeMaintenanceOwners()
                generation.scope.coroutineContext[Job]?.cancelAndJoin()
                generation.preferences?.close()
                generation.database?.let(::checkpointAndCloseDatabase)
            }
            if (currentRuntime === generation.runtime) currentRuntime = null
            storageGenerations.remove(generation)
        }
    }

    private fun showStorageBlocked() {
        RestoreFileAccess.blocked = true
        mutableStorageState.value = AppStorageState(StoragePresentation.BLOCKED)
    }

    private suspend fun detachStorageGeneration() {
        RestoreFileAccess.blocked = true
        withContext(Dispatchers.Main.immediate) {
            mutableStorageState.value = AppStorageState(StoragePresentation.SWITCHING)
            activityReaders.forEach { it.detachStorageReaders() }
        }
    }

    fun registerReaders(activity: MainActivity) { activityReaders += activity }
    fun unregisterReaders(activity: MainActivity) { activityReaders -= activity }

    override suspend fun requireCurrent(owner: BackupStorageOwner) {
        check(::container.isInitialized && container === owner && mutableStorageState.value.phase in
            setOf(StoragePresentation.OPEN, StoragePresentation.BUSY)) {
            "Storage generation is no longer available"
        }
    }
    override suspend fun setMaintenanceBusy(busy: Boolean) {
        withContext(Dispatchers.Main.immediate) {
            val state = mutableStorageState.value
            if (state.phase in setOf(StoragePresentation.OPEN, StoragePresentation.BUSY)) {
                mutableStorageState.value = state.copy(
                    phase = if (busy) StoragePresentation.BUSY else StoragePresentation.OPEN,
                    statusMessage = if (busy) null else state.statusMessage,
                )
            }
        }
    }
    override suspend fun runRestore(block: suspend () -> Unit) {
        // Caller cancellation can stop waiting, but cannot cancel the resource-switch owner.
        maintenanceScope.async {
            restoreSerial.withLock {
                val previous = mutableStorageState.value.container
                try {
                    block()
                    showRestoreResult(previous, "完整备份已恢复，可以继续使用 Mirra。")
                } catch (failure: Exception) {
                    showRestoreResult(previous, "数据已安全重新打开，但本次恢复未完整结束，请重新检查备份。")
                    throw failure
                }
            }
        }.await()
    }

    private suspend fun showRestoreResult(previous: AppContainer?, message: String) {
        withContext(Dispatchers.Main.immediate) {
            val state = mutableStorageState.value
            if (state.phase == StoragePresentation.OPEN && state.container !== previous) {
                mutableStorageState.value = state.copy(statusMessage = state.statusMessage ?: message)
            }
        }
    }

    fun acknowledgeStorageMessage() {
        mutableStorageState.value = mutableStorageState.value.copy(statusMessage = null)
    }

    override suspend fun replace(owner: BackupStorageOwner, candidate: RestoreResources,
        permit: MaintenanceCoordinator.ExclusivePermit) {
        requireCurrent(owner)
        owner.storageGate.retire(permit, "Restore selects a different storage generation")
        val journal = installedRestoreJournal(applicationContext)
        var readersDetached = false
        var storesClosed = false
        var resourcesSelected = false
        try {
            detachStorageGeneration()
            readersDetached = true
            closeStorageGenerations()
            storesClosed = true
            // Only closed/checkpointed DB, closed protobuf owner and private media are copied.
            journal.apply(journal.prepare(candidate))
            resourcesSelected = true
            // Service maintenance still owns the operation; only its finally can reopen interaction.
            openSelectedGeneration(constructSelectedGeneration(), StoragePresentation.BUSY)
        } catch (failure: Exception) {
            if (!readersDetached) {
                // Retain the old owners if composition disposal itself could not be confirmed.
                showStorageBlocked()
                throw failure
            }
            try { closeStorageGenerations() }
            catch (closeFailure: Exception) {
                failure.addSuppressed(closeFailure)
                showStorageBlocked()
                throw RestoreSafetyException("Storage owners could not be closed", failure)
            }
            if (storesClosed && !resourcesSelected && failure is RestoreRolledBackException) {
                // Only an explicitly verified durable rollback selects OLD. Pointer absence
                // alone can also follow COMMITTED cleanup whose final directory sync failed.
                try {
                    openSelectedGeneration(constructSelectedGeneration(), StoragePresentation.BUSY,
                        "本次恢复未完成，已安全重新打开原数据。")
                } catch (rebindFailure: Exception) {
                    try { closeStorageGenerations() }
                    catch (closeFailure: Exception) { rebindFailure.addSuppressed(closeFailure) }
                    showStorageBlocked()
                    throw RestoreSafetyException("Storage owners could not be reopened", rebindFailure)
                }
            } else {
                // Never choose from a partial/uncertain set of resources in this process.
                showStorageBlocked()
            }
            throw failure
        }
    }

    fun retryRestoreBootstrap() {
        if (mutableStorageState.value.phase != StoragePresentation.BLOCKED) return
        maintenanceScope.launch {
            restoreSerial.withLock {
                if (mutableStorageState.value.phase != StoragePresentation.BLOCKED) return@withLock
                var readersDetached = false
                try {
                    detachStorageGeneration()
                    readersDetached = true
                    // Do not construct another Room/DataStore until every previous owner has closed.
                    closeStorageGenerations()
                    installedRestoreJournal(applicationContext).recoverBeforeOpeningStores()
                    openSelectedGeneration(constructSelectedGeneration(), StoragePresentation.OPEN,
                        "数据已安全重新打开，可以继续使用 Mirra。")
                } catch (failure: Exception) {
                    if (readersDetached) {
                        try { closeStorageGenerations() }
                        catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
                    }
                    showStorageBlocked()
                }
            }
        }
    }
}
