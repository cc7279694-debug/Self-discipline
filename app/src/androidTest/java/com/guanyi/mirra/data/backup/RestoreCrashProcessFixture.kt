package com.guanyi.mirra.data.backup

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in two-stage fixture; the ordinary suite performs no file operations.
 *
 * Driver sequence, using the same unique Run and Boundary arguments in both stages:
 * 1. instrument prepareDurableDecisionForExternalProcessStop with mirra4cRestoreStage=prepare.
 * 2. wait for instrumentation success, then `am force-stop com.guanyi.mirra` and cold-start it.
 * 3. instrument recoverBeforeOpeningSandboxStoresInNewProcess with mirra4cRestoreStage=assert.
 *
 * Required args: mirra4cRestoreRun=[A-Za-z0-9_-]{1,64}; mirra4cRestoreBoundary=<exact effect>.
 * At least images:published (precommit) and COMMITTED (postcommit) must be separate runs.
 * This proves external process-cycle recovery only when the driver's stop/start evidence is retained.
 * The sandbox journal is deliberately recovered here, not by production application bootstrap.
 * All writable resources are synthetic cache children; installed mirra.db/DataStore/images are untouched.
 */
@RunWith(AndroidJUnit4::class)
class RestoreCrashProcessFixture {
    @Test fun prepareDurableDecisionForExternalProcessStop() = runBlocking {
        val request = request("prepare")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = RestoreAndroidFixture(context, restoreAndroidTestRoot(context, "restore-process-fixture", request.run))
        fixture.seed()
        val oldFrame = restoreAndroidResourceFrame(fixture.live)
        val newFrame = restoreAndroidResourceFrame(fixture.candidate)
        val marker = Properties().apply {
            setProperty("version", "1")
            setProperty("run", request.run)
            setProperty("boundary", request.boundary)
            setProperty("preparedPid", Process.myPid().toString())
            setProperty("prepared", "false")
            oldFrame.forEach { (key, value) -> setProperty("old.$key", value) }
            newFrame.forEach { (key, value) -> setProperty("new.$key", value) }
        }
        writeMarker(fixture.root, marker)
        val interrupted = fixture.journal { if (it == request.boundary) throw RestoreInjectedDeath() }
        try {
            interrupted.apply(interrupted.prepare(fixture.candidate))
            fail("The requested process-stop fixture boundary was not reached")
        } catch (_: RestoreInjectedDeath) {
            // Keep the genuine durable journal plus both snapshots for the subsequent external stop.
        }
        assertTrue(interrupted.hasPendingRecovery())
        marker.setProperty("prepared", "true")
        writeMarker(fixture.root, marker)
        println("MIRRA_4C_PROCESS_PREPARED run=${request.run} boundary=${request.boundary} pid=${Process.myPid()} pending=true resources=3 root=${fixture.root.path}")
        // Do not recover or remove the root. Instrumentation ends safely; the host performs force-stop.
    }

    @Test fun recoverBeforeOpeningSandboxStoresInNewProcess() = runBlocking {
        val request = request("assert")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = restoreAndroidTestRoot(context, "restore-process-fixture", request.run)
        val markerFile = File(root, "process-fixture.properties")
        assertTrue("Missing persisted prepare evidence", markerFile.isFile && markerFile.length() in 1..16_384 && markerFile.canonicalFile == markerFile.absoluteFile)
        val marker = Properties().apply { markerFile.inputStream().use { load(it) } }
        assertEquals("1", marker.getProperty("version"))
        assertEquals(request.run, marker.getProperty("run"))
        assertEquals(request.boundary, marker.getProperty("boundary"))
        assertEquals("true", marker.getProperty("prepared"))
        val preparedPid = checkNotNull(marker.getProperty("preparedPid")).toInt()
        assertNotEquals("Two fixture stages must run in different app processes", preparedPid, Process.myPid())
        val selected = if (request.boundary in RESTORE_ANDROID_COMMITTED_BOUNDARIES) RestoreTestGeneration.NEW else RestoreTestGeneration.OLD
        val expected = listOf("database", "preferences", "images/$RESTORE_TEST_IMAGE").associateWith { key ->
            val hash = checkNotNull(marker.getProperty("${selected.token}.$key"))
            require(hash.matches(Regex("[0-9a-f]{64}")))
            hash
        }
        val fixture = RestoreAndroidFixture(context, root)
        val journal = fixture.journal()
        assertTrue("The pending decision must survive the external process cycle", journal.hasPendingRecovery())
        // This is the first sandbox storage operation: no Room or ManagedPreferences exists yet.
        journal.recoverBeforeOpeningStores()
        assertFalse(journal.hasPendingRecovery())
        assertEquals("Never expose mixed database/preferences/images", expected, restoreAndroidResourceFrame(fixture.live))
        fixture.assertGeneration(selected)
        println("MIRRA_4C_PROCESS_RECOVERED run=${request.run} boundary=${request.boundary} preparedPid=$preparedPid assertPid=${Process.myPid()} selected=${selected.token} resources=3 tables=12 recoveryBeforeSandboxStores=true productionBootstrap=false physicalPowerCut=false")
        fixture.removeSyntheticRoot()
    }

    private data class Request(val run: String, val boundary: String)
    private fun request(expectedStage: String): Request {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit restore process fixture stage required", arguments.getString("mirra4cRestoreStage") == expectedStage)
        val run = checkNotNull(arguments.getString("mirra4cRestoreRun")) { "Unique restore fixture run is required" }
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val boundary = checkNotNull(arguments.getString("mirra4cRestoreBoundary")) { "Exact restore effect boundary is required" }
        require(boundary in RESTORE_ANDROID_BOUNDARIES)
        return Request(run, boundary)
    }

    private fun writeMarker(root: File, marker: Properties) {
        val bytes = ByteArrayOutputStream().also { marker.store(it, "Synthetic Mirra Phase4C process-cycle fixture only") }.toByteArray()
        check(bytes.size <= 16_384)
        androidDurableFiles().write(File(root, "process-fixture.properties"), bytes)
    }
}
