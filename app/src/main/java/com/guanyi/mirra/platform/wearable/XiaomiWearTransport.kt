package com.guanyi.mirra.platform.wearable

import android.content.Context
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.exception.AppNotInstalledException
import com.xiaomi.xms.wearable.exception.DeviceDisconnectedException
import com.xiaomi.xms.wearable.exception.PermissionDeniedException
import com.xiaomi.xms.wearable.exception.SignatureVerifyFailedException
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import com.xiaomi.xms.wearable.tasks.Task
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Official xms-wearable-lib 1.4 transport. No API is constructed until explicit connection.
 * The public SDK has no unbind/dispose API; closing retires our listeners, not its static
 * system-service client. It starts no Mirra service and requests no Android permissions.
 */
class XiaomiWearTransport(context: Context) : WearTransport {
    private val applicationContext = context.applicationContext
    private val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val owned = OwnedWearTransport({ OfficialGateway(applicationContext) }, callbacks)

    override suspend fun connect(requestAuthorization: Boolean) = owned.connect(requestAuthorization)
    override suspend fun isWearAppInstalled(nodeId: String) = owned.isWearAppInstalled(nodeId)
    override suspend fun send(nodeId: String, payload: ByteArray) = owned.send(nodeId, payload)
    override fun setListener(listener: WearTransportListener?) = owned.setListener(listener)
    override suspend fun close() = owned.close()
}

private class OfficialGateway(context: Context) : WearSdkGateway {
    private val service = Wearable.getServiceApi(context)
    private val nodes = Wearable.getNodeApi(context)
    private val auth = Wearable.getAuthApi(context)
    private val messages = Wearable.getMessageApi(context)
    private var serviceListener: OnServiceConnectionListener? = null

    override suspend fun serviceApiLevel(): Int =
        service.serviceApiLevel.awaitSdk() ?: throw WearTransportException("SERVICE_UNAVAILABLE")

    override suspend fun connectedNodes(): List<SdkWearNode> =
        (nodes.connectedNodes.awaitSdk() ?: throw WearTransportException("SDK_ERROR"))
            .map { node -> SdkWearNode(node.id, node.name) }

    override suspend fun isWearAppInstalled(nodeId: String): Boolean =
        nodes.isWearAppInstalled(nodeId).awaitSdk() ?: throw WearTransportException("SDK_ERROR")

    override suspend fun authorized(nodeId: String): Boolean =
        auth.checkPermission(nodeId, Permission.DEVICE_MANAGER).awaitSdk()
            ?: throw WearTransportException("SDK_ERROR")

    override suspend fun requestAuthorization(nodeId: String): Boolean {
        // The platform may grant NOTIFY implicitly (documented in v1.4); Mirra never calls it.
        val granted = auth.requestPermission(nodeId, Permission.DEVICE_MANAGER).awaitSdk()
            ?: throw WearTransportException("SDK_ERROR")
        return granted.any { it.name == Permission.DEVICE_MANAGER.name } && authorized(nodeId)
    }

    override suspend fun addMessageListener(nodeId: String, listener: (String, ByteArray) -> Unit) {
        messages.addListener(nodeId) { sourceNode, bytes -> listener(sourceNode, bytes) }.awaitSdk()
    }

    override suspend fun removeMessageListener(nodeId: String) {
        // Actual AAR has only this overload, unlike the historical PDF example.
        messages.removeListener(nodeId).awaitSdk()
    }

    override suspend fun sendMessage(nodeId: String, payload: ByteArray) {
        // Actual AAR returns Task<Void>. Completion acknowledges transport, not a learning action.
        messages.sendMessage(nodeId, payload).awaitSdk()
    }

    override suspend fun observeService(disconnected: () -> Unit, available: () -> Unit) = withContext(Dispatchers.Main.immediate) {
        // Official SDK stores observers in an unsynchronized ArrayList that its main-thread
        // ServiceConnection iterates. Mutations must share that thread to avoid a process crash.
        removeServiceObserver()
        serviceListener = object : OnServiceConnectionListener {
            override fun onServiceConnected() = available()
            override fun onServiceDisconnected() = disconnected()
        }.also { service.registerServiceConnectionListener(it) }
    }

    override suspend fun removeServiceObserver() = withContext(Dispatchers.Main.immediate) {
        serviceListener?.let { service.unregisterServiceConnectionListener(it) }
        serviceListener = null
    }
}

private val sdkCallbackExecutor = Executor { command -> command.run() }

private suspend fun <T> Task<T>.awaitSdk(): T? = suspendCancellableCoroutine { continuation ->
    val completed = AtomicBoolean(false)
    addOnSuccessListener(sdkCallbackExecutor) { value ->
        if (completed.compareAndSet(false, true) && continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener(sdkCallbackExecutor) { error ->
        if (completed.compareAndSet(false, true) && continuation.isActive) {
            val category = when (error) {
                is SignatureVerifyFailedException -> "SIGNING_IDENTITY_MISMATCH"
                is PermissionDeniedException -> "NOT_AUTHORIZED"
                is DeviceDisconnectedException -> "SERVICE_UNAVAILABLE"
                is AppNotInstalledException -> "COUNTERPART_APP_MISSING"
                else -> "SDK_ERROR"
            }
            continuation.resumeWithException(WearTransportException(category))
        }
    }
    // SDK Tasks expose no cancellation method. Late completions do not resume a retired request.
    continuation.invokeOnCancellation { completed.set(true) }
}
