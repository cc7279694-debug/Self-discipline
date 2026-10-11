package com.guanyi.mirra.platform.wearable

/** An optional, device-local message channel. It has no learning-write operations. */
interface WearTransport {
    suspend fun isWearAppInstalled(nodeId: String): Boolean = throw WearTransportException("CHECK_UNAVAILABLE")
    suspend fun connect(requestAuthorization: Boolean = false): WearConnection
    suspend fun send(nodeId: String, payload: ByteArray)
    fun setListener(listener: WearTransportListener?)
    suspend fun close()
}

data class WearConnection(
    val nodeId: String,
    val serviceApiLevel: Int,
    val authorized: Boolean,
    val deviceName: String? = null,
)

interface WearTransportListener {
    fun onMessage(nodeId: String, payload: ByteArray)
    fun onDisconnected()
    fun onError(category: String)
    /** SDK service availability only; a new authorized connection/handshake is still required. */
    fun onAvailable() = Unit
}

/** Categories are safe to display/log; the SDK's original exception may contain device data. */
class WearTransportException(val category: String) : Exception(category)
