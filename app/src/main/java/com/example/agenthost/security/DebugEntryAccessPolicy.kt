package com.example.agenthost.security

object DebugEntryAccessPolicy {
    const val CODE_ACCESS_OK = "DEBUG_ACCESS_OK"
    const val CODE_DISABLED = "DEBUG_ENTRY_DISABLED"
    const val CODE_PERMISSION_DENIED = "DEBUG_PERMISSION_DENIED"
    const val CODE_CALLER_IDENTITY_UNAVAILABLE = "DEBUG_CALLER_IDENTITY_UNAVAILABLE"

    private const val ROOT_UID = 0
    private const val SHELL_UID = 2000

    data class CallerIdentity(
        val uid: Int?,
        val packageName: String?,
        val identitySource: String,
        val capabilityMissing: Boolean = false,
        val failureReason: String? = null
    )

    data class Environment(
        val appUid: Int,
        val appPackage: String,
        val debugHooksEnabled: Boolean,
        val deviceBuildType: String,
        val permissionGatedFallbackAllowed: Boolean = false
    )

    data class Decision(
        val allowed: Boolean,
        val code: String,
        val reason: String,
        val callerUid: Int?,
        val callerPackage: String?,
        val identitySource: String
    )

    fun evaluate(callerIdentity: CallerIdentity, environment: Environment): Decision {
        if (!environment.debugHooksEnabled) {
            return Decision(
                allowed = false,
                code = CODE_DISABLED,
                reason = "debug_hooks_disabled",
                callerUid = callerIdentity.uid,
                callerPackage = callerIdentity.packageName,
                identitySource = callerIdentity.identitySource
            )
        }

        val callerUid = callerIdentity.uid
        if (callerUid == null || callerUid < 0) {
            val normalizedBuildType = environment.deviceBuildType.trim().lowercase()
            if (
                environment.permissionGatedFallbackAllowed &&
                (normalizedBuildType == "userdebug" || normalizedBuildType == "eng")
            ) {
                return Decision(
                    allowed = true,
                    code = CODE_ACCESS_OK,
                    reason = "permission_gated_identity_fallback",
                    callerUid = callerIdentity.uid,
                    callerPackage = callerIdentity.packageName,
                    identitySource = callerIdentity.identitySource
                )
            }
            return Decision(
                allowed = false,
                code = CODE_CALLER_IDENTITY_UNAVAILABLE,
                reason = if (callerIdentity.capabilityMissing) {
                    callerIdentity.failureReason ?: "caller_identity_capability_missing"
                } else {
                    callerIdentity.failureReason ?: "caller_identity_unavailable"
                },
                callerUid = callerIdentity.uid,
                callerPackage = callerIdentity.packageName,
                identitySource = callerIdentity.identitySource
            )
        }

        if (callerUid == environment.appUid) {
            return Decision(
                allowed = true,
                code = CODE_ACCESS_OK,
                reason = "same_app_uid",
                callerUid = callerUid,
                callerPackage = callerIdentity.packageName ?: environment.appPackage,
                identitySource = callerIdentity.identitySource
            )
        }

        if (callerUid == ROOT_UID || callerUid == SHELL_UID) {
            val normalizedBuildType = environment.deviceBuildType.trim().lowercase()
            return if (normalizedBuildType == "userdebug" || normalizedBuildType == "eng") {
                Decision(
                    allowed = true,
                    code = CODE_ACCESS_OK,
                    reason = "adb_shell_allowed_on_debuggable_device",
                    callerUid = callerUid,
                    callerPackage = callerIdentity.packageName,
                    identitySource = callerIdentity.identitySource
                )
            } else {
                Decision(
                    allowed = false,
                    code = CODE_PERMISSION_DENIED,
                    reason = "adb_shell_disallowed_on_user_build",
                    callerUid = callerUid,
                    callerPackage = callerIdentity.packageName,
                    identitySource = callerIdentity.identitySource
                )
            }
        }

        return Decision(
            allowed = false,
            code = CODE_PERMISSION_DENIED,
            reason = "caller_not_allowed",
            callerUid = callerUid,
            callerPackage = callerIdentity.packageName,
            identitySource = callerIdentity.identitySource
        )
    }
}
