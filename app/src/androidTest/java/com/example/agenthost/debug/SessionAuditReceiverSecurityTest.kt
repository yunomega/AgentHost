package com.example.agenthost.debug

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agenthost.security.DebugEntryAccessPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionAuditReceiverSecurityTest {
    @Test
    fun deniedResultCarriesAccessMetadata() {
        val decision = DebugEntryAccessPolicy.Decision(
            allowed = false,
            code = DebugEntryAccessPolicy.CODE_PERMISSION_DENIED,
            reason = "caller_not_allowed",
            callerUid = 12345,
            callerPackage = "com.example.attacker",
            identitySource = "test"
        )

        val result = SessionAuditReceiver.emitAccessDeniedForTest(
            action = "com.example.agenthost.debug.DUMP_ACTIVE_SESSION",
            decision = decision
        )

        assertFalse(result.ok)
        assertEquals(DebugEntryAccessPolicy.CODE_PERMISSION_DENIED, result.code)
        assertEquals("caller_not_allowed", result.data["accessReason"])
        assertEquals(12345, result.data["callerUid"])
        assertEquals("com.example.attacker", result.data["callerPackage"])
        assertEquals("test", result.data["callerIdentitySource"])
    }

    @Test
    fun identityUnavailableResultUsesDedicatedCode() {
        val decision = DebugEntryAccessPolicy.Decision(
            allowed = false,
            code = DebugEntryAccessPolicy.CODE_CALLER_IDENTITY_UNAVAILABLE,
            reason = "caller_identity_capability_missing",
            callerUid = null,
            callerPackage = null,
            identitySource = "unavailable"
        )

        val result = SessionAuditReceiver.emitAccessDeniedForTest(
            action = "com.example.agenthost.debug.DUMP_ACTIVE_SESSION",
            decision = decision
        )

        assertFalse(result.ok)
        assertEquals(DebugEntryAccessPolicy.CODE_CALLER_IDENTITY_UNAVAILABLE, result.code)
        assertEquals("caller_identity_capability_missing", result.data["accessReason"])
        assertTrue(result.data.containsKey("callerUid"))
    }
}
