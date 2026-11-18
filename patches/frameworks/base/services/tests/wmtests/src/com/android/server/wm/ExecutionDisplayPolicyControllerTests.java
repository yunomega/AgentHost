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

import static android.view.Display.FLAG_PRIVATE;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.util.IntArray;
import android.util.SparseArray;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import com.android.server.wm.ActivityStarter.Request;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link ExecutionDisplayPolicyController}.
 *
 * Build/Install/Run:
 *  atest WmTests:ExecutionDisplayPolicyControllerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ExecutionDisplayPolicyControllerTests extends WindowTestsBase {

    @Test
    public void testRegisterUpdateAndClearLifecycle() {
        final int registrantUid = 13001;
        final TestDisplayContent executionDisplay = createPrivateDisplay(23001);
        final ExecutionDisplayPolicyController controller = mAtm.mExecutionDisplayPolicyController;

        final Bundle registered = controller.registerExecutionDisplaySession(
                "session-lifecycle", executionDisplay.mDisplayId, registrantUid);

        assertThat(registered.getString("code")).isEqualTo("EXECUTION_SESSION_REGISTERED");
        assertThat(controller.getActiveSessionId()).isEqualTo("session-lifecycle");
        assertThat(controller.getExecutionDisplayId()).isEqualTo(executionDisplay.mDisplayId);
        assertThat(controller.getState())
                .isEqualTo(ExecutionDisplayPolicyController.STATE_EXECUTION);

        final Bundle updated = controller.updateExecutionDisplaySessionState(
                "session-lifecycle", executionDisplay.mDisplayId,
                ExecutionDisplayPolicyController.STATE_USER_HANDOFF, "test");
        assertThat(updated.getString("code")).isEqualTo("EXECUTION_SESSION_UPDATED");
        assertThat(controller.getState())
                .isEqualTo(ExecutionDisplayPolicyController.STATE_USER_HANDOFF);

        final Bundle cleared = controller.clearExecutionDisplaySession(
                "session-lifecycle", "done");
        assertThat(cleared.getString("code")).isEqualTo("EXECUTION_SESSION_CLEARED");
        assertThat(controller.getActiveSessionId()).isNull();
        assertThat(controller.getExecutionDisplayId()).isEqualTo(-1);
    }

    @Test
    public void testRegisterImmediatelyPushesRegistrantUidToDisplayAccessAllowlist() {
        final int registrantUid = 13002;
        final TestDisplayContent executionDisplay = createPrivateDisplay(23002);
        final ExecutionDisplayPolicyController controller = mAtm.mExecutionDisplayPolicyController;

        controller.registerExecutionDisplaySession(
                "session-register-sync", executionDisplay.mDisplayId, registrantUid);

        assertThat(mRootWindowContainer.isUidListedInDisplayAccessAllowlist(
                executionDisplay.mDisplayId, registrantUid)).isTrue();
    }

    @Test
    public void testAppendAdditionalDisplayAccessUidsIncludesLaunchTargetUid() {
        final int registrantUid = 13003;
        final int targetUid = 14003;
        final TestDisplayContent executionDisplay = createPrivateDisplay(23003);
        final ExecutionDisplayPolicyController controller = mAtm.mExecutionDisplayPolicyController;
        final ActivityRecord target = new ActivityBuilder(mAtm).setUid(targetUid).build();
        final Request request = new Request();
        request.callingPackage = "com.example.agenthost";
        request.realCallingUid = registrantUid;

        controller.registerExecutionDisplaySession(
                "session-access", executionDisplay.mDisplayId, registrantUid);
        controller.adjustLaunchTaskDisplayArea(null /* currentTaskDisplayArea */, null /* task */,
                null /* options */, null /* source */, target, request);

        final SparseArray<IntArray> accessUids = new SparseArray<>();
        controller.appendAdditionalDisplayAccessUids(accessUids);

        assertThat(contains(accessUids.get(executionDisplay.mDisplayId), registrantUid)).isTrue();
        assertThat(contains(accessUids.get(executionDisplay.mDisplayId), targetUid)).isTrue();
        assertThat(mRootWindowContainer.isUidListedInDisplayAccessAllowlist(
                executionDisplay.mDisplayId, registrantUid)).isTrue();
        assertThat(mRootWindowContainer.isUidListedInDisplayAccessAllowlist(
                executionDisplay.mDisplayId, targetUid)).isTrue();
    }

    @Test
    public void testClearImmediatelyRemovesAdditionalDisplayAccessUids() {
        final int registrantUid = 13004;
        final int targetUid = 14004;
        final TestDisplayContent executionDisplay = createPrivateDisplay(23004);
        final ExecutionDisplayPolicyController controller = mAtm.mExecutionDisplayPolicyController;
        final ActivityRecord target = new ActivityBuilder(mAtm).setUid(targetUid).build();
        final Request request = new Request();
        request.callingPackage = "com.example.agenthost";
        request.realCallingUid = registrantUid;

        controller.registerExecutionDisplaySession(
                "session-clear-sync", executionDisplay.mDisplayId, registrantUid);
        controller.adjustLaunchTaskDisplayArea(null /* currentTaskDisplayArea */, null /* task */,
                null /* options */, null /* source */, target, request);

        assertThat(mRootWindowContainer.isUidListedInDisplayAccessAllowlist(
                executionDisplay.mDisplayId, targetUid)).isTrue();

        controller.clearExecutionDisplaySession("session-clear-sync", "done");

        assertThat(mRootWindowContainer.isUidListedInDisplayAccessAllowlist(
                executionDisplay.mDisplayId, registrantUid)).isFalse();
        assertThat(mRootWindowContainer.isUidListedInDisplayAccessAllowlist(
                executionDisplay.mDisplayId, targetUid)).isFalse();
    }

    @Test
    public void testStartupFailureKeepsRequestedDisplayId() {
        final ExecutionDisplayPolicyController controller = mAtm.mExecutionDisplayPolicyController;

        final Bundle result = controller.startExecutionDisplayStartupTarget(
                "missing-session", 23005,
                ExecutionDisplayPolicyController.STARTUP_MODE_SECONDARY_HOME,
                null /* componentName */, "test");

        assertThat(result.getString("code")).isEqualTo("EXECUTION_SESSION_NOT_ACTIVE");
        assertThat(result.getInt("requestedDisplayId")).isEqualTo(23005);
        assertThat(result.getInt("boundDisplayId")).isEqualTo(-1);
        assertThat(result.getInt("displayId")).isEqualTo(23005);
    }

    @Test
    public void testExplicitStartupOnUntrustedPrivateDisplayDoesNotRequireTrustedDisplay() {
        final int registrantUid = 13005;
        final TestDisplayContent executionDisplay = createPrivateDisplay(23006);
        final ExecutionDisplayPolicyController controller = mAtm.mExecutionDisplayPolicyController;

        controller.registerExecutionDisplaySession(
                "session-explicit", executionDisplay.mDisplayId, registrantUid);

        final Bundle result = controller.startExecutionDisplayStartupTarget(
                "session-explicit", executionDisplay.mDisplayId,
                ExecutionDisplayPolicyController.STARTUP_MODE_EXPLICIT_COMPONENT_ON_DISPLAY,
                "com.example.missing/.MissingActivity", "test");

        assertThat(result.getString("code"))
                .isEqualTo("EXECUTION_DISPLAY_STARTUP_COMPONENT_NOT_FOUND");
        assertThat(result.getInt("requestedDisplayId")).isEqualTo(executionDisplay.mDisplayId);
        assertThat(result.getInt("boundDisplayId")).isEqualTo(-1);
    }

    private TestDisplayContent createPrivateDisplay(int ownerUid) {
        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.flags |= FLAG_PRIVATE;
        displayInfo.ownerUid = ownerUid;
        return new TestDisplayContent.Builder(mAtm, displayInfo).build();
    }

    private boolean contains(IntArray values, int target) {
        if (values == null) {
            return false;
        }
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i) == target) {
                return true;
            }
        }
        return false;
    }
}
