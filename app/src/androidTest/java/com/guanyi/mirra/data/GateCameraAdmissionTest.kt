package com.guanyi.mirra.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.guanyi.mirra.data.local.entity.ImageAssetEntity
import com.guanyi.mirra.data.maintenance.GateImageRepository
import com.guanyi.mirra.data.repository.ImageRepository
import com.guanyi.mirra.data.storage.CameraTarget
import com.guanyi.mirra.domain.maintenance.InvalidOperationPermitException
import com.guanyi.mirra.domain.maintenance.MaintenancePhase
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GateCameraAdmissionTest {
    @Test fun reconstructedCameraCompletionPublishesBeforeExclusiveSnapshot() = runBlocking {
        withFixture { gate, images, files ->
            val target = images.createCameraTarget()
            val snapshot = async { gate.coordinator.withExclusive(5_000) { files.toSet() } }
            gate.coordinator.state.first { it.phase == MaintenancePhase.DRAINING }
            assertFalse(snapshot.isCompleted)
            images.completeCameraImport("note", CameraTarget(target.tempPath, Uri.parse(target.uri.toString())))
            assertEquals(setOf("images/final.jpg"), snapshot.await())
            assertEquals(0, gate.coordinator.state.value.activePermits)
            val late = runCatching { images.completeCameraImport("note", target) }.exceptionOrNull()
            assertTrue(late is InvalidOperationPermitException)
            assertEquals(setOf("images/final.jpg"), files)
        }
    }

    @Test fun reconstructedCameraCancellationDiscardsBeforeExclusiveSnapshot() = runBlocking {
        withFixture { gate, images, files ->
            val target = images.createCameraTarget()
            val snapshot = async { gate.coordinator.withExclusive(5_000) { files.toSet() } }
            gate.coordinator.state.first { it.phase == MaintenancePhase.DRAINING }
            assertFalse(snapshot.isCompleted)
            images.cancelCameraTarget(CameraTarget(target.tempPath, Uri.parse(target.uri.toString())))
            assertTrue(snapshot.await().isEmpty())
            assertEquals(0, gate.coordinator.state.value.activePermits)
        }
    }

    @Test fun mismatchedCallbackCannotConsumeOrPublishAnOutstandingCameraRequest() = runBlocking {
        withFixture { gate, images, files ->
            val target = images.createCameraTarget()
            val forged = CameraTarget(target.tempPath, Uri.parse("content://another/target"))
            val failure = runCatching { images.completeCameraImport("note", forged) }.exceptionOrNull()
            assertTrue(failure is InvalidOperationPermitException)
            assertEquals(setOf("camera/source.jpg"), files)
            assertEquals(1, gate.coordinator.state.value.activePermits)
            images.cancelCameraTarget(target)
            assertTrue(files.isEmpty())
        }
    }

    private suspend fun withFixture(block: suspend (StorageMaintenanceGate, ImageRepository, MutableSet<String>) -> Unit) {
        val leaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = StorageMaintenanceGate(leaseScope = leaseScope)
        val files = mutableSetOf<String>()
        val unrelated = Proxy.newProxyInstance(ImageRepository::class.java.classLoader,
            arrayOf(ImageRepository::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as ImageRepository
        val repository = object : ImageRepository by unrelated {
            override suspend fun createCameraTarget(): CameraTarget {
                files += "camera/source.jpg"
                return CameraTarget("camera/source.jpg", Uri.parse("content://test/camera/source"))
            }
            override suspend fun completeCameraImport(noteId: String, target: CameraTarget): ImageAssetEntity {
                check(files.remove(target.tempPath))
                files += "images/final.jpg"
                return ImageAssetEntity("image", noteId, "images/final.jpg", null, 1, 1, 1, 1)
            }
            override suspend fun cancelCameraTarget(target: CameraTarget) { files.remove(target.tempPath) }
        }
        try { block(gate, GateImageRepository(repository, gate), files) }
        finally { leaseScope.cancel() }
    }
}
