package com.example.agenthost.platform;

import android.os.Bundle;

interface IAgentHostPlatformService {
    Bundle captureDisplay(String sessionId, int displayId);

    Bundle injectTap(String sessionId, int displayId, int x, int y);

    Bundle injectSwipe(
        String sessionId,
        int displayId,
        int x1,
        int y1,
        int x2,
        int y2,
        long durationMs
    );

    Bundle injectKey(String sessionId, int displayId, int keyCode);

    Bundle injectText(String sessionId, int displayId, String text);

    Bundle injectLongPress(String sessionId, int displayId, int x, int y, long durationMs);
}
