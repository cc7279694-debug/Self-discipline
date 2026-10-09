package com.guanyi.mirra.data.maintenance

import com.guanyi.mirra.domain.SessionManager
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.monitoring.ClockSample

/** Own the whole workflow, including platform side effects and NonCancellable cleanup. */
class GateSessionManager(
    private val delegate: SessionManager, private val gate: StorageMaintenanceGate,
) : SessionManager {
    override suspend fun start(intentId: String, startPage: Int) =
        gate.writerOperation { delegate.start(intentId, startPage) }
    override suspend fun updatePage(sessionId: String, page: Int) =
        gate.writerOperation { delegate.updatePage(sessionId, page) }
    override suspend fun finish(sessionId: String, endPage: Int, sample: ClockSample) =
        gate.writerOperation { delegate.finish(sessionId, endPage, sample) }
    override suspend fun retryPendingFinish(sessionId: String) =
        gate.writerOperation { delegate.retryPendingFinish(sessionId) }
    override suspend fun recoverInterruptedSession() =
        gate.writerOperation { delegate.recoverInterruptedSession() }
}
