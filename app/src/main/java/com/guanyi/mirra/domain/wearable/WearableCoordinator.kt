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
    val recordSyncEnabled: Boolean get() = false
    fun setRecordSyncEnabled(value: Boolean) { check(!value) }
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
    val recordSyncEnabled: Boolean = false,
    val recordNegotiated: Boolean = false,
    val recordStatus: String = "IDLE",
    val recordError: String? = null,
    val lastRecordAt: Long? = null,
    val importedRecordCount: Long? = null,
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
        catalogSelectedIds = settings.catalogSelectedIds, recordSyncEnabled = settings.recordSyncEnabled))
    val state = mutableState.asStateFlow()
    @Volatile private var epoch = 0L
    @Volatile private var source: WearableSnapshotSource? = null
    @Volatile private var executor: WearableCommandExecutor? = null
    @Volatile private var catalogSource: WearableCatalogSource? = null
    @Volatile private var recordReceiver: WearableRecordReceiver? = null
    @Volatile private var recordConsentEpoch = 0L
    @Volatile private var streamRecord = false
    private class RecordPending(val epoch: Long, val consent: Long, val receiver: WearableRecordReceiver,
        val node: String, val offer: WearableRequest, val startedAt: Long) {
        val frame = checkNotNull(offer.record)
        val parts = arrayOfNulls<String>(checkNotNull(frame.partCount))
        var receivedBytes = 0
        var phase = "OFFERING"
        var failureCode: String? = null
        var receipt: WearableRecordReceipt? = null
    }
    @Volatile private var recordPending: RecordPending? = null
    private var recordOfferJob: Job? = null
    private var recordCommitJob: Job? = null
    private var recordTimeoutJob: Job? = null
    private var recordCountJob: Job? = null
    private var recordCountGeneration = 0L
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
        catalog: WearableCatalogSource? = null, records: WearableRecordReceiver? = null) = lifecycle.withLock {
        invalidateAndDrain(resetTerminal = true)
        source = value
        executor = commandExecutor
        catalogSource = catalog
        recordReceiver = records
        mutableState.update { it.copy(sourceReady = true) }
        loadRecordCountFromUser()
        if (settings.enabled) connect(false)
    }
    suspend fun pauseSource() = lifecycle.withLock {
        invalidateAndDrain()
        source = null
        executor = null
        catalogSource = null
        recordReceiver = null
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
    fun setRecordSyncEnabledFromUser(value: Boolean) {
        val consent = synchronized(channelPolicy) {
            recordConsentEpoch++
            streamRecord = false
            retireRecordTransfer()
            mutableState.update { it.copy(recordNegotiated = false) }
            recordConsentEpoch
        }
        try { settings.setRecordSyncEnabled(value) }
        catch (_: Exception) {
            synchronized(channelPolicy) {
                if (consent == recordConsentEpoch) mutableState.update { it.copy(recordSyncEnabled = false,
                    recordNegotiated = false, recordError = "SETTINGS_UNAVAILABLE") }
            }
            return
        }
        synchronized(channelPolicy) {
            if (consent == recordConsentEpoch) mutableState.update { it.copy(recordSyncEnabled = value, recordNegotiated = false,
                recordStatus = "IDLE", recordError = null, lastRecordAt = null) }
        }
    }
    fun loadRecordCountFromUser() {
        val receiver = recordReceiver ?: return
        val captured = epoch
        val generation = synchronized(channelPolicy) { ++recordCountGeneration }
        recordCountJob?.cancel()
        recordCountJob = scope.launch {
            val count = try { withTimeout(3_000) { receiver.count().also { require(it >= 0) } } }
            catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled; null }
            catch (_: Exception) { null }
            synchronized(channelPolicy) {
                if (captured == epoch && receiver === recordReceiver && generation == recordCountGeneration)
                    mutableState.update { it.copy(importedRecordCount = count) }
            }
        }
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
            catalogSelectedIds = settings.catalogSelectedIds, recordSyncEnabled = settings.recordSyncEnabled)
    } } }
    suspend fun close() = lifecycle.withLock {
        invalidateAndDrain(); source = null; executor = null; catalogSource = null; recordReceiver = null; transport.close(); selectedNode = null
    }

    private suspend fun invalidateAndDrain(clearFollowed: Boolean = true, resetTerminal: Boolean = false) {
        synchronized(channelPolicy) {
            epoch++
            recordCountGeneration++
            controlAuthority.clear()
            if (resetTerminal) terminalChannelError = null
        }
        retireAppCheck()
        retireCatalogTransfer()
        retireRecordTransfer()
        recordCountJob?.cancelAndJoin(); recordCountJob = null
        catalogChoicesGeneration++
        catalogChoicesJob?.cancelAndJoin(); catalogChoicesJob = null
        transport.setListener(null)
        connectJob?.cancelAndJoin(); connectJob = null
        requestJob?.cancelAndJoin(); requestJob = null
        recordOfferJob?.cancelAndJoin(); recordOfferJob = null
        recordCommitJob?.cancelAndJoin(); recordCommitJob = null
        clientId = null; streamId = null; sequence = 0; usedRequests.clear()
        streamVersion = 1; streamControl = false; streamCatalog = false; streamRecord = false
        if (clearFollowed) { lastFollowedSessionId = null; lastFollowedNode = null }
        mutableState.update { it.copy(handshaken = false, lastSnapshotAt = null, controlNegotiated = false,
            catalogNegotiated = false, catalogChoicesLoading = false, recordNegotiated = false,
            recordStatus = "IDLE", recordError = null, lastRecordAt = null, importedRecordCount = null) }
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
                if (request.record != null) { receiveRecord(captured, nodeId, request); return }
                if (request.type == "HELLO" && (request.clientInstanceId != clientId || request.requestId !in usedRequests)) {
                    synchronized(channelPolicy) {
                        streamRecord = false; retireRecordTransfer()
                        recordCountGeneration++
                        recordCountJob?.cancel()
                        mutableState.update { it.copy(recordNegotiated = false, recordStatus = "IDLE",
                            recordError = null, lastRecordAt = null, importedRecordCount = null) }
                    }
                }
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
                catalogNegotiated = false, recordNegotiated = false)
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
            streamCatalog = streamVersion >= 3 && "BOOK_CATALOG" in request.capabilities
            streamRecord = streamVersion == 4 && "LOCAL_RECORD_SYNC" in request.capabilities &&
                settings.recordSyncEnabled && mutableState.value.recordSyncEnabled && recordReceiver != null
            retireCatalogTransfer()
            retireRecordTransfer()
            controlAuthority.clear()
            usedRequests += request.requestId
            if (!currentChannel(captured)) return
            transport.send(node, WearableProtocol.helloAck(request, checkNotNull(streamId), streamRecord))
            updateCurrentChannel(captured) { it.copy(serviceAvailable = true,
                authorized = true, handshaken = true, errorCategory = null, controlNegotiated = streamControl,
                catalogNegotiated = streamCatalog, recordNegotiated = streamRecord) }
            loadRecordCountFromUser()
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
            request.protocolVersion != streamVersion || request.clientInstanceId != value.client ||
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
    private fun recordWithinDeadline(value: RecordPending): Boolean = try {
        val elapsed = monotonicClock() - value.startedAt
        elapsed >= 0 && elapsed < WearableRecordProtocol.DEADLINE_MS
    } catch (_: Exception) { false }
    private fun recordContextCurrent(captured: Long, node: String, request: WearableRequest,
        receiver: WearableRecordReceiver?, consent: Long) = currentChannel(captured) && receiver != null &&
        receiver === recordReceiver && source != null && node == selectedNode && request.protocolVersion == 4 &&
        request.clientInstanceId == clientId && request.streamId == streamId && streamVersion == 4 &&
        consent == recordConsentEpoch && streamRecord && settings.recordSyncEnabled &&
        mutableState.value.recordSyncEnabled && mutableState.value.handshaken &&
        mutableState.value.authorized && mutableState.value.serviceAvailable
    private fun recordCurrent(value: RecordPending) = recordPending === value &&
        recordContextCurrent(value.epoch, value.node, value.offer, value.receiver, value.consent)
    private fun sameRecord(a: WearableRecordFrame, b: WearableRecordFrame) = a.clientInstanceId == b.clientInstanceId &&
        a.requestId == b.requestId && a.streamId == b.streamId && a.sourceInstallationId == b.sourceInstallationId &&
        a.localSessionId == b.localSessionId && a.recordDigest == b.recordDigest && a.totalBytes == b.totalBytes
    private fun retireRecordTransfer() {
        recordPending = null
        recordTimeoutJob?.cancel(); recordTimeoutJob = null
        recordOfferJob?.cancel()
        recordCommitJob?.cancel()
        mutableState.update { if (it.recordStatus in setOf("RECEIVING", "COMMITTING"))
            it.copy(recordStatus = "IDLE", lastRecordAt = null) else it }
    }
    private fun timeOutRecord(value: RecordPending) = synchronized(channelPolicy) {
        if (!recordCurrent(value)) return@synchronized
        retireRecordTransfer()
        mutableState.update { it.copy(recordStatus = "ERROR", recordError = "TIMEOUT", lastRecordAt = null) }
    }
    private fun failRecord(value: RecordPending, code: String) = synchronized(channelPolicy) {
        if (!recordCurrent(value) || value.phase == "FAILED") return@synchronized
        value.phase = "FAILED"
        value.failureCode = code
        recordTimeoutJob?.cancel(); recordTimeoutJob = null
        mutableState.update { it.copy(recordStatus = "ERROR", recordError = code, lastRecordAt = null) }
        if (recordCommitJob?.isActive == true) return@synchronized
        recordCommitJob = scope.launch {
            val job = currentCoroutineContext()[Job]
            try {
                messageSerial.withLock {
                    if (recordCurrent(value)) transport.send(value.node, WearableRecordProtocol.error(value.offer, code))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* No failure response can authorize a completed import. */ }
            finally { synchronized(channelPolicy) { if (recordCommitJob === job) recordCommitJob = null } }
        }
    }
    /** One bounded offer job and one commit job; PART callbacks never create a queue. */
    private fun receiveRecord(captured: Long, node: String, request: WearableRequest) = synchronized(channelPolicy) {
        val frame = request.record ?: return@synchronized
        if (!currentChannel(captured) || node != selectedNode || request.protocolVersion != streamVersion ||
            request.clientInstanceId != clientId || request.streamId != streamId) return@synchronized
        if (request.type == "LOCAL_RECORD_OFFER") {
            if (recordOfferJob?.isActive == true) return@synchronized
            val receiver = recordReceiver
            val consent = recordConsentEpoch
            val startedAt = try { monotonicClock() } catch (_: Exception) { return@synchronized }
            recordOfferJob = scope.launch {
                val job = currentCoroutineContext()[Job]
                try {
                    withTimeout(WearableRecordProtocol.DEADLINE_MS) {
                        messageSerial.withLock { processRecordOffer(captured, node, request, receiver, consent, startedAt) }
                    }
                } catch (cancelled: CancellationException) {
                    if (cancelled is TimeoutCancellationException) recordPending?.takeIf { it.offer === request }?.let(::timeOutRecord)
                    else throw cancelled
                } catch (failure: Exception) {
                    recordPending?.takeIf { it.offer === request }?.let { failRecord(it, recordFailureCode(failure)) }
                } finally { synchronized(channelPolicy) { if (recordOfferJob === job) recordOfferJob = null } }
            }
            return@synchronized
        }
        val value = recordPending ?: return@synchronized
        if (!recordCurrent(value) || !sameRecord(value.frame, frame)) return@synchronized
        if (!recordWithinDeadline(value)) { timeOutRecord(value); return@synchronized }
        if (request.type == "LOCAL_RECORD_ERROR") {
            retireRecordTransfer()
            mutableState.update { it.copy(recordStatus = "ERROR", recordError = frame.code, lastRecordAt = null) }
            return@synchronized
        }
        if (value.phase == "COMMITTED" && request.type == "LOCAL_RECORD_COMMIT") {
            val receipt = value.receipt ?: return@synchronized
            if (recordCommitJob?.isActive == true) return@synchronized
            recordCommitJob = scope.launch {
                val job = currentCoroutineContext()[Job]
                try { messageSerial.withLock {
                    if (recordCurrent(value) && recordWithinDeadline(value))
                        transport.send(value.node, WearableRecordProtocol.ack(value.offer, receipt.alreadyPresent))
                } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { updateCurrentChannel(value.epoch) { it.copy(recordError = "RESULT_UNKNOWN") } }
                finally { synchronized(channelPolicy) { if (recordCommitJob === job) recordCommitJob = null } }
            }
            return@synchronized
        }
        if (value.phase != "RECEIVING") return@synchronized
        if (request.type == "LOCAL_RECORD_PART") {
            val index = checkNotNull(frame.index)
            if (index !in value.parts.indices) { failRecord(value, "INVALID_RECORD"); return@synchronized }
            val data = checkNotNull(frame.data)
            val previous = value.parts[index]
            if (previous != null) { if (previous != data) failRecord(value, "CONFLICT"); return@synchronized }
            val bytes = data.toByteArray(Charsets.UTF_8).size
            if (value.receivedBytes + bytes > value.frame.totalBytes) { failRecord(value, "INVALID_RECORD"); return@synchronized }
            value.parts[index] = data; value.receivedBytes += bytes
            return@synchronized
        }
        if (request.type != "LOCAL_RECORD_COMMIT") return@synchronized
        if (value.parts.any { it == null } || value.receivedBytes != value.frame.totalBytes) {
            failRecord(value, "INVALID_RECORD"); return@synchronized
        }
        val raw = value.parts.joinToString("") { checkNotNull(it) }
        value.phase = "COMMITTING"
        mutableState.update { it.copy(recordStatus = "COMMITTING") }
        recordCommitJob = scope.launch {
            val job = currentCoroutineContext()[Job]
            try { messageSerial.withLock { commitRecord(value, raw) } }
            catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) timeOutRecord(value) else throw cancelled
            } catch (failure: Exception) {
                if (value.phase == "COMMITTED") updateCurrentChannel(value.epoch) { it.copy(recordError = "RESULT_UNKNOWN") }
                else failRecord(value, recordFailureCode(failure))
            }
            finally {
                synchronized(channelPolicy) { if (recordCommitJob === job) recordCommitJob = null }
                if (value.phase == "FAILED" && recordCurrent(value)) {
                    try { messageSerial.withLock {
                        if (recordCurrent(value)) transport.send(value.node, WearableRecordProtocol.error(value.offer,
                            value.failureCode ?: "DB_FAILED"))
                    } } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A failed transfer has no durable-success response. */ }
                }
            }
        }
    }
    private suspend fun processRecordOffer(captured: Long, node: String, request: WearableRequest,
        receiver: WearableRecordReceiver?, consent: Long, startedAt: Long) {
        if (!recordContextCurrent(captured, node, request, receiver, consent)) {
            if (currentChannel(captured) && node == selectedNode && request.clientInstanceId == clientId && request.streamId == streamId)
                transport.send(node, WearableRecordProtocol.error(request, "DISABLED"))
            return
        }
        val frame = checkNotNull(request.record)
        val value = synchronized(channelPolicy) {
            val old = recordPending
            if (old != null && old.phase in setOf("OFFERING", "RECEIVING", "COMMITTING")) {
                if (!sameRecord(old.frame, frame)) {
                    null
                } else if (old.frame.partCount != frame.partCount || old.frame.schemaVersion != frame.schemaVersion) {
                    failRecord(old, "CONFLICT"); null
                } else old
            } else {
                recordTimeoutJob?.cancel()
                recordPending = null
                recordCommitJob?.cancel()
                RecordPending(captured, consent, checkNotNull(receiver), node, request,
                    if (old != null && sameRecord(old.frame, frame)) old.startedAt else startedAt).also { pending ->
                    recordPending = pending
                    mutableState.update { it.copy(recordStatus = "RECEIVING", recordError = null, lastRecordAt = null) }
                    val remaining = WearableRecordProtocol.DEADLINE_MS - (monotonicClock() - pending.startedAt)
                    recordTimeoutJob = scope.launch { delay(maxOf(0L, remaining)); timeOutRecord(pending) }
                }
            }
        }
        if (value == null) {
            if (recordContextCurrent(captured, node, request, receiver, consent))
                transport.send(node, WearableRecordProtocol.error(request, "BUSY"))
            return
        }
        if (!recordCurrent(value)) return
        if (!recordWithinDeadline(value)) { timeOutRecord(value); return }
        if (value.phase == "COMMITTING") {
            transport.send(node, WearableRecordProtocol.error(request, "BUSY")); return
        }
        if (value.phase == "OFFERING") {
            try { value.receiver.offer(frame.sourceInstallationId, frame.localSessionId, frame.recordDigest, frame.totalBytes) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failRecord(value, recordFailureCode(failure)); return }
            synchronized(channelPolicy) {
                if (!recordCurrent(value)) return
                if (!recordWithinDeadline(value)) { timeOutRecord(value); return }
                value.phase = "RECEIVING"
            }
        }
        // A matching stored digest never produces an ACK: full original bytes are still required.
        if (recordCurrent(value) && recordWithinDeadline(value)) transport.send(node, WearableRecordProtocol.need(request))
    }
    private suspend fun commitRecord(value: RecordPending, raw: String) {
        if (!recordCurrent(value)) return
        if (!recordWithinDeadline(value)) { timeOutRecord(value); return }
        val parsed = try { WearableLocalRecord.parse(raw, value.frame.recordDigest) }
        catch (_: Exception) { failRecord(value, "INVALID_RECORD"); return }
        if (parsed.installationId != value.frame.sourceInstallationId || parsed.sessionId != value.frame.localSessionId ||
            parsed.bytes != value.frame.totalBytes) { failRecord(value, "INVALID_RECORD"); return }
        val remaining = WearableRecordProtocol.DEADLINE_MS - (monotonicClock() - value.startedAt)
        if (remaining <= 0) { timeOutRecord(value); return }
        val receipt = withTimeout(remaining) { value.receiver.persist(raw, value.frame.recordDigest) }
        synchronized(channelPolicy) {
            if (!recordCurrent(value)) return
            if (!recordWithinDeadline(value)) { timeOutRecord(value); return }
            if (receipt.installationId != parsed.installationId || receipt.sessionId != parsed.sessionId ||
                receipt.digest != parsed.digest || receipt.bytes != parsed.bytes) { failRecord(value, "DB_FAILED"); return }
            value.receipt = receipt; value.phase = "COMMITTED"
            recordTimeoutJob?.cancel(); recordTimeoutJob = null
            recordCountGeneration++
            recordCountJob?.cancel()
            mutableState.update { it.copy(recordStatus = "IMPORTED", recordError = null, lastRecordAt = clock(),
                importedRecordCount = it.importedRecordCount?.let { count -> if (receipt.alreadyPresent) count else count + 1 }) }
        }
        // The captured gate/Room transaction has returned before SDK send. SDK send is never a DB receipt.
        if (!recordCurrent(value) || !recordWithinDeadline(value)) return
        transport.send(value.node, WearableRecordProtocol.ack(value.offer, receipt.alreadyPresent))
        if (recordCurrent(value)) loadRecordCountFromUser()
    }
    private fun recordFailureCode(failure: Exception): String = when (failure) {
        is WearableRecordStorageException -> failure.code.takeIf { it in WearableRecordProtocol.ERRORS } ?: "DB_FAILED"
        is WearableSourceBusy -> "BUSY"
        is com.guanyi.mirra.domain.maintenance.RetiredStorageEpochException -> "DISABLED"
        is WearableSourceUnavailable -> "DB_FAILED"
        else -> "DB_FAILED"
    }
    private companion object {
        val AUTO_RETRYABLE = setOf("NO_DEVICE", "SERVICE_UNAVAILABLE", "TIMEOUT")
        val AUTO_RETRY_DELAYS = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)
        val APP_CHECK_ERRORS = setOf("TIMEOUT", "NO_DEVICE", "MULTIPLE_DEVICES", "WRONG_DEVICE",
            "NOT_AUTHORIZED", "SERVICE_UNAVAILABLE", "SIGNING_IDENTITY_MISMATCH", "COUNTERPART_APP_MISSING",
            "CLEANUP_PENDING", "CHECK_UNAVAILABLE", "SDK_ERROR")
    }
}
