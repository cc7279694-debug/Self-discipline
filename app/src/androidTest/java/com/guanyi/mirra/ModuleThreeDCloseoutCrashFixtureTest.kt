package com.guanyi.mirra

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.guanyi.mirra.data.local.MIGRATION_1_2
import com.guanyi.mirra.data.local.MIGRATION_2_3
import com.guanyi.mirra.data.local.MIGRATION_3_4
import com.guanyi.mirra.data.local.MirraDatabase
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.SessionEndType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties

/**
 * No side effects in the ordinary suite. Only explicit, identified AVD fixtures may access mirra.db.
 * The driver must preserve a complete installed baseline and restore it after these fixtures.
 */
internal fun requireDedicatedThreeDScenario(expected: String) {
    val args = InstrumentationRegistry.getArguments()
    assumeTrue("Explicit $expected fixture required", args.getString("mirra3dScenario") == expected)
    val requestedAvd = args.getString("mirra3dAvd")
    assumeTrue("Explicit dedicated AVD name required", requestedAvd in setOf("Mirra_API_37", "Mirra_API_37_Phase4B_Final"))
    assumeTrue("Only API37 emulator fixtures are permitted", Build.VERSION.SDK_INT == 37 && Build.HARDWARE == "ranchu")
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val avdName = automation.executeShellCommand("getprop ro.boot.qemu.avd_name").let { fd ->
        ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() }
    }
    assumeTrue("Not the confirmed dedicated AVD", avdName == requestedAvd)
}

@RunWith(AndroidJUnit4::class)
class ModuleThreeDCloseoutCrashFixtureTest {
    @Test fun preparePendingFixture() = runBlocking {
        requireDedicatedThreeDScenario("pending_prepare")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as MirraApplication).container
        withTimeout(30_000) { container.startup.await() }
        val db = Room.databaseBuilder(context, MirraDatabase::class.java, "mirra.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        try {
            assertNull("Never disturb an existing learning Session", db.sessionDao().getActive())
            assertNull("Never replace an existing Intent", db.intentDao().getActive())
            val item = container.learningItemRepository.create(
                "3D4 Pending Recovery", 320, 40, "翻到第 40 页", setAsMainline = false,
            )
            val intent = container.studyWorkflowRepository.createIntent(item.id)
            container.studyWorkflowRepository.markTransitioned(intent.id)
            val session = container.studyWorkflowRepository.startSession(intent.id, 40)
            container.noteRepository.save(item.id, session.id, "3D4 controlled pending fixture", 35)
            container.studyWorkflowRepository.updateCurrentPage(session.id, 42)
            // Actual wall sample; no changed system clock and no invented Focus/monitoring evidence.
            val decision = container.studyWorkflowRepository.beginCloseout(session.id, 42, System.currentTimeMillis())
            assertEquals(FocusCloseoutState.PENDING, db.focusDao().getContext(session.id)?.closeoutState)
            assertEquals(MonitoringCoverage.NONE, db.focusDao().getContext(session.id)?.monitoringStatus)
            assertNull(db.focusDao().getActiveSegment(session.id))
            assertEquals(session.id, db.sessionDao().getActive()?.id)
            assertNull(db.sessionDao().get(session.id)?.endedAt)
            val marker = Properties().apply {
                setProperty("sessionId", session.id)
                setProperty("itemId", item.id)
                setProperty("frozenBoundary", decision.closeoutStartedAt.toString())
                setProperty("requestedPage", decision.requestedEndPage.toString())
            }
            markerFile().outputStream().use { marker.store(it, "Dedicated 3D4 test fixture only") }
            // Deliberately DO NOT complete B, remove this Session, or start an Activity here.
            println("3D4_PENDING_DURABLE boundary=${decision.closeoutStartedAt} page=${decision.requestedEndPage}")
        } finally {
            db.close() // Only the test connection; application database and fixture remain intact.
        }
    }

    @Test fun assertRecoveredFixture() = runBlocking {
        requireDedicatedThreeDScenario("pending_assert")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = Properties().apply { markerFile().inputStream().use { load(it) } }
        val sessionId = checkNotNull(marker.getProperty("sessionId"))
        val frozenBoundary = marker.getProperty("frozenBoundary").toLong()
        val requestedPage = marker.getProperty("requestedPage").toInt()
        val container = (context.applicationContext as MirraApplication).container
        withTimeout(30_000) { container.startup.await() }
        val db = Room.databaseBuilder(context, MirraDatabase::class.java, "mirra.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        try {
            val session = checkNotNull(db.sessionDao().get(sessionId))
            val focus = checkNotNull(db.focusDao().getContext(sessionId))
            assertEquals(SessionEndType.NORMAL, session.endType)
            assertEquals(FocusCloseoutState.COMPLETED, focus.closeoutState)
            assertEquals(frozenBoundary, session.endedAt)
            assertEquals(frozenBoundary, focus.closeoutStartedAt)
            assertEquals(requestedPage, session.endPage)
            assertEquals(requestedPage, focus.requestedEndPage)
            assertEquals(requestedPage, db.learningItemDao().get(marker.getProperty("itemId"))?.currentPage)
            assertEquals(MonitoringCoverage.NONE, focus.monitoringStatus)
            assertNull(db.sessionDao().getActive())
            assertNull(db.focusDao().getActiveSegment(sessionId))
            assertEquals(frozenBoundary, db.focusDao().listSegments(sessionId).last().endedAt)
            assertNotNull(session.generatedSummary)
            assertTrue(db.searchFtsDao().dump().any { it.entityType == "SESSION" && it.entityId == sessionId })
            println("3D4_PENDING_RECOVERED_NORMAL boundary=$frozenBoundary page=$requestedPage")
            // The driver must also capture the actual cold-start readback BEFORE this instrumentation.
        } finally {
            db.close()
        }
    }

    private fun markerFile(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "mirra-3d4-pending.properties",
    )
}
