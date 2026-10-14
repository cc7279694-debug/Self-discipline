package com.guanyi.mirra.platform.wearable

import com.guanyi.mirra.domain.wearable.WearableProtocol
import com.guanyi.mirra.domain.wearable.WearableProtocolException
import com.guanyi.mirra.domain.wearable.WearableRecordProtocol
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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
    private data class Incoming(val nodeId: String, val payload: ByteArray, val target: WearTransportListener)
    private val incoming = ArrayDeque<Incoming>()
    private var incomingGeneration = 0L
    // Include the frame currently awaiting authorization, not only the FIFO's waiting entries.
    private var incomingCount = 0
    private var incomingBytes = 0
    private var pendingAvailabilityEpoch: Long? = null
    @Volatile private var listener: WearTransportListener? = null
    @Volatile private var connected = false
    @Volatile private var serviceLost = false

    override fun setListener(listener: WearTransportListener?) {
        val availableListener = synchronized(callbackLock) {
            if (this.listener !== listener) cancelIncomingLocked()
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
        val recordFrame = isRecordFrame(immutableMessage)
        var startWorker: Job? = null
        var overflowTarget: WearTransportListener? = null
        synchronized(callbackLock) {
            if (epoch.get() != currentEpoch || !connected || ownedNode != expectedNode) return
            val target = listener ?: return
            if (callbackScope.coroutineContext[Job]?.isActive == false) return
            // Legacy busy-drop behavior remains; only fully validated v4 record frames may wait.
            if (incomingJob != null && !recordFrame) return
            if (incomingCount >= MAX_INCOMING_COUNT || immutableMessage.size > MAX_INCOMING_BYTES - incomingBytes) {
                overflowTarget = retireConnectionLocked(currentEpoch)
            } else {
                incoming.addLast(Incoming(sourceNode, immutableMessage, target))
                incomingCount++
                incomingBytes += immutableMessage.size
                if (incomingJob == null) {
                    val generation = incomingGeneration
                    // Assign ownership before starting: native callbacks may run synchronously.
                    val worker = callbackScope.launch(start = CoroutineStart.LAZY) {
                        drainIncoming(currentEpoch, expectedNode, generation)
                    }
                    incomingJob = worker
                    startWorker = worker
                }
            }
        }
        // Do not invoke domain callbacks under callbackLock: their policy lock installs listeners.
        overflowTarget?.onError("MESSAGE_OVERFLOW")
        startWorker?.start()
    }

    private fun isRecordFrame(payload: ByteArray): Boolean = try {
        val tree = WearableProtocol.decodeMessage(payload)
        tree["protocolVersion"]?.jsonPrimitive?.intOrNull == WearableRecordProtocol.VERSION &&
            tree["type"]?.jsonPrimitive?.content in WearableRecordProtocol.TYPES
    } catch (_: WearableProtocolException) { false }

    private suspend fun drainIncoming(currentEpoch: Long, expectedNode: String, generation: Long) {
        try {
            while (true) {
                val message = synchronized(callbackLock) {
                    if (incomingGeneration != generation || epoch.get() != currentEpoch || !connected || ownedNode != expectedNode) null
                    else incoming.pollFirst().also { if (it == null) finishIncomingLocked(generation) }
                } ?: return
                try {
                    val sdk = gateway ?: return
                    // Authorization is fresh for every queued frame; never reuse a burst-wide grant.
                    if (!bounded { sdk.authorized(message.nodeId) }) {
                        retireConnection("NOT_AUTHORIZED", currentEpoch)
                        return
                    }
                    val target = synchronized(callbackLock) {
                        if (incomingGeneration == generation && epoch.get() == currentEpoch && connected &&
                            ownedNode == expectedNode && listener === message.target) message.target else null
                    }
                    target?.onMessage(message.nodeId, message.payload)
                } finally {
                    synchronized(callbackLock) {
                        if (incomingGeneration == generation) {
                            incomingCount--
                            incomingBytes -= message.payload.size
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: WearTransportException) {
            failIncoming(currentEpoch, generation, error.category)
        } catch (_: Exception) {
            failIncoming(currentEpoch, generation, "SDK_ERROR")
        } finally {
            synchronized(callbackLock) { finishIncomingLocked(generation) }
        }
    }

    private fun failIncoming(currentEpoch: Long, generation: Long, category: String) {
        val target = synchronized(callbackLock) {
            if (incomingGeneration != generation || epoch.get() != currentEpoch) null
            else listener.also { finishIncomingLocked(generation) }
        }
        target?.onError(category)
    }

    private fun finishIncomingLocked(generation: Long) {
        if (incomingGeneration != generation) return
        incomingGeneration++
        incomingJob = null
        incoming.clear()
        incomingCount = 0
        incomingBytes = 0
    }

    private fun cancelIncomingLocked() {
        incomingJob?.cancel()
        finishIncomingLocked(incomingGeneration)
    }

    private fun cancelIncoming() = synchronized(callbackLock) { cancelIncomingLocked() }

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

    private fun retireConnectionLocked(expectedEpoch: Long? = null): WearTransportListener? {
        if (expectedEpoch != null && epoch.get() != expectedEpoch) return null
        connected = false
        epoch.incrementAndGet()
        serviceLost = false
        pendingAvailabilityEpoch = null
        cancelIncomingLocked()
        return listener
    }

    private fun retireConnection(category: String, expectedEpoch: Long? = null) {
        val target = synchronized(callbackLock) { retireConnectionLocked(expectedEpoch) }
        target?.onError(category)
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

    private companion object {
        const val MAX_MESSAGE_BYTES = 4_096
        const val MAX_INCOMING_COUNT = 10
        const val MAX_INCOMING_BYTES = 32_768
    }
}
