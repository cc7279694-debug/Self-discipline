package com.guanyi.mirra.domain.monitoring

import com.guanyi.mirra.data.local.entity.*
import com.guanyi.mirra.data.repository.*
import com.guanyi.mirra.platform.focus.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FocusSessionActionsTest {
    private val binding = MonitoringBinding("s", "g", 0)
    private val context = SessionFocusContextEntity("s", monitoringStatus = MonitoringCoverage.FULL,
        monitoringLostAt = null, priorDndInterruptionFilter = null, dndRuleId = null,
        requestedEndPage = null, closeoutStartedAt = null, lastHeartbeatAt = 1_000, createdAt = 1_000, updatedAt = 1_000)
    private fun segment(type: SessionSegmentType, at: Long = 1_000) = SessionSegmentEntity("initial", "s", type,
        at, null, "risk.a".takeIf { type == SessionSegmentType.DISTRACTION }, null, null, 0, null, 1)
    private fun sample(elapsed: Long) = ClockSample(elapsed + 1_000, elapsed)
    private fun snapshot(elapsed: Long, observation: ForegroundObservation = ForegroundObservation.Unknown("no event")) =
        MonitorSnapshot(true, elapsed + 1, elapsed, sample(elapsed), observation = observation)
    private fun port(type: SessionSegmentType = SessionSegmentType.DISTRACTION, count: Int = 1): Port = Port(
        RuntimeFocusFacts(1_000, segment(type), MonitoringCoverage.FULL, 1_000, setOf("risk.a", "risk.b"),
            context, FocusEventEntity("event", "s", FocusEventType.RISK_APP_CONFIRMED, 1_000, "risk.a", "source", null),
            mapOf("risk.a" to count), null, type == SessionSegmentType.DISTRACTION))

    @Test fun allowanceWaitPausesOffscreenAndOnlySuccessfulGrantConsumesPrompt() = runTest {
        val p = port(count = 2); val c = BoundSessionMonitoringController(p)
        c.refresh("s", sample(1_000))
        c.selectAllowanceReason("s", "event", AllowanceReason.REPLY, true, sample(1_000))
        assertEquals(FocusActionResult.CONFLICT, c.grantAllowance("s", "initial", "event", "risk.a", AllowanceReason.REPLY, null, sample(2_000)))
        c.setPromptVisible("s", "event", false, sample(3_000))
        c.setPromptVisible("s", "event", true, sample(30_000))
        assertEquals(FocusActionResult.CONFLICT, c.grantAllowance("s", "initial", "event", "risk.a", AllowanceReason.REPLY, null, sample(32_999)))
        assertEquals(FocusActionResult.SUCCESS, c.grantAllowance("s", "initial", "event", "risk.a", AllowanceReason.REPLY, null, sample(33_000)))
        assertEquals(34_000L, p.current!!.activeSegment.startedAt)
        assertEquals(214_000L, p.current!!.activeSegment.plannedEndAt)
        assertNull(c.intervention.value)
        assertEquals(FocusActionResult.EXPIRED, c.grantAllowance("s", "initial", "event", "risk.a", AllowanceReason.REPLY, null, sample(34_000)))
    }
    @Test fun dismissAndReturnDoNotDeclareRecoverySuccessOrRepeatPrompt() = runTest {
        val p = port(); val c = BoundSessionMonitoringController(p)
        c.refresh("s", sample(1_000)); c.returnToStudy("s", "event", sample(2_000))
        c.refresh("s", sample(3_000))
        assertTrue(c.intervention.value!!.dismissed)
        assertEquals(SessionSegmentType.DISTRACTION, p.current!!.activeSegment.type)
        assertTrue(p.milestones.isEmpty()); assertTrue(p.commands.isEmpty())
    }
    @Test fun endedSessionRejectsOldCommandAndSaveFailureRetainsPrompt() = runTest {
        val p = port(); val c = BoundSessionMonitoringController(p); c.refresh("s", sample(1_000))
        c.selectAllowanceReason("s", "event", AllowanceReason.REPLY, true, sample(1_000))
        p.failSave = true
        assertEquals(FocusActionResult.SAVE_FAILED, c.grantAllowance("s", "initial", "event", "risk.a", AllowanceReason.REPLY, null, sample(2_000)))
        assertNotNull(c.intervention.value)
        p.current = null
        assertEquals(FocusActionResult.EXPIRED, c.startBreak("s", "initial", 300_000, sample(3_000)))
    }
    @Test fun sameEpisodeCanGrantAfterLegitimateRiskExitButNotAfterAnotherEpisode() = runTest {
        val p = port(); val c = BoundSessionMonitoringController(p); c.refresh("s", sample(1_000))
        c.selectAllowanceReason("s", "event", AllowanceReason.REPLY, true, sample(1_000))
        p.current = p.current!!.copy(activeSegment = segment(SessionSegmentType.RECOVERY, 2_000).copy(id = "recovery"), interventionEligible = true)
        c.refresh("s", sample(2_000))
        assertEquals(FocusActionResult.SUCCESS, c.grantAllowance("s", "recovery", "event", "risk.a", AllowanceReason.REPLY, null, sample(3_000)))
    }
    @Test fun sessionVisibleEvidenceOutlivesUsagePackageFreshnessAndGapWinsOverLateRecovery() = runTest {
        val p = port(SessionSegmentType.RECOVERY); val c = BoundSessionMonitoringController(p)
        for (second in 1L..90L) {
            val elapsed = second * 1_000
            c.onSample(binding, snapshot(elapsed), sample(elapsed))
            c.observeEvidence("s", sample(elapsed), false, true)
        }
        assertTrue(p.milestones.isEmpty())
        c.onSample(binding, snapshot(91_000), sample(91_000))
        c.observeEvidence("s", sample(91_000), false, true)
        assertEquals(listOf(EvidenceMilestone.RECOVERY), p.milestones)
        val p2 = port(SessionSegmentType.RECOVERY); val c2 = BoundSessionMonitoringController(p2)
        for (second in 1L..90L) {
            c2.onSample(binding, snapshot(second * 1_000), sample(second * 1_000))
            c2.observeEvidence("s", sample(second * 1_000), true, false)
        }
        c2.onServiceLost(binding, sample(91_000), "permission revoked")
        c2.observeEvidence("s", sample(91_000), true, false)
        assertTrue(p2.milestones.isEmpty()); assertEquals(MonitoringCoverage.PARTIAL, p2.current!!.coverage)
    }
    @Test fun missingPositiveSamplesCannotBeBackfilledEvenWithHealthyQueries() = runTest {
        val p = port(SessionSegmentType.RECOVERY); val c = BoundSessionMonitoringController(p)
        for (second in 1L..91L) {
            c.onSample(binding, snapshot(second * 1_000), sample(second * 1_000))
            if (second == 1L || second == 91L) c.observeEvidence("s", sample(second * 1_000), true, false)
        }
        assertTrue(p.milestones.isEmpty())
    }
    @Test fun breakSuppressesRiskAndExpiryStartsFreshCandidate() = runTest {
        val p = port(SessionSegmentType.BREAK)
        p.current = p.current!!.copy(activeSegment = p.current!!.activeSegment.copy(plannedEndAt = 301_000))
        val c = BoundSessionMonitoringController(p)
        for (second in 1L..310L) {
            val e = second * 1_000
            c.onSample(binding, snapshot(e, ForegroundObservation.Package("risk.a", e, 1_000)), sample(e))
            if (second < 300) assertTrue(p.confirmations.isEmpty())
        }
        assertEquals(1, p.confirmations.size)
        assertEquals(301_000L, p.confirmations.single().candidateStartedAt)
    }
    @Test fun allowanceAIsNotWhitelistForRiskBAndBriefBDoesNotConsumeIt() = runTest {
        val p = port(SessionSegmentType.TEMPORARY_ALLOWANCE)
        p.current = p.current!!.copy(activeSegment = p.current!!.activeSegment.copy(packageName = "risk.a", plannedEndAt = 301_000))
        val c = BoundSessionMonitoringController(p)
        for (second in 1L..5L) c.onSample(binding, snapshot(second * 1_000,
            ForegroundObservation.Package("risk.b", second * 1_000, 2_000)), sample(second * 1_000))
        c.onSample(binding, snapshot(6_000, ForegroundObservation.Package("risk.a", 6_000, 7_000)), sample(6_000))
        assertTrue(p.confirmations.isEmpty())
        assertEquals(listOf("risk.b"), p.briefs)
        for (second in 7L..18L) c.onSample(binding, snapshot(second * 1_000,
            ForegroundObservation.Package("risk.b", second * 1_000, 8_000)), sample(second * 1_000))
        assertEquals("risk.b", p.confirmations.single().packageName)
    }

    @Test fun overdueQueryBeforeUserActionPersistsGapBeforeAllowingAnyExtension() = runTest {
        val p = port(SessionSegmentType.TEMPORARY_ALLOWANCE)
        p.current = p.current!!.copy(activeSegment = p.current!!.activeSegment.copy(plannedEndAt = 301_000))
        val c = BoundSessionMonitoringController(p)
        c.onSample(binding, snapshot(1_000), sample(1_000))
        assertEquals(FocusActionResult.EXPIRED, c.extendAllowance("s", "initial", "extend", sample(7_000)))
        assertEquals(MonitoringCoverage.PARTIAL, p.current!!.coverage)
        assertEquals(SessionSegmentType.UNMONITORED, p.current!!.activeSegment.type)
        assertTrue(p.commands.isEmpty())
    }

    @Test fun fullFocusMilestonesUseFreshEvidenceAndUnlockOnlyDemotesDeepFocus() = runTest {
        val p = port(SessionSegmentType.FOCUS); val c = BoundSessionMonitoringController(p)
        for (second in 1L..901L) {
            val e = second * 1_000
            c.onSample(binding, snapshot(e), sample(e))
            c.observeEvidence("s", sample(e), second >= 301, second < 301)
        }
        assertEquals(listOf(EvidenceMilestone.STABLE, EvidenceMilestone.DEEP), p.milestones)
        assertEquals(SessionSegmentType.DEEP_FOCUS, p.current!!.activeSegment.type)
        c.onSample(binding, snapshot(902_000), sample(902_000))
        c.observeEvidence("s", sample(902_000), false, false)
        assertEquals(SessionSegmentType.FOCUS, p.current!!.activeSegment.type)
        assertTrue(p.confirmations.isEmpty())
    }

    @Test fun changingReasonRestartsVisibleWaitAndNeutralEvidenceDoesNotCauseGap() = runTest {
        val p = port(count = 3); val c = BoundSessionMonitoringController(p)
        c.refresh("s", sample(1_000))
        c.selectAllowanceReason("s", "event", AllowanceReason.REPLY, true, sample(1_000))
        c.selectAllowanceReason("s", "event", AllowanceReason.RESEARCH, true, sample(15_000))
        assertEquals(15_000L, c.intervention.value!!.remainingWaitMillis)
        assertEquals(FocusActionResult.CONFLICT, c.grantAllowance("s", "initial", "event", "risk.a",
            AllowanceReason.RESEARCH, null, sample(29_999)))
        assertEquals(FocusActionResult.SUCCESS, c.grantAllowance("s", "initial", "event", "risk.a",
            AllowanceReason.RESEARCH, null, sample(30_000)))
        val p2 = port(SessionSegmentType.RECOVERY); val c2 = BoundSessionMonitoringController(p2)
        for (second in 1L..100L) {
            c2.onSample(binding, snapshot(second * 1_000), sample(second * 1_000))
            c2.observeEvidence("s", sample(second * 1_000), false, false)
        }
        assertEquals(MonitoringCoverage.FULL, p2.current!!.coverage)
        assertTrue(p2.milestones.isEmpty())
    }

    private class Port(var current: RuntimeFocusFacts?) : RuntimeFactsPort {
        val commands = mutableListOf<BehaviorCommand>(); val milestones = mutableListOf<EvidenceMilestone>()
        val confirmations = mutableListOf<RiskConfirmation>(); var failSave = false; var sequence = 0
        val briefs = mutableListOf<String>()
        override suspend fun read(sessionId: String) = current?.takeIf { it.activeSegment.sessionId == sessionId }
        override suspend fun heartbeat(sessionId: String, at: Long) = Unit
        override suspend fun lose(sessionId: String, trustedAt: Long, detectedAt: Long) {
            current = current?.copy(coverage = MonitoringCoverage.PARTIAL,
                activeSegment = current!!.activeSegment.copy(id = "gap", type = SessionSegmentType.UNMONITORED))
        }
        override suspend fun confirm(command: RiskConfirmation): Boolean {
            confirmations += command
            current = current!!.copy(activeSegment = current!!.activeSegment.copy(id = "risk-${++sequence}",
                type = SessionSegmentType.DISTRACTION, startedAt = command.candidateStartedAt, packageName = command.packageName))
            return true
        }
        override suspend fun brief(sessionId: String, packageName: String, at: Long): Boolean { briefs += packageName; return true }
        override suspend fun exit(sessionId: String, packageName: String, at: Long): Boolean {
            current = current!!.copy(activeSegment = current!!.activeSegment.copy(id = "recovery-${++sequence}",
                type = SessionSegmentType.RECOVERY, startedAt = at)); return true
        }
        override suspend fun milestone(sessionId: String, at: Long, evidence: StableEvidence): Boolean {
            milestones += evidence.milestone
            current = if (evidence.milestone == EvidenceMilestone.STABLE) current!!.copy(stableStartedAt = at)
            else current!!.copy(activeSegment = current!!.activeSegment.copy(id = "focus-${++sequence}",
                type = if (evidence.milestone == EvidenceMilestone.DEEP) SessionSegmentType.DEEP_FOCUS else SessionSegmentType.FOCUS))
            return true
        }
        override suspend fun demoteDeep(sessionId: String, at: Long): Boolean {
            current = current!!.copy(activeSegment = current!!.activeSegment.copy(id = "demoted", type = SessionSegmentType.FOCUS))
            return true
        }
        override suspend fun behavior(command: BehaviorCommand): SessionSegmentEntity? {
            if (failSave) error("disk error")
            val facts = current ?: return null
            if (facts.activeSegment.id != command.expectedSegmentId) return null
            commands += command
            val next = when (command.action) {
                BehaviorAction.GRANT_ALLOWANCE -> SessionSegmentType.TEMPORARY_ALLOWANCE
                BehaviorAction.START_BREAK -> SessionSegmentType.BREAK
                else -> SessionSegmentType.RECOVERY
            }
            val opened = facts.activeSegment.copy(id = "next-${++sequence}", type = next, startedAt = command.at,
                plannedEndAt = command.durationMillis?.let { command.at + it }, packageName = command.packageName)
            current = facts.copy(activeSegment = opened, interventionEligible = false)
            return opened
        }
    }
}
