package com.example.agenthost.automation

import com.example.agenthost.core.AgentHostLog
import com.example.agenthost.platform.PlatformAutomationGateway
import java.util.Base64

class PlatformObservationProvider(
    private val gateway: PlatformAutomationGateway
) : ObservationProvider {
    companion object {
        private const val TAG = "PlatformObservationProvider"
    }

    init {
        AgentHostLog.i(
            TAG,
            "event=observation_provider_selected sessionId=null vdDisplayId=-1 " +
                "sessionTaskId=-1 step=0 actionType=NONE code=PLATFORM_PROVIDER extras=service=agenthost_platform"
        )
    }

    override fun capture(request: ObservationRequest): ObservationResult {
        val response = gateway.captureDisplay(
            sessionId = request.sessionId,
            displayId = request.vdDisplayId
        )
        if (!response.ok) {
            return ObservationResult.Failed(
                code = response.code,
                message = response.details
            )
        }

        val pngBytes = response.pngBytes ?: return ObservationResult.Failed(
            code = "INTERNAL_ERROR",
            message = "PNG payload missing"
        )

        return ObservationResult.Captured(
            base64Png = Base64.getEncoder().encodeToString(pngBytes),
            width = response.width,
            height = response.height
        )
    }
}
