package com.guanyi.mirra.data.maintenance

import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.data.repository.*
import com.guanyi.mirra.domain.DndStateStore
import com.guanyi.mirra.domain.intervention.InterventionDeliveryReceipt
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.monitoring.BackwardClockCloseoutEvidence
import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import com.guanyi.mirra.domain.monitoring.StableEvidence
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.platform.focus.MonitoringReadyLease

/** Composition preserves each frozen transaction, helper and file compensation algorithm. */
class GateLearningItemRepository(
    private val delegate: LearningItemRepository, private val gate: StorageMaintenanceGate,
) : LearningItemRepository by delegate {
    override suspend fun create(name: String, totalPages: Int, currentPage: Int, firstAction: String?,
        setAsMainline: Boolean) = gate.writerOperation {
        delegate.create(name, totalPages, currentPage, firstAction, setAsMainline)
    }
    override suspend fun updateFirstAction(id: String, firstAction: String) =
        gate.writerOperation { delegate.updateFirstAction(id, firstAction) }
    override suspend fun setMainline(id: String) = gate.writerOperation { delegate.setMainline(id) }
    override suspend fun pause(id: String) = gate.writerOperation { delegate.pause(id) }
    override suspend fun resume(id: String) = gate.writerOperation { delegate.resume(id) }
    override suspend fun complete(id: String) = gate.writerOperation { delegate.complete(id) }
}

class GateStudyWorkflowRepository(
    private val delegate: StudyWorkflowRepository, private val gate: StorageMaintenanceGate,
) : StudyWorkflowRepository by delegate {
    override suspend fun createIntent(learningItemId: String, setAsMainline: Boolean) =
        gate.writerOperation { delegate.createIntent(learningItemId, setAsMainline) }
    override suspend fun markTransitioned(intentId: String) = gate.writerOperation { delegate.markTransitioned(intentId) }
    override suspend fun abandonIntent(intentId: String) = gate.writerOperation { delegate.abandonIntent(intentId) }
    override suspend fun startSession(intentId: String, startPage: Int) =
        gate.writerOperation { delegate.startSession(intentId, startPage) }
    override suspend fun startMonitoredSession(intentId: String, startPage: Int,
        readyLease: MonitoringReadyLease, proposedSessionId: String) = gate.writerOperation {
        delegate.startMonitoredSession(intentId, startPage, readyLease, proposedSessionId)
    }
    override suspend fun updateCurrentPage(sessionId: String, page: Int) =
        gate.writerOperation { delegate.updateCurrentPage(sessionId, page) }
    override suspend fun beginCloseout(sessionId: String, requestedEndPage: Int, closeoutStartedAt: Long,
        backwardClockEvidence: BackwardClockCloseoutEvidence?) = gate.writerOperation {
        delegate.beginCloseout(sessionId, requestedEndPage, closeoutStartedAt, backwardClockEvidence)
    }
    override suspend fun completeCloseout(sessionId: String) = gate.writerOperation { delegate.completeCloseout(sessionId) }
    override suspend fun recoverInterruptedSession() = gate.writerOperation { delegate.recoverInterruptedSession() }
}

class GateNoteRepository(
    private val delegate: NoteRepository, private val gate: StorageMaintenanceGate,
) : NoteRepository by delegate {
    override suspend fun save(learningItemId: String, sessionId: String?, content: String,
        pageNumber: Int?, semanticType: NoteSemanticType, id: String?) = gate.writerOperation {
        delegate.save(learningItemId, sessionId, content, pageNumber, semanticType, id)
    }
    override suspend fun createStandalone(learningItemId: String, content: String,
        semanticType: NoteSemanticType, pageNumber: Int?, id: String?) = gate.writerOperation {
        delegate.createStandalone(learningItemId, content, semanticType, pageNumber, id)
    }
    override suspend fun update(noteId: String, content: String, semanticType: NoteSemanticType, pageNumber: Int?) =
        gate.writerOperation { delegate.update(noteId, content, semanticType, pageNumber) }
    override suspend fun delete(noteId: String) = gate.writerOperation { delegate.delete(noteId) }
}

class GateTopicRepository(
    private val delegate: TopicRepository, private val gate: StorageMaintenanceGate,
) : TopicRepository by delegate {
    override suspend fun create(name: String) = gate.writerOperation { delegate.create(name) }
    override suspend fun createAndLink(noteId: String, name: String) =
        gate.writerOperation { delegate.createAndLink(noteId, name) }
    override suspend fun link(noteId: String, topicId: String) = gate.writerOperation { delegate.link(noteId, topicId) }
    override suspend fun unlink(noteId: String, topicId: String) = gate.writerOperation { delegate.unlink(noteId, topicId) }
}

class GateFocusRepository(
    private val delegate: FocusRepository, private val gate: StorageMaintenanceGate,
) : FocusRepository by delegate {
    override suspend fun replaceRiskApp(packageName: String, label: String) =
        gate.writerOperation { delegate.replaceRiskApp(packageName, label) }
    override suspend fun removeRiskApp(packageName: String) = gate.writerOperation { delegate.removeRiskApp(packageName) }
    override suspend fun transition(command: SegmentTransitionCommand) = gate.writerOperation { delegate.transition(command) }
    override suspend fun recordEvent(event: FocusEventInput) = gate.writerOperation { delegate.recordEvent(event) }
    override suspend fun updateHeartbeat(sessionId: String, at: Long) =
        gate.writerOperation { delegate.updateHeartbeat(sessionId, at) }
    override suspend fun markMonitoringLost(sessionId: String, lastTrustedAt: Long, detectedAt: Long) =
        gate.writerOperation { delegate.markMonitoringLost(sessionId, lastTrustedAt, detectedAt) }
    override suspend fun markStableStarted(sessionId: String, at: Long, evidence: StableEvidence?) =
        gate.writerOperation { delegate.markStableStarted(sessionId, at, evidence) }
    override suspend fun completeRecovery(sessionId: String, at: Long, evidence: StableEvidence?) =
        gate.writerOperation { delegate.completeRecovery(sessionId, at, evidence) }
    override suspend fun applyBehavior(command: BehaviorCommand) = gate.writerOperation { delegate.applyBehavior(command) }
    override suspend fun promoteDeepFocus(sessionId: String, at: Long, evidence: StableEvidence) =
        gate.writerOperation { delegate.promoteDeepFocus(sessionId, at, evidence) }
    override suspend fun confirmRisk(candidate: RiskConfirmation) = gate.writerOperation { delegate.confirmRisk(candidate) }
    override suspend fun recordBriefRiskVisit(sessionId: String, packageName: String, exitedAt: Long) =
        gate.writerOperation { delegate.recordBriefRiskVisit(sessionId, packageName, exitedAt) }
    override suspend fun exitRisk(sessionId: String, packageName: String, at: Long) =
        gate.writerOperation { delegate.exitRisk(sessionId, packageName, at) }
}

class GateDndStateStore(
    private val delegate: DndStateStore, private val gate: StorageMaintenanceGate,
) : DndStateStore by delegate {
    override suspend fun prepare(sessionId: String, priorFilter: Int?, ruleId: String?) =
        gate.writerOperation { delegate.prepare(sessionId, priorFilter, ruleId) }
    override suspend fun setLifecycle(sessionId: String, lifecycle: DndLifecycle) =
        gate.writerOperation { delegate.setLifecycle(sessionId, lifecycle) }
}

class GateSearchRepository(
    private val delegate: SearchRepository, private val gate: StorageMaintenanceGate,
) : SearchRepository {
    // Queries can repair FTS; their complete read/repair/retry chain is one registered operation.
    override suspend fun search(rawQuery: String, limit: Int) = gate.writerOperation { delegate.search(rawQuery, limit) }
    override suspend fun rebuildIndex() = gate.writerOperation { delegate.rebuildIndex() }
}

class GateAppPreferencesRepository(
    private val delegate: AppPreferencesRepository, private val gate: StorageMaintenanceGate,
) : AppPreferencesRepository by delegate {
    override suspend fun setLastDestination(destination: TopLevelDestination) =
        gate.writerOperation { delegate.setLastDestination(destination) }
    override suspend fun setThemeId(themeId: MirraThemeId) = gate.writerOperation { delegate.setThemeId(themeId) }
    override suspend fun setDndEnabled(enabled: Boolean) = gate.writerOperation { delegate.setDndEnabled(enabled) }
    override suspend fun setCrossAppInterventionEnabled(enabled: Boolean) =
        gate.writerOperation { delegate.setCrossAppInterventionEnabled(enabled) }
}

class GateInterventionReceiptRepository(
    private val delegate: InterventionReceiptStore, private val gate: StorageMaintenanceGate,
) : InterventionReceiptStore {
    override suspend fun isEligible(prompt: InterventionUiModel) = gate.writerOperation { delegate.isEligible(prompt) }
    override suspend fun record(prompt: InterventionUiModel, receipt: InterventionDeliveryReceipt) =
        gate.writerOperation { delegate.record(prompt, receipt) }
}
