package com.guanyi.mirra.domain.wearable

import com.guanyi.mirra.platform.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val appInstalled: Boolean? = null,
    val appCheckInProgress: Boolean = false,
    val appCheckError: String? = null,
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
    private val channelPolicy = Any()
    private val mutableState = MutableStateFlow(WearableCompanionState(enabled = settings.enabled))
    val state = mutableState.asStateFlow()
    @Volatile private var epoch = 0L
    @Volatile private var source: WearableSnapshotSource? = null
    @Volatile private var selectedNode: String? = null
    @Volatile private var terminalChannelError: String? = null
    private var clientId: String? = null
    private var streamId: String? = null
    private var sequence = 0L
    private var lastFollowedSessionId: String? = null
    private var lastFollowedNode: String? = null
    private var connectJob: Job? = null
    private var requestJob: Job? = null
    private var appCheckJob: Job? = null
    private var appCheckGeneration = 0L
    private val usedRequests = linkedSetOf<String>()

    fun checkWearAppFromUser() { scope.launch { lifecycle.withLock {
        val node = selectedNode ?: return@withLock
        val state = mutableState.value
        if (!settings.enabled || !state.serviceAvailable || !state.authorized || connectJob?.isActive == true || appCheckJob?.isActive == true)
            return@withLock
        val captured = epoch
        val generation = ++appCheckGeneration
        mutableState.update { it.copy(appInstalled = null, appCheckInProgress = true, appCheckError = null) }
        appCheckJob = scope.launch {
            try {
                val installed = withTimeout(3_000) { transport.isWearAppInstalled(node) }
                finishAppCheck(captured, generation, node, installed, null)
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) finishAppCheck(captured, generation, node, null, "TIMEOUT")
                else throw cancelled
            } catch (failure: Exception) {
                val category = (failure as? WearTransportException)?.category
                val safeCategory = category?.takeIf { it in APP_CHECK_ERRORS } ?: "SDK_ERROR"
                finishAppCheck(captured, generation, node, null, safeCategory)
            }
        }
    } } }

    fun cancelWearAppCheck() { scope.launch { lifecycle.withLock { retireAppCheck() } } }

    private suspend fun finishAppCheck(captured: Long, generation: Long, node: String, installed: Boolean?, error: String?) = lifecycle.withLock {
        // The same lock owns retirement and publication; a late result cannot restore an old UI.
        if (captured != epoch || generation != appCheckGeneration || node != selectedNode || !settings.enabled)
            return@withLock
        mutableState.update { state ->
            if (!state.serviceAvailable || !state.authorized) state
            else state.copy(appInstalled = installed, appCheckInProgress = false, appCheckError = error)
        }
    }

    private fun retireAppCheck() {
        appCheckGeneration++
        appCheckJob?.cancel(); appCheckJob = null
        // Native Tasks have no cancel operation. A query owns no message listener to drain.
        mutableState.update { it.copy(appInstalled = null, appCheckInProgress = false, appCheckError = null) }
    }

    suspend fun bindSource(value: WearableSnapshotSource) = lifecycle.withLock {
        invalidateAndDrain(resetTerminal = true)
        source = value
        mutableState.update { it.copy(sourceReady = true) }
        if (settings.enabled) connect(false)
    }
    suspend fun pauseSource() = lifecycle.withLock {
        invalidateAndDrain()
        source = null
        mutableState.update { it.copy(sourceReady = false, lastSnapshotAt = null) }
        // Listener can answer SOURCE_BUSY with no old personal payload. SDK send remains outside Room.
        if (settings.enabled && selectedNode != null) installListener(epoch)
    }
    fun enableFromUser() { scope.launch { lifecycle.withLock {
        try { settings.setEnabled(true) }
        catch (_: Exception) { mutableState.update { it.copy(errorCategory = "SETTINGS_UNAVAILABLE") }; return@withLock }
        invalidateAndDrain(resetTerminal = true)
        mutableState.update { it.copy(enabled = true, errorCategory = null) }
        connect(true)
    } } }
    fun reconnectFromUser() { scope.launch { lifecycle.withLock {
        if (!settings.enabled) return@withLock
        invalidateAndDrain(clearFollowed = false, resetTerminal = true)
        connect(true)
    } } }
    fun disableFromUser() { scope.launch { lifecycle.withLock {
        try { settings.setEnabled(false) }
        catch (_: Exception) { mutableState.update { it.copy(errorCategory = "SETTINGS_UNAVAILABLE") }; return@withLock }
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

    private suspend fun invalidateAndDrain(clearFollowed: Boolean = true, resetTerminal: Boolean = false) {
        synchronized(channelPolicy) {
            epoch++
            if (resetTerminal) terminalChannelError = null
        }
        retireAppCheck()
        transport.setListener(null)
        connectJob?.cancelAndJoin(); connectJob = null
        requestJob?.cancelAndJoin(); requestJob = null
        clientId = null; streamId = null; sequence = 0; usedRequests.clear()
        if (clearFollowed) { lastFollowedSessionId = null; lastFollowedNode = null }
        mutableState.update { it.copy(handshaken = false, lastSnapshotAt = null) }
    }
    private fun connect(authorization: Boolean) {
        val captured = epoch
        connectJob = scope.launch {
            var mayAuthorize = authorization
            var retryIndex = 0
            // One application-owned job serializes both SDK attempts and cancellable delays.
            // A late node can recover while this process lives; no alarm, service or wake lock.
            while (isActive && currentChannel(captured) && source != null) {
                val category = try {
                    // Only the explicitly requested first attempt may display authorization.
                    val connection = withTimeout(if (mayAuthorize) 90_000 else 15_000) { transport.connect(mayAuthorize) }
                    synchronized(channelPolicy) {
                        if (!currentChannel(captured) || source == null) return@launch
                        if (!connection.authorized) {
                            reportChannelFailure(captured, "NOT_AUTHORIZED")
                            return@launch
                        }
                        if (selectedNode != null && selectedNode != connection.nodeId) {
                            lastFollowedSessionId = null; lastFollowedNode = null
                        }
                        selectedNode = connection.nodeId
                        updateCurrentChannel(captured) { it.copy(deviceName = connection.deviceName ?: "已选择的小米设备",
                            serviceAvailable = true, authorized = true, errorCategory = null) }
                        installListener(captured)
                    }
                    // SDK readiness never grants a business handshake or snapshot freshness.
                    return@launch
                } catch (cancelled: CancellationException) {
                    if (cancelled is TimeoutCancellationException) "TIMEOUT" else throw cancelled
                } catch (failure: Exception) {
                    (failure as? WearTransportException)?.category ?: "SDK_ERROR"
                }
                if (source == null || !reportChannelFailure(captured, category)) return@launch
                mayAuthorize = false
                // No SDK or lifecycle mutex is held while waiting; retirement cancels this job.
                delay(AUTO_RETRY_DELAYS[retryIndex])
                retryIndex = minOf(retryIndex + 1, AUTO_RETRY_DELAYS.lastIndex)
            }
        }
    }
    private fun installListener(captured: Long): Unit = synchronized(channelPolicy) {
        if (!currentChannel(captured)) return
        transport.setListener(object : WearTransportListener {
            override fun onMessage(nodeId: String, payload: ByteArray) {
                if (!currentChannel(captured) || nodeId != selectedNode) return
                val request = try { WearableProtocol.decodeRequest(payload) } catch (_: WearableProtocolException) { return }
                // Back pressure: at most one request owns the source read/send, no detached publisher queue.
                if (!messageSerial.tryLock()) return
                requestJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try { withTimeout(3_000) { process(captured, nodeId, request) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (failure is WearTransportException) lostConnection(captured, failure.category)
                        else updateCurrentChannel(captured) { it.copy(errorCategory = "SOURCE_UNAVAILABLE") }
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
                    if (!currentChannel(captured) || source == null || mutableState.value.serviceAvailable || connectJob?.isActive == true)
                        return@withLock
                    if (mutableState.value.errorCategory != "SERVICE_UNAVAILABLE") return@withLock
                    invalidateAndDrain(clearFollowed = false)
                    connect(false)
                } }
            }
        })
    }
    private fun lostConnection(captured: Long, category: String) {
        val alreadyRecovering = synchronized(channelPolicy) {
            if (!currentChannel(captured)) return
            val recovering = category in AUTO_RETRYABLE && connectJob?.isActive == true && !mutableState.value.serviceAvailable
            // A permanent policy stops callbacks immediately, before the async drain can run.
            reportChannelFailure(captured, category)
            recovering
        }
        if (alreadyRecovering) return
        scope.launch { lifecycle.withLock {
            if (captured != epoch) return@withLock
            invalidateAndDrain(clearFollowed = false)
            mutableState.update { it.copy(errorCategory = terminalChannelError ?: category) }
            if (settings.enabled && selectedNode != null) installListener(epoch)
            if (settings.enabled && source != null && terminalChannelError == null && category in AUTO_RETRYABLE) connect(false)
        } }
    }
    private fun currentChannel(captured: Long) = captured == epoch && settings.enabled && terminalChannelError == null

    /** Short policy/publication lock only: never held across an SDK or Room wait. */
    private fun updateCurrentChannel(captured: Long, transform: (WearableCompanionState) -> WearableCompanionState) = synchronized(channelPolicy) {
        mutableState.update { state -> if (currentChannel(captured)) transform(state) else state }
    }

    private fun reportChannelFailure(captured: Long, category: String): Boolean = synchronized(channelPolicy) {
        if (!currentChannel(captured)) return false
        val retryable = category in AUTO_RETRYABLE
        if (!retryable) {
            terminalChannelError = category
            transport.setListener(null)
        }
        mutableState.update { state ->
            if (captured != epoch || !settings.enabled) state
            else state.copy(serviceAvailable = false, authorized = false, handshaken = false,
                lastSnapshotAt = null, errorCategory = terminalChannelError ?: category)
        }
        retryable
    }
    private suspend fun process(captured: Long, node: String, request: WearableRequest) {
        if (!currentChannel(captured)) return
        val reader = source
        if (reader == null) {
            if (currentChannel(captured)) transport.send(node, WearableProtocol.status(request, "SOURCE_BUSY", request.streamId))
            return
        }
        if (request.type == "HELLO") {
            if (request.clientInstanceId == clientId && request.requestId in usedRequests) return
            clientId = request.clientInstanceId; streamId = newId(); sequence = 0; usedRequests.clear()
            usedRequests += request.requestId
            if (!currentChannel(captured)) return
            transport.send(node, WearableProtocol.helloAck(request, checkNotNull(streamId)))
            updateCurrentChannel(captured) { it.copy(serviceAvailable = true,
                authorized = true, handshaken = true, errorCategory = null) }
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
            if (!currentChannel(captured) || reader !== source) return
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
        if (!currentChannel(captured) || reader !== source) return
        transport.send(node, reply)
        synchronized(channelPolicy) {
            if (currentChannel(captured) && reader === source) {
                val observedAt = confirmedObservedAt
                if (observedAt != null) {
                    lastFollowedSessionId = observedSession; lastFollowedNode = node
                    updateCurrentChannel(captured) { state ->
                        if (reader === source) state.copy(lastSnapshotAt = observedAt, errorCategory = null) else state
                    }
                } else {
                    updateCurrentChannel(captured) { it.copy(errorCategory = sourceFailure) }
                }
            }
        }
    }
    private companion object {
        val AUTO_RETRYABLE = setOf("NO_DEVICE", "SERVICE_UNAVAILABLE", "TIMEOUT")
        val AUTO_RETRY_DELAYS = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)
        val APP_CHECK_ERRORS = setOf("TIMEOUT", "NO_DEVICE", "MULTIPLE_DEVICES", "WRONG_DEVICE",
            "NOT_AUTHORIZED", "SERVICE_UNAVAILABLE", "SIGNING_IDENTITY_MISMATCH", "COUNTERPART_APP_MISSING",
            "CLEANUP_PENDING", "CHECK_UNAVAILABLE", "SDK_ERROR")
    }
}
