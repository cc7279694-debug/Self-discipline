package com.guanyi.mirra.data.maintenance

import android.net.Uri
import com.guanyi.mirra.data.repository.ImageRepository
import com.guanyi.mirra.data.storage.CameraTarget
import com.guanyi.mirra.domain.maintenance.InvalidOperationPermitException
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class GateImageRepository(
    private val delegate: ImageRepository, private val gate: StorageMaintenanceGate,
) : ImageRepository by delegate {
    private data class CameraRequest(val target: CameraTarget, val lease: StorageMaintenanceGate.OperationLease)
    private val cameraMutex = Mutex()
    private val cameraRequests = mutableMapOf<String, CameraRequest>()

    override suspend fun importFromGallery(noteId: String, uris: List<Uri>) =
        gate.writerOperation { delegate.importFromGallery(noteId, uris) }

    override suspend fun createCameraTarget(): CameraTarget {
        val lease = gate.openLease()
        var target: CameraTarget? = null
        try {
            val created = lease.operation { delegate.createCameraTarget() }
            target = created
            cameraMutex.withLock {
                check(!cameraRequests.containsKey(created.tempPath)) { "Camera target already registered" }
                cameraRequests[created.tempPath] = CameraRequest(created, lease)
            }
            return created
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    target?.let { created -> lease.operation { delegate.cancelCameraTarget(created) } }
                } finally { lease.releaseAndJoin() }
            }
            throw failure
        }
    }

    override suspend fun completeCameraImport(noteId: String, target: CameraTarget) =
        consumeCameraRequest(target) { delegate.completeCameraImport(noteId, target) }

    override suspend fun cancelCameraTarget(target: CameraTarget) =
        consumeCameraRequest(target) { delegate.cancelCameraTarget(target) }

    override suspend fun updateCaption(imageId: String, caption: String?) =
        gate.writerOperation { delegate.updateCaption(imageId, caption) }
    override suspend fun deleteImage(imageId: String) = gate.writerOperation { delegate.deleteImage(imageId) }
    override suspend fun reconcileStorage() = gate.writerOperation { delegate.reconcileStorage() }

    private suspend fun <T> consumeCameraRequest(target: CameraTarget, block: suspend () -> T): T {
        val request = cameraMutex.withLock {
            val registered = cameraRequests[target.tempPath] ?: throw InvalidOperationPermitException()
            // ActivityResult reconstructs the value. Neither an arbitrary path nor a new epoch may publish it.
            if (registered.target.uri.toString() != target.uri.toString()) throw InvalidOperationPermitException()
            cameraRequests.remove(target.tempPath)
            registered
        }
        try {
            return request.lease.operation { block() }
        } finally { request.lease.releaseAndJoin() }
    }
}
