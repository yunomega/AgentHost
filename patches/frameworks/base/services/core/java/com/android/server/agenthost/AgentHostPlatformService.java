package com.android.server.agenthost;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.ViewConfiguration;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;
import com.example.agenthost.platform.IAgentHostPlatformService;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class AgentHostPlatformService extends SystemService {
    public static final String SERVICE_NAME = "agenthost_platform";

    private static final String TAG = "AgentHostPlatformService";
    private static final String TARGET_PACKAGE = "com.example.agenthost";
    private static final String PERMISSION =
            "com.example.agenthost.permission.ACCESS_PLATFORM_AUTOMATION";

    private static final String KEY_CODE = "code";
    private static final String KEY_DETAILS = "details";
    private static final String KEY_WIDTH = "width";
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_PNG_FD = "png_fd";
    private static final String KEY_TASK_ID = "taskId";
    private static final String KEY_TOP_ACTIVITY = "topActivity";
    private static final String KEY_TOP_PACKAGE = "topPackage";
    private static final String KEY_DISPLAY_ID = "displayId";
    private static final String KEY_RESOLVED_COMPONENT = "resolvedComponent";

    private static final String CODE_OK = "OK";
    private static final String CODE_DISPLAY_NOT_FOUND = "DISPLAY_NOT_FOUND";
    private static final String CODE_CAPTURE_FAILED = "CAPTURE_FAILED";
    private static final String CODE_PERMISSION_DENIED = "PERMISSION_DENIED";
    private static final String CODE_UNSUPPORTED = "UNSUPPORTED";
    private static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String CODE_INJECTION_REJECTED = "INJECTION_REJECTED";

    private static final int MAX_HISTORY = 100;
    private static final int DEFAULT_DEVICE_ID = 0;
    private static final float DEFAULT_PRESSURE = 1.0f;
    private static final float NO_PRESSURE = 0.0f;
    private static final float DEFAULT_SIZE = 1.0f;
    private static final int DEFAULT_META_STATE = 0;
    private static final float DEFAULT_PRECISION_X = 1.0f;
    private static final float DEFAULT_PRECISION_Y = 1.0f;
    private static final int DEFAULT_EDGE_FLAGS = 0;
    private static final int DEFAULT_BUTTON_STATE = 0;
    private static final int DEFAULT_FLAGS = 0;
    private static final long DEFAULT_SWIPE_DURATION_MS = 300L;

    private final Object mLock = new Object();
    private final ArrayDeque<String> mHistory = new ArrayDeque<>();
    private final BinderService mBinderService = new BinderService();

    private DisplayManagerInternal mDisplayManagerInternal;
    private WindowManagerInternal mWindowManagerInternal;
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;

    public AgentHostPlatformService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        publishBinderService(SERVICE_NAME, mBinderService);
    }

    private final class BinderService extends IAgentHostPlatformService.Stub {
        @Override
        public Bundle captureDisplay(String sessionId, int displayId) {
            return captureDisplayInternal(sessionId, displayId);
        }

        @Override
        public Bundle registerExecutionDisplaySession(String sessionId, int executionDisplayId) {
            return registerExecutionDisplaySessionInternal(sessionId, executionDisplayId);
        }

        @Override
        public Bundle updateExecutionDisplaySessionState(String sessionId, int executionDisplayId,
                String state, String reason) {
            return updateExecutionDisplaySessionStateInternal(sessionId, executionDisplayId,
                    state, reason);
        }

        @Override
        public Bundle clearExecutionDisplaySession(String sessionId, String reason) {
            return clearExecutionDisplaySessionInternal(sessionId, reason);
        }

        @Override
        public Bundle startExecutionDisplayStartupTarget(String sessionId, int executionDisplayId,
                String startupMode, String componentName, String reason) {
            return startExecutionDisplayStartupTargetInternal(sessionId, executionDisplayId,
                    startupMode, componentName, reason);
        }

        @Override
        public Bundle injectTap(String sessionId, int displayId, int x, int y) {
            return injectTapInternal(sessionId, displayId, x, y);
        }

        @Override
        public Bundle injectSwipe(String sessionId, int displayId, int x1, int y1, int x2,
                int y2, long durationMs) {
            return injectSwipeInternal(sessionId, displayId, x1, y1, x2, y2, durationMs);
        }

        @Override
        public Bundle injectKey(String sessionId, int displayId, int keyCode) {
            return injectKeyInternal(sessionId, displayId, keyCode);
        }

        @Override
        public Bundle injectText(String sessionId, int displayId, String text) {
            return injectTextInternal(sessionId, displayId, text);
        }

        @Override
        public Bundle injectLongPress(String sessionId, int displayId, int x, int y,
                long durationMs) {
            return injectLongPressInternal(sessionId, displayId, x, y, durationMs);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }
            dumpInternal(pw);
        }
    }

    private Bundle captureDisplayInternal(String sessionId, int displayId) {
        final CallerIdentity caller = getCallerIdentity();
        final DisplayInfo displayInfo = getDisplayInfo(displayId);
        final int width = displayInfo != null ? displayInfo.logicalWidth : -1;
        final int height = displayInfo != null ? displayInfo.logicalHeight : -1;
        final long captureStartedAt = SystemClock.elapsedRealtime();
        logCapture("platform_capture_begin", sessionId, caller, displayId, width, height,
                "BEGIN", "begin");

        final AuthorizationResult authorization = authorizeCaller(caller);
        if (!authorization.authorized) {
            return captureFailure(sessionId, caller, displayId, width, height,
                    CODE_PERMISSION_DENIED, authorization.details);
        }
        if (mDisplayManagerInternal == null) {
            return captureFailure(sessionId, caller, displayId, width, height,
                    CODE_UNSUPPORTED, "DisplayManagerInternal unavailable");
        }
        if (displayInfo == null) {
            return captureFailure(sessionId, caller, displayId, -1, -1,
                    CODE_DISPLAY_NOT_FOUND, "displayId=" + displayId);
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
                    mDisplayManagerInternal.userScreenshot(displayId);
            final long screenshotReadyAt = SystemClock.elapsedRealtime();
            if (screenshotBuffer == null) {
                return captureFailure(sessionId, caller, displayId, width, height,
                        CODE_CAPTURE_FAILED, "userScreenshot returned null");
            }

            final HardwareBuffer hardwareBuffer = screenshotBuffer.getHardwareBuffer();
            final Bitmap hardwareBitmap = screenshotBuffer.asBitmap();
            if (hardwareBuffer == null || hardwareBitmap == null) {
                if (hardwareBuffer != null) {
                    hardwareBuffer.close();
                }
                return captureFailure(sessionId, caller, displayId, width, height,
                        CODE_CAPTURE_FAILED, "Screenshot buffer unavailable");
            }

            Bitmap softwareBitmap = null;
            try {
                softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                final long bitmapCopiedAt = SystemClock.elapsedRealtime();
                if (softwareBitmap == null) {
                    return captureFailure(sessionId, caller, displayId,
                            hardwareBitmap.getWidth(), hardwareBitmap.getHeight(),
                            CODE_CAPTURE_FAILED, "Failed to copy screenshot bitmap");
                }

                final byte[] pngBytes;
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (!softwareBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        return captureFailure(sessionId, caller, displayId,
                                softwareBitmap.getWidth(), softwareBitmap.getHeight(),
                                CODE_CAPTURE_FAILED, "PNG compression failed");
                    }
                    pngBytes = output.toByteArray();
                }
                final long pngEncodedAt = SystemClock.elapsedRealtime();

                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                try {
                    writePngToPipeAsync(sessionId, caller, displayId,
                            softwareBitmap.getWidth(), softwareBitmap.getHeight(),
                            pngBytes, pipe[1]);
                } catch (RuntimeException runtimeException) {
                    closeQuietly(pipe[0]);
                    return captureFailure(sessionId, caller, displayId,
                            softwareBitmap.getWidth(), softwareBitmap.getHeight(),
                            CODE_INTERNAL_ERROR,
                            runtimeException.getMessage() != null
                                    ? runtimeException.getMessage()
                                    : runtimeException.getClass().getSimpleName());
                }

                final String successDetails = "capture ok bytes=" + pngBytes.length
                        + " totalMs=" + (pngEncodedAt - captureStartedAt)
                        + " screenshotMs=" + (screenshotReadyAt - captureStartedAt)
                        + " copyMs=" + (bitmapCopiedAt - screenshotReadyAt)
                        + " pngMs=" + (pngEncodedAt - bitmapCopiedAt)
                        + " transport=pipe_async";
                final Bundle success = buildResult(CODE_OK, successDetails);
                success.putInt(KEY_WIDTH, softwareBitmap.getWidth());
                success.putInt(KEY_HEIGHT, softwareBitmap.getHeight());
                success.putParcelable(KEY_PNG_FD, pipe[0]);
                logCapture("platform_capture_success", sessionId, caller, displayId,
                        softwareBitmap.getWidth(), softwareBitmap.getHeight(),
                        CODE_OK, successDetails);
                return success;
            } finally {
                hardwareBuffer.close();
                hardwareBitmap.recycle();
                if (softwareBitmap != null) {
                    softwareBitmap.recycle();
                }
            }
        } catch (Throwable t) {
            return captureFailure(sessionId, caller, displayId, width, height,
                    CODE_INTERNAL_ERROR,
                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void writePngToPipeAsync(String sessionId, CallerIdentity caller, int displayId,
            int width, int height, byte[] pngBytes, ParcelFileDescriptor writeSide) {
        BackgroundThread.getExecutor().execute(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {
                output.write(pngBytes);
                output.flush();
            } catch (IOException ioException) {
                logCapture("platform_capture_pipe_write_failed", sessionId, caller, displayId,
                        width, height, CODE_INTERNAL_ERROR,
                        ioException.getMessage() != null
                                ? ioException.getMessage()
                                : ioException.getClass().getSimpleName());
            }
        });
    }

    private Bundle registerExecutionDisplaySessionInternal(String sessionId,
            int executionDisplayId) {
        final int callerUid = Binder.getCallingUid();
        return performExecutionDisplayPolicyCall("REGISTER_EXECUTION_DISPLAY_SESSION", sessionId,
                executionDisplayId, "state=EXECUTION", () ->
                        mActivityTaskManagerInternal.registerExecutionDisplaySession(
                                sessionId, executionDisplayId, callerUid));
    }

    private Bundle updateExecutionDisplaySessionStateInternal(String sessionId,
            int executionDisplayId, String state, String reason) {
        final String safeState = state == null ? "" : state;
        final String safeReason = reason == null ? "" : reason;
        return performExecutionDisplayPolicyCall("UPDATE_EXECUTION_DISPLAY_SESSION", sessionId,
                executionDisplayId, "state=" + safeState + " reason=" + safeReason, () ->
                        mActivityTaskManagerInternal.updateExecutionDisplaySessionState(sessionId,
                                executionDisplayId, safeState, safeReason));
    }

    private Bundle clearExecutionDisplaySessionInternal(String sessionId, String reason) {
        final String safeReason = reason == null ? "" : reason;
        return performExecutionDisplayPolicyCall("CLEAR_EXECUTION_DISPLAY_SESSION", sessionId,
                -1 /* displayId */, "reason=" + safeReason,
                () -> mActivityTaskManagerInternal.clearExecutionDisplaySession(sessionId,
                        safeReason));
    }

    private Bundle startExecutionDisplayStartupTargetInternal(String sessionId,
            int executionDisplayId, String startupMode, String componentName, String reason) {
        final String safeMode = startupMode == null ? "" : startupMode;
        final String safeComponent = componentName == null ? "" : componentName;
        final String safeReason = reason == null ? "" : reason;
        return performExecutionDisplayStartupCall("START_EXECUTION_DISPLAY_STARTUP_TARGET",
                sessionId, executionDisplayId,
                "mode=" + safeMode + " component=" + safeComponent + " reason=" + safeReason,
                () -> mActivityTaskManagerInternal.startExecutionDisplayStartupTarget(sessionId,
                        executionDisplayId, safeMode, safeComponent, safeReason));
    }

    private Bundle injectTapInternal(String sessionId, int displayId, int x, int y) {
        return performInjection(sessionId, displayId, "TAP", () -> sendTap(x, y, displayId));
    }

    private Bundle injectSwipeInternal(String sessionId, int displayId, int x1, int y1, int x2,
            int y2, long durationMs) {
        return performInjection(sessionId, displayId, "SWIPE",
                () -> sendSwipe(x1, y1, x2, y2, durationMs, displayId));
    }

    private Bundle injectKeyInternal(String sessionId, int displayId, int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            final CallerIdentity caller = getCallerIdentity();
            final DisplayInfo displayInfo = getDisplayInfo(displayId);
            final int width = displayInfo != null ? displayInfo.logicalWidth : -1;
            final int height = displayInfo != null ? displayInfo.logicalHeight : -1;
            logInject("platform_inject_begin", sessionId, caller, displayId, width, height,
                    "KEY", "BEGIN", "keyCode=" + keyCode);
            return injectFailure(sessionId, caller, displayId, width, height, "KEY",
                    CODE_UNSUPPORTED,
                    "HOME is treated as global navigation and is not guaranteed display-scoped");
        }
        final CallerIdentity caller = getCallerIdentity();
        final DisplayInfo displayInfo = getDisplayInfo(displayId);
        final int width = displayInfo != null ? displayInfo.logicalWidth : -1;
        final int height = displayInfo != null ? displayInfo.logicalHeight : -1;
        return performInjection(sessionId, displayId, "KEY",
                () -> sendKey(sessionId, caller, displayId, width, height, keyCode));
    }

    private Bundle injectTextInternal(String sessionId, int displayId, String text) {
        final String safeText = text == null ? "" : text;
        return performInjection(sessionId, displayId, "TEXT",
                () -> sendText(safeText, displayId));
    }

    private Bundle injectLongPressInternal(String sessionId, int displayId, int x, int y,
            long durationMs) {
        return performInjection(sessionId, displayId, "LONG_PRESS",
                () -> sendLongPress(x, y, durationMs, displayId));
    }

    private Bundle performInjection(String sessionId, int displayId, String actionType,
            InjectionOperation operation) {
        final CallerIdentity caller = getCallerIdentity();
        final DisplayInfo displayInfo = getDisplayInfo(displayId);
        final int width = displayInfo != null ? displayInfo.logicalWidth : -1;
        final int height = displayInfo != null ? displayInfo.logicalHeight : -1;
        logInject("platform_inject_begin", sessionId, caller, displayId, width, height,
                actionType, "BEGIN", "begin");

        final AuthorizationResult authorization = authorizeCaller(caller);
        if (!authorization.authorized) {
            return injectFailure(sessionId, caller, displayId, width, height, actionType,
                    CODE_PERMISSION_DENIED, authorization.details);
        }
        if (displayInfo == null) {
            return injectFailure(sessionId, caller, displayId, -1, -1, actionType,
                    CODE_DISPLAY_NOT_FOUND, "displayId=" + displayId);
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final InjectionResult result = operation.run();
            if (!result.ok) {
                return injectFailure(sessionId, caller, displayId, width, height, actionType,
                        result.code, result.details);
            }
            final Bundle success = buildResult(CODE_OK, result.details);
            logInject("platform_inject_success", sessionId, caller, displayId, width, height,
                    actionType, CODE_OK, result.details);
            return success;
        } catch (Throwable t) {
            return injectFailure(sessionId, caller, displayId, width, height, actionType,
                    CODE_INTERNAL_ERROR,
                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Bundle performExecutionDisplayPolicyCall(String actionType, String sessionId,
            int displayId, String details, PolicyOperation operation) {
        final CallerIdentity caller = getCallerIdentity();
        logExecutionDisplayPolicy("platform_execution_display_policy_begin", sessionId, caller,
                displayId, actionType, "BEGIN", details);

        final AuthorizationResult authorization = authorizeCaller(caller);
        if (!authorization.authorized) {
            return executionDisplayPolicyFailure(sessionId, caller, displayId, actionType,
                    CODE_PERMISSION_DENIED, authorization.details);
        }
        if (mActivityTaskManagerInternal == null) {
            return executionDisplayPolicyFailure(sessionId, caller, displayId, actionType,
                    CODE_UNSUPPORTED, "ActivityTaskManagerInternal unavailable");
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final Bundle result = operation.run();
            if (result == null) {
                return executionDisplayPolicyFailure(sessionId, caller, displayId, actionType,
                        CODE_INTERNAL_ERROR, "null bundle");
            }
            final String code = result.getString(KEY_CODE) != null
                    ? result.getString(KEY_CODE) : CODE_INTERNAL_ERROR;
            final String resultDetails = result.getString(KEY_DETAILS) != null
                    ? result.getString(KEY_DETAILS) : "no details";
            logExecutionDisplayPolicy(isExecutionDisplayPolicySuccess(code)
                            ? "platform_execution_display_policy_success"
                            : "platform_execution_display_policy_failed",
                    sessionId, caller, displayId, actionType, code, resultDetails);
            return result;
        } catch (Throwable t) {
            return executionDisplayPolicyFailure(sessionId, caller, displayId, actionType,
                    CODE_INTERNAL_ERROR,
                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Bundle performExecutionDisplayStartupCall(String actionType, String sessionId,
            int displayId, String details, PolicyOperation operation) {
        final CallerIdentity caller = getCallerIdentity();
        logExecutionDisplayPolicy("platform_execution_display_policy_begin", sessionId, caller,
                displayId, actionType, "BEGIN", details);

        final AuthorizationResult authorization = authorizeCaller(caller);
        if (!authorization.authorized) {
            return executionDisplayPolicyFailure(sessionId, caller, displayId, actionType,
                    CODE_PERMISSION_DENIED, authorization.details);
        }
        if (mActivityTaskManagerInternal == null) {
            return executionDisplayPolicyFailure(sessionId, caller, displayId, actionType,
                    CODE_UNSUPPORTED, "ActivityTaskManagerInternal unavailable");
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final Bundle result = operation.run();
            if (result == null) {
                return executionDisplayPolicyFailure(sessionId, caller, displayId, actionType,
                        CODE_INTERNAL_ERROR, "null bundle");
            }
            final String code = result.getString(KEY_CODE) != null
                    ? result.getString(KEY_CODE) : CODE_INTERNAL_ERROR;
            final String resultDetails = result.getString(KEY_DETAILS) != null
                    ? result.getString(KEY_DETAILS) : "no details";
            logExecutionDisplayPolicy(isExecutionDisplayStartupSuccess(code)
                            ? "platform_execution_display_policy_success"
                            : "platform_execution_display_policy_failed",
                    sessionId, caller, displayId, actionType, code, resultDetails);
            return result;
        } catch (Throwable t) {
            return executionDisplayPolicyFailure(sessionId, caller, displayId, actionType,
                    CODE_INTERNAL_ERROR,
                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private InjectionResult sendTap(int x, int y, int displayId) {
        final long now = SystemClock.uptimeMillis();
        final boolean down = injectMotionEvent(MotionEvent.ACTION_DOWN, now, now, x, y,
                DEFAULT_PRESSURE, displayId);
        final boolean up = injectMotionEvent(MotionEvent.ACTION_UP, now,
                SystemClock.uptimeMillis(), x, y, NO_PRESSURE, displayId);
        return down && up
                ? InjectionResult.ok("tap injected")
                : InjectionResult.error(CODE_INJECTION_REJECTED, "tap injection rejected");
    }

    private InjectionResult sendSwipe(int x1, int y1, int x2, int y2, long durationMs,
            int displayId) {
        final long safeDuration = durationMs > 0 ? durationMs : DEFAULT_SWIPE_DURATION_MS;
        final long down = SystemClock.uptimeMillis();
        if (!injectMotionEvent(MotionEvent.ACTION_DOWN, down, down, x1, y1, DEFAULT_PRESSURE,
                displayId)) {
            return InjectionResult.error(CODE_INJECTION_REJECTED, "swipe down rejected");
        }

        long now = SystemClock.uptimeMillis();
        final long endTime = down + safeDuration;
        while (now < endTime) {
            final long elapsedTime = now - down;
            final float alpha = safeDuration == 0 ? 1.0f : (float) elapsedTime / safeDuration;
            if (!injectMotionEvent(MotionEvent.ACTION_MOVE, down, now,
                    lerp(x1, x2, alpha), lerp(y1, y2, alpha), DEFAULT_PRESSURE, displayId)) {
                return InjectionResult.error(CODE_INJECTION_REJECTED, "swipe move rejected");
            }
            now = SystemClock.uptimeMillis();
        }

        final boolean up = injectMotionEvent(MotionEvent.ACTION_UP, down,
                SystemClock.uptimeMillis(), x2, y2, NO_PRESSURE, displayId);
        return up
                ? InjectionResult.ok("swipe injected")
                : InjectionResult.error(CODE_INJECTION_REJECTED, "swipe up rejected");
    }

    private InjectionResult sendLongPress(int x, int y, long durationMs, int displayId) {
        final long safeDuration = durationMs > 0 ? durationMs : ViewConfiguration.getLongPressTimeout();
        final long down = SystemClock.uptimeMillis();
        if (!injectMotionEvent(MotionEvent.ACTION_DOWN, down, down, x, y, DEFAULT_PRESSURE,
                displayId)) {
            return InjectionResult.error(CODE_INJECTION_REJECTED, "long press down rejected");
        }
        sleepSafely(safeDuration);
        final boolean up = injectMotionEvent(MotionEvent.ACTION_UP, down,
                SystemClock.uptimeMillis(), x, y, NO_PRESSURE, displayId);
        return up
                ? InjectionResult.ok("long press injected")
                : InjectionResult.error(CODE_INJECTION_REJECTED, "long press up rejected");
    }

    private InjectionResult sendKey(String sessionId, CallerIdentity caller, int displayId,
            int width, int height, int keyCode) {
        final long now = SystemClock.uptimeMillis();
        final KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode,
                0 /* repeatCount */, 0 /* metaState */, KeyCharacterMap.VIRTUAL_KEYBOARD,
                0 /* scancode */, 0 /* flags */, InputDevice.SOURCE_KEYBOARD);
        down.setDisplayId(displayId);
        logKeyDispatchAttempt(sessionId, caller, displayId, width, height, keyCode, "DOWN");
        if (!injectKeyEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT)) {
            logKeyDispatchRejected(sessionId, caller, displayId, width, height,
                    keyCode, "DOWN", "key down rejected");
            return InjectionResult.error(CODE_INJECTION_REJECTED, "key down rejected");
        }
        final KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        up.setDisplayId(displayId);
        logKeyDispatchAttempt(sessionId, caller, displayId, width, height, keyCode, "UP");
        if (!injectKeyEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT)) {
            logKeyDispatchRejected(sessionId, caller, displayId, width, height,
                    keyCode, "UP", "key up rejected");
            return InjectionResult.error(CODE_INJECTION_REJECTED, "key up rejected");
        }
        return InjectionResult.ok("key injected");
    }

    private InjectionResult sendText(String text, int displayId) {
        if (text.isEmpty()) {
            return InjectionResult.ok("empty text ignored");
        }

        final KeyCharacterMap keyCharacterMap =
                KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        final KeyEvent[] events = keyCharacterMap.getEvents(text.toCharArray());
        if (events == null || events.length == 0) {
            return InjectionResult.error(CODE_UNSUPPORTED,
                    "Only basic KeyCharacterMap text input is supported");
        }

        for (KeyEvent event : events) {
            event.setSource(InputDevice.SOURCE_KEYBOARD);
            event.setDisplayId(displayId);
            if (!injectKeyEvent(event)) {
                return InjectionResult.error(CODE_INJECTION_REJECTED,
                        "text injection rejected for action=" + event.getAction());
            }
        }
        return InjectionResult.ok("text injected");
    }

    private boolean injectKeyEvent(KeyEvent event) {
        return injectKeyEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    private boolean injectKeyEvent(KeyEvent event, int syncMode) {
        return InputManager.getInstance().injectInputEvent(
                event, syncMode);
    }

    private boolean injectMotionEvent(int action, long downTime, long when, float x, float y,
            float pressure, int displayId) {
        final int pointerCount = 1;
        final MotionEvent.PointerProperties[] pointerProperties =
                new MotionEvent.PointerProperties[pointerCount];
        final MotionEvent.PointerCoords[] pointerCoords =
                new MotionEvent.PointerCoords[pointerCount];
        for (int index = 0; index < pointerCount; index++) {
            pointerProperties[index] = new MotionEvent.PointerProperties();
            pointerProperties[index].id = index;
            pointerProperties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
            pointerCoords[index] = new MotionEvent.PointerCoords();
            pointerCoords[index].x = x;
            pointerCoords[index].y = y;
            pointerCoords[index].pressure = pressure;
            pointerCoords[index].size = DEFAULT_SIZE;
        }
        final MotionEvent event = MotionEvent.obtain(downTime, when, action, pointerCount,
                pointerProperties, pointerCoords, DEFAULT_META_STATE, DEFAULT_BUTTON_STATE,
                DEFAULT_PRECISION_X, DEFAULT_PRECISION_Y,
                getInputDeviceId(InputDevice.SOURCE_TOUCHSCREEN), DEFAULT_EDGE_FLAGS,
                InputDevice.SOURCE_TOUCHSCREEN, displayId, DEFAULT_FLAGS);
        if (event == null) {
            return false;
        }
        try {
            return InputManager.getInstance().injectInputEvent(
                    event, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
        } finally {
            event.recycle();
        }
    }

    private int getInputDeviceId(int inputSource) {
        final int[] deviceIds = InputDevice.getDeviceIds();
        for (int deviceId : deviceIds) {
            final InputDevice inputDevice = InputDevice.getDevice(deviceId);
            if (inputDevice != null && inputDevice.supportsSource(inputSource)) {
                return deviceId;
            }
        }
        return DEFAULT_DEVICE_ID;
    }

    private DisplayInfo getDisplayInfo(int displayId) {
        if (displayId < 0 || mDisplayManagerInternal == null) {
            return null;
        }
        return mDisplayManagerInternal.getDisplayInfo(displayId);
    }

    private AuthorizationResult authorizeCaller(CallerIdentity caller) {
        if (getContext().checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return AuthorizationResult.denied("missing permission " + PERMISSION);
        }
        if (!caller.hasTargetPackage) {
            return AuthorizationResult.denied("calling uid is not " + TARGET_PACKAGE);
        }
        return AuthorizationResult.authorized();
    }

    private CallerIdentity getCallerIdentity() {
        final int uid = Binder.getCallingUid();
        final String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        final List<String> packageNames = new ArrayList<>();
        boolean hasTargetPackage = false;
        if (packages != null) {
            for (String packageName : packages) {
                packageNames.add(packageName);
                if (TARGET_PACKAGE.equals(packageName)) {
                    hasTargetPackage = true;
                }
            }
        }
        final String packageSummary = packageNames.isEmpty()
                ? "unknown"
                : String.join(",", packageNames);
        return new CallerIdentity(uid, packageSummary, hasTargetPackage);
    }

    private Bundle captureFailure(String sessionId, CallerIdentity caller, int displayId, int width,
            int height, String code, String details) {
        logCapture("platform_capture_failed", sessionId, caller, displayId, width, height,
                code, details);
        return buildResult(code, details);
    }

    private Bundle injectFailure(String sessionId, CallerIdentity caller, int displayId, int width,
            int height, String actionType, String code, String details) {
        logInject("platform_inject_failed", sessionId, caller, displayId, width, height,
                actionType, code, details);
        return buildResult(code, details);
    }

    private Bundle buildResult(String code, String details) {
        final Bundle bundle = new Bundle();
        bundle.putString(KEY_CODE, code);
        bundle.putString(KEY_DETAILS, details);
        return bundle;
    }

    private void logCapture(String event, String sessionId, CallerIdentity caller, int displayId,
            int width, int height, String code, String details) {
        final String line = "event=" + event
                + " sessionId=" + sanitizeSessionId(sessionId)
                + " callerUid=" + caller.uid
                + " packageName=" + caller.packageName
                + " displayId=" + displayId
                + " width=" + width
                + " height=" + height
                + " code=" + code
                + " details=" + sanitize(details);
        recordHistory(line);
        if (event.endsWith("failed")) {
            Slog.w(TAG, line);
        } else {
            Slog.i(TAG, line);
        }
    }

    private void logInject(String event, String sessionId, CallerIdentity caller, int displayId,
            int width, int height, String actionType, String code, String details) {
        final String line = "event=" + event
                + " sessionId=" + sanitizeSessionId(sessionId)
                + " callerUid=" + caller.uid
                + " packageName=" + caller.packageName
                + " displayId=" + displayId
                + " width=" + width
                + " height=" + height
                + " actionType=" + actionType
                + " code=" + code
                + " details=" + sanitize(details);
        recordHistory(line);
        if (event.endsWith("failed")) {
            Slog.w(TAG, line);
        } else {
            Slog.i(TAG, line);
        }
    }

    private Bundle executionDisplayPolicyFailure(String sessionId, CallerIdentity caller,
            int displayId, String actionType, String code, String details) {
        logExecutionDisplayPolicy("platform_execution_display_policy_failed", sessionId, caller,
                displayId, actionType, code, details);
        return buildResult(code, details);
    }

    private void logExecutionDisplayPolicy(String event, String sessionId, CallerIdentity caller,
            int displayId, String actionType, String code, String details) {
        final String line = "event=" + event
                + " sessionId=" + sanitizeSessionId(sessionId)
                + " callerUid=" + caller.uid
                + " packageName=" + caller.packageName
                + " executionDisplayId=" + displayId
                + " actionType=" + actionType
                + " code=" + code
                + " details=" + sanitize(details);
        recordHistory(line);
        if (event.endsWith("failed")) {
            Slog.w(TAG, line);
        } else {
            Slog.i(TAG, line);
        }
    }

    private boolean isExecutionDisplayPolicySuccess(String code) {
        return "EXECUTION_SESSION_REGISTERED".equals(code)
                || "EXECUTION_SESSION_UPDATED".equals(code)
                || "EXECUTION_SESSION_UNCHANGED".equals(code)
                || "EXECUTION_SESSION_CLEARED".equals(code)
                || "EXECUTION_SESSION_ALREADY_CLEARED".equals(code);
    }

    private boolean isExecutionDisplayStartupSuccess(String code) {
        return "EXECUTION_DISPLAY_STARTUP_OK".equals(code);
    }

    private void logKeyDispatchAttempt(String sessionId, CallerIdentity caller, int displayId,
            int width, int height, int keyCode, String keyAction) {
        final FocusSnapshot snapshot = captureFocusSnapshot();
        final String line = buildKeyDispatchLine("platform_key_dispatch_attempt", sessionId, caller,
                displayId, width, height, keyCode, keyAction, "BEGIN", "attempt", snapshot);
        recordHistory(line);
        Slog.i(TAG, line);
    }

    private void logKeyDispatchRejected(String sessionId, CallerIdentity caller, int displayId,
            int width, int height, int keyCode, String keyAction, String details) {
        final FocusSnapshot snapshot = captureFocusSnapshot();
        final String line = buildKeyDispatchLine("platform_key_dispatch_rejected", sessionId,
                caller, displayId, width, height, keyCode, keyAction,
                CODE_INJECTION_REJECTED, details, snapshot);
        recordHistory(line);
        Slog.w(TAG, line);
    }

    private String buildKeyDispatchLine(String event, String sessionId, CallerIdentity caller,
            int displayId, int width, int height, int keyCode, String keyAction, String code,
            String details, FocusSnapshot snapshot) {
        return "event=" + event
                + " sessionId=" + sanitizeSessionId(sessionId)
                + " callerUid=" + caller.uid
                + " packageName=" + caller.packageName
                + " displayId=" + displayId
                + " width=" + width
                + " height=" + height
                + " actionType=KEY"
                + " keyCode=" + keyCode
                + " keyAction=" + keyAction
                + " topFocusedDisplayId=" + snapshot.topFocusedDisplayId
                + " focusedWindowDisplayId=" + snapshot.focusedWindowDisplayId
                + " focusedWindowToken=" + snapshot.focusedWindowToken
                + " focusedTaskId=" + snapshot.focusedTaskId
                + " focusedTaskDisplayId=" + snapshot.focusedTaskDisplayId
                + " focusedTopActivity=" + snapshot.focusedTopActivity
                + " elapsedRealtimeMs=" + SystemClock.elapsedRealtime()
                + " code=" + code
                + " details=" + sanitize(details);
    }

    private FocusSnapshot captureFocusSnapshot() {
        int topFocusedDisplayId = -1;
        int focusedWindowDisplayId = -1;
        String focusedWindowToken = "null";
        if (mWindowManagerInternal != null) {
            try {
                topFocusedDisplayId = mWindowManagerInternal.getTopFocusedDisplayId();
                final IBinder token = mWindowManagerInternal.getFocusedWindowTokenFromWindowStates();
                if (token != null) {
                    focusedWindowToken = sanitize(token.toString());
                    focusedWindowDisplayId = mWindowManagerInternal.getDisplayIdForWindow(token);
                }
            } catch (Throwable t) {
                focusedWindowToken = sanitize("lookup_failed:" + t.getClass().getSimpleName());
            }
        }

        int focusedTaskId = -1;
        int focusedTaskDisplayId = -1;
        String focusedTopActivity = "null";
        try {
            final ActivityTaskManager.RootTaskInfo rootTaskInfo =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            if (rootTaskInfo != null) {
                focusedTaskId = rootTaskInfo.taskId;
                focusedTaskDisplayId = rootTaskInfo.displayId;
                focusedTopActivity = sanitize(String.valueOf(rootTaskInfo.topActivity));
            }
        } catch (RemoteException remoteException) {
            focusedTopActivity = sanitize("lookup_failed:" + remoteException.getClass().getSimpleName());
        } catch (Throwable t) {
            focusedTopActivity = sanitize("lookup_failed:" + t.getClass().getSimpleName());
        }
        return new FocusSnapshot(topFocusedDisplayId, focusedWindowDisplayId, focusedWindowToken,
                focusedTaskId, focusedTaskDisplayId, focusedTopActivity);
    }

    private void recordHistory(String line) {
        synchronized (mLock) {
            while (mHistory.size() >= MAX_HISTORY) {
                mHistory.removeFirst();
            }
            mHistory.addLast(line);
        }
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("AgentHostPlatformService");
        pw.println("  serviceName=" + SERVICE_NAME);
        pw.println("  targetPackage=" + TARGET_PACKAGE);
        pw.println("  permission=" + PERMISSION);
        pw.println("  displayManagerReady=" + (mDisplayManagerInternal != null));
        pw.println("  windowManagerReady=" + (mWindowManagerInternal != null));
        pw.println("  activityTaskManagerReady=" + (mActivityTaskManagerInternal != null));
        final List<String> snapshot = new ArrayList<>();
        synchronized (mLock) {
            snapshot.addAll(mHistory);
        }
        pw.println("  historyCount=" + snapshot.size());
        for (String line : snapshot) {
            pw.println("  " + line);
        }
    }

    private void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private void sleepSafely(long durationMs) {
        try {
            Thread.sleep(Math.max(1L, durationMs));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private float lerp(float start, float end, float alpha) {
        return (end - start) * alpha + start;
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replace('\n', ' ');
    }

    private String sanitizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isEmpty() ? "null" : sessionId;
    }

    private interface InjectionOperation {
        InjectionResult run();
    }

    private interface PolicyOperation {
        Bundle run();
    }

    private static final class InjectionResult {
        final boolean ok;
        final String code;
        final String details;

        private InjectionResult(boolean ok, String code, String details) {
            this.ok = ok;
            this.code = code;
            this.details = details;
        }

        static InjectionResult ok(String details) {
            return new InjectionResult(true, CODE_OK, details);
        }

        static InjectionResult error(String code, String details) {
            return new InjectionResult(false, code, details);
        }
    }

    private static final class AuthorizationResult {
        final boolean authorized;
        final String details;

        private AuthorizationResult(boolean authorized, String details) {
            this.authorized = authorized;
            this.details = details;
        }

        static AuthorizationResult authorized() {
            return new AuthorizationResult(true, "ok");
        }

        static AuthorizationResult denied(String details) {
            return new AuthorizationResult(false, details);
        }
    }

    private static final class CallerIdentity {
        final int uid;
        final String packageName;
        final boolean hasTargetPackage;

        private CallerIdentity(int uid, String packageName, boolean hasTargetPackage) {
            this.uid = uid;
            this.packageName = packageName;
            this.hasTargetPackage = hasTargetPackage;
        }
    }

    private static final class FocusSnapshot {
        final int topFocusedDisplayId;
        final int focusedWindowDisplayId;
        final String focusedWindowToken;
        final int focusedTaskId;
        final int focusedTaskDisplayId;
        final String focusedTopActivity;

        private FocusSnapshot(int topFocusedDisplayId, int focusedWindowDisplayId,
                String focusedWindowToken, int focusedTaskId, int focusedTaskDisplayId,
                String focusedTopActivity) {
            this.topFocusedDisplayId = topFocusedDisplayId;
            this.focusedWindowDisplayId = focusedWindowDisplayId;
            this.focusedWindowToken = focusedWindowToken;
            this.focusedTaskId = focusedTaskId;
            this.focusedTaskDisplayId = focusedTaskDisplayId;
            this.focusedTopActivity = focusedTopActivity;
        }
    }
}
