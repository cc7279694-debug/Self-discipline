package com.guanyi.mirra.feature.profile

import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.platform.focus.CapabilityStatus
import com.guanyi.mirra.platform.focus.MonitorSnapshot
import com.guanyi.mirra.platform.focus.MonitoringBinding
import com.guanyi.mirra.platform.focus.MonitoringCapabilities
import com.guanyi.mirra.platform.focus.NotificationStatus
import com.guanyi.mirra.platform.focus.ServicePhase
import com.guanyi.mirra.platform.focus.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LearningProtectionProjectionTest {
    private val session = StudySessionEntity("current", "book", "intent", 1_000,
        null, null, 1, 1, null, null, null, 1)
    private val binding = MonitoringBinding("current", "current-generation", 100)
    private val ready = MonitoringCapabilities(
        usage = CapabilityStatus.AVAILABLE,
        service = ServiceState(ServicePhase.MONITOR_READY, "current-generation", foregroundAck = true),
        monitor = MonitorSnapshot(running = true, lastSuccessfulQueryElapsed = 200),
    )

    @Test fun grantedPermissionsWithStoppedServiceDoNotClaimReady() {
        val projected = projectLearningProtection(MonitoringCapabilities(usage = CapabilityStatus.AVAILABLE,
            dnd = CapabilityStatus.AVAILABLE, notification = NotificationStatus(drawerVisible = true)),
            null, session, context())

        assertEquals(LearningProtectionServiceStatus.STOPPED, projected.serviceStatus)
        assertEquals(MonitoringCoverage.FULL, projected.coverage)
    }

    @Test fun matchingSessionAndGenerationCanShowActualReady() {
        val projected = projectLearningProtection(ready, binding, session, context(), elapsedNowMillis = 300)

        assertEquals("current", projected.activeSessionId)
        assertEquals(LearningProtectionServiceStatus.READY, projected.serviceStatus)
        assertEquals(MonitoringCoverage.FULL, projected.coverage)
    }

    @Test fun oldSessionBindingDoesNotShowCurrentServiceReady() {
        val projected = projectLearningProtection(ready, binding.copy(sessionId = "old"), session, context())

        assertEquals(LearningProtectionServiceStatus.NOT_CONFIRMED, projected.serviceStatus)
        assertEquals(MonitoringCoverage.FULL, projected.coverage)
    }

    @Test fun oldBindingGenerationDoesNotShowCurrentServiceReady() {
        val projected = projectLearningProtection(ready, binding.copy(generation = "old-generation"), session, context())

        assertEquals(LearningProtectionServiceStatus.NOT_CONFIRMED, projected.serviceStatus)
    }

    @Test fun staleCapabilityGenerationDoesNotShowCurrentServiceReady() {
        val projected = projectLearningProtection(ready.copy(service = ready.service.copy(generation = "old-generation")),
            binding, session, context())

        assertEquals(LearningProtectionServiceStatus.NOT_CONFIRMED, projected.serviceStatus)
    }

    @Test fun permissionsDoNotReplaceDurableCoverage() {
        for (coverage in MonitoringCoverage.entries) {
            val projected = projectLearningProtection(ready, binding, session, context(coverage), elapsedNowMillis = 300)

            assertEquals(coverage, projected.coverage)
            assertEquals(LearningProtectionServiceStatus.READY, projected.serviceStatus)
        }
    }

    @Test fun notificationAndDndDenialDoNotChangeMonitoringReadinessOrCoverage() {
        val projected = projectLearningProtection(ready.copy(dnd = CapabilityStatus.NEEDS_USER_ACTION,
            notification = NotificationStatus(drawerVisible = false)), binding, session, context(), elapsedNowMillis = 300)

        assertEquals(LearningProtectionServiceStatus.READY, projected.serviceStatus)
        assertEquals(MonitoringCoverage.FULL, projected.coverage)
    }

    @Test fun pendingCloseoutNeverLooksLikeActiveFullMonitoring() {
        val projected = projectLearningProtection(ready, binding, session,
            context().copy(closeoutState = FocusCloseoutState.PENDING, requestedEndPage = 1, closeoutStartedAt = 2_000))

        assertNull(projected.activeSessionId)
        assertNull(projected.coverage)
        assertEquals(LearningProtectionServiceStatus.INACTIVE, projected.serviceStatus)
    }

    @Test fun contextFromAnotherSessionIsNotCurrentCoverage() {
        val projected = projectLearningProtection(ready, binding, session, context().copy(sessionId = "old"))

        assertNull(projected.activeSessionId)
        assertNull(projected.coverage)
    }

    @Test fun missingContextDoesNotFabricateNone() {
        val projected = projectLearningProtection(ready, binding, session, null)

        assertNull(projected.coverage)
        assertNull(projected.activeSessionId)
    }

    @Test fun endedOrInactiveSessionIsNotActiveLearning() {
        for (closed in listOf(session.copy(endedAt = 2_000), session.copy(activeSlot = null))) {
            val projected = projectLearningProtection(ready, binding, closed, context())

            assertNull(projected.coverage)
            assertNull(projected.activeSessionId)
            assertEquals(LearningProtectionServiceStatus.INACTIVE, projected.serviceStatus)
        }
    }

    @Test fun absentSessionDoesNotFabricateNoneOrServiceReady() {
        val projected = projectLearningProtection(ready, binding, null, context())

        assertNull(projected.coverage)
        assertEquals(LearningProtectionServiceStatus.INACTIVE, projected.serviceStatus)
    }

    @Test fun sixSecondOldQueryCannotShowCurrentReadyOrRewriteFullCoverage() {
        val projected = projectLearningProtection(ready, binding, session, context(), elapsedNowMillis = 6_200)

        assertEquals(LearningProtectionServiceStatus.NOT_CONFIRMED, projected.serviceStatus)
        assertEquals(MonitoringCoverage.FULL, projected.coverage)
    }

    @Test fun queryJustInsideExistingSixSecondContractCanShowCurrentReady() {
        val projected = projectLearningProtection(ready, binding, session, context(), elapsedNowMillis = 6_199)

        assertEquals(LearningProtectionServiceStatus.READY, projected.serviceStatus)
    }

    @Test fun negativeQueryAgeCannotShowCurrentReady() {
        val projected = projectLearningProtection(ready, binding, session, context(), elapsedNowMillis = 199)

        assertEquals(LearningProtectionServiceStatus.NOT_CONFIRMED, projected.serviceStatus)
    }

    @Test fun missingCurrentElapsedSampleCannotShowCurrentReady() {
        val projected = projectLearningProtection(ready, binding, session, context(), elapsedNowMillis = null)

        assertEquals(LearningProtectionServiceStatus.NOT_CONFIRMED, projected.serviceStatus)
    }

    @Test fun missingSuccessfulQueryTimeDoesNotClaimCurrentReady() {
        val projected = projectLearningProtection(ready.copy(monitor = ready.monitor.copy(lastSuccessfulQueryElapsed = null)),
            binding, session, context(), elapsedNowMillis = 300)

        assertEquals(LearningProtectionServiceStatus.NOT_CONFIRMED, projected.serviceStatus)
    }

    @Test fun overflowingQueryAgeCannotShowCurrentReady() {
        val projected = projectLearningProtection(ready.copy(monitor = ready.monitor.copy(lastSuccessfulQueryElapsed = Long.MIN_VALUE)),
            binding, session, context(), elapsedNowMillis = Long.MAX_VALUE)

        assertEquals(LearningProtectionServiceStatus.NOT_CONFIRMED, projected.serviceStatus)
    }

    @Test fun restoredSnapshotCannotWidenTheFrozenServiceFreshnessBoundary() {
        val projected = projectLearningProtection(ready, binding, session,
            context().copy(maxObservationGapMillis = 60_000), elapsedNowMillis = 6_200)

        assertEquals(LearningProtectionServiceStatus.NOT_CONFIRMED, projected.serviceStatus)
        assertEquals(MonitoringCoverage.FULL, projected.coverage)
    }

    private fun context(coverage: MonitoringCoverage = MonitoringCoverage.FULL) = SessionFocusContextEntity(
        sessionId = "current", monitoringStatus = coverage, monitoringLostAt = null,
        priorDndInterruptionFilter = null, dndRuleId = null, requestedEndPage = null, closeoutStartedAt = null,
        lastHeartbeatAt = 1_000, createdAt = 1_000, updatedAt = 1_000,
    )
}
