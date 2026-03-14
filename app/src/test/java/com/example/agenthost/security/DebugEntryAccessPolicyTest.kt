package com.example.agenthost.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugEntryAccessPolicyTest {
    private val baseEnvironment = DebugEntryAccessPolicy.Environment(
        appUid = 10123,
        appPackage = "com.example.agenthost",
        debugHooksEnabled = true,
        deviceBuildType = "userdebug"
    )

    @Test
    fun sameAppUidIsAllowed() {
        val decision = DebugEntryAccessPolicy.evaluate(
            callerIdentity = DebugEntryAccessPolicy.CallerIdentity(
                uid = 10123,
                packageName = "com.example.agenthost",
                identitySource = "test"
            ),
            environment = baseEnvironment
        )

        assertTrue(decision.allowed)
        assertEquals(DebugEntryAccessPolicy.CODE_ACCESS_OK, decision.code)
        assertEquals("same_app_uid", decision.reason)
    }

    @Test
    fun shellUidAllowedOnUserdebug() {
        val decision = DebugEntryAccessPolicy.evaluate(
            callerIdentity = DebugEntryAccessPolicy.CallerIdentity(
                uid = 2000,
                packageName = "com.android.shell",
                identitySource = "test"
            ),
            environment = baseEnvironment
        )

        assertTrue(decision.allowed)
        assertEquals(DebugEntryAccessPolicy.CODE_ACCESS_OK, decision.code)
        assertEquals("adb_shell_allowed_on_debuggable_device", decision.reason)
    }

    @Test
    fun shellUidDeniedOnUserBuild() {
        val decision = DebugEntryAccessPolicy.evaluate(
            callerIdentity = DebugEntryAccessPolicy.CallerIdentity(
                uid = 2000,
                packageName = "com.android.shell",
                identitySource = "test"
            ),
            environment = baseEnvironment.copy(deviceBuildType = "user")
        )

        assertFalse(decision.allowed)
        assertEquals(DebugEntryAccessPolicy.CODE_PERMISSION_DENIED, decision.code)
        assertEquals("adb_shell_disallowed_on_user_build", decision.reason)
    }

    @Test
    fun debugHooksDisabledDenied() {
        val decision = DebugEntryAccessPolicy.evaluate(
            callerIdentity = DebugEntryAccessPolicy.CallerIdentity(
                uid = 10123,
                packageName = "com.example.agenthost",
                identitySource = "test"
            ),
            environment = baseEnvironment.copy(debugHooksEnabled = false)
        )

        assertFalse(decision.allowed)
        assertEquals(DebugEntryAccessPolicy.CODE_DISABLED, decision.code)
        assertEquals("debug_hooks_disabled", decision.reason)
    }

    @Test
    fun identityCapabilityMissingUsesDedicatedCode() {
        val decision = DebugEntryAccessPolicy.evaluate(
            callerIdentity = DebugEntryAccessPolicy.CallerIdentity(
                uid = null,
                packageName = null,
                identitySource = "unavailable",
                capabilityMissing = true,
                failureReason = "caller_identity_capability_missing"
            ),
            environment = baseEnvironment
        )

        assertFalse(decision.allowed)
        assertEquals(DebugEntryAccessPolicy.CODE_CALLER_IDENTITY_UNAVAILABLE, decision.code)
        assertEquals("caller_identity_capability_missing", decision.reason)
    }

    @Test
    fun identityCapabilityMissingCanFallbackWhenManifestPermissionAlreadyGated() {
        val decision = DebugEntryAccessPolicy.evaluate(
            callerIdentity = DebugEntryAccessPolicy.CallerIdentity(
                uid = null,
                packageName = null,
                identitySource = "unavailable",
                capabilityMissing = true,
                failureReason = "getSentFromUid_missing"
            ),
            environment = baseEnvironment.copy(permissionGatedFallbackAllowed = true)
        )

        assertTrue(decision.allowed)
        assertEquals(DebugEntryAccessPolicy.CODE_ACCESS_OK, decision.code)
        assertEquals("permission_gated_identity_fallback", decision.reason)
    }

    @Test
    fun unknownUidDenied() {
        val decision = DebugEntryAccessPolicy.evaluate(
            callerIdentity = DebugEntryAccessPolicy.CallerIdentity(
                uid = 12345,
                packageName = "com.example.attacker",
                identitySource = "test"
            ),
            environment = baseEnvironment
        )

        assertFalse(decision.allowed)
        assertEquals(DebugEntryAccessPolicy.CODE_PERMISSION_DENIED, decision.code)
        assertEquals("caller_not_allowed", decision.reason)
    }
}
