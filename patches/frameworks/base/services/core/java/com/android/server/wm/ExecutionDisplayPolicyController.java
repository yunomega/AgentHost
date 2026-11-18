/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.ActivityManager.isStartResultSuccessful;
import static android.os.Process.INVALID_UID;
import static android.os.Process.SYSTEM_UID;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.ActivityStarter.Request;

/**
 * Keeps a single active execution-display preference for AgentHost-driven automation sessions.
 *
 * <p>This controller intentionally scopes all policy decisions to the one registered execution
 * display. It does not grant broader private-display access.</p>
 */
final class ExecutionDisplayPolicyController {
    private static final String TAG = "ExecutionDisplayPolicy";
    private static final String TARGET_PACKAGE = "com.example.agenthost";

    private static final String KEY_CODE = "code";
    private static final String KEY_DETAILS = "details";
    private static final String KEY_TASK_ID = "taskId";
    private static final String KEY_TOP_ACTIVITY = "topActivity";
    private static final String KEY_TOP_PACKAGE = "topPackage";
    private static final String KEY_DISPLAY_ID = "displayId";
    private static final String KEY_REQUESTED_DISPLAY_ID = "requestedDisplayId";
    private static final String KEY_BOUND_DISPLAY_ID = "boundDisplayId";
    private static final String KEY_RESOLVED_COMPONENT = "resolvedComponent";

    private static final String CODE_EXECUTION_SESSION_REGISTERED =
            "EXECUTION_SESSION_REGISTERED";
    private static final String CODE_EXECUTION_SESSION_UPDATED =
            "EXECUTION_SESSION_UPDATED";
    private static final String CODE_EXECUTION_SESSION_UNCHANGED =
            "EXECUTION_SESSION_UNCHANGED";
    private static final String CODE_EXECUTION_SESSION_CLEARED =
            "EXECUTION_SESSION_CLEARED";
    private static final String CODE_EXECUTION_SESSION_ALREADY_CLEARED =
            "EXECUTION_SESSION_ALREADY_CLEARED";
    private static final String CODE_EXECUTION_SESSION_ALREADY_ACTIVE =
            "EXECUTION_SESSION_ALREADY_ACTIVE";
    private static final String CODE_EXECUTION_SESSION_NOT_ACTIVE =
            "EXECUTION_SESSION_NOT_ACTIVE";
    private static final String CODE_EXECUTION_SESSION_MISMATCH =
            "EXECUTION_SESSION_MISMATCH";
    private static final String CODE_EXECUTION_SESSION_INVALID_STATE =
            "EXECUTION_SESSION_INVALID_STATE";
    private static final String CODE_EXECUTION_SESSION_INVALID_SESSION_ID =
            "EXECUTION_SESSION_INVALID_SESSION_ID";
    private static final String CODE_EXECUTION_SESSION_INVALID_DISPLAY =
            "EXECUTION_SESSION_INVALID_DISPLAY";
    private static final String CODE_EXECUTION_SESSION_DISPLAY_NOT_FOUND =
            "EXECUTION_SESSION_DISPLAY_NOT_FOUND";
    private static final String CODE_EXECUTION_SESSION_DISPLAY_NOT_PRIVATE =
            "EXECUTION_SESSION_DISPLAY_NOT_PRIVATE";
    private static final String CODE_EXECUTION_DISPLAY_STARTUP_OK =
            "EXECUTION_DISPLAY_STARTUP_OK";
    private static final String CODE_EXECUTION_DISPLAY_STARTUP_INVALID_TARGET =
            "EXECUTION_DISPLAY_STARTUP_INVALID_TARGET";
    private static final String CODE_EXECUTION_DISPLAY_STARTUP_UNSUPPORTED_DISPLAY =
            "EXECUTION_DISPLAY_STARTUP_UNSUPPORTED_DISPLAY";
    private static final String CODE_EXECUTION_DISPLAY_STARTUP_UNSUPPORTED_NO_SECONDARY_HOME =
            "EXECUTION_DISPLAY_STARTUP_UNSUPPORTED_NO_SECONDARY_HOME";
    private static final String CODE_EXECUTION_DISPLAY_STARTUP_COMPONENT_NOT_FOUND =
            "EXECUTION_DISPLAY_STARTUP_COMPONENT_NOT_FOUND";
    private static final String CODE_EXECUTION_DISPLAY_STARTUP_COMPONENT_NOT_ALLOWED =
            "EXECUTION_DISPLAY_STARTUP_COMPONENT_NOT_ALLOWED";
    private static final String CODE_EXECUTION_DISPLAY_STARTUP_LAUNCH_FAILED =
            "EXECUTION_DISPLAY_STARTUP_LAUNCH_FAILED";
    private static final String CODE_EXECUTION_DISPLAY_STARTUP_WRONG_DISPLAY =
            "EXECUTION_DISPLAY_STARTUP_WRONG_DISPLAY";
    private static final String CODE_EXECUTION_DISPLAY_STARTUP_TIMEOUT =
            "EXECUTION_DISPLAY_STARTUP_TIMEOUT";

    @VisibleForTesting static final String STARTUP_MODE_SECONDARY_HOME = "SECONDARY_HOME";
    @VisibleForTesting static final String STARTUP_MODE_EXPLICIT_COMPONENT_ON_DISPLAY =
            "EXPLICIT_COMPONENT_ON_DISPLAY";
    private static final long STARTUP_TIMEOUT_MS = 4_000L;
    private static final long STARTUP_POLL_INTERVAL_MS = 100L;

    @VisibleForTesting static final String STATE_EXECUTION = "EXECUTION";
    @VisibleForTesting static final String STATE_USER_HANDOFF = "USER_HANDOFF";
    @VisibleForTesting static final String STATE_COMPLETED = "COMPLETED";

    private final ActivityTaskManagerService mService;
    private final ArraySet<Integer> mAdditionalDisplayAccessUids = new ArraySet<>();

    @Nullable
    private String mActiveSessionId;
    @Nullable
    private String mState;
    private int mExecutionDisplayId = INVALID_DISPLAY;
    private int mDisplayOwnerUid = INVALID_UID;
    private int mRegistrantUid = INVALID_UID;

    ExecutionDisplayPolicyController(ActivityTaskManagerService service) {
        mService = service;
    }

    Bundle registerExecutionDisplaySession(@Nullable String sessionId, int executionDisplayId,
            int registrantUid) {
        if (TextUtils.isEmpty(sessionId)) {
            return error(CODE_EXECUTION_SESSION_INVALID_SESSION_ID, "sessionId is empty");
        }
        if (executionDisplayId == INVALID_DISPLAY || executionDisplayId == DEFAULT_DISPLAY) {
            return error(CODE_EXECUTION_SESSION_INVALID_DISPLAY,
                    "executionDisplayId=" + executionDisplayId);
        }

        final DisplayContent displayContent =
                mService.mRootWindowContainer.getDisplayContent(executionDisplayId);
        if (displayContent == null || displayContent.isRemoved()) {
            return error(CODE_EXECUTION_SESSION_DISPLAY_NOT_FOUND,
                    "executionDisplayId=" + executionDisplayId);
        }
        if (!displayContent.isPrivate()) {
            return error(CODE_EXECUTION_SESSION_DISPLAY_NOT_PRIVATE,
                    "executionDisplayId=" + executionDisplayId);
        }

        if (hasActiveSession() && !TextUtils.equals(mActiveSessionId, sessionId)) {
            return error(CODE_EXECUTION_SESSION_ALREADY_ACTIVE,
                    "activeSessionId=" + mActiveSessionId + " executionDisplayId="
                            + mExecutionDisplayId);
        }

        final int ownerUid = displayContent.mDisplay.getOwnerUid();
        if (TextUtils.equals(mActiveSessionId, sessionId)
                && mExecutionDisplayId == executionDisplayId
                && TextUtils.equals(mState, STATE_EXECUTION)) {
            mRegistrantUid = registrantUid;
            mDisplayOwnerUid = ownerUid;
            addAdditionalDisplayAccessUid(registrantUid, "register_unchanged");
            final String details = "sessionId=" + sessionId
                    + " executionDisplayId=" + executionDisplayId
                    + " registrantUid=" + registrantUid
                    + " ownerUid=" + ownerUid
                    + " state=" + mState;
            logSessionEvent("execution_display_session_register_unchanged",
                    CODE_EXECUTION_SESSION_UNCHANGED, details);
            return success(CODE_EXECUTION_SESSION_UNCHANGED, details);
        }

        mActiveSessionId = sessionId;
        mExecutionDisplayId = executionDisplayId;
        mDisplayOwnerUid = ownerUid;
        mRegistrantUid = registrantUid;
        mState = STATE_EXECUTION;
        mAdditionalDisplayAccessUids.clear();
        addAdditionalDisplayAccessUid(registrantUid, "register_session");

        final String details = "sessionId=" + sessionId
                + " executionDisplayId=" + executionDisplayId
                + " registrantUid=" + registrantUid
                + " ownerUid=" + ownerUid
                + " state=" + mState;
        logSessionEvent("execution_display_session_registered",
                CODE_EXECUTION_SESSION_REGISTERED, details);
        return success(CODE_EXECUTION_SESSION_REGISTERED, details);
    }

    Bundle updateExecutionDisplaySessionState(@Nullable String sessionId, int executionDisplayId,
            @Nullable String state, @Nullable String reason) {
        if (!hasActiveSession()) {
            return error(CODE_EXECUTION_SESSION_NOT_ACTIVE,
                    "sessionId=" + sessionId + " reason=" + sanitize(reason));
        }
        if (!TextUtils.equals(mActiveSessionId, sessionId)
                || mExecutionDisplayId != executionDisplayId) {
            return error(CODE_EXECUTION_SESSION_MISMATCH,
                    "activeSessionId=" + mActiveSessionId
                            + " requestedSessionId=" + sessionId
                            + " activeDisplayId=" + mExecutionDisplayId
                            + " requestedDisplayId=" + executionDisplayId);
        }
        if (!isValidState(state)) {
            return error(CODE_EXECUTION_SESSION_INVALID_STATE,
                    "state=" + sanitize(state));
        }
        if (TextUtils.equals(mState, state)) {
            final String details = "sessionId=" + sessionId
                    + " executionDisplayId=" + executionDisplayId
                    + " state=" + state
                    + " reason=" + sanitize(reason);
            logSessionEvent("execution_display_session_update_unchanged",
                    CODE_EXECUTION_SESSION_UNCHANGED, details);
            return success(CODE_EXECUTION_SESSION_UNCHANGED, details);
        }

        mState = state;
        final String details = "sessionId=" + sessionId
                + " executionDisplayId=" + executionDisplayId
                + " state=" + state
                + " reason=" + sanitize(reason);
        logSessionEvent("execution_display_session_updated",
                CODE_EXECUTION_SESSION_UPDATED, details);
        return success(CODE_EXECUTION_SESSION_UPDATED, details);
    }

    Bundle clearExecutionDisplaySession(@Nullable String sessionId, @Nullable String reason) {
        if (!hasActiveSession()) {
            final String details = "sessionId=" + sanitize(sessionId)
                    + " reason=" + sanitize(reason);
            logSessionEvent("execution_display_session_clear_skipped",
                    CODE_EXECUTION_SESSION_ALREADY_CLEARED, details);
            return success(CODE_EXECUTION_SESSION_ALREADY_CLEARED, details);
        }
        if (!TextUtils.equals(mActiveSessionId, sessionId)) {
            return error(CODE_EXECUTION_SESSION_MISMATCH,
                    "activeSessionId=" + mActiveSessionId
                            + " requestedSessionId=" + sanitize(sessionId));
        }

        final String details = "sessionId=" + sanitize(sessionId)
                + " executionDisplayId=" + mExecutionDisplayId
                + " state=" + sanitize(mState)
                + " reason=" + sanitize(reason);
        logSessionEvent("execution_display_session_cleared",
                CODE_EXECUTION_SESSION_CLEARED, details);
        reset();
        requestImmediateDisplayAccessSync(INVALID_UID, "clear_session");
        return success(CODE_EXECUTION_SESSION_CLEARED, details);
    }

    Bundle startExecutionDisplayStartupTarget(@Nullable String sessionId, int executionDisplayId,
            @Nullable String startupMode, @Nullable String componentName, @Nullable String reason) {
        final StartupRequest request;
        final int startResult;
        synchronized (mService.mGlobalLock) {
            request = prepareStartupRequest(sessionId, executionDisplayId,
                    startupMode, componentName, reason);
            if (request.mError != null) {
                return request.mError;
            }
            startResult = request.mMode.equals(STARTUP_MODE_SECONDARY_HOME)
                    ? startSecondaryHome(request)
                    : startExplicitComponent(request);
        }
        if (!isStartResultSuccessful(startResult)) {
            return startupError(CODE_EXECUTION_DISPLAY_STARTUP_LAUNCH_FAILED,
                    "mode=" + request.mMode
                            + " executionDisplayId=" + request.mExecutionDisplayId
                            + " startResult=" + startResult
                            + " reason=" + sanitize(request.mReason),
                    request.mRequestedDisplayId);
        }

        return waitForStartupResult(request);
    }

    @Nullable
    TaskDisplayArea adjustLaunchTaskDisplayArea(@Nullable TaskDisplayArea currentTaskDisplayArea,
            @Nullable Task task, @Nullable ActivityOptions options, @Nullable ActivityRecord source,
            @NonNull ActivityRecord activityRecord, @Nullable Request request) {
        if (!hasActiveSession()) {
            return currentTaskDisplayArea;
        }

        final int currentDisplayId = currentTaskDisplayArea != null
                ? currentTaskDisplayArea.getDisplayId() : INVALID_DISPLAY;
        final int explicitDisplayId = options != null
                ? options.getLaunchDisplayId() : INVALID_DISPLAY;
        final TaskDisplayArea explicitTaskDisplayArea = options != null
                && options.getLaunchTaskDisplayArea() != null
                ? (TaskDisplayArea) WindowContainer.fromBinder(
                        options.getLaunchTaskDisplayArea().asBinder())
                : null;
        final int explicitTaskDisplayAreaDisplayId = explicitTaskDisplayArea != null
                ? explicitTaskDisplayArea.getDisplayId() : INVALID_DISPLAY;
        final String callingPackage = request != null ? request.callingPackage : null;
        final int callingUid = getEffectiveCallingUid(request);

        if (!TextUtils.equals(mState, STATE_EXECUTION)) {
            logLaunchDecision("execution_display_launch_not_forced",
                    currentDisplayId, currentDisplayId, task, source, activityRecord, request,
                    explicitDisplayId, explicitTaskDisplayAreaDisplayId,
                    "state=" + sanitize(mState));
            return currentTaskDisplayArea;
        }

        if (explicitTaskDisplayAreaDisplayId != INVALID_DISPLAY
                && explicitTaskDisplayAreaDisplayId != mExecutionDisplayId) {
            logLaunchDecision("launch_routed_to_main_display",
                    currentDisplayId, currentDisplayId, task, source, activityRecord, request,
                    explicitDisplayId, explicitTaskDisplayAreaDisplayId,
                    "explicit_task_display_area");
            return currentTaskDisplayArea;
        }

        if (explicitDisplayId != INVALID_DISPLAY && explicitDisplayId != mExecutionDisplayId) {
            logLaunchDecision("launch_routed_to_main_display",
                    currentDisplayId, currentDisplayId, task, source, activityRecord, request,
                    explicitDisplayId, explicitTaskDisplayAreaDisplayId,
                    "explicit_launch_display");
            return currentTaskDisplayArea;
        }

        boolean shouldPreferExecutionDisplay = currentDisplayId == mExecutionDisplayId;
        String reason = "retain_execution_display";
        if (!shouldPreferExecutionDisplay && source != null
                && source.getDisplayId() == mExecutionDisplayId) {
            shouldPreferExecutionDisplay = true;
            reason = "source_activity_on_execution_display";
        }
        if (!shouldPreferExecutionDisplay && task != null
                && task.getDisplayId() == mExecutionDisplayId) {
            shouldPreferExecutionDisplay = true;
            reason = "task_on_execution_display";
        }
        if (!shouldPreferExecutionDisplay && TextUtils.equals(callingPackage, TARGET_PACKAGE)) {
            shouldPreferExecutionDisplay = true;
            reason = "agenthost_calling_package";
        }
        if (!shouldPreferExecutionDisplay && callingUid != INVALID_UID
                && callingUid == mRegistrantUid) {
            shouldPreferExecutionDisplay = true;
            reason = "registrant_uid";
        }

        if (!shouldPreferExecutionDisplay) {
            logLaunchDecision("launch_routed_to_main_display",
                    currentDisplayId, currentDisplayId, task, source, activityRecord, request,
                    explicitDisplayId, explicitTaskDisplayAreaDisplayId,
                    "no_execution_display_affinity");
            return currentTaskDisplayArea;
        }

        final DisplayContent executionDisplay =
                mService.mRootWindowContainer.getDisplayContent(mExecutionDisplayId);
        if (executionDisplay == null || executionDisplay.isRemoved()) {
            logLaunchDecision("launch_routed_to_main_display",
                    currentDisplayId, currentDisplayId, task, source, activityRecord, request,
                    explicitDisplayId, explicitTaskDisplayAreaDisplayId,
                    "execution_display_not_found");
            return currentTaskDisplayArea;
        }
        if (!executionDisplay.isPrivate()) {
            logLaunchDecision("launch_routed_to_main_display",
                    currentDisplayId, currentDisplayId, task, source, activityRecord, request,
                    explicitDisplayId, explicitTaskDisplayAreaDisplayId,
                    "execution_display_not_private");
            return currentTaskDisplayArea;
        }
        if (!mService.mSupportsMultiDisplay && mExecutionDisplayId != DEFAULT_DISPLAY) {
            logLaunchDecision("launch_routed_to_main_display",
                    currentDisplayId, currentDisplayId, task, source, activityRecord, request,
                    explicitDisplayId, explicitTaskDisplayAreaDisplayId,
                    "multi_display_unsupported");
            return currentTaskDisplayArea;
        }

        final TaskDisplayArea executionTaskDisplayArea =
                executionDisplay.getDefaultTaskDisplayArea();
        if (executionTaskDisplayArea == null) {
            logLaunchDecision("launch_routed_to_main_display",
                    currentDisplayId, currentDisplayId, task, source, activityRecord, request,
                    explicitDisplayId, explicitTaskDisplayAreaDisplayId,
                    "execution_task_display_area_missing");
            return currentTaskDisplayArea;
        }

        if (activityRecord.isActivityTypeHome()
                && !mService.mRootWindowContainer.canStartHomeOnDisplayArea(activityRecord.info,
                        executionTaskDisplayArea, false /* allowInstrumenting */)) {
            logLaunchDecision("launch_routed_to_main_display",
                    currentDisplayId, currentDisplayId, task, source, activityRecord, request,
                    explicitDisplayId, explicitTaskDisplayAreaDisplayId,
                    "home_not_supported_on_execution_display");
            return currentTaskDisplayArea;
        }

        addAdditionalDisplayAccessUid(activityRecord.getUid(), "launch_target_uid");
        logLaunchDecision("launch_routed_to_execution_display",
                currentDisplayId, executionTaskDisplayArea.getDisplayId(), task, source,
                activityRecord, request, explicitDisplayId, explicitTaskDisplayAreaDisplayId,
                reason);
        return executionTaskDisplayArea;
    }

    void appendAdditionalDisplayAccessUids(SparseArray<IntArray> displayAccessUids) {
        if (!hasActiveSession() || mExecutionDisplayId == INVALID_DISPLAY
                || mAdditionalDisplayAccessUids.isEmpty()) {
            return;
        }
        IntArray mergedUids = displayAccessUids.get(mExecutionDisplayId);
        if (mergedUids == null) {
            mergedUids = new IntArray();
            displayAccessUids.put(mExecutionDisplayId, mergedUids);
        }
        for (int i = 0; i < mAdditionalDisplayAccessUids.size(); i++) {
            final int uid = mAdditionalDisplayAccessUids.valueAt(i);
            if (!contains(mergedUids, uid)) {
                mergedUids.add(uid);
            }
        }
    }

    boolean isLaunchAllowedOnExecutionDisplay(int displayId, int uid) {
        if (!hasActiveSession() || displayId != mExecutionDisplayId || uid == INVALID_UID) {
            return false;
        }
        return uid == mRegistrantUid || mAdditionalDisplayAccessUids.contains(uid);
    }

    @VisibleForTesting
    @Nullable
    String getActiveSessionId() {
        return mActiveSessionId;
    }

    @VisibleForTesting
    int getExecutionDisplayId() {
        return mExecutionDisplayId;
    }

    @VisibleForTesting
    @Nullable
    String getState() {
        return mState;
    }

    @VisibleForTesting
    int getRegistrantUid() {
        return mRegistrantUid;
    }

    @VisibleForTesting
    boolean hasAdditionalDisplayAccessUid(int uid) {
        return mAdditionalDisplayAccessUids.contains(uid);
    }

    @NonNull
    String getAdditionalDisplayAccessUidsDebugString() {
        final IntArray uids = new IntArray(mAdditionalDisplayAccessUids.size());
        for (int i = 0; i < mAdditionalDisplayAccessUids.size(); i++) {
            uids.add(mAdditionalDisplayAccessUids.valueAt(i));
        }
        return formatUids(uids);
    }

    private boolean hasActiveSession() {
        return !TextUtils.isEmpty(mActiveSessionId) && mExecutionDisplayId != INVALID_DISPLAY;
    }

    private StartupRequest prepareStartupRequest(@Nullable String sessionId, int executionDisplayId,
            @Nullable String startupMode, @Nullable String componentName, @Nullable String reason) {
        final StartupRequest request = new StartupRequest();
        request.mRequestedDisplayId = executionDisplayId;
        request.mMode = startupMode == null ? "" : startupMode;
        request.mComponentName = componentName == null ? "" : componentName;
        request.mReason = reason == null ? "" : reason;

        if (!hasActiveSession()) {
            request.mError = startupError(CODE_EXECUTION_SESSION_NOT_ACTIVE,
                    "sessionId=" + sanitize(sessionId) + " reason=" + request.mReason,
                    request.mRequestedDisplayId);
            return request;
        }
        if (!TextUtils.equals(mActiveSessionId, sessionId)
                || mExecutionDisplayId != executionDisplayId) {
            request.mError = startupError(CODE_EXECUTION_SESSION_MISMATCH,
                    "activeSessionId=" + sanitize(mActiveSessionId)
                            + " requestedSessionId=" + sanitize(sessionId)
                            + " activeDisplayId=" + mExecutionDisplayId
                            + " requestedDisplayId=" + executionDisplayId,
                    request.mRequestedDisplayId);
            return request;
        }
        if (!TextUtils.equals(mState, STATE_EXECUTION)) {
            request.mError = startupError(CODE_EXECUTION_DISPLAY_STARTUP_UNSUPPORTED_DISPLAY,
                    "state=" + sanitize(mState), request.mRequestedDisplayId);
            return request;
        }

        final DisplayContent displayContent =
                mService.mRootWindowContainer.getDisplayContent(mExecutionDisplayId);
        if (displayContent == null || displayContent.isRemoved()) {
            request.mError = startupError(CODE_EXECUTION_DISPLAY_STARTUP_UNSUPPORTED_DISPLAY,
                    "execution_display_not_found", request.mRequestedDisplayId);
            return request;
        }
        request.mDisplayContent = displayContent;
        request.mExecutionDisplayId = displayContent.getDisplayId();
        request.mTaskDisplayArea = displayContent.getDefaultTaskDisplayArea();
        request.mUserId = mService.getCurrentUserId();
        if (request.mTaskDisplayArea == null) {
            request.mError = startupError(CODE_EXECUTION_DISPLAY_STARTUP_UNSUPPORTED_DISPLAY,
                    "execution_task_display_area_missing", request.mRequestedDisplayId);
            return request;
        }

        logStartupDisplayAudit("execution_display_startup_prepare_audit", request,
                null /* matchedRecord */);

        if (TextUtils.equals(request.mMode, STARTUP_MODE_SECONDARY_HOME)) {
            request.mError = prepareSecondaryHomeRequest(request);
            return request;
        }
        if (TextUtils.equals(request.mMode, STARTUP_MODE_EXPLICIT_COMPONENT_ON_DISPLAY)) {
            request.mError = prepareExplicitComponentRequest(request);
            return request;
        }

        request.mError = startupError(CODE_EXECUTION_DISPLAY_STARTUP_INVALID_TARGET,
                "startupMode=" + sanitize(request.mMode), request.mRequestedDisplayId);
        return request;
    }

    private Bundle prepareSecondaryHomeRequest(@NonNull StartupRequest request) {
        final String unsupportedReason = getSecondaryHomeUnsupportedReason(request.mDisplayContent,
                request.mTaskDisplayArea, request.mUserId);
        if (unsupportedReason != null) {
            return startupError(CODE_EXECUTION_DISPLAY_STARTUP_UNSUPPORTED_DISPLAY,
                    unsupportedReason, request.mRequestedDisplayId);
        }

        final Pair<ActivityInfo, Intent> resolved = mService.mRootWindowContainer
                .resolveSecondaryHomeActivity(request.mUserId, request.mTaskDisplayArea);
        if (resolved == null || resolved.first == null || resolved.second == null) {
            return startupError(CODE_EXECUTION_DISPLAY_STARTUP_UNSUPPORTED_NO_SECONDARY_HOME,
                    "secondary_home_not_found", request.mRequestedDisplayId);
        }
        request.mResolvedActivityInfo = resolved.first;
        request.mExpectedComponent = new ComponentName(
                resolved.first.applicationInfo.packageName, resolved.first.name);
        request.mStartupIntent = new Intent(resolved.second);
        request.mStartupIntent.setComponent(request.mExpectedComponent);
        return null;
    }

    private Bundle prepareExplicitComponentRequest(@NonNull StartupRequest request) {
        if (TextUtils.isEmpty(request.mComponentName)) {
            return startupError(CODE_EXECUTION_DISPLAY_STARTUP_INVALID_TARGET,
                    "explicit_component_empty", request.mRequestedDisplayId);
        }
        final ComponentName component = ComponentName.unflattenFromString(request.mComponentName);
        if (component == null) {
            return startupError(CODE_EXECUTION_DISPLAY_STARTUP_INVALID_TARGET,
                    "invalid_component=" + sanitize(request.mComponentName),
                    request.mRequestedDisplayId);
        }

        final ActivityInfo activityInfo;
        try {
            activityInfo = AppGlobals.getPackageManager().getActivityInfo(component,
                    0 /* flags */, request.mUserId);
        } catch (Exception e) {
            return startupError(CODE_EXECUTION_DISPLAY_STARTUP_COMPONENT_NOT_FOUND,
                    "component=" + sanitize(request.mComponentName),
                    request.mRequestedDisplayId);
        }
        if (activityInfo == null) {
            return startupError(CODE_EXECUTION_DISPLAY_STARTUP_COMPONENT_NOT_FOUND,
                    "component=" + sanitize(request.mComponentName),
                    request.mRequestedDisplayId);
        }
        if (!activityInfo.exported && activityInfo.applicationInfo.uid != mDisplayOwnerUid) {
            return startupError(CODE_EXECUTION_DISPLAY_STARTUP_COMPONENT_NOT_ALLOWED,
                    "component_not_exported=" + sanitize(request.mComponentName),
                    request.mRequestedDisplayId);
        }

        request.mResolvedActivityInfo = new ActivityInfo(activityInfo);
        request.mResolvedActivityInfo.applicationInfo = mService.getAppInfoForUser(
                activityInfo.applicationInfo, request.mUserId);
        request.mExpectedComponent = component;
        request.mStartupIntent = new Intent(Intent.ACTION_MAIN)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return null;
    }

    @VisibleForTesting
    @Nullable
    String getSecondaryHomeUnsupportedReason(@NonNull DisplayContent displayContent,
            @NonNull TaskDisplayArea taskDisplayArea, int userId) {
        if (!displayContent.isTrusted()) {
            return "not_trusted";
        }
        if (!displayContent.supportsSystemDecorations()) {
            return "no_system_decorations";
        }
        if (!taskDisplayArea.canHostHomeTask()) {
            return "cannot_host_home_task";
        }
        if (taskDisplayArea.getDisplayId() != DEFAULT_DISPLAY && !mService.mSupportsMultiDisplay) {
            return "multi_display_unsupported";
        }
        final boolean deviceProvisioned = Settings.Global.getInt(
                mService.mContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        if (!deviceProvisioned) {
            return "device_not_provisioned";
        }
        if (!StorageManager.isUserKeyUnlocked(userId)) {
            return "user_locked";
        }
        return null;
    }

    private int startSecondaryHome(@NonNull StartupRequest request) {
        final boolean started = mService.mRootWindowContainer.startHomeOnDisplay(request.mUserId,
                "executionDisplayStartup:" + sanitize(request.mReason),
                request.mExecutionDisplayId, false /* allowInstrumenting */,
                false /* fromHomeKey */);
        return started ? 0 : -1;
    }

    private int startExplicitComponent(@NonNull StartupRequest request) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(1 /* WINDOWING_MODE_FULLSCREEN */);
        options.setLaunchDisplayId(request.mExecutionDisplayId);
        options.setLaunchTaskDisplayArea(request.mTaskDisplayArea.mRemoteToken
                .toWindowContainerToken());
        return mService.getActivityStartController().obtainStarter(request.mStartupIntent,
                        "executionDisplayStartup:" + sanitize(request.mReason))
                .setCallingUid(SYSTEM_UID)
                .setCallingPackage("android")
                .setActivityInfo(request.mResolvedActivityInfo)
                .setActivityOptions(options.toBundle())
                .setUserId(request.mUserId)
                .execute();
    }

    private Bundle waitForStartupResult(@NonNull StartupRequest request) {
        final long deadline = SystemClock.uptimeMillis() + STARTUP_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            final MatchedActivity matched = findExpectedActivityLocked(request.mExpectedComponent,
                    request.mExecutionDisplayId);
            if (matched != null && matched.mRecord != null) {
                final Task task = matched.mRecord.getTask();
                if (task != null) {
                    logStartupDisplayAudit("execution_display_startup_matched_audit", request,
                            matched.mRecord);
                    return startupSuccess(CODE_EXECUTION_DISPLAY_STARTUP_OK,
                            "mode=" + request.mMode
                                    + " executionDisplayId=" + request.mExecutionDisplayId
                                    + " reason=" + sanitize(request.mReason),
                            request.mExpectedComponent, request.mRequestedDisplayId,
                            task.mTaskId, matched.mRecord);
                }
            }
            if (matched != null && matched.mWrongDisplayId != INVALID_DISPLAY) {
                return startupError(CODE_EXECUTION_DISPLAY_STARTUP_WRONG_DISPLAY,
                        "expectedComponent=" + request.mExpectedComponent.flattenToShortString()
                                + " wrongDisplayId=" + matched.mWrongDisplayId
                                + " executionDisplayId=" + request.mExecutionDisplayId,
                        request.mRequestedDisplayId);
            }
            SystemClock.sleep(STARTUP_POLL_INTERVAL_MS);
        }

        return startupError(CODE_EXECUTION_DISPLAY_STARTUP_TIMEOUT,
                "mode=" + request.mMode
                        + " expectedComponent="
                        + request.mExpectedComponent.flattenToShortString()
                        + " executionDisplayId=" + request.mExecutionDisplayId,
                request.mRequestedDisplayId);
    }

    @Nullable
    private MatchedActivity findExpectedActivityLocked(@NonNull ComponentName expectedComponent,
            int executionDisplayId) {
        synchronized (mService.mGlobalLock) {
            final MatchedActivity matched = new MatchedActivity();
            final RootWindowContainer root = mService.mRootWindowContainer;
            for (int i = root.getChildCount() - 1; i >= 0; i--) {
                final DisplayContent displayContent = root.getChildAt(i);
                final ActivityRecord top = displayContent.topRunningActivity();
                if (top == null || top.mActivityComponent == null
                        || !expectedComponent.equals(top.mActivityComponent)) {
                    continue;
                }
                if (displayContent.getDisplayId() == executionDisplayId) {
                    matched.mRecord = top;
                } else {
                    matched.mWrongDisplayId = displayContent.getDisplayId();
                }
                return matched;
            }
            return null;
        }
    }

    private Bundle startupSuccess(@NonNull String code, @NonNull String details,
            @NonNull ComponentName expectedComponent, int requestedDisplayId, int taskId,
            @NonNull ActivityRecord record) {
        final Bundle bundle = success(code, details);
        bundle.putInt(KEY_TASK_ID, taskId);
        bundle.putString(KEY_TOP_ACTIVITY, record.shortComponentName);
        bundle.putString(KEY_TOP_PACKAGE, record.packageName);
        bundle.putInt(KEY_REQUESTED_DISPLAY_ID, requestedDisplayId);
        bundle.putInt(KEY_BOUND_DISPLAY_ID, record.getDisplayId());
        bundle.putInt(KEY_DISPLAY_ID, record.getDisplayId());
        bundle.putString(KEY_RESOLVED_COMPONENT, expectedComponent.flattenToShortString());
        return bundle;
    }

    private Bundle startupError(@NonNull String code, @NonNull String details,
            int requestedDisplayId) {
        final Bundle bundle = error(code, details);
        bundle.putInt(KEY_TASK_ID, -1);
        bundle.putInt(KEY_REQUESTED_DISPLAY_ID, requestedDisplayId);
        bundle.putInt(KEY_BOUND_DISPLAY_ID, INVALID_DISPLAY);
        bundle.putInt(KEY_DISPLAY_ID, requestedDisplayId);
        return bundle;
    }

    private boolean isValidState(@Nullable String state) {
        return TextUtils.equals(STATE_EXECUTION, state)
                || TextUtils.equals(STATE_USER_HANDOFF, state)
                || TextUtils.equals(STATE_COMPLETED, state);
    }

    private int getEffectiveCallingUid(@Nullable Request request) {
        if (request == null) {
            return INVALID_UID;
        }
        if (request.realCallingUid != Request.DEFAULT_REAL_CALLING_UID) {
            return request.realCallingUid;
        }
        return request.callingUid;
    }

    private void addAdditionalDisplayAccessUid(int uid, @NonNull String reason) {
        if (uid == INVALID_UID || uid <= 0) {
            return;
        }
        if (mAdditionalDisplayAccessUids.contains(uid)) {
            return;
        }
        mAdditionalDisplayAccessUids.add(uid);
        Slog.i(TAG, "event=display_access_uid_added"
                + " sessionId=" + sanitize(mActiveSessionId)
                + " executionDisplayId=" + mExecutionDisplayId
                + " state=" + sanitize(mState)
                + " uid=" + uid
                + " reason=" + reason);
        requestImmediateDisplayAccessSync(uid, reason);
    }

    private void requestImmediateDisplayAccessSync(int uid, @NonNull String reason) {
        mService.mRootWindowContainer.updateUIDsPresentOnDisplayImmediately(uid, reason);
    }

    private void logLaunchDecision(@NonNull String event, int currentDisplayId,
            int resolvedDisplayId, @Nullable Task task, @Nullable ActivityRecord source,
            @NonNull ActivityRecord activityRecord, @Nullable Request request,
            int explicitDisplayId, int explicitTaskDisplayAreaDisplayId, @NonNull String reason) {
        Slog.i(TAG, "event=" + event
                + " sessionId=" + sanitize(mActiveSessionId)
                + " state=" + sanitize(mState)
                + " executionDisplayId=" + mExecutionDisplayId
                + " currentDisplayId=" + currentDisplayId
                + " resolvedDisplayId=" + resolvedDisplayId
                + " explicitDisplayId=" + explicitDisplayId
                + " explicitTaskDisplayAreaDisplayId=" + explicitTaskDisplayAreaDisplayId
                + " sourceDisplayId=" + (source != null ? source.getDisplayId() : INVALID_DISPLAY)
                + " taskDisplayId=" + (task != null ? task.getDisplayId() : INVALID_DISPLAY)
                + " callingUid=" + getEffectiveCallingUid(request)
                + " callingPackage=" + sanitize(request != null ? request.callingPackage : null)
                + " activityUid=" + activityRecord.getUid()
                + " activity=" + sanitize(activityRecord.shortComponentName)
                + " reason=" + reason);
    }

    private void logSessionEvent(@NonNull String event, @NonNull String code,
            @NonNull String details) {
        Slog.i(TAG, "event=" + event
                + " sessionId=" + sanitize(mActiveSessionId)
                + " executionDisplayId=" + mExecutionDisplayId
                + " state=" + sanitize(mState)
                + " registrantUid=" + mRegistrantUid
                + " ownerUid=" + mDisplayOwnerUid
                + " code=" + code
                + " details=" + sanitize(details));
    }

    private void logStartupDisplayAudit(@NonNull String event, @NonNull StartupRequest request,
            @Nullable ActivityRecord matchedRecord) {
        final DisplayContent displayContent = request.mDisplayContent;
        final TaskDisplayArea taskDisplayArea = request.mTaskDisplayArea;
        if (displayContent == null || taskDisplayArea == null) {
            return;
        }

        final Point realSize = new Point();
        displayContent.getDisplay().getRealSize(realSize);
        final int logicalWidth = displayContent.getDisplayInfo().logicalWidth;
        final int logicalHeight = displayContent.getDisplayInfo().logicalHeight;
        final int rotation = displayContent.getDisplayInfo().rotation;
        final Rect displayBounds = new Rect(displayContent.getBounds());
        final Rect appBounds = copyRect(
                displayContent.getConfiguration().windowConfiguration.getAppBounds());
        final Rect stableBounds = new Rect();
        displayContent.getStableRect(stableBounds);
        final DisplayPolicy.DecorInsets.Info decorInfo =
                displayContent.getDisplayPolicy().getDecorInsetsInfo(rotation,
                        logicalWidth, logicalHeight);
        final Rect taskDisplayAreaBounds = new Rect();
        taskDisplayArea.getBounds(taskDisplayAreaBounds);

        final ActivityRecord topActivity =
                matchedRecord != null ? matchedRecord : displayContent.topRunningActivity();
        final Task topTask = topActivity != null ? topActivity.getTask() : null;
        final Rect topTaskBounds = topTask != null ? new Rect(topTask.getBounds()) : new Rect();
        final Rect topActivityBounds = topActivity != null ? new Rect(topActivity.getBounds())
                : new Rect();
        final Rect topActivityAppBounds = topActivity != null
                ? copyRect(topActivity.getConfiguration().windowConfiguration.getAppBounds())
                : new Rect();
        final Rect topLetterboxInsets = topActivity != null
                ? new Rect(topActivity.getLetterboxInsets()) : new Rect();
        final WindowState mainWindow = topActivity != null ? topActivity.findMainWindow() : null;
        final Rect topWindowFrame = mainWindow != null ? new Rect(mainWindow.getFrame())
                : new Rect();
        final Rect topWindowBounds = mainWindow != null ? new Rect(mainWindow.getBounds())
                : new Rect();
        final Rect topWindowDisplayFrame = mainWindow != null
                ? new Rect(mainWindow.getDisplayFrame()) : new Rect();
        final Rect topWindowParentFrame = mainWindow != null
                ? new Rect(mainWindow.getParentFrame()) : new Rect();

        Slog.i(TAG, "event=" + event
                + " sessionId=" + sanitize(mActiveSessionId)
                + " executionDisplayId=" + mExecutionDisplayId
                + " state=" + sanitize(mState)
                + " requestedDisplayId=" + request.mRequestedDisplayId
                + " mode=" + sanitize(request.mMode)
                + " realSize=" + realSize.x + "x" + realSize.y
                + " logicalSize=" + logicalWidth + "x" + logicalHeight
                + " displayBounds=" + rectToString(displayBounds)
                + " appBounds=" + rectToString(appBounds)
                + " stableBounds=" + rectToString(stableBounds)
                + " decorInsets=" + rectToString(decorInfo != null
                        ? decorInfo.mNonDecorInsets : null)
                + " configInsets=" + rectToString(decorInfo != null
                        ? decorInfo.mConfigInsets : null)
                + " nonDecorFrame=" + rectToString(decorInfo != null
                        ? decorInfo.mNonDecorFrame : null)
                + " configFrame=" + rectToString(decorInfo != null
                        ? decorInfo.mConfigFrame : null)
                + " taskDisplayAreaBounds=" + rectToString(taskDisplayAreaBounds)
                + " displayTrusted=" + displayContent.isTrusted()
                + " displayPrivate=" + displayContent.isPrivate()
                + " supportsSystemDecorations=" + displayContent.supportsSystemDecorations()
                + " topTaskId=" + (topTask != null ? topTask.mTaskId : -1)
                + " topTaskBounds=" + rectToString(topTaskBounds)
                + " topActivity=" + sanitize(topActivity != null
                        ? topActivity.shortComponentName : null)
                + " topPackage=" + sanitize(topActivity != null ? topActivity.packageName : null)
                + " topActivityBounds=" + rectToString(topActivityBounds)
                + " topActivityAppBounds=" + rectToString(topActivityAppBounds)
                + " topInSizeCompat=" + (topActivity != null && topActivity.inSizeCompatMode())
                + " topLetterboxInsets=" + rectToString(topLetterboxInsets)
                + " topWindowFrame=" + rectToString(topWindowFrame)
                + " topWindowBounds=" + rectToString(topWindowBounds)
                + " topWindowDisplayFrame=" + rectToString(topWindowDisplayFrame)
                + " topWindowParentFrame=" + rectToString(topWindowParentFrame));
    }

    private Bundle success(@NonNull String code, @NonNull String details) {
        final Bundle bundle = new Bundle();
        bundle.putString(KEY_CODE, code);
        bundle.putString(KEY_DETAILS, details);
        return bundle;
    }

    private Bundle error(@NonNull String code, @NonNull String details) {
        Slog.w(TAG, "event=execution_display_session_error"
                + " sessionId=" + sanitize(mActiveSessionId)
                + " executionDisplayId=" + mExecutionDisplayId
                + " state=" + sanitize(mState)
                + " registrantUid=" + mRegistrantUid
                + " ownerUid=" + mDisplayOwnerUid
                + " code=" + code
                + " details=" + sanitize(details));
        final Bundle bundle = new Bundle();
        bundle.putString(KEY_CODE, code);
        bundle.putString(KEY_DETAILS, details);
        return bundle;
    }

    private boolean contains(@NonNull IntArray uidArray, int uid) {
        for (int i = uidArray.size() - 1; i >= 0; i--) {
            if (uidArray.get(i) == uid) {
                return true;
            }
        }
        return false;
    }

    private void reset() {
        mActiveSessionId = null;
        mState = null;
        mExecutionDisplayId = INVALID_DISPLAY;
        mDisplayOwnerUid = INVALID_UID;
        mRegistrantUid = INVALID_UID;
        mAdditionalDisplayAccessUids.clear();
    }

    @NonNull
    private String formatUids(@Nullable IntArray values) {
        if (values == null || values.size() == 0) {
            return "[]";
        }
        final StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values.get(i));
        }
        return builder.append(']').toString();
    }

    private String sanitize(@Nullable String value) {
        return value == null ? "null" : value.replace('\n', ' ');
    }

    private Rect copyRect(@Nullable Rect value) {
        return value != null ? new Rect(value) : new Rect();
    }

    private String rectToString(@Nullable Rect value) {
        return value != null ? value.toShortString() : "null";
    }

    private static final class StartupRequest {
        @Nullable Bundle mError;
        @Nullable DisplayContent mDisplayContent;
        @Nullable TaskDisplayArea mTaskDisplayArea;
        @Nullable ActivityInfo mResolvedActivityInfo;
        @Nullable Intent mStartupIntent;
        @Nullable ComponentName mExpectedComponent;
        int mRequestedDisplayId = INVALID_DISPLAY;
        int mExecutionDisplayId = INVALID_DISPLAY;
        int mUserId = UserHandle.USER_NULL;
        @NonNull String mMode = "";
        @NonNull String mComponentName = "";
        @NonNull String mReason = "";
    }

    private static final class MatchedActivity {
        @Nullable ActivityRecord mRecord;
        int mWrongDisplayId = INVALID_DISPLAY;
    }
}
