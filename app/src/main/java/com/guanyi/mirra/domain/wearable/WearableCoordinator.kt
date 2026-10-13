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
    val controlEnabled: Boolean get() = false
    fun setControlEnabled(value: Boolean) { check(!value) }
    val catalogEnabled: Boolean get() = false
    val catalogSelectedIds: List<String> get() = emptyList()
    fun setCatalogEnabled(value: Boolean) { check(!value) }
    fun setCatalogSelectedIds(value: List<String>) { check(value.isEmpty()) }
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
    val controlEnabled: Boolean = false,
    val controlNegotiated: Boolean = false,
    val catalogEnabled: Boolean = false,
    val catalogNegotiated: Boolean = false,
    val catalogStatus: String = "NOT_SENT",
    val catalogError: String? = null,
    val catalogRevision: String? = null,
    val lastCatalogAt: Long? = null,
    val catalogSelectedIds: List<String> = emptyList(),
    val catalogChoices: List<WearableCatalogChoice> = emptyList(),
    val catalogChoicesLoading: Boolean = false,
)

/** Application-owned listener; no UI lifecycle owns a transport callback or learning fact. */
class WearableCoordinator(
    private val scope: CoroutineScope,
    private val transport: WearTransport,
    private val settings: WearableSettingsStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lifecycle = Mutex()
    private val messageSerial = Mutex()
    private val channelPolicy = Any()
    private val mutableState = MutableStateFlow(WearableCompanionState(enabled = settings.enabled,
        controlEnabled = settings.controlEnabled, catalogEnabled = settings.catalogEnabled,
        catalogSelectedIds = settings.catalogSelectedIds))
    val state = mutableState.asStateFlow()
    @Volatile private var epoch = 0L
    @Volatile private var source: WearableSnapshotSource? = null
    @Volatile private var executor: WearableCommandExecutor? = null
    @Volatile private var catalogSource: WearableCatalogSource? = null
    @Volatile private var catalogConsentEpoch = 0L
    private var streamCatalog = false
    private data class CatalogPending(val epoch: Long, val consent: Long, val source: WearableCatalogSource,
        val node: String, val client: String, val stream: String, val request: String, val startedAt: Long) {
        @Volatile var revision: String? = null
        @Volatile var finalPartIssued = false
    }
    @Volatile private var catalogPending: CatalogPending? = null
    private var catalogTimeoutJob: Job? = null
    private var catalogChoicesJob: Job? = null
    private var catalogChoicesGeneration = 0L
    @Volatile private var controlConsentEpoch = 0L
    private val controlAuthority = WearableControlAuthority(newId)
    private var streamVersion = 1
    private var streamControl = false
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

    suspend fun bindSource(value: WearableSnapshotSource, commandExecutor: WearableCommandExecutor? = null,
        catalog: WearableCatalogSource? = null) = lifecycle.withLock {
        invalidateAndDrain(resetTerminal = true)
        source = value
        executor = commandExecutor
        catalogSource = catalog
        mutableState.update { it.copy(sourceReady = true) }
        if (settings.enabled) connect(false)
    }
    suspend fun pauseSource() = lifecycle.withLock {
        invalidateAndDrain()
        source = null
        executor = null
        catalogSource = null
        mutableState.update { it.copy(catalogChoices = emptyList(), catalogChoicesLoading = false) }
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
    /** Persist and revoke synchronously: a queued SDK callback cannot retain old consent. */
    fun setControlsFromUser(value: Boolean) {
        controlConsentEpoch++
        controlAuthority.revokeGrants()
        requestJob?.cancel()
        try { settings.setControlEnabled(value) }
        catch (_: Exception) {
            mutableState.update { it.copy(controlEnabled = false, errorCategory = "SETTINGS_UNAVAILABLE") }
            return
        }
        mutableState.update { it.copy(controlEnabled = value) }
    }
    fun setCatalogEnabledFromUser(value: Boolean) {
        catalogConsentEpoch++
        retireCatalogTransfer()
        try { settings.setCatalogEnabled(value) }
        catch (_: Exception) {
            mutableState.update { it.copy(catalogEnabled = false, catalogError = "SETTINGS_UNAVAILABLE") }
            return
        }
        mutableState.update { it.copy(catalogEnabled = value, catalogStatus = "NOT_SENT", catalogError = null,
            lastCatalogAt = null, catalogRevision = null) }
    }
    fun setCatalogBooksFromUser(ids: List<String>) {
        val eligible = mutableState.value.catalogChoices.filterNot { it.isMainline }.map { it.id }.toSet()
        if (ids.distinct().size != ids.size || ids.size > WearableCatalogProtocol.MAX_BOOKS - 1 ||
            ids.any { it !in eligible || !WearableCatalogProtocol.validBookId(it) }) {
            mutableState.update { it.copy(catalogError = "INVALID_CATALOG") }; return
        }
        catalogConsentEpoch++
        retireCatalogTransfer()
        try { settings.setCatalogSelectedIds(ids.sorted()) }
        catch (_: Exception) { mutableState.update { it.copy(catalogError = "SETTINGS_UNAVAILABLE") }; return }
        mutableState.update { it.copy(catalogSelectedIds = settings.catalogSelectedIds, catalogStatus = "NOT_SENT",
            catalogError = null, lastCatalogAt = null, catalogRevision = null) }
    }
    fun loadCatalogChoicesFromUser() { scope.launch { lifecycle.withLock {
        val reader = catalogSource ?: return@withLock
        if (catalogChoicesJob?.isActive == true) return@withLock
        val captured = epoch
        val generation = ++catalogChoicesGeneration
        mutableState.update { it.copy(catalogChoicesLoading = true, catalogError = null) }
        catalogChoicesJob = scope.launch {
            try {
                val choices = withTimeout(3_000) { reader.choices() }
                lifecycle.withLock {
                    if (captured == epoch && generation == catalogChoicesGeneration && reader === catalogSource)
                        mutableState.update { it.copy(catalogChoices = choices, catalogChoicesLoading = false) }
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                finishCatalogChoicesFailure(captured, generation, "TIMEOUT")
            } catch (_: WearableSourceBusy) { finishCatalogChoicesFailure(captured, generation, "SOURCE_BUSY") }
            catch (_: Exception) { finishCatalogChoicesFailure(captured, generation, "SOURCE_UNAVAILABLE") }
        }
    } } }
    private suspend fun finishCatalogChoicesFailure(captured: Long, generation: Long, code: String) = lifecycle.withLock {
        if (captured == epoch && generation == catalogChoicesGeneration)
            mutableState.update { it.copy(catalogChoicesLoading = false, catalogError = code) }
    }
    fun disableFromUser() { scope.launch { lifecycle.withLock {
        try { settings.setEnabled(false) }
        catch (_: Exception) { mutableState.update { it.copy(errorCategory = "SETTINGS_UNAVAILABLE") }; return@withLock }
        val lastNode = selectedNode; val lastClient = clientId; val lastStream = streamId
        val lastVersion = streamVersion
        invalidateAndDrain()
        if (lastNode != null && lastClient != null) runCatching {
            withTimeout(3_000) { transport.send(lastNode, WearableProtocol.status(
                WearableRequest(lastVersion, "GET_SNAPSHOT", lastClient, newId()), "DISABLED", lastStream)) }
        }
        try { transport.close() } catch (_: Exception) { /* SDK failures never reach learning owners. */ }
        selectedNode = null
        mutableState.value = WearableCompanionState(enabled = false, sourceReady = source != null,
            controlEnabled = settings.controlEnabled, catalogEnabled = settings.catalogEnabled,
            catalogSelectedIds = settings.catalogSelectedIds)
    } } }
    suspend fun close() = lifecycle.withLock {
        invalidateAndDrain(); source = null; executor = null; catalogSource = null; transport.close(); selectedNode = null
    }

    private suspend fun invalidateAndDrain(clearFollowed: Boolean = true, resetTerminal: Boolean = false) {
        synchronized(channelPolicy) {
            epoch++
            controlAuthority.clear()
            if (resetTerminal) terminalChannelError = null
        }
        retireAppCheck()
        retireCatalogTransfer()
        catalogChoicesGeneration++
        catalogChoicesJob?.cancelAndJoin(); catalogChoicesJob = null
        transport.setListener(null)
        connectJob?.cancelAndJoin(); connectJob = null
        requestJob?.cancelAndJoin(); requestJob = null
        clientId = null; streamId = null; sequence = 0; usedRequests.clear()
        streamVersion = 1; streamControl = false; streamCatalog = false
        if (clearFollowed) { lastFollowedSessionId = null; lastFollowedNode = null }
        mutableState.update { it.copy(handshaken = false, lastSnapshotAt = null, controlNegotiated = false,
            catalogNegotiated = false, catalogChoicesLoading = false) }
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
                // A durable ACK may arrive inside the final SDK send callback. It owns no Room
                // read or SDK send and must not be dropped by the outbound message mutex.
                if (request.type in setOf("CATALOG_ACK", "CATALOG_ERROR")) {
                    acceptCatalogReceipt(captured, nodeId, request); return
                }
                // Back pressure: at most one request owns the source read/send, no detached publisher queue.
                if (!messageSerial.tryLock()) return
                requestJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try { withTimeout(when (request.type) {
                        "COMMAND" -> 12_000L
                        "GET_CATALOG" -> WearableCatalogProtocol.DEADLINE_MS
                        else -> 3_000L
                    }) { process(captured, nodeId, request) } }
                    catch (cancelled: CancellationException) {
                        if (cancelled is TimeoutCancellationException && request.type == "COMMAND" && currentChannel(captured)) {
                            val outcome = WearableCommandOutcome("RESULT_UNKNOWN")
                            controlAuthority.remember(request, outcome)
                            withTimeoutOrNull(3_000) { transport.send(nodeId, WearableProtocol.commandResult(request, checkNotNull(streamId), outcome)) }
                        } else throw cancelled
                    }
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
            controlAuthority.clear()
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
                lastSnapshotAt = null, errorCategory = terminalChannelError ?: category, controlNegotiated = false,
                catalogNegotiated = false)
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
            streamVersion = request.protocolVersion
            streamControl = streamVersion >= 2 && "SESSION_CONTROL" in request.capabilities
            streamCatalog = streamVersion == 3 && "BOOK_CATALOG" in request.capabilities
            retireCatalogTransfer()
            controlAuthority.clear()
            usedRequests += request.requestId
            if (!currentChannel(captured)) return
            transport.send(node, WearableProtocol.helloAck(request, checkNotNull(streamId)))
            updateCurrentChannel(captured) { it.copy(serviceAvailable = true,
                authorized = true, handshaken = true, errorCategory = null, controlNegotiated = streamControl,
                catalogNegotiated = streamCatalog) }
            return
        }
        if (request.clientInstanceId != clientId || request.streamId != streamId || request.protocolVersion != streamVersion) return
        if (request.type == "COMMAND") {
            processCommand(captured, node, request)
            return
        }
        if (request.type == "GET_CATALOG") {
            processCatalog(captured, node, request)
            return
        }
        if (request.requestId in usedRequests) return
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
            val control = executor?.let { owner -> controlAuthority.issue(node, request.clientInstanceId,
                checkNotNull(streamId), owner, body, observedAt, streamControl && settings.controlEnabled && mutableState.value.controlEnabled) } ?: WearableControlState()
            WearableProtocol.snapshot(request, checkNotNull(streamId), ++sequence, observedAt, body, control).also {
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
    private suspend fun processCommand(captured: Long, node: String, request: WearableRequest) {
        val owner = executor
        val stream = checkNotNull(streamId)
        val consent = controlConsentEpoch
        fun permitted() = currentChannel(captured) && streamId == stream && request.clientInstanceId == clientId &&
            node == selectedNode && owner === executor && consent == controlConsentEpoch && settings.controlEnabled &&
            mutableState.value.controlEnabled && mutableState.value.authorized && mutableState.value.serviceAvailable &&
            mutableState.value.handshaken && streamControl
        fun executionPermitted() = permitted() && owner != null && controlAuthority.valid(request, node, owner, clock())
        val cached = controlAuthority.cached(request)
        val outcome = when {
            !permitted() || owner == null -> WearableCommandOutcome("DISABLED")
            cached != null -> cached
            request.requestId in usedRequests -> WearableCommandOutcome("STALE_STATE")
            !controlAuthority.valid(request, node, owner, clock()) -> WearableCommandOutcome("STALE_STATE")
            else -> owner.execute(request, ::executionPermitted)
        }
        if (!currentChannel(captured) || owner !== executor || stream != streamId) return
        controlAuthority.remember(request, outcome)
        controlAuthority.revokeGrants()
        // Business admission and Room transaction have returned before SDK send.
        transport.send(node, WearableProtocol.commandResult(request, stream, outcome))
    }
    private fun retireCatalogTransfer() {
        catalogPending = null
        catalogTimeoutJob?.cancel(); catalogTimeoutJob = null
        mutableState.update { state -> if (state.catalogStatus in setOf("SENDING", "AWAITING_ACK"))
            state.copy(catalogStatus = "NOT_SENT", lastCatalogAt = null) else state }
    }
    private fun catalogCurrent(value: CatalogPending) = currentChannel(value.epoch) &&
        value.consent == catalogConsentEpoch && settings.catalogEnabled && mutableState.value.catalogEnabled &&
        value.source === catalogSource && value.node == selectedNode && value.client == clientId &&
        value.stream == streamId && streamCatalog
    private fun catalogWithinDeadline(value: CatalogPending): Boolean {
        val elapsed = monotonicClock() - value.startedAt
        return elapsed >= 0 && elapsed < WearableCatalogProtocol.DEADLINE_MS
    }
    private fun timeOutCatalog(value: CatalogPending) = synchronized(channelPolicy) {
        if (catalogPending !== value || !catalogCurrent(value)) return@synchronized
        catalogPending = null
        catalogTimeoutJob?.cancel(); catalogTimeoutJob = null
        mutableState.update { it.copy(catalogStatus = "ERROR", catalogError = "TIMEOUT", lastCatalogAt = null) }
    }
    private fun acceptCatalogReceipt(captured: Long, node: String, request: WearableRequest) = synchronized(channelPolicy) {
        val value = catalogPending ?: return@synchronized
        if (value.epoch != captured || !catalogCurrent(value) || node != value.node ||
            request.protocolVersion != 3 || request.clientInstanceId != value.client ||
            request.streamId != value.stream || request.requestId != value.request ||
            request.type == "CATALOG_ACK" && (request.catalogRevision != value.revision || !value.finalPartIssued)) return@synchronized
        // The receipt deadline is checked even if a coroutine timer was delayed while asleep.
        if (!catalogWithinDeadline(value)) { timeOutCatalog(value); return@synchronized }
        catalogPending = null
        catalogTimeoutJob?.cancel(); catalogTimeoutJob = null
        mutableState.update { it.copy(catalogStatus = if (request.type == "CATALOG_ACK") "SYNCED" else "ERROR",
            catalogRevision = if (request.type == "CATALOG_ACK") value.revision else it.catalogRevision,
            lastCatalogAt = if (request.type == "CATALOG_ACK") clock() else null,
            catalogError = if (request.type == "CATALOG_ACK") null else request.catalogCode) }
    }
    private suspend fun processCatalog(captured: Long, node: String, request: WearableRequest) {
        val reader = catalogSource
        val stream = checkNotNull(streamId)
        if (!streamCatalog || !settings.catalogEnabled || !mutableState.value.catalogEnabled) {
            transport.send(node, WearableCatalogProtocol.error(request, stream, "CATALOG_DISABLED")); return
        }
        if (request.requestId in usedRequests) return
        usedRequests += request.requestId
        if (usedRequests.size > 64) usedRequests.remove(usedRequests.first())
        val consent = catalogConsentEpoch
        fun contextPermitted() = currentChannel(captured) && reader != null && reader === catalogSource &&
            consent == catalogConsentEpoch && settings.catalogEnabled && mutableState.value.catalogEnabled &&
            streamId == stream && clientId == request.clientInstanceId && streamCatalog
        if (reader == null) {
            transport.send(node, WearableCatalogProtocol.error(request, stream, "SOURCE_BUSY")); return
        }
        val pending = CatalogPending(captured, consent, reader, node, request.clientInstanceId,
            stream, request.requestId, monotonicClock())
        synchronized(channelPolicy) {
            if (!contextPermitted()) return
            retireCatalogTransfer()
            catalogPending = pending
            mutableState.update { it.copy(catalogStatus = "SENDING", catalogError = null, lastCatalogAt = null) }
            // One fixed total budget includes the captured source read, encoding and SDK sends.
            catalogTimeoutJob = scope.launch {
                delay(WearableCatalogProtocol.DEADLINE_MS)
                timeOutCatalog(pending)
            }
        }
        fun permitted() = contextPermitted() && catalogPending === pending && catalogWithinDeadline(pending)
        val transfer = try {
            WearableCatalogProtocol.encode(reader.read(settings.catalogSelectedIds))
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) timeOutCatalog(pending)
            throw cancelled
        }
        catch (failure: Exception) {
            val code = when (failure) {
                is WearableSourceBusy -> "SOURCE_BUSY"
                is WearableCatalogException -> failure.code
                else -> "SOURCE_UNAVAILABLE"
            }
            if (contextPermitted() && catalogPending === pending) {
                synchronized(channelPolicy) {
                    if (catalogPending === pending && contextPermitted()) {
                        retireCatalogTransfer()
                        mutableState.update { it.copy(catalogStatus = "ERROR", catalogError = code) }
                    }
                }
                transport.send(node, WearableCatalogProtocol.error(request, stream, code))
            }
            return
        }
        if (!permitted()) { if (!catalogWithinDeadline(pending)) timeOutCatalog(pending); return }
        pending.revision = transfer.revision
        // The gate/Room read has returned. Sending the complete frozen bytes is never a receipt.
        transport.send(node, WearableCatalogProtocol.manifest(request, stream, transfer))
        transfer.parts.indices.forEach { index ->
            if (!permitted()) { if (!catalogWithinDeadline(pending)) timeOutCatalog(pending); return }
            if (index == transfer.parts.lastIndex) pending.finalPartIssued = true
            transport.send(node, WearableCatalogProtocol.part(request, stream, transfer, index))
        }
        synchronized(channelPolicy) {
            if (catalogPending === pending && contextPermitted()) {
                if (catalogWithinDeadline(pending)) mutableState.update { it.copy(catalogStatus = "AWAITING_ACK") }
                else timeOutCatalog(pending)
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
