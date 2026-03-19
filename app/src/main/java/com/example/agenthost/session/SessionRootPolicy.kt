package com.example.agenthost.session

import android.view.Display

internal object SessionRootPolicy {
    fun shouldUsePassivePrimaryShell(rootRole: String): Boolean {
        return rootRole == SessionRootActivity.ROOT_ROLE_PRIMARY
    }

    fun primaryInteractionMode(
        rootRole: String,
        displayMode: String,
        executionDisplayId: Int
    ): String {
        if (rootRole != SessionRootActivity.ROOT_ROLE_PRIMARY) {
            return "non_primary_root"
        }
        return if (
            displayMode == SessionDisplayMode.VIRTUAL_DISPLAY.name &&
            executionDisplayId != Display.DEFAULT_DISPLAY
        ) {
            "passive_virtual_display"
        } else {
            "passive_main_display"
        }
    }
}
