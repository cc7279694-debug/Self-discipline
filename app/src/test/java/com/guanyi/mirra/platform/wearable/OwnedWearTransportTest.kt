package com.guanyi.mirra.platform.wearable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedWearTransportTest {
    @Test fun startupEmptyNodesRecoverThroughTheRealCoordinatorAndOwnedTransport() = runTest {
        val sdk = FakeGateway().apply { nodes = emptyList() }
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        val settings = object : com.guanyi.mirra.domain.wearable.WearableSettingsStore {
            override val enabled = true
            override fun setEnabled(value: Boolean) = Unit
        }
        val coordinator = com.guanyi.mirra.domain.wearable.WearableCoordinator(backgroundScope, transport, settings)
        coordinator.bindSource(com.guanyi.mirra.domain.wearable.WearableSnapshotSource {
            fail("transport recovery must not read learning data")
            com.guanyi.mirra.domain.wearable.WearableSnapshot(com.guanyi.mirra.domain.wearable.WearablePhase.IDLE)
        })
        runCurrent()
        assertEquals("NO_DEVICE", coordinator.state.value.errorCategory)
        assertEquals(0, sdk.serviceObservers)
        sdk.nodes = listOf(SdkWearNode("node-1", "Band"))
        advanceTimeBy(2_001); runCurrent()
        assertTrue(coordinator.state.value.serviceAvailable)
        assertTrue(coordinator.state.value.authorized)
        assertFalse(coordinator.state.value.handshaken)
        assertNull(coordinator.state.value.lastSnapshotAt)
        assertEquals(0, sdk.authorizationRequests)
        assertEquals(1, sdk.listenCalls)
        coordinator.close()
    }

    @Test fun appCheckBeforeConnectionNeverInitializesSdk() = runTest {
        var initializations = 0
        val transport = OwnedWearTransport({ initializations++; FakeGateway() }, backgroundScope)
        assertCategory("SERVICE_UNAVAILABLE") { transport.isWearAppInstalled("node-1") }
        assertEquals(0, initializations)
    }

    @Test fun appPresenceQueryPreservesTheOwnedListenerAndDoesNotRequestPermission() = runTest {
        val sdk = FakeGateway()
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        transport.connect()
        val callback = sdk.messageCallback
        assertTrue(transport.isWearAppInstalled("node-1"))
        sdk.appInstalled = false
        assertFalse(transport.isWearAppInstalled("node-1"))
        assertEquals(listOf("node-1", "node-1"), sdk.appQueries)
        assertEquals(1, sdk.listenCalls)
        assertTrue(callback === sdk.messageCallback)
        assertTrue(sdk.removedNodes.isEmpty())
        assertEquals(0, sdk.authorizationRequests)
        assertTrue(sdk.sentMessages.isEmpty())
        transport.close()
    }

    @Test fun appCheckRejectsRevokedAuthorizationAndChangedOrAmbiguousNodes() = runTest {
        val sdk = FakeGateway()
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        transport.connect()
        assertCategory("WRONG_DEVICE") { transport.isWearAppInstalled("foreign") }
        sdk.authorized = false
        assertCategory("NOT_AUTHORIZED") { transport.isWearAppInstalled("node-1") }
        sdk.authorized = true
        sdk.nodes = listOf(SdkWearNode("other", "Other"))
        assertCategory("WRONG_DEVICE") { transport.isWearAppInstalled("node-1") }
        sdk.nodes = emptyList()
        assertCategory("NO_DEVICE") { transport.isWearAppInstalled("node-1") }
        sdk.nodes = listOf(SdkWearNode("node-1", "Band"), SdkWearNode("other", "Other"))
        assertCategory("MULTIPLE_DEVICES") { transport.isWearAppInstalled("node-1") }
        assertTrue(sdk.appQueries.isEmpty())
        assertEquals(1, sdk.listenCalls)
        assertEquals(0, sdk.authorizationRequests)
        transport.close()
    }

    @Test fun appCheckRevalidatesAuthorizationAndNodeAfterTheNativeResult() = runTest {
        for (change in listOf("authorization", "node", "service")) {
            val sdk = FakeGateway().apply { appBarrier = CompletableDeferred() }
            val transport = OwnedWearTransport({ sdk }, backgroundScope)
            transport.connect()
            var category: String? = null
            val check = launch {
                try { transport.isWearAppInstalled("node-1") }
                catch (e: WearTransportException) { category = e.category }
            }
            runCurrent()
            when (change) {
                "authorization" -> sdk.authorized = false
                "node" -> sdk.nodes = listOf(SdkWearNode("other", "Other"))
                "service" -> sdk.disconnectedCallback?.invoke()
            }
            sdk.appBarrier?.complete(Unit)
            check.join()
            assertEquals(when (change) {
                "authorization" -> "NOT_AUTHORIZED"
                "node" -> "WRONG_DEVICE"
                else -> "SERVICE_UNAVAILABLE"
            }, category)
            assertEquals(1, sdk.listenCalls)
            transport.close()
        }
    }

    @Test fun appCheckTimeoutAndLateCompletionNeverReplaceTheListener() = runTest {
        val sdk = FakeGateway().apply { appBarrier = CompletableDeferred() }
        val transport = OwnedWearTransport({ sdk }, backgroundScope, timeoutMs = 10)
        transport.connect()
        val callback = sdk.messageCallback
        assertCategory("TIMEOUT") { transport.isWearAppInstalled("node-1") }
        sdk.appBarrier?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, sdk.listenCalls)
        assertTrue(callback === sdk.messageCallback)
        assertTrue(sdk.removedNodes.isEmpty())
        transport.close()
        assertCategory("SERVICE_UNAVAILABLE") { transport.isWearAppInstalled("node-1") }
        assertEquals(1, sdk.appQueries.size)
    }

    @Test fun disabledTransportDoesNotInitializeSdk() = runTest {
        var calls = 0
        val transport = OwnedWearTransport({ calls++; FakeGateway() }, backgroundScope)
        transport.setListener(RecordingListener())
        transport.close()
        assertEquals(0, calls)
    }

    @Test fun reconnectNeverAsksForAuthorization() = runTest {
        val sdk = FakeGateway().apply { authorized = false }
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        assertCategory("NOT_AUTHORIZED") { transport.connect() }
        assertEquals(0, sdk.authorizationRequests)
        assertEquals(0, sdk.listenCalls)
    }

    @Test fun explicitConnectAsksOnlyOnceAndConfirmsResult() = runTest {
        val sdk = FakeGateway().apply { authorized = false }
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        val result = transport.connect(true)
        assertTrue(result.authorized)
        assertEquals("node-1", result.nodeId)
        assertEquals(1, sdk.authorizationRequests)
    }

    @Test fun deniedPermissionNeverAddsMessageListener() = runTest {
        val sdk = FakeGateway().apply { authorized = false; grantAuthorization = false }
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        assertCategory("AUTHORIZATION_DENIED") { transport.connect(true) }
        assertEquals(0, sdk.listenCalls)
    }

    @Test fun noNodeAndAmbiguousNodesAreNotInventedConnections() = runTest {
        val sdk = FakeGateway().apply { nodes = emptyList() }
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        assertCategory("NO_DEVICE") { transport.connect() }
        sdk.nodes = listOf(SdkWearNode("a", null), SdkWearNode("b", null))
        assertCategory("MULTIPLE_DEVICES") { transport.connect() }
        assertEquals(0, sdk.listenCalls)
    }

    @Test fun foreignNodeAndOversizeIncomingMessagesAreRejected() = runTest {
        val sdk = FakeGateway()
        val listener = RecordingListener()
        val transport = OwnedWearTransport({ sdk }, this)
        transport.setListener(listener)
        transport.connect()
        sdk.messageCallback?.invoke("foreign", byteArrayOf(1))
        sdk.messageCallback?.invoke("node-1", ByteArray(4097))
        advanceUntilIdle()
        assertTrue(listener.messages.isEmpty())
        assertTrue(listener.errors.contains("WRONG_DEVICE"))
        assertTrue(listener.errors.contains("INVALID_MESSAGE"))
        transport.close()
    }

    @Test fun receivedMessageIsCopiedBeforeCallbackReturns() = runTest {
        val sdk = FakeGateway()
        val listener = RecordingListener()
        val transport = OwnedWearTransport({ sdk }, this)
        transport.setListener(listener)
        transport.connect()
        val bytes = byteArrayOf(42)
        sdk.messageCallback?.invoke("node-1", bytes)
        bytes[0] = 0
        advanceUntilIdle()
        assertEquals(42, listener.messages.single()[0].toInt())
        transport.close()
    }

    @Test fun closeAndNewEpochIgnoreLateOldCallbacks() = runTest {
        val sdk = FakeGateway()
        val listener = RecordingListener()
        val transport = OwnedWearTransport({ sdk }, this)
        transport.setListener(listener)
        transport.connect()
        val oldCallback = sdk.messageCallback
        transport.close()
        transport.connect()
        oldCallback?.invoke("node-1", byteArrayOf(1))
        sdk.messageCallback?.invoke("node-1", byteArrayOf(2))
        advanceUntilIdle()
        assertEquals(1, listener.messages.size)
        assertEquals(2, listener.messages.single()[0].toInt())
        transport.close()
    }

    @Test fun repeatedCloseRemovesOnlyOwnedNodeOnce() = runTest {
        val sdk = FakeGateway()
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        transport.connect()
        transport.close()
        transport.close()
        assertEquals(listOf("node-1"), sdk.removedNodes)
        assertEquals(0, sdk.serviceObservers)
    }

    @Test fun permissionRevocationRejectsSendAndReceive() = runTest {
        val sdk = FakeGateway()
        val listener = RecordingListener()
        val transport = OwnedWearTransport({ sdk }, this)
        transport.setListener(listener)
        transport.connect()
        sdk.authorized = false
        assertCategory("NOT_AUTHORIZED") { transport.send("node-1", byteArrayOf(1)) }
        sdk.messageCallback?.invoke("node-1", byteArrayOf(2))
        advanceUntilIdle()
        assertEquals(0, sdk.sentMessages.size)
        assertTrue(listener.messages.isEmpty())
        transport.close()
    }

    @Test fun sendRejectsUnknownNodeEmptyAndOversizedPayload() = runTest {
        val sdk = FakeGateway()
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        transport.connect()
        assertCategory("WRONG_DEVICE") { transport.send("other", byteArrayOf(1)) }
        assertCategory("INVALID_MESSAGE") { transport.send("node-1", byteArrayOf()) }
        assertCategory("INVALID_MESSAGE") { transport.send("node-1", ByteArray(4097)) }
        assertTrue(sdk.sentMessages.isEmpty())
        transport.close()
    }

    @Test fun revokedAuthorizationRequiresExplicitReconnectBeforeAnyMoreMessages() = runTest {
        val sdk = FakeGateway()
        val listener = RecordingListener()
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        transport.setListener(listener)
        transport.connect()
        sdk.authorized = false
        assertCategory("NOT_AUTHORIZED") { transport.send("node-1", byteArrayOf(1)) }
        sdk.authorized = true
        assertCategory("SERVICE_UNAVAILABLE") { transport.send("node-1", byteArrayOf(2)) }
        assertTrue(listener.errors.contains("NOT_AUTHORIZED"))
        assertTrue(sdk.sentMessages.isEmpty())
        transport.close()
    }

    @Test fun serviceDisconnectImmediatelyRetiresIncomingEpoch() = runTest {
        val sdk = FakeGateway()
        val listener = RecordingListener()
        val transport = OwnedWearTransport({ sdk }, this)
        transport.setListener(listener)
        transport.connect()
        sdk.disconnectedCallback?.invoke()
        sdk.messageCallback?.invoke("node-1", byteArrayOf(1))
        advanceUntilIdle()
        assertEquals(1, listener.disconnections)
        assertTrue(listener.messages.isEmpty())
        assertCategory("SERVICE_UNAVAILABLE") { transport.send("node-1", byteArrayOf(1)) }
        transport.close()
    }

    @Test fun timedOutRegistrationCannotStealListenerFromAnotherEpoch() = runTest {
        val sdk = FakeGateway().apply { registrationBarrier = CompletableDeferred() }
        val transport = OwnedWearTransport({ sdk }, backgroundScope, timeoutMs = 10)
        assertCategory("TIMEOUT") { transport.connect() }
        assertCategory("CLEANUP_PENDING") { transport.connect() }
        assertEquals(1, sdk.listenCalls)
        transport.close()
    }

    @Test fun uncertainOldListenerRemovalPreventsNewOwnerRegistration() = runTest {
        val sdk = FakeGateway()
        val transport = OwnedWearTransport({ sdk }, backgroundScope, timeoutMs = 10)
        transport.connect()
        sdk.removalBarrier = CompletableDeferred()
        assertCategory("CLEANUP_PENDING") { transport.connect() }
        assertEquals(1, sdk.listenCalls)
        transport.close()
    }

    @Test fun serviceReturnSignalsOnceAndNeverRestoresOldConnection() = runTest {
        val sdk = FakeGateway()
        val listener = RecordingListener()
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        transport.setListener(listener)
        transport.connect()
        sdk.availableCallback?.invoke()
        assertEquals(0, listener.availabilitySignals)
        sdk.disconnectedCallback?.invoke()
        sdk.availableCallback?.invoke()
        sdk.availableCallback?.invoke()
        assertEquals(1, listener.availabilitySignals)
        assertCategory("SERVICE_UNAVAILABLE") { transport.send("node-1", byteArrayOf(1)) }
        transport.close()
        sdk.availableCallback?.invoke()
        assertEquals(1, listener.availabilitySignals)
        assertFalse(sdk.sentMessages.isNotEmpty())
    }

    @Test fun serviceReturnSurvivesDetachedListenerAndReplaysOnlyOnce() = runTest {
        val sdk = FakeGateway()
        val oldListener = RecordingListener()
        val nextListener = RecordingListener()
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        transport.setListener(oldListener)
        transport.connect()
        sdk.disconnectedCallback?.invoke()
        transport.setListener(null)
        sdk.availableCallback?.invoke()
        sdk.availableCallback?.invoke()
        transport.setListener(nextListener)
        transport.setListener(nextListener)
        assertEquals(0, oldListener.availabilitySignals)
        assertEquals(1, nextListener.availabilitySignals)
        assertCategory("SERVICE_UNAVAILABLE") { transport.send("node-1", byteArrayOf(1)) }
        assertEquals(0, sdk.authorizationRequests)
        transport.close()
    }

    @Test fun closeRetiresAvailabilityQueuedDuringDetachedListener() = runTest {
        val sdk = FakeGateway()
        val listener = RecordingListener()
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        transport.connect()
        val oldAvailable = checkNotNull(sdk.availableCallback)
        sdk.disconnectedCallback?.invoke()
        oldAvailable()
        transport.close()
        transport.setListener(listener)
        oldAvailable()
        assertEquals(0, listener.availabilitySignals)
        assertCategory("SERVICE_UNAVAILABLE") { transport.send("node-1", byteArrayOf(1)) }
    }

    @Test fun newConnectionEpochRetiresDetachedListenerAvailability() = runTest {
        val sdk = FakeGateway()
        val listener = RecordingListener()
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        transport.connect()
        val oldAvailable = checkNotNull(sdk.availableCallback)
        sdk.disconnectedCallback?.invoke()
        oldAvailable()
        transport.connect(false)
        transport.setListener(listener)
        oldAvailable()
        assertEquals(0, listener.availabilitySignals)
        assertEquals(0, sdk.authorizationRequests)
        transport.send("node-1", byteArrayOf(1))
        transport.close()
    }

    @Test fun humanAuthorizationMayFinishAfterMessageDeadlineWithoutTimingOut() = runTest {
        val sdk = FakeGateway().apply {
            authorized = false
            authorizationBarrier = CompletableDeferred()
        }
        val transport = OwnedWearTransport({ sdk }, backgroundScope)
        var result: WearConnection? = null
        val request = launch { result = transport.connect(true) }
        advanceTimeBy(3_001)
        assertTrue(request.isActive)
        assertEquals(0, sdk.listenCalls)
        sdk.authorizationBarrier?.complete(Unit)
        advanceUntilIdle()
        assertTrue(result?.authorized == true)
        assertEquals(1, sdk.authorizationRequests)
        transport.close()
    }

    private suspend fun assertCategory(expected: String, operation: suspend () -> Unit) {
        var actual: String? = null
        try { operation() } catch (e: WearTransportException) { actual = e.category }
        assertEquals(expected, actual)
    }

    private class RecordingListener : WearTransportListener {
        val messages = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()
        var disconnections = 0
        var availabilitySignals = 0
        override fun onMessage(nodeId: String, payload: ByteArray) { messages += payload }
        override fun onDisconnected() { disconnections++ }
        override fun onError(category: String) { errors += category }
        override fun onAvailable() { availabilitySignals++ }
    }

    private class FakeGateway : WearSdkGateway {
        var appInstalled = true
        var appBarrier: CompletableDeferred<Unit>? = null
        val appQueries = mutableListOf<String>()
        override suspend fun isWearAppInstalled(nodeId: String): Boolean {
            appQueries += nodeId
            appBarrier?.await()
            return appInstalled
        }
        var nodes = listOf(SdkWearNode("node-1", "Band"))
        var authorized = true
        var grantAuthorization = true
        var authorizationRequests = 0
        var listenCalls = 0
        var serviceObservers = 0
        var messageCallback: ((String, ByteArray) -> Unit)? = null
        var disconnectedCallback: (() -> Unit)? = null
        var availableCallback: (() -> Unit)? = null
        var registrationBarrier: CompletableDeferred<Unit>? = null
        var removalBarrier: CompletableDeferred<Unit>? = null
        var authorizationBarrier: CompletableDeferred<Unit>? = null
        val removedNodes = mutableListOf<String>()
        val sentMessages = mutableListOf<ByteArray>()
        override suspend fun serviceApiLevel() = 1
        override suspend fun connectedNodes() = nodes
        override suspend fun authorized(nodeId: String) = authorized
        override suspend fun requestAuthorization(nodeId: String): Boolean {
            authorizationRequests++
            authorizationBarrier?.await()
            authorized = grantAuthorization
            return authorized
        }
        override suspend fun addMessageListener(nodeId: String, listener: (String, ByteArray) -> Unit) {
            listenCalls++
            messageCallback = listener
            registrationBarrier?.await()
        }
        override suspend fun removeMessageListener(nodeId: String) {
            removedNodes += nodeId
            removalBarrier?.await()
        }
        override suspend fun sendMessage(nodeId: String, payload: ByteArray) { sentMessages += payload }
        override suspend fun observeService(disconnected: () -> Unit, available: () -> Unit) {
            serviceObservers++
            disconnectedCallback = disconnected
            availableCallback = available
        }
        override suspend fun removeServiceObserver() { if (serviceObservers > 0) serviceObservers-- }
    }
}
