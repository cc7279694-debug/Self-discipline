package com.guanyi.mirra.platform.intervention

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.guanyi.mirra.MainActivity
import com.guanyi.mirra.domain.intervention.*
import org.junit.Assert.*
import org.junit.Test

class InterventionActivityIntentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val request = InterventionNavigationRequest("session", "token", InterventionNavigationAction.OPEN_ALLOWANCE)
    @Test fun explicitIntentRoundTripsAndCannotBecomeTrampoline() {
        val intent = InterventionActivityIntents.intent(context, request)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(request, InterventionActivityIntents.read(context, intent))
        val pending = InterventionActivityIntents.pendingIntent(context, request)
        assertTrue(pending.isActivity)
        assertTrue(pending.isImmutable)
        assertFalse(pending.isBroadcast)
        assertFalse(pending.isService)
    }
    @Test fun differentTokensAndActionsHaveDifferentPendingIntentIdentities() {
        val first = InterventionActivityIntents.pendingIntent(context, request)
        assertNotEquals(first, InterventionActivityIntents.pendingIntent(context, request.copy(promptToken = "next")))
        assertNotEquals(first, InterventionActivityIntents.pendingIntent(context, request.copy(action = InterventionNavigationAction.OPEN_FINISH)))
        assertEquals(first, InterventionActivityIntents.pendingIntent(context, request))
    }
    @Test fun mismatchingExtrasAndMalformedActionsAreRejected() {
        val original = InterventionActivityIntents.intent(context, request)
        assertNull(InterventionActivityIntents.read(context, original.putExtra("promptToken", "other")))
        assertNull(InterventionActivityIntents.read(context,
            InterventionActivityIntents.intent(context, request).putExtra("requestedAction", "GRANT_ALLOWANCE")))
        assertNull(InterventionActivityIntents.read(context, android.content.Intent()))
    }
    @Test fun durableRiskEventTokenWithLongPackageNameRoundTrips() {
        val riskToken = "risk:${java.util.UUID.randomUUID()}:${java.util.UUID.randomUUID()}:com.example.long.package." +
            "app".repeat(50) + ":1791086400000"
        val longRequest = request.copy(promptToken = riskToken)
        assertEquals(longRequest, InterventionActivityIntents.read(context,
            InterventionActivityIntents.intent(context, longRequest)))
    }
}
