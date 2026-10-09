package com.guanyi.mirra.data.maintenance

import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.local.entity.FocusEventType
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.preferences.AppPreferencesRepository
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.data.repository.*
import com.guanyi.mirra.domain.DndStateStore
import com.guanyi.mirra.domain.intervention.InterventionDeliveryReceipt
import com.guanyi.mirra.domain.maintenance.MaintenanceUnavailableException
import com.guanyi.mirra.domain.maintenance.StorageMaintenanceGate
import com.guanyi.mirra.domain.monitoring.EvidenceMilestone
import com.guanyi.mirra.domain.monitoring.StableEvidence
import com.guanyi.mirra.domain.monitoring.InterventionUiModel
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.platform.focus.MonitoringReadyLease
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Each lambda exercises a real wrapper's admission before any storage work can occur. */
class GateWriterAdmissionCoverageTest {
    @Test fun `every ordinary writer refuses exclusive before it reaches its resource`() = runTest {
        val gate = StorageMaintenanceGate(leaseScope = backgroundScope)
        val items = GateLearningItemRepository(forbiddenDelegate(), gate)
        val workflow = GateStudyWorkflowRepository(forbiddenDelegate(), gate)
        val notes = GateNoteRepository(forbiddenDelegate(), gate)
        val images = GateImageRepository(forbiddenDelegate(), gate)
        val topics = GateTopicRepository(forbiddenDelegate(), gate)
        val focus = GateFocusRepository(forbiddenDelegate(), gate)
        val dnd = GateDndStateStore(forbiddenDelegate(), gate)
        val search = GateSearchRepository(forbiddenDelegate(), gate)
        val preferences = GateAppPreferencesRepository(forbiddenDelegate(), gate)
        val receipts = GateInterventionReceiptRepository(forbiddenDelegate(), gate)
        val ready = MonitoringReadyLease("generation", 1, 1, 1, 1, 1, 1, true, true)
        val deep = StableEvidence("segment", EvidenceMilestone.DEEP, 1, 0)
        val prompt = InterventionUiModel("session", "event", "event", "segment", "package", 1, 1)
        val writers: List<Pair<String, suspend () -> Unit>> = listOf(
            "item.create" to { items.create("name", 10) },
            "item.updateFirstAction" to { items.updateFirstAction("item", "act") },
            "item.setMainline" to { items.setMainline("item") },
            "item.pause" to { items.pause("item") },
            "item.resume" to { items.resume("item") },
            "item.complete" to { items.complete("item") },
            "workflow.createIntent" to { workflow.createIntent("item") },
            "workflow.markTransitioned" to { workflow.markTransitioned("intent") },
            "workflow.abandonIntent" to { workflow.abandonIntent("intent") },
            "workflow.startSession" to { workflow.startSession("intent", 1) },
            "workflow.startMonitoredSession" to { workflow.startMonitoredSession("intent", 1, ready, "session") },
            "workflow.updateCurrentPage" to { workflow.updateCurrentPage("session", 1) },
            "workflow.beginCloseout" to { workflow.beginCloseout("session", 1, 1) },
            "workflow.completeCloseout" to { workflow.completeCloseout("session") },
            "workflow.recoverInterruptedSession" to { workflow.recoverInterruptedSession() },
            "note.save" to { notes.save("item", null, "content", 1) },
            "note.createStandalone" to { notes.createStandalone("item", "content") },
            "note.update" to { notes.update("note", "content", NoteSemanticType.UNDERSTANDING, 1) },
            "note.delete" to { notes.delete("note") },
            "image.importFromGallery" to { images.importFromGallery("note", emptyList()) },
            "image.createCameraTarget" to { images.createCameraTarget() },
            "image.updateCaption" to { images.updateCaption("image", "caption") },
            "image.deleteImage" to { images.deleteImage("image") },
            "image.reconcileStorage" to { images.reconcileStorage() },
            "topic.create" to { topics.create("topic") },
            "topic.createAndLink" to { topics.createAndLink("note", "topic") },
            "topic.link" to { topics.link("note", "topic") },
            "topic.unlink" to { topics.unlink("note", "topic") },
            "focus.confirmRisk" to { focus.confirmRisk(RiskConfirmation("session", "segment", "pkg", 1, 2)) },
            "focus.recordBriefRiskVisit" to { focus.recordBriefRiskVisit("session", "pkg", 2) },
            "focus.exitRisk" to { focus.exitRisk("session", "pkg", 2) },
            "focus.replaceRiskApp" to { focus.replaceRiskApp("pkg", "app") },
            "focus.removeRiskApp" to { focus.removeRiskApp("pkg") },
            "focus.transition" to { focus.transition(SegmentTransitionCommand("session", SessionSegmentType.FOCUS, 1)) },
            "focus.recordEvent" to { focus.recordEvent(FocusEventInput("session", FocusEventType.SCREEN_INTERACTIVE, 1)) },
            "focus.updateHeartbeat" to { focus.updateHeartbeat("session", 1) },
            "focus.markMonitoringLost" to { focus.markMonitoringLost("session", 1, 2) },
            "focus.markStableStarted" to { focus.markStableStarted("session", 1) },
            "focus.completeRecovery" to { focus.completeRecovery("session", 1) },
            "focus.promoteDeepFocus" to { focus.promoteDeepFocus("session", 1, deep) },
            "focus.applyBehavior" to { focus.applyBehavior(BehaviorCommand("session", "segment", BehaviorAction.START_BREAK, 1, "token")) },
            "dnd.prepare" to { dnd.prepare("session", null, null) },
            "dnd.setLifecycle" to { dnd.setLifecycle("session", DndLifecycle.RELEASED) },
            "search.search" to { search.search("query") },
            "search.rebuildIndex" to { search.rebuildIndex() },
            "preferences.setLastDestination" to { preferences.setLastDestination(TopLevelDestination.Start) },
            "preferences.setThemeId" to { preferences.setThemeId(MirraThemeId.BLUE) },
            "preferences.setDndEnabled" to { preferences.setDndEnabled(false) },
            "preferences.setCrossAppInterventionEnabled" to { preferences.setCrossAppInterventionEnabled(false) },
            "receipt.record" to { receipts.record(prompt, InterventionDeliveryReceipt.IN_APP_PRESENTED) },
        )
        assertEquals(50, writers.size)
        gate.coordinator.withExclusive(1_000) {
            for ((name, writer) in writers) {
                val failure = runCatching { writer() }.exceptionOrNull()
                assertTrue("$name reached resources or did not reject: $failure", failure is MaintenanceUnavailableException)
            }
        }
        assertEquals(0, gate.coordinator.state.value.activePermits)
    }

    private inline fun <reified T> forbiddenDelegate(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> throw AssertionError("Resource touched through ${method.name}") } as T
}
