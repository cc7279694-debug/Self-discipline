package com.guanyi.mirra

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.data.backup.BackupStorageOwner
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.preferences.MirraThemeId
import com.guanyi.mirra.di.AppContainer
import com.guanyi.mirra.di.DefaultAppContainer
import com.guanyi.mirra.domain.SessionFinishResult
import com.guanyi.mirra.domain.monitoring.ClockSample
import com.guanyi.mirra.feature.profile.LearningProtectionServiceStatus
import com.guanyi.mirra.feature.profile.projectLearningProtection
import com.guanyi.mirra.navigation.TopLevelDestination
import com.guanyi.mirra.platform.focus.AndroidUsageAccessGateway
import com.guanyi.mirra.platform.focus.CapabilityStatus
import com.guanyi.mirra.platform.focus.ReadyLeasePolicy
import com.guanyi.mirra.platform.focus.UsageAccessCapability
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed production journey, never a normal-suite fixture. Host owns permission changes,
 * screenrecord and the final InstalledBackupJourneyFixture.restore_baseline invocation.
 * No production clock, Session/Segment fact or capability is replaced by this test.
 */
@RunWith(AndroidJUnit4::class)
class V111InstalledReadingJourneyFixture {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: MirraApplication
    private lateinit var container: AppContainer
    private lateinit var owner: BackupStorageOwner
    private lateinit var markers: File
    private var scenario: ActivityScenario<MainActivity>? = null
    private var ownedBookId: String? = null
    private var ownedIntentId: String? = null
    private var ownedSessionId: String? = null

    @Test fun installedReadingJourney() {
        // All opt-in / device / baseline checks precede application access and business writes.
        val stage = guardedStage()
        app = instrumentation.targetContext.applicationContext as MirraApplication
        if (stage == "permissions") {
            assertNativePermissions()
            return
        }
        io {
            withTimeout(15_000) { app.storageState.first { it.phase in setOf(StoragePresentation.OPEN, StoragePresentation.BLOCKED) } }
            check(app.storageState.value.phase == StoragePresentation.OPEN) { "Installed storage blocked" }
            container = app.container
            check(container is DefaultAppContainer) { "Actual production AppContainer required" }
            owner = container as BackupStorageOwner
            container.startup.await()
            app.requireCurrent(owner)
            assertQuiescent()
        }
        markers = File(instrumentation.targetContext.cacheDir, "v111-reading-journey")
        check(markers.isDirectory || markers.mkdirs())
        check(markers.listFiles()?.none { it.name.startsWith("$stage.") } == true) {
            "Stage markers already exist; preserve evidence and use explicit host recovery"
        }
        try {
            io {
                // Portable settings only; the separately protected baseline restores presence too.
                container.appPreferencesRepository.apply {
                    setThemeId(MirraThemeId.BLUE)
                    setLastDestination(TopLevelDestination.Start)
                    setDndEnabled(false)
                    setCrossAppInterventionEnabled(false)
                }
                assertQuiescent()
                ownedBookId = container.learningItemRepository.create(
                    "安静阅读", 520, currentPage = 40,
                    firstAction = "翻到第 40 页，读这一页的第一段。", setAsMainline = true,
                ).id
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            prepareViaUi()
            if (stage == "recording") recording() else lifecycle()
        } catch (failure: Throwable) {
            // Close the real Activity first, allowing its official pending-edit flush to run.
            try { scenario?.close(); scenario = null }
            catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            try { cleanupOnlyOwnedWorkflow() }
            catch (cleanupFailure: Throwable) {
                progress("Owned workflow cleanup needs host baseline recovery")
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        } finally {
            scenario?.close()
            scenario = null
            // Never remove the backup fixture's cache, original baseline, synthetic rows or media.
        }
    }

    private fun guardedStage(): String {
        val args = InstrumentationRegistry.getArguments()
        val enabled = args.getString("mirraV111Journey")
        val stage = args.getString("mirraV111Stage")
        assumeTrue("Explicit installed V1.1.1 journey only", enabled == "true" || stage != null)
        require(enabled == "true") { "mirraV111Journey=true required" }
        require(stage in setOf("recording", "lifecycle", "permissions")) {
            "mirraV111Stage=recording|lifecycle|permissions required"
        }
        require(Build.VERSION.SDK_INT == 37 && Build.HARDWARE.lowercase() in setOf("ranchu", "goldfish", "qemu")) {
            "Dedicated API37 qemu hardware required"
        }
        val protectedRoot = File(instrumentation.targetContext.cacheDir,
            "installed-backup-journey/v111_20261030")
        val baseline = File(protectedRoot, "original-baseline.zip")
        val manifest = File(protectedRoot, "journey.properties")
        check(baseline.isFile && baseline.length() > 0 && manifest.isFile) { "Protected baseline missing" }
        val facts = Properties().apply { manifest.inputStream().use { load(it) } }
        check(facts.getProperty("run") == "v111_20261030" && facts.getProperty("stage") in
            setOf("baseline_saved", "prepared", "mutated", "asserted")) { "Baseline protection stage invalid" }
        val digest = MessageDigest.getInstance("SHA-256")
        baseline.inputStream().use { stream ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = stream.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        check(actualHash.equals(facts.getProperty("baseline.archiveSha256"), ignoreCase = true)) {
            "Protected baseline digest mismatch"
        }
        return checkNotNull(stage)
    }

    private fun assertNativePermissions() {
        val context = instrumentation.targetContext
        assertEquals("Effective Usage Access must be granted", CapabilityStatus.AVAILABLE,
            UsageAccessCapability(AndroidUsageAccessGateway(context)).checkLightweight())
        assertTrue("Effective DND policy access must be granted",
            context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted)
        assertTrue("Effective overlay access must be granted", Settings.canDrawOverlays(context))
        assertEquals("Runtime notification permission must be granted", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue("Effective app notifications must be enabled",
            NotificationManagerCompat.from(context).areNotificationsEnabled())
        progress("V111 effective native permission assertions completed")
    }

    private suspend fun assertQuiescent() {
        check(container.studyWorkflowRepository.observeActiveIntent().first() == null) { "Existing Active Intent" }
        check(container.studyWorkflowRepository.observeActiveSession().first() == null) { "Existing Active Session" }
        owner.backupDatabase.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM session_focus_contexts WHERE closeoutState='PENDING'",
        ).use { check(it.moveToFirst() && it.getLong(0) == 0L) { "Existing PENDING closeout" } }
        owner.assertRuntimeQuiescent()
    }

    private fun prepareViaUi() {
        awaitText("开始准备", enabled = true)
        rule.onNode(hasText("开始准备") and hasClickAction()).performScrollTo().performClick()
        awaitText("我已完成，开始阅读", enabled = true)
        val intent = io { checkNotNull(container.studyWorkflowRepository.observeActiveIntent().first()) }
        assertEquals(ownedBookId, intent.learningItemId)
        ownedIntentId = intent.id
        assertNull(io { container.studyWorkflowRepository.observeActiveSession().first() })
    }

    private fun startViaUi(): StudySessionEntity {
        rule.onNodeWithText("我已完成，开始阅读").performScrollTo().assertIsEnabled().performClick()
        awaitTag("session-reading-timer")
        val session = io { checkNotNull(container.studyWorkflowRepository.observeActiveSession().first()) }
        assertEquals(ownedBookId, session.learningItemId)
        assertEquals(ownedIntentId, session.intentId)
        ownedSessionId = session.id
        assertEquals(40, session.startPage)
        awaitHealthyFull(session.id)
        return session
    }

    private fun recording() {
        publish("recording.ready")
        awaitAck("recording.started")
        scene("v111-installed-preparation")
        val original = startViaUi()
        scene("v111-installed-normal-reading")
        rule.onNodeWithTag("session-focus-display-action").performScrollTo().performClick()
        awaitText("退出专注显示")
        scene("v111-installed-focus-display")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("session-note-action")
        assertSameSession(original)
        val note = writeNote(original.id, "阅读从眼前这一段开始。")
        scene("v111-installed-note-expanded")
        collapseNote(original.id, note)
        updatePage42(original.id)
        finishViaUi(original, note, MonitoringCoverage.FULL)
        scene("v111-installed-saved")
        publish("recording.done")
        // Keep the actual saved result visible until the host finalizes its own screenrecord.
        awaitAck("recording.stopped")
    }

    private fun lifecycle() {
        val original = startViaUi()
        val note = writeNote(original.id, "保持眼前这一页，回来继续阅读。")
        collapseNote(original.id, note)
        val oldView = composeView()
        val prior = onMain { oldView.keepScreenOn }
        rule.onNodeWithTag("session-focus-display-action").performScrollTo().performClick()
        awaitText("退出专注显示")
        await("Focus display must own actual Compose View flag") { onMain { oldView.keepScreenOn } }
        scenario!!.moveToState(Lifecycle.State.STARTED)
        await("STARTED releases flag") { onMain { oldView.keepScreenOn == prior } }
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        await("RESUMED reacquires flag") { onMain { oldView.keepScreenOn && oldView.hasWindowFocus() } }
        assertSameSession(original)

        scenario!!.recreate()
        awaitText("退出专注显示")
        val newView = composeView()
        assertNotSame(oldView, newView)
        await("Destroyed Compose View flag released") { onMain { oldView.keepScreenOn == prior } }
        await("Recreated focus display reacquires new View flag") { onMain { newView.keepScreenOn } }
        assertSameSession(original)
        assertNote(original.id, note)

        val power = app.getSystemService(PowerManager::class.java)
        shell("input keyevent 223") // Real emulator sleep; no synthetic ACTION_SCREEN_OFF broadcast.
        await("PowerManager must actually report non-interactive") { !power.isInteractive }
        await("Screen off releases Compose View flag") { onMain { newView.keepScreenOn == prior } }
        shell("input keyevent 224")
        shell("wm dismiss-keyguard") // Does not unlock or bypass a secure PIN/password.
        await("Wake must resume and focus actual Activity") {
            power.isInteractive && onMain { newView.hasWindowFocus() } &&
                scenario!!.state == Lifecycle.State.RESUMED
        }
        awaitText("退出专注显示")
        await("Wake reacquires Compose View flag") { onMain { newView.keepScreenOn } }
        assertSameSession(original)
        awaitHealthyFull(original.id)
        rule.onNodeWithText("退出专注显示").performScrollTo().performClick()
        val beforeExitSynchronization = onMain { newView.keepScreenOn }
        val beforeExitClock = rule.mainClock.currentTime
        // Unlike lifecycle callbacks, this release runs in a Compose SideEffect after rendering.
        rule.waitForIdle()
        rule.onNodeWithText("退出专注显示").assertDoesNotExist()
        rule.onNodeWithTag("session-focus-display-action").assertIsDisplayed()
        progress("Exit synchronization: before=$beforeExitSynchronization after=${onMain { newView.keepScreenOn }} prior=$prior clockBefore=$beforeExitClock clockAfter=${rule.mainClock.currentTime} autoAdvance=${rule.mainClock.autoAdvance}")
        await("Exit display restores original View flag") { onMain { newView.keepScreenOn == prior } }
        rule.onNodeWithTag("session-note-action").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).assertTextContains(note.content)
        collapseNote(original.id, note)

        publish("lifecycle.revoke-ready")
        awaitAck("lifecycle.revoked")
        // Frozen six-second deadline + one poll + one DB second; never inflate to hide a failure.
        io {
            withTimeout(8_000) {
                combine(container.focusRepository.observeContext(original.id),
                    container.focusRepository.observeActiveSegment(original.id)) { context, segment ->
                    context?.monitoringStatus == MonitoringCoverage.PARTIAL && segment?.type == SessionSegmentType.UNMONITORED
                }.first { it }
            }
        }
        awaitText("监测已中断")
        captureReadingRecordEvidence("v111-installed-monitoring-interrupted")
        assertSameSession(original)
        publish("lifecycle.regrant-ready")
        awaitAck("lifecycle.regranted")
        app.monitoringPlatform.recheckUsageWithoutQuery() // Existing lightweight check; never start service.
        assertEquals(CapabilityStatus.AVAILABLE, app.monitoringPlatform.capabilities.state.value.usage)
        assertEquals(MonitoringCoverage.PARTIAL, io { container.focusRepository.observeContext(original.id).first() }!!.monitoringStatus)
        assertEquals(SessionSegmentType.UNMONITORED, io { container.focusRepository.observeActiveSegment(original.id).first() }!!.type)
        awaitText("监测已中断")
        assertSameSession(original)
        assertNote(original.id, note)
        updatePage42(original.id)
        finishViaUi(original, note, MonitoringCoverage.PARTIAL)
        captureReadingRecordEvidence("v111-installed-lifecycle-saved-partial")
        publish("lifecycle.done")
    }

    private fun awaitHealthyFull(id: String) {
        await("Real current binding, fresh healthy generation and durable FULL", 10_000) {
            val runtime = app.monitoringPlatform
            val state = runtime.capabilities.state.value
            val binding = runtime.binding
            val lease = ReadyLeasePolicy().capture(state, SystemClock.elapsedRealtime())
            io {
                val session = container.studyWorkflowRepository.observeSession(id).first()
                val context = container.focusRepository.observeContext(id).first()
                val projection = projectLearningProtection(state, binding, session, context, SystemClock.elapsedRealtime())
                lease != null && binding != null && binding.sessionId == id && binding.generation == lease.generation &&
                    state.monitor.queryGeneration > 0 && state.monitor.lastPlatformError == null &&
                    projection.serviceStatus == LearningProtectionServiceStatus.READY &&
                    projection.coverage == MonitoringCoverage.FULL && context?.monitoringLostAt == null
            }
        }
        awaitText("监测正常")
    }

    private fun writeNote(id: String, text: String): NoteEntity {
        rule.onNodeWithTag("session-note-action").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).performTextInput(text)
        await("Exactly one real auto-saved Note", 5_000) {
            io { container.noteRepository.observeForSession(id).first().let { it.size == 1 && it.single().content == text } }
        }
        Espresso.closeSoftKeyboard()
        return io { container.noteRepository.observeForSession(id).first().single() }
    }

    private fun collapseNote(id: String, note: NoteEntity) {
        rule.onNodeWithText("收起笔记").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("写下摘录或想法")).assertDoesNotExist()
        assertNote(id, note)
    }

    private fun assertNote(id: String, note: NoteEntity) {
        val current = io { container.noteRepository.observeForSession(id).first().single() }
        assertEquals(note.id, current.id)
        assertEquals(note.content, current.content)
        assertEquals(ownedBookId, current.learningItemId)
        assertEquals(id, current.sessionId)
    }

    private fun updatePage42(id: String) {
        rule.onNodeWithTag("session-page-action").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("当前页码")).performTextReplacement("42")
        await("UI page edit must persist page 42", 5_000) {
            io { container.studyWorkflowRepository.observeSession(id).first()?.currentPage == 42 }
        }
        Espresso.closeSoftKeyboard()
        rule.onNodeWithText("完成").performClick()
    }

    private fun finishViaUi(original: StudySessionEntity, note: NoteEntity, coverage: MonitoringCoverage) {
        assertSameSession(original)
        rule.onNodeWithTag("session-end-action").performScrollTo().performClick()
        awaitTag("confirm-session-finish")
        rule.onNodeWithText("结束本次阅读？").assertExists()
        rule.onNode(hasSetTextAction() and hasText("结束页码")).assertTextContains("42")
        assertNull(io { container.studyWorkflowRepository.observeSession(original.id).first() }!!.endedAt)
        rule.onNodeWithTag("confirm-session-finish").performClick()
        rule.waitUntil(10_000) {
            // Poll observable failure copy as well as success; no invented no-flash guarantee.
            for (message in listOf("保存失败，请重试", "笔记保存失败，请重试", "保存被中断，请重试", "重试保存")) {
                assertTrue("Observed save failure UI: $message", rule.onAllNodesWithText(message, substring = true).fetchSemanticsNodes().isEmpty())
            }
            rule.onAllNodesWithText("本次阅读已保存").fetchSemanticsNodes().isNotEmpty()
        }
        io {
            val ended = checkNotNull(container.studyWorkflowRepository.observeSession(original.id).first())
            val context = checkNotNull(container.focusRepository.observeContext(original.id).first())
            val segments = container.focusRepository.observeSegments(original.id).first()
            assertEquals(original.id, ended.id)
            assertEquals(original.startedAt, ended.startedAt)
            assertEquals(SessionEndType.NORMAL, ended.endType)
            assertEquals(FocusCloseoutState.COMPLETED, context.closeoutState)
            assertEquals(coverage, context.monitoringStatus)
            assertNotNull(context.closeoutStartedAt)
            assertEquals(context.closeoutStartedAt, ended.endedAt)
            assertEquals(ended.endedAt, segments.last().endedAt)
            assertTrue(segments.all { it.activeSlot == null && it.endedAt != null })
            assertEquals(42, ended.endPage)
            assertEquals(42, container.learningItemRepository.get(checkNotNull(ownedBookId))!!.currentPage)
            assertNull(container.studyWorkflowRepository.observeActiveSession().first())
        }
        assertNote(original.id, note)
        assertOneOwnedSession(original)
    }

    private fun assertSameSession(original: StudySessionEntity) {
        val active = io { checkNotNull(container.studyWorkflowRepository.observeActiveSession().first()) }
        assertEquals(original.id, active.id)
        assertEquals(original.startedAt, active.startedAt)
        assertEquals(original.intentId, active.intentId)
        assertNull(active.endedAt)
        assertOneOwnedSession(original)
    }

    private fun assertOneOwnedSession(original: StudySessionEntity) = io {
        owner.backupDatabase.openHelper.readableDatabase.query(
            "SELECT COUNT(*), MIN(id) FROM study_sessions WHERE learningItemId=?", arrayOf(checkNotNull(ownedBookId)),
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(1L, it.getLong(0))
            assertEquals(original.id, it.getString(1))
        }
    }

    private fun cleanupOnlyOwnedWorkflow() = io {
        val book = ownedBookId ?: return@io
        app.requireCurrent(owner)
        val active = container.studyWorkflowRepository.observeActiveSession().first()
        // UUID returned by production create + exact book/Intent binding proves ownership.
        val owned = ownedSessionId?.let { container.studyWorkflowRepository.observeSession(it).first() }
            ?: active?.takeIf { it.learningItemId == book }
        if (active != null && (active.learningItemId != book || active.intentId != ownedIntentId)) {
            error("Unrelated active Session left untouched; host review required")
        }
        if (owned != null && owned.learningItemId == book && owned.intentId == ownedIntentId &&
            (ownedSessionId == null || owned.id == ownedSessionId)) {
            when (container.studyWorkflowRepository.getCloseoutState(owned.id)) {
                FocusCloseoutState.ACTIVE -> {
                    check(active?.id == owned.id) { "Owned ACTIVE Session no longer current" }
                    val result = container.sessionManager.finish(owned.id, owned.currentPage,
                        ClockSample(System.currentTimeMillis(), SystemClock.elapsedRealtime()))
                    val completed = result is SessionFinishResult.Completed ||
                        (result is SessionFinishResult.PendingRetry &&
                            container.sessionManager.retryPendingFinish(owned.id) is SessionFinishResult.Completed)
                    check(completed) {
                        "Owned workflow cleanup unconfirmed; leave facts untouched for host baseline recovery"
                    }
                }
                FocusCloseoutState.PENDING -> check(container.sessionManager.retryPendingFinish(owned.id) is SessionFinishResult.Completed)
                FocusCloseoutState.COMPLETED -> Unit
                else -> error("Owned active Session has unknown cleanup state; host recovery required")
            }
        }
        container.studyWorkflowRepository.observeActiveIntent().first()?.let { intent ->
            if (intent.learningItemId == book && (ownedIntentId == null || intent.id == ownedIntentId)) {
                container.studyWorkflowRepository.abandonIntent(intent.id)
            } else error("Unrelated Active Intent left untouched")
        }
    }

    private fun composeView(): View {
        var selected: View? = null
        scenario!!.onActivity { activity ->
            fun find(view: View): View? {
                if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            selected = find(activity.window.decorView)
        }
        return checkNotNull(selected) { "Actual Activity AndroidComposeView missing" }
    }

    private fun scene(name: String) {
        rule.waitForIdle()
        captureReadingRecordEvidence(name)
        // Video pacing only, never a proof of timing correctness or monitoring continuity.
        SystemClock.sleep(2_000)
    }

    private fun publish(name: String) {
        val file = File(markers, name)
        check(file.createNewFile()) { "Refusing stale stage marker" }
        progress("V111 $name")
    }

    private fun awaitAck(name: String) {
        progress("Waiting for host $name")
        await("Host acknowledgement $name", 45_000) { File(markers, name).isFile }
    }

    private fun progress(message: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$message\n") })
    }

    private fun awaitText(text: String, enabled: Boolean = false) {
        rule.waitUntil(10_000) {
            rule.onAllNodes(if (enabled) hasText(text) and isEnabled() else hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitTag(tag: String) = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun await(reason: String, timeoutMillis: Long = 5_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var nextProgress = SystemClock.elapsedRealtime() + 5_000
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { reason }
            if (SystemClock.elapsedRealtime() >= nextProgress) {
                progress("Still waiting: $reason")
                nextProgress += 5_000
            }
            SystemClock.sleep(100)
        }
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).use { it.readBytes() }
    }

    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { withTimeout(20_000) { block() } }
    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        return checkNotNull(value)
    }
}
