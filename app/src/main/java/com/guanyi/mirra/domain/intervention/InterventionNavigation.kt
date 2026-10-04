package com.guanyi.mirra.domain.intervention

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

enum class InterventionNavigationAction { VIEW, RETURN_TO_STUDY, OPEN_ALLOWANCE, OPEN_FINISH }
data class InterventionNavigationRequest(val sessionId: String, val promptToken: String,
    val action: InterventionNavigationAction) {
    val id get() = "$sessionId:$promptToken:$action"
}
class InterventionNavigationController(private val validate: suspend (InterventionNavigationRequest) -> Boolean) {
    private val mutableRequests = MutableStateFlow<InterventionNavigationRequest?>(null)
    val requests: StateFlow<InterventionNavigationRequest?> = mutableRequests
    private val mutex = Mutex()
    private val accepted = LinkedHashSet<String>()
    suspend fun submit(request: InterventionNavigationRequest): Boolean = mutex.withLock {
        if (request.sessionId.isBlank() || request.promptToken.isBlank() || request.id in accepted ||
            !valid(request)) return@withLock false
        accepted += request.id
        if (accepted.size > 128) accepted.remove(accepted.first())
        mutableRequests.value = request
        true
    }
    suspend fun consume(id: String): InterventionNavigationRequest? = mutex.withLock {
        val request = mutableRequests.value?.takeIf { it.id == id } ?: return@withLock null
        mutableRequests.value = null
        request.takeIf { valid(it) }
    }
    private suspend fun valid(request: InterventionNavigationRequest): Boolean = try { validate(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { false }
}

/** Process-local snapshot; never stores a content URI or resurrects a dead Session. */
class SessionInterventionSnapshot {
    var sessionId: String? = null; private set
    private var enabled = false
    @Synchronized fun capture(id: String, value: Boolean) {
        if (sessionId == id) return
        sessionId = id; enabled = value
    }
    @Synchronized fun enabledFor(id: String?) = id != null && sessionId == id && enabled
    @Synchronized fun clear(id: String? = sessionId) {
        if (sessionId == id) { sessionId = null; enabled = false }
    }
}
