package com.guanyi.mirra.platform.wearable

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class SdkWearNode(val id: String, val name: String?)

/** The production implementation calls the inspected official AAR, never a substitute SDK. */
internal interface WearSdkGateway {
    suspend fun isWearAppInstalled(nodeId: String): Boolean = throw WearTransportException("CHECK_UNAVAILABLE")
    suspend fun serviceApiLevel(): Int
    suspend fun connectedNodes(): List<SdkWearNode>
    suspend fun authorized(nodeId: String): Boolean
    suspend fun requestAuthorization(nodeId: String): Boolean
    suspend fun addMessageListener(nodeId: String, listener: (String, ByteArray) -> Unit)
    suspend fun removeMessageListener(nodeId: String)
    suspend fun sendMessage(nodeId: String, payload: ByteArray)
    suspend fun observeService(disconnected: () -> Unit, available: () -> Unit = {})
    suspend fun removeServiceObserver()
}

/**
 * Serializes ownership of the SDK's process-wide, node-only message listener.
 * SDK task cancellation is unavailable: an uncertain registration/removal quarantines this
 * instance instead of letting a late operation remove a replacement listener.
 */
internal class OwnedWearTransport(
    private val gatewayFactory: () -> WearSdkGateway,
    private val callbackScope: CoroutineScope,
    private val timeoutMs: Long = 3_000,
    private val authorizationTimeoutMs: Long = 60_000,
) : WearTransport {
    private val mutex = Mutex()
    private val epoch = AtomicLong(0)
    private val callbackLock = Any()
    private var gateway: WearSdkGateway? = null
    @Volatile private var ownedNode: String? = null
    private var cleanupUncertain = false
    private var incomingJob: Job? = null
    private var pendingAvailabilityEpoch: Long? = null
    @Volatile private var listener: WearTransportListener? = null
    @Volatile private var connected = false
    @Volatile private var serviceLost = false

    override fun setListener(listener: WearTransportListener?) {
        val availableListener = synchronized(callbackLock) {
            this.listener = listener
            takeAvailableListener()
        }
        availableListener?.onAvailable()
    }

    override suspend fun isWearAppInstalled(nodeId: String): Boolean {
        val (currentEpoch, sdk) = mutex.withLock {
            if (!connected || cleanupUncertain) throw WearTransportException("SERVICE_UNAVAILABLE")
            if (nodeId != ownedNode) throw WearTransportException("WRONG_DEVICE")
            epoch.get() to (gateway ?: throw WearTransportException("SERVICE_UNAVAILABLE"))
        }
        fun verifyOwner() {
            checkEpoch(currentEpoch)
            if (!connected) throw WearTransportException("SERVICE_UNAVAILABLE")
            if (nodeId != ownedNode) throw WearTransportException("WRONG_DEVICE")
        }
        suspend fun verifyTarget() {
            verifyOwner()
            val nodes = bounded { sdk.connectedNodes() }
            verifyOwner()
            if (nodes.isEmpty()) throw WearTransportException("NO_DEVICE")
            if (nodes.size != 1) throw WearTransportException("MULTIPLE_DEVICES")
            if (nodes.single().id != nodeId) throw WearTransportException("WRONG_DEVICE")
            val authorized = bounded { sdk.authorized(nodeId) }
            verifyOwner()
            if (!authorized) throw WearTransportException("NOT_AUTHORIZED")
        }
        // Read-only SDK waits never hold listener ownership or register a new listener.
        verifyTarget()
        val installed = bounded { sdk.isWearAppInstalled(nodeId) }
        verifyOwner()
        verifyTarget()
        return installed
    }

    override suspend fun connect(requestAuthorization: Boolean): WearConnection = mutex.withLock {
        if (cleanupUncertain) throw WearTransportException("CLEANUP_PENDING")
        val currentEpoch = epoch.incrementAndGet()
        connected = false
        serviceLost = false
        cancelIncoming()
        clearAvailability()
        removeOwnedListener()
        if (cleanupUncertain) throw WearTransportException("CLEANUP_PENDING")
        val sdk = gateway ?: gatewayFactory().also { gateway = it }
        sdk.observeService(disconnected = {
            if (epoch.compareAndSet(currentEpoch, currentEpoch + 1)) {
                connected = false
                serviceLost = true
                cancelIncoming()
                listener?.onDisconnected()
            }
        }, available = {
            val availableListener = synchronized(callbackLock) {
                if (serviceLost && !connected && ownedNode != null && epoch.get() == currentEpoch + 1) {
                    serviceLost = false
                    // Coordinator temporarily detaches its listener while draining an old request.
                    // Retain this event for that epoch instead of losing a rapid service return.
                    pendingAvailabilityEpoch = currentEpoch + 1
                    takeAvailableListener()
                } else null
            }
            availableListener?.onAvailable()
        })
        var registrationPending = false
        try {
            val apiLevel = bounded { sdk.serviceApiLevel() }
            checkEpoch(currentEpoch)
            val nodes = bounded { sdk.connectedNodes() }
            checkEpoch(currentEpoch)
            if (nodes.isEmpty()) throw WearTransportException("NO_DEVICE")
            if (nodes.size != 1) throw WearTransportException("MULTIPLE_DEVICES")
            val node = nodes.single()
            if (node.id.isBlank() || node.id.length > 256 || node.id.any { it.isISOControl() }) {
                throw WearTransportException("INVALID_DEVICE")
            }
            var authorized = bounded { sdk.authorized(node.id) }
            checkEpoch(currentEpoch)
            if (!authorized && requestAuthorization) {
                authorized = bounded(authorizationTimeoutMs) { sdk.requestAuthorization(node.id) }
                checkEpoch(currentEpoch)
                if (!authorized) throw WearTransportException("AUTHORIZATION_DENIED")
            }
            if (!authorized) throw WearTransportException("NOT_AUTHORIZED")
            ownedNode = node.id
            registrationPending = true
            bounded {
                sdk.addMessageListener(node.id) { sourceNode, payload ->
                    receive(currentEpoch, node.id, sourceNode, payload)
                }
            }
            registrationPending = false
            checkEpoch(currentEpoch)
            connected = true
            WearConnection(node.id, apiLevel, true, node.name)
        } catch (error: Throwable) {
            if (registrationPending) cleanupUncertain = true
            connected = false
            epoch.compareAndSet(currentEpoch, currentEpoch + 1)
            clearAvailability()
            withContext(NonCancellable) {
                removeOwnedListener()
                sdk.removeServiceObserver()
            }
            throw error
        }
    }

    override suspend fun send(nodeId: String, payload: ByteArray) = mutex.withLock {
        if (!connected) throw WearTransportException("SERVICE_UNAVAILABLE")
        if (nodeId != ownedNode) throw WearTransportException("WRONG_DEVICE")
        if (payload.isEmpty() || payload.size > MAX_MESSAGE_BYTES) {
            throw WearTransportException("INVALID_MESSAGE")
        }
        val currentEpoch = epoch.get()
        val sdk = gateway ?: throw WearTransportException("SERVICE_UNAVAILABLE")
        if (!bounded { sdk.authorized(nodeId) }) {
            retireConnection("NOT_AUTHORIZED")
            throw WearTransportException("NOT_AUTHORIZED")
        }
        checkEpoch(currentEpoch)
        bounded { sdk.sendMessage(nodeId, payload.copyOf()) }
        checkEpoch(currentEpoch)
    }

    override suspend fun close() {
        epoch.incrementAndGet()
        connected = false
        serviceLost = false
        cancelIncoming()
        clearAvailability()
        withContext(NonCancellable) {
            mutex.withLock {
                removeOwnedListener()
                gateway?.removeServiceObserver()
            }
        }
    }

    private fun receive(currentEpoch: Long, expectedNode: String, sourceNode: String, bytes: ByteArray) {
        if (epoch.get() != currentEpoch || !connected) return
        if (sourceNode != expectedNode) { listener?.onError("WRONG_DEVICE"); return }
        if (bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) {
            listener?.onError("INVALID_MESSAGE")
            return
        }
        val immutableMessage = bytes.copyOf()
        synchronized(callbackLock) {
            // One callback read in flight; bursts cannot create an unbounded coroutine queue.
            if (incomingJob?.isActive == true) return
            incomingJob = callbackScope.launch {
                try {
                    val sdk = gateway ?: return@launch
                    if (!bounded { sdk.authorized(sourceNode) }) {
                        if (epoch.get() == currentEpoch) retireConnection("NOT_AUTHORIZED")
                        return@launch
                    }
                    if (epoch.get() == currentEpoch && connected) {
                        listener?.onMessage(sourceNode, immutableMessage)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: WearTransportException) {
                    if (epoch.get() == currentEpoch) listener?.onError(error.category)
                } catch (_: Exception) {
                    if (epoch.get() == currentEpoch) listener?.onError("SDK_ERROR")
                }
            }
        }
    }

    private fun cancelIncoming() = synchronized(callbackLock) {
        incomingJob?.cancel()
        incomingJob = null
    }

    /** Called under callbackLock. Availability never restores authorization or message ownership. */
    private fun takeAvailableListener(): WearTransportListener? {
        val availableEpoch = pendingAvailabilityEpoch ?: return null
        if (epoch.get() != availableEpoch || connected || ownedNode == null) {
            pendingAvailabilityEpoch = null
            return null
        }
        val target = listener ?: return null
        pendingAvailabilityEpoch = null
        return target
    }

    private fun clearAvailability() = synchronized(callbackLock) { pendingAvailabilityEpoch = null }

    private fun retireConnection(category: String) {
        connected = false
        epoch.incrementAndGet()
        serviceLost = false
        clearAvailability()
        cancelIncoming()
        listener?.onError(category)
    }

    private suspend fun removeOwnedListener() {
        val node = ownedNode ?: return
        ownedNode = null
        try {
            gateway?.let { sdk -> bounded { sdk.removeMessageListener(node) } }
        } catch (error: Exception) {
            cleanupUncertain = true
            listener?.onError(if (error is WearTransportException) error.category else "SDK_ERROR")
        }
    }

    private fun checkEpoch(expected: Long) {
        if (epoch.get() != expected) throw WearTransportException("SERVICE_UNAVAILABLE")
    }

    private suspend fun <T> bounded(deadlineMs: Long = timeoutMs, operation: suspend () -> T): T = try {
        withTimeout(deadlineMs) { operation() }
    } catch (_: TimeoutCancellationException) {
        throw WearTransportException("TIMEOUT")
    }

    private companion object { const val MAX_MESSAGE_BYTES = 4_096 }
}
