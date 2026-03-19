# Model Regression Runbook

Short device-side runbook for validating AgentHost `MODEL` planning against the local mock gateway.

## Preconditions

- Debug app is built and deployed to the board.
- `adb devices -l` shows the RK3588 device as `device`.
- Host machine has `python3` available.
- Use `scripts/verify_p25.sh regression` for the original scripted regression; this runbook only covers `smoke-model` / `model-regression`.

## Recommended path

- Local mock server: prefer `adb reverse`, so the device uses `http://127.0.0.1:8080`.
- Real remote gateway: do **not** rely on `adb reverse`; pass `MOCK_SERVER_IP=<server-ip>` explicitly.
- Real BigModel upstream: keep the device pointed at the local proxy `http://127.0.0.1:8080/plan`; configure the proxy with `BIGMODEL_API_KEY` (or `UPSTREAM_API_KEY`), `BIGMODEL_BASE_URL=https://open.bigmodel.cn/api/paas/v4`, and `BIGMODEL_MODEL=glm-4.6v`.

## Mock server startup

- Recommended: let `scripts/verify_p25.sh smoke-model ...` or `model-regression` auto-start the mock server when needed.
- Manual standalone start if you want to inspect requests directly:
  - `python3 scripts/mock_generic_gateway.py --host 0.0.0.0 --port 8080`

## Fixed logcat command

- `adb logcat -v time -s SessionAuditReceiver SessionCoordinator SessionAutomationEngine PlannerSelector ModelPlanningAdapter HttpModelClient`

## Single-case commands

- `scripts/verify_p25.sh smoke-model click`
- `scripts/verify_p25.sh smoke-model ask-user`
- `scripts/verify_p25.sh smoke-model home`
- `scripts/verify_p25.sh smoke-model invalid`
- `scripts/verify_p25.sh smoke-model timeout`

## Full suite

- `scripts/verify_p25.sh model-regression`

## Expected results

- `click`: planner selects `MODEL`, mock receives `/plan?mode=click`, action executes successfully.
- `ask-user`: session enters `WAITING_USER`, `REPLY_SESSION` is sent, then session reaches `COMPLETED`.
- `home`: session fails with `MODEL_OUTPUT_UNSUPPORTED_ACTION`.
- `invalid`: session fails with `MODEL_OUTPUT_INVALID`.
- `timeout`: session fails with `MODEL_TIMEOUT`.
- `model-regression`: all five cases pass in order, and the mock server is cleaned up at the end.

## Common troubleshooting

- `Cleartext HTTP traffic ... not permitted`
  - Make sure the deployed build includes `android:usesCleartextTraffic="true"`.
- `Failed to connect to /127.0.0.1:8080` or no mock requests arrive
  - Check `adb reverse --list`.
  - Re-run the smoke command; the script should try `adb reverse tcp:8080 tcp:8080` automatically.
  - If you are using a real remote gateway, set `MOCK_SERVER_IP=<server-ip>` and do not use reverse.
- `missing log pattern`
  - Keep the fixed logcat command running and inspect `plannerMode`, `planner_result`, and `session_failed` lines.
- Device is not ready
  - Confirm `adb devices -l`, then redeploy if needed with `AUTO_VALIDATE=0 ./scripts/sign_deploy.sh`.

## Case matrix

| Case | Command | Expected result |
| --- | --- | --- |
| `click` | `scripts/verify_p25.sh smoke-model click` | `CLICK` planned and executed |
| `ask-user` | `scripts/verify_p25.sh smoke-model ask-user` | `WAITING_USER -> REPLY -> COMPLETED` |
| `home` | `scripts/verify_p25.sh smoke-model home` | `MODEL_OUTPUT_UNSUPPORTED_ACTION` |
| `invalid` | `scripts/verify_p25.sh smoke-model invalid` | `MODEL_OUTPUT_INVALID` |
| `timeout` | `scripts/verify_p25.sh smoke-model timeout` | `MODEL_TIMEOUT` |
| `all` | `scripts/verify_p25.sh model-regression` | all five cases pass |
