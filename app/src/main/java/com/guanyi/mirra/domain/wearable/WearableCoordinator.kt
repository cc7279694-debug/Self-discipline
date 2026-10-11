package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.platform.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

interface WearableSettingsStore {
    val enabled: Boolean
    fun setEnabled(value: Boolean)
}
data class WearableCompanionState(
    val enabled: Boolean = false,
    val deviceName: String? = null,
    val serviceAvailable: Boolean = false,
    val authorized: Boolean = false,
    val handshaken: Boolean = false,
    val sourceReady: Boolean = false,
    val lastSnapshotAt: Long? = null,
    val errorCategory: String? = null,
)

/** Application-owned listener; no UI lifecycle owns a transport callback or learning fact. */
class WearableCoordinator(
    private val scope: CoroutineScope,
    private val transport: WearTransport,
    private val settings: WearableSettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lifecycle = Mutex()
    private val messageSerial = Mutex()
    private val mutableState = MutableStateFlow(WearableCompanionState(enabled = settings.enabled))
    val state = mutableState.asStateFlow()
    @Volatile private var epoch = 0L
    @Volatile private var source: WearableSnapshotSource? = null
    @Volatile private var selectedNode: String? = null
    private var clientId: String? = null
    private var streamId: String? = null
    private var sequence = 0L
    private var lastFollowedSessionId: String? = null
    private var lastFollowedNode: String? = null
    private var connectJob: Job? = null
    private var requestJob: Job? = null
    private val usedRequests = linkedSetOf<String>()

    suspend fun bindSource(value: WearableSnapshotSource) = lifecycle.withLock {
        invalidateAndDrain()
        source = value
        mutableState.value = mutableState.value.copy(sourceReady = true)
        if (settings.enabled) connect(false)
    }
    suspend fun pauseSource() = lifecycle.withLock {
        invalidateAndDrain()
        source = null
        mutableState.value = mutableState.value.copy(sourceReady = false, lastSnapshotAt = null)
        // Listener can answer SOURCE_BUSY with no old personal payload. SDK send remains outside Room.
        if (settings.enabled && selectedNode != null) installListener()
    }
    fun enableFromUser() { scope.launch { lifecycle.withLock {
        try { settings.setEnabled(true) }
        catch (_: Exception) { mutableState.value = mutableState.value.copy(errorCategory = "SETTINGS_UNAVAILABLE"); return@withLock }
        mutableState.value = mutableState.value.copy(enabled = true, errorCategory = null)
        invalidateAndDrain(); connect(true)
    } } }
    fun reconnectFromUser() { scope.launch { lifecycle.withLock {
        if (!settings.enabled) return@withLock
        invalidateAndDrain(clearFollowed = false); connect(true)
    } } }
    fun disableFromUser() { scope.launch { lifecycle.withLock {
        try { settings.setEnabled(false) }
        catch (_: Exception) { mutableState.value = mutableState.value.copy(errorCategory = "SETTINGS_UNAVAILABLE"); return@withLock }
        val lastNode = selectedNode; val lastClient = clientId; val lastStream = streamId
        invalidateAndDrain()
        if (lastNode != null && lastClient != null) runCatching {
            withTimeout(3_000) { transport.send(lastNode, WearableProtocol.status(
                WearableRequest(1, "GET_SNAPSHOT", lastClient, newId()), "DISABLED", lastStream)) }
        }
        try { transport.close() } catch (_: Exception) { /* SDK failures never reach learning owners. */ }
        selectedNode = null
        mutableState.value = WearableCompanionState(enabled = false, sourceReady = source != null)
    } } }
    suspend fun close() = lifecycle.withLock {
        invalidateAndDrain(); source = null; transport.close(); selectedNode = null
    }

    private suspend fun invalidateAndDrain(clearFollowed: Boolean = true) {
        epoch++
        transport.setListener(null)
        connectJob?.cancelAndJoin(); connectJob = null
        requestJob?.cancelAndJoin(); requestJob = null
        clientId = null; streamId = null; sequence = 0; usedRequests.clear()
        if (clearFollowed) { lastFollowedSessionId = null; lastFollowedNode = null }
        mutableState.value = mutableState.value.copy(handshaken = false, lastSnapshotAt = null)
    }
    private fun connect(authorization: Boolean) {
        val captured = epoch
        connectJob = scope.launch {
            try {
                // Human authorization is an explicit, cancelable interaction, unlike a 3s protocol request.
                val connection = withTimeout(if (authorization) 90_000 else 15_000) { transport.connect(authorization) }
                if (captured != epoch || !settings.enabled) return@launch
                if (selectedNode != null && selectedNode != connection.nodeId) {
                    lastFollowedSessionId = null; lastFollowedNode = null
                }
                selectedNode = connection.nodeId
                mutableState.value = mutableState.value.copy(deviceName = connection.deviceName ?: "已选择的小米设备",
                    serviceAvailable = true, authorized = connection.authorized, errorCategory = null)
                if (connection.authorized) installListener()
                else mutableState.value = mutableState.value.copy(errorCategory = "NOT_AUTHORIZED")
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException && captured == epoch)
                    mutableState.value = mutableState.value.copy(serviceAvailable = false, errorCategory = "TIMEOUT")
                else throw cancelled
            } catch (failure: Exception) {
                if (captured == epoch) mutableState.value = mutableState.value.copy(serviceAvailable = false,
                    authorized = false, errorCategory = (failure as? WearTransportException)?.category ?: "SDK_ERROR")
            }
        }
    }
    private fun installListener() {
        val captured = epoch
        transport.setListener(object : WearTransportListener {
            override fun onMessage(nodeId: String, payload: ByteArray) {
                if (captured != epoch || !settings.enabled || nodeId != selectedNode) return
                val request = try { WearableProtocol.decodeRequest(payload) } catch (_: WearableProtocolException) { return }
                // Back pressure: at most one request owns the source read/send, no detached publisher queue.
                if (!messageSerial.tryLock()) return
                requestJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try { withTimeout(3_000) { process(captured, nodeId, request) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (failure is WearTransportException) lostConnection(captured, failure.category)
                        else if (captured == epoch) mutableState.value = mutableState.value.copy(errorCategory = "SOURCE_UNAVAILABLE")
                    }
                    finally { messageSerial.unlock() }
                }
            }
            override fun onDisconnected() {
                lostConnection(captured, "SERVICE_UNAVAILABLE")
            }
            override fun onError(category: String) {
                if (category !in setOf("WRONG_DEVICE", "INVALID_MESSAGE")) lostConnection(captured, category)
            }
            override fun onAvailable() {
                // Availability has no learning facts. Serialize after a rapid disconnect callback.
                scope.launch { lifecycle.withLock {
                    if (!settings.enabled || source == null || mutableState.value.serviceAvailable || connectJob?.isActive == true)
                        return@withLock
                    if (mutableState.value.errorCategory != "SERVICE_UNAVAILABLE") return@withLock
                    invalidateAndDrain(clearFollowed = false)
                    connect(false)
                } }
            }
        })
    }
    private fun lostConnection(captured: Long, category: String) {
        if (captured != epoch) return
        // Display loss immediately; serial cleanup drains the owned publisher before reattachment.
        mutableState.value = mutableState.value.copy(serviceAvailable = false, authorized = false,
            handshaken = false, lastSnapshotAt = null, errorCategory = category)
        scope.launch { lifecycle.withLock {
            if (captured != epoch) return@withLock
            invalidateAndDrain(clearFollowed = false)
            mutableState.value = mutableState.value.copy(errorCategory = category)
            if (settings.enabled && selectedNode != null) installListener()
        } }
    }
    private suspend fun process(captured: Long, node: String, request: WearableRequest) {
        if (captured != epoch || !settings.enabled) return
        val reader = source
        if (reader == null) { transport.send(node, WearableProtocol.status(request, "SOURCE_BUSY", request.streamId)); return }
        if (request.type == "HELLO") {
            if (request.clientInstanceId == clientId && request.requestId in usedRequests) return
            clientId = request.clientInstanceId; streamId = newId(); sequence = 0; usedRequests.clear()
            usedRequests += request.requestId
            transport.send(node, WearableProtocol.helloAck(request, checkNotNull(streamId)))
            if (captured == epoch) mutableState.value = mutableState.value.copy(serviceAvailable = true,
                authorized = true, handshaken = true, errorCategory = null)
            return
        }
        if (request.clientInstanceId != clientId || request.streamId != streamId || request.requestId in usedRequests) return
        usedRequests += request.requestId
        if (usedRequests.size > 64) usedRequests.remove(usedRequests.first())
        val followed = request.lastSeenSessionId?.takeIf { it == lastFollowedSessionId && lastFollowedNode == node }
        var observedSession: String? = null
        var confirmedObservedAt: Long? = null
        var sourceFailure: String? = null
        val reply = try {
            // Foreign historical IDs are never queried, but a current active Session can still be read.
            val body = reader.read(followed)
            if (captured != epoch || reader !== source || !settings.enabled) return
            val observedAt = clock()
            WearableProtocol.snapshot(request, checkNotNull(streamId), ++sequence, observedAt, body).also {
                // Only a fully encoded, valid snapshot can authorize history or mark a sync.
                observedSession = body.sessionId; confirmedObservedAt = observedAt
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: WearableSourceBusy) {
            sourceFailure = "SOURCE_BUSY"; WearableProtocol.status(request, "SOURCE_BUSY", streamId)
        }
        catch (_: Exception) {
            sourceFailure = "SOURCE_UNAVAILABLE"; WearableProtocol.status(request, "SOURCE_UNAVAILABLE", streamId)
        }
        if (captured != epoch || !settings.enabled || reader !== source) return
        transport.send(node, reply)
        if (captured == epoch && reader === source && settings.enabled) {
            val observedAt = confirmedObservedAt
            if (observedAt != null) {
                lastFollowedSessionId = observedSession; lastFollowedNode = node
                mutableState.value = mutableState.value.copy(lastSnapshotAt = observedAt, errorCategory = null)
            } else {
                mutableState.value = mutableState.value.copy(errorCategory = sourceFailure)
            }
        }
    }
}
