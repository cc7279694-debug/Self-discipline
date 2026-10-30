package com.guanyi.mirra.feature.session

import org.junit.Assert.*
import org.junit.Test

class ReadingScreenOnLeaseTest {
    private var screenOn = false
    private val writes = mutableListOf<Boolean>()
    private val lease = ReadingScreenOnLease({ screenOn }, { screenOn = it; writes += it })

    @Test fun inactiveDoesNotTouchWindow() { lease.update(false); assertTrue(writes.isEmpty()) }
    @Test fun visibleEligibleModeOwnsThenRestoresFalse() {
        lease.update(true); assertTrue(screenOn)
        lease.update(false); assertFalse(screenOn)
        assertEquals(listOf(true, false), writes)
    }
    @Test fun preservesAnExistingOwnersTrueSetting() {
        screenOn = true; lease.update(true); lease.close(); assertTrue(screenOn)
    }
    @Test fun repeatedTicksDoNotReacquireOrLoseOriginalSetting() {
        repeat(10) { lease.update(true) }; lease.close()
        assertFalse(screenOn); assertEquals(listOf(true, false), writes)
    }
    @Test fun repeatedReleaseIsSafe() {
        lease.update(true); repeat(10) { lease.close() }
        assertEquals(listOf(true, false), writes)
    }
    @Test fun backgroundReleaseCanReacquireOnlyAfterEligibilityReturns() {
        lease.update(true); lease.update(false); assertFalse(screenOn)
        lease.update(true); assertTrue(screenOn); lease.close(); assertFalse(screenOn)
    }
    @Test fun laterExternalChangeIsNotOverwrittenOnRelease() {
        screenOn = true; lease.update(true); screenOn = false; lease.close(); assertFalse(screenOn)
        assertEquals(listOf(true), writes)
    }
    @Test fun temporaryDarkSystemBarAppearanceRestoresTheOriginalLightFlag() {
        var lightAppearance = true
        val appearance = ReadingScreenOnLease({ lightAppearance }, { lightAppearance = it }, activeValue = false)
        appearance.update(true); assertFalse(lightAppearance)
        appearance.update(false); assertTrue(lightAppearance)
    }
}
