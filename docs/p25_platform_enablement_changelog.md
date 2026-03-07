# P2.5 Platform Enablement Changelog

## Scope

This change set delivers the minimum platform capability layer required by P2.5 without changing Phase 1 or Phase 2 semantics:

- stable `captureDisplay(displayId)` for VirtualDisplay
- stable display-scoped input injection for `tap` / `swipe` / `key` / `text` / `longPress`
- explicit permission boundary, structured logging, and dumpsys visibility
- app-side adapter swap only; no planner or state-machine rewrite

## Framework-side

### New binder protocol

- Added `IAgentHostPlatformService`:
  - `/home/alientek/rk3588_android_sdk/frameworks/base/core/java/com/example/agenthost/platform/IAgentHostPlatformService.aidl`
- Binder surface is intentionally small and display-scoped:
  - `captureDisplay(sessionId, displayId)`
  - `injectTap(sessionId, displayId, x, y)`
  - `injectSwipe(sessionId, displayId, x1, y1, x2, y2, durationMs)`
  - `injectKey(sessionId, displayId, keyCode)`
  - `injectText(sessionId, displayId, text)`
  - `injectLongPress(sessionId, displayId, x, y, durationMs)`

### New system service

- Added `AgentHostPlatformService`:
  - `/home/alientek/rk3588_android_sdk/frameworks/base/services/core/java/com/android/server/agenthost/AgentHostPlatformService.java`
- Registered service name:
  - `agenthost_platform`
- Started from `SystemServer` after `DisplayManagerService`
- Exposed to app side via `SystemServiceRegistry` as raw `IBinder`

### Capture behavior

- Reuses `DisplayManagerInternal.userScreenshot(displayId)`
- Returns PNG via `ParcelFileDescriptor` in `Bundle`
- Supports VirtualDisplay `displayId`
- Returns explicit result codes:
  - `OK`
  - `DISPLAY_NOT_FOUND`
  - `CAPTURE_FAILED`
  - `PERMISSION_DENIED`
  - `UNSUPPORTED`
  - `INTERNAL_ERROR`
- Fixed runtime screenshot conversion issue by copying hardware bitmap before PNG encoding

### Input behavior

- Injects `MotionEvent` / `KeyEvent` with explicit `displayId`
- Uses `InputManager.injectInputEvent(...WAIT_FOR_FINISH)`
- No shell fallback
- No default-display fallback
- `BACK` is supported as display-scoped key injection
- `HOME` is intentionally rejected with `UNSUPPORTED`
- `TEXT` uses `KeyCharacterMap.getEvents()` and is currently scoped to basic stable text entry
- Input result codes:
  - `OK`
  - `DISPLAY_NOT_FOUND`
  - `INJECTION_REJECTED`
  - `PERMISSION_DENIED`
  - `UNSUPPORTED`
  - `INTERNAL_ERROR`

### Dumpsys and logs

- Added ring-buffered structured service history via:
  - `dumpsys agenthost_platform`
- Platform log events:
  - `platform_capture_begin`
  - `platform_capture_success`
  - `platform_capture_failed`
  - `platform_inject_begin`
  - `platform_inject_success`
  - `platform_inject_failed`

## App-side

### New platform adapters

- Added app AIDL mirror:
  - `app/src/main/aidl/com/example/agenthost/platform/IAgentHostPlatformService.aidl`
- Added platform client:
  - `app/src/main/java/com/example/agenthost/platform/AgentHostPlatformClient.kt`
- Added observation adapter:
  - `app/src/main/java/com/example/agenthost/automation/PlatformObservationProvider.kt`
- Added input executor adapter:
  - `app/src/main/java/com/example/agenthost/automation/PlatformDisplayActionExecutor.kt`

### Wiring change only at capability layer

- `SessionCoordinator` now wires platform provider/executor instead of shell-based primary path
- `SessionAutomationEngine`, `ScriptedStepPlanningAdapter`, Phase 1 migration/close logic, and Phase 2 state semantics remain unchanged

### Executor contract update

- `DisplayActionExecutor` now takes `ActionExecutionRequest(sessionId, vdDisplayId, action)` so session/display context reaches the platform layer without changing engine semantics

### Failure semantics

- App adapters propagate platform result codes directly
- No fallback to shell `input`
- No fallback to default display
- Engine short-circuits retries for:
  - `UNSUPPORTED`
  - `PERMISSION_DENIED`
  - `DISPLAY_NOT_FOUND`

### App logs

- Adapter log events now include:
  - `observation_provider_selected`
  - `display_executor_selected`
  - `platform_call_begin`
  - `platform_call_failed`
  - upper-layer `action_executed`

### Test support

- Enabled AIDL generation in app module
- Added `AgentHostLog` wrapper so JVM tests do not fail on direct `android.util.Log` access
- Added/updated platform-focused unit tests for:
  - error-code forwarding
  - no-fallback behavior
  - missing PNG payload failure

## SEPolicy and permissions

### Permission boundary

- Added privileged permission:
  - `com.example.agenthost.permission.ACCESS_PLATFORM_AUTOMATION`
- Protection level:
  - `signature|privileged`
- Service also checks calling UID packages contain `com.example.agenthost`
- Unauthorized callers receive `PERMISSION_DENIED`

### SELinux service registration

- Added service type:
  - `agenthost_platform_service`
- Added service context:
  - `agenthost_platform -> u:object_r:agenthost_platform_service:s0`
- Updated both current private policy and API 33 prebuilts

## Regression order

The fixed P2.5 regression order is now:

1. `service-check`
2. `dumpsys`
3. `unauthorized-check`
4. `capture`
5. `click`
6. `swipe`
7. `back`
8. `home`
9. `text`
10. `ask-reply`

Use this command for the standard regression pass:

- `scripts/verify_p25.sh regression`

Additional optional check kept outside the fixed order:

- `scripts/verify_p25.sh long-press`

## Validation results

Validated on device `ATK_DLRK3588-userdebug`:

- `service check agenthost_platform`: pass
- `dumpsys agenthost_platform`: pass
- unauthorized caller: `PERMISSION_DENIED`
- `captureDisplay(displayId)`: pass
- `CLICK`: pass
- `SWIPE`: pass
- `BACK`: pass
- `HOME`: explicit `UNSUPPORTED` by platform definition
- `TYPE_TEXT` with `hello123`: pass
- `ASK_USER -> REPLY -> TYPE_TEXT -> DONE -> STOP`: pass
- `LONG_PRESS`: pass as supplemental capability check

## Protocol freeze for Phase 3

The following interface is now considered frozen for Phase 3 consumption and should not be changed unless a new platform phase explicitly reopens the contract:

### Frozen binder methods

- `captureDisplay`
- `injectTap`
- `injectSwipe`
- `injectKey`
- `injectText`
- `injectLongPress`

### Frozen result codes

- `OK`
- `DISPLAY_NOT_FOUND`
- `CAPTURE_FAILED`
- `PERMISSION_DENIED`
- `UNSUPPORTED`
- `INTERNAL_ERROR`
- `INJECTION_REJECTED`

### Frozen app-side adapter boundary

- `PlatformAutomationGateway`
- `PlatformObservationProvider`
- `PlatformDisplayActionExecutor`
- `ActionExecutionRequest(sessionId, vdDisplayId, action)`

### Phase 3 rule

Phase 3 must consume this platform capability layer as-is and should not reopen binder naming, error-code semantics, or the app adapter abstraction while integrating the real planner.
