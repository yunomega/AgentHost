package com.example.agenthost.core

import android.content.Context
import com.example.agenthost.migration.FrameworkTaskMigrationController
import com.example.agenthost.runtime.VirtualDisplayRuntime
import com.example.agenthost.session.SessionCoordinator

object AgentHostRuntime {
    @Volatile
    private var coordinator: SessionCoordinator? = null

    fun sessionCoordinator(context: Context): SessionCoordinator {
        return coordinator ?: synchronized(this) {
            coordinator ?: SessionCoordinator(
                appContext = context.applicationContext,
                virtualDisplayRuntime = VirtualDisplayRuntime(context.applicationContext),
                taskMigrationController = FrameworkTaskMigrationController()
            ).also { coordinator = it }
        }
    }
}
