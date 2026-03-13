#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PKG="${PKG:-com.example.agenthost}"
RECV="${RECV:-${PKG}/.debug.SessionAuditReceiver}"
ADB_SERIAL="${ADB_SERIAL:-}"
CASE_SELECTOR="${CASE:-all}"
AUTO_CONFIRM="${AUTO_CONFIRM:-}"
ENABLE_MANUAL_CONFIRM="${ENABLE_MANUAL_CONFIRM:-1}"
LOG_DIR="${LOG_DIR:-$(mktemp -d /tmp/agenthost-task-migration-concurrency.XXXXXX)}"
WAIT_TIMEOUT_SECS="${WAIT_TIMEOUT_SECS:-35}"
POLL_INTERVAL_SECS="${POLL_INTERVAL_SECS:-1}"
FOREGROUND_POLL_SECS="${FOREGROUND_POLL_SECS:-0.5}"
RUN_STABILITY="${RUN_STABILITY:-0}"
STABILITY_RUNS="${STABILITY_RUNS:-5}"
STABILITY_CASES="${STABILITY_CASES:-concurrent_click concurrent_swipe concurrent_back_home concurrent_capture_inject migration_boundary_isolation}"
TEXT_FIELD_X="${TEXT_FIELD_X:-540}"
TEXT_FIELD_Y="${TEXT_FIELD_Y:-180}"
MAIN_TEXT_FIELD_X="${MAIN_TEXT_FIELD_X:-540}"
MAIN_TEXT_FIELD_Y="${MAIN_TEXT_FIELD_Y:-180}"
EXEC_TEXT_VALUE="${EXEC_TEXT_VALUE:-virtual123}"
MAIN_TEXT_VALUE="${MAIN_TEXT_VALUE:-main123}"

LOG_TAGS=(
  SessionCoordinator
  SessionAuditReceiver
  TaskMigrationController
  AtmHiddenApi
  SessionRootActivity
  SessionAutomationEngine
  AgentHostPlatformClient
  PlatformDisplayExecutor
  PlatformObservationProvider
)

SESSION_ID=""
EXECUTION_DISPLAY_ID=""
CURRENT_CASE_NAME=""
CURRENT_CASE_DIR=""
CURRENT_ITERATION=1
SAMPLER_PID=""
SAMPLER_STOP_FILE=""
DRIVER_PID=""
DRIVER_STOP_FILE=""

log() {
  echo "[INFO] $*"
}

ok() {
  echo "[ OK ] $*"
}

warn() {
  echo "[WARN] $*" >&2
}

adb_cmd() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    adb -s "${ADB_SERIAL}" "$@"
  else
    adb "$@"
  fi
}

extract_broadcast_json() {
  local output="$1"
  printf '%s\n' "${output}" | sed -n 's/.*data="\({.*}\)".*/\1/p'
}

extract_json_string() {
  local json="$1"
  local key="$2"
  printf '%s\n' "${json}" | sed -n "s/.*\"${key}\":\"\\([^\"]*\\)\".*/\\1/p"
}

extract_json_bool() {
  local json="$1"
  local key="$2"
  printf '%s\n' "${json}" | sed -n "s/.*\"${key}\":\\(true\\|false\\).*/\\1/p"
}

extract_json_number() {
  local json="$1"
  local key="$2"
  printf '%s\n' "${json}" | sed -n "s/.*\"${key}\":\\(-\\{0,1\\}[0-9][0-9]*\\).*/\\1/p"
}

prompt_yes_no() {
  local prompt="$1"
  local answer=""
  if [[ "${ENABLE_MANUAL_CONFIRM}" != "1" ]]; then
    return 0
  fi
  if [[ -n "${AUTO_CONFIRM}" ]]; then
    answer="${AUTO_CONFIRM}"
    echo "${prompt} [YES/NO]: ${answer}"
  else
    read -r -p "${prompt} [YES/NO]: " answer
  fi
  case "${answer}" in
    YES|yes|Y|y) return 0 ;;
    NO|no|N|n) return 1 ;;
    *) echo "[ERR ] expected YES or NO, got '${answer}'" >&2; return 2 ;;
  esac
}

record_failure() {
  local class_name="$1"
  local summary="$2"
  local evidence="$3"
  mkdir -p "${CURRENT_CASE_DIR}"
  cat > "${CURRENT_CASE_DIR}/failure_report.txt" <<EOF
case=${CURRENT_CASE_NAME}
iteration=${CURRENT_ITERATION}
class=${class_name}
summary=${summary}
evidence=${evidence}
template=$(failure_template "${class_name}")
EOF
  echo
  echo "[ERR ] case=${CURRENT_CASE_NAME} iteration=${CURRENT_ITERATION} class=${class_name}"
  echo "summary=${summary}"
  echo "evidence=${evidence}"
  echo "template=$(failure_template "${class_name}")"
  exit 1
}

failure_template() {
  local class_name="$1"
  case "${class_name}" in
    inject_cross_display)
      echo "inject 串屏: injectDisplayId 未命中原始 executionDisplayId，需检查 platform_call_* 与 action_executed 日志。"
      ;;
    capture_cross_display)
      echo "capture 串屏: captureDisplayId/observationDisplayId 未命中原始 executionDisplayId，需检查平台 capture 与 observation 日志。"
      ;;
    main_foreground_stolen)
      echo "主屏前台被抢: 未 handoff 阶段 display 0 出现 AgentHost/SessionRoot 或意外接手真实任务，需检查 foreground sampler 与 migration 日志。"
      ;;
    dump_normal_manual_anomaly)
      echo "dump 正常但人工体感异常: 自动日志未发现 display 串扰，但人工观察到 focus 抖动、Recent 闪现、输入法被切走等异常。"
      ;;
    main_display_not_applicable_violation)
      echo "MAIN_DISPLAY 不适用断言失败: 主屏模式错误进入了 VIRTUAL_DISPLAY 专属隔离/迁移检查路径。"
      ;;
    *)
      echo "未知失败类型: 需结合 case artifacts 手工定位。"
      ;;
  esac
}

clear_logs() {
  adb_cmd logcat -c || true
}

logcat_dump() {
  adb_cmd logcat -d -v time -s "${LOG_TAGS[@]}"
}

capture_device_log() {
  local dest="$1"
  logcat_dump > "${dest}"
}

broadcast_start_session() {
  local payload="$1"
  local display_mode="$2"
  local bootstrap_target="$3"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.START_SESSION' --es command '${payload}' --es session_display_mode '${display_mode}' --es bootstrap_target '${bootstrap_target}'"
}

broadcast_dump_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.DUMP_ACTIVE_SESSION'"
}

broadcast_reply_session() {
  local session_id="$1"
  local user_text="${2:-YES}"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.REPLY_SESSION' --es session_id '${session_id}' --es user_text '${user_text}'"
}

broadcast_stop_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.STOP_SESSION' --ez migrate false"
}

broadcast_migrate_to_main() {
  local session_id="$1"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.MIGRATE_TO_MAIN' --es session_id '${session_id}'"
}

broadcast_migrate_to_execution() {
  local session_id="$1"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.MIGRATE_TO_EXECUTION' --es session_id '${session_id}'"
}

wait_for_log_pattern() {
  local pattern="$1"
  local timeout_secs="${2:-${WAIT_TIMEOUT_SECS}}"
  local elapsed=0
  while (( elapsed < timeout_secs )); do
    if logcat_dump | grep -E -q "${pattern}"; then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECS}"
    elapsed=$((elapsed + POLL_INTERVAL_SECS))
  done
  return 1
}

wait_for_dump_status() {
  local expected_status="$1"
  local timeout_secs="${2:-${WAIT_TIMEOUT_SECS}}"
  local elapsed=0
  while (( elapsed < timeout_secs )); do
    local output json status
    output="$(broadcast_dump_session)"
    json="$(extract_broadcast_json "${output}")"
    status="$(extract_json_string "${json}" "status")"
    if [[ "${status}" == "${expected_status}" ]]; then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECS}"
    elapsed=$((elapsed + POLL_INTERVAL_SECS))
  done
  return 1
}

best_effort_stop_active() {
  local output json status
  output="$(broadcast_dump_session 2>/dev/null || true)"
  json="$(extract_broadcast_json "${output}")"
  status="$(extract_json_string "${json}" "status")"
  if [[ -n "${status}" && "${status}" != "NO_ACTIVE_SESSION" ]]; then
    warn "stopping leftover active session"
    broadcast_stop_session >/dev/null || true
    sleep 1
  fi
}

assert_non_empty() {
  local value="$1"
  local message="$2"
  if [[ -z "${value}" ]]; then
    record_failure "dump_normal_manual_anomaly" "${message}" "${CURRENT_CASE_DIR}"
  fi
}

assert_equals() {
  local actual="$1"
  local expected="$2"
  local message="$3"
  local class_name="${4:-dump_normal_manual_anomaly}"
  if [[ "${actual}" != "${expected}" ]]; then
    record_failure "${class_name}" "${message}: expected='${expected}' actual='${actual}'" "${CURRENT_CASE_DIR}"
  fi
}

capture_foreground_context_once() {
  local dest="$1"
  {
    echo "=== foreground_activity ==="
    adb_cmd shell dumpsys activity activities | awk '
      /displayId=/ { current=$0 }
      /topResumedActivity=|Resumed:/ {
        if (current == "") current="displayId=unknown"
        print current " :: " $0
      }
    '
    echo "=== foreground_window ==="
    adb_cmd shell dumpsys window displays | awk '
      /Display #[0-9]+/ { current=$0 }
      /displayId=/ { current=$0 }
      /mCurrentFocus=|mFocusedApp=|mObscuringWindow=/ {
        if (current == "") current="display=unknown"
        print current " :: " $0
      }
    '
  } > "${dest}"
}

start_foreground_sampler() {
  local dest="$1"
  SAMPLER_STOP_FILE="${dest}.stop"
  rm -f "${SAMPLER_STOP_FILE}"
  (
    local sample=0
    while [[ ! -f "${SAMPLER_STOP_FILE}" ]]; do
      {
        echo "=== sample=${sample} ts=$(date '+%Y-%m-%d %H:%M:%S') ==="
        capture_foreground_context_once "/dev/stdout"
      } >> "${dest}" 2>&1
      sample=$((sample + 1))
      sleep "${FOREGROUND_POLL_SECS}"
    done
  ) &
  SAMPLER_PID="$!"
}

stop_foreground_sampler() {
  if [[ -n "${SAMPLER_STOP_FILE}" ]]; then
    touch "${SAMPLER_STOP_FILE}" || true
  fi
  if [[ -n "${SAMPLER_PID}" ]]; then
    wait "${SAMPLER_PID}" 2>/dev/null || true
  fi
  SAMPLER_PID=""
  SAMPLER_STOP_FILE=""
}

drive_main_actions() {
  local mode="$1"
  local log_file="$2"
  DRIVER_STOP_FILE="${log_file}.stop"
  rm -f "${DRIVER_STOP_FILE}"
  (
    local cycle=0
    while [[ ! -f "${DRIVER_STOP_FILE}" ]]; do
      case "${mode}" in
        click)
          adb_cmd shell input keyevent HOME >/dev/null 2>&1 || true
          adb_cmd shell input tap 120 120 >/dev/null 2>&1 || true
          adb_cmd shell input tap 540 960 >/dev/null 2>&1 || true
          adb_cmd shell input tap 900 1700 >/dev/null 2>&1 || true
          ;;
        swipe)
          adb_cmd shell input keyevent HOME >/dev/null 2>&1 || true
          adb_cmd shell input swipe 300 1500 300 350 220 >/dev/null 2>&1 || true
          adb_cmd shell input swipe 900 950 120 950 220 >/dev/null 2>&1 || true
          ;;
        back_home)
          adb_cmd shell input keyevent BACK >/dev/null 2>&1 || true
          adb_cmd shell input keyevent HOME >/dev/null 2>&1 || true
          ;;
        text)
          adb_cmd shell am start -a android.settings.SETTINGS >/dev/null 2>&1 || true
          sleep 1
          adb_cmd shell input tap "${MAIN_TEXT_FIELD_X}" "${MAIN_TEXT_FIELD_Y}" >/dev/null 2>&1 || true
          sleep 0.5
          adb_cmd shell input text "${MAIN_TEXT_VALUE}" >/dev/null 2>&1 || true
          ;;
        app_switch)
          adb_cmd shell input keyevent HOME >/dev/null 2>&1 || true
          adb_cmd shell am start -a android.settings.SETTINGS >/dev/null 2>&1 || true
          sleep 0.8
          adb_cmd shell input keyevent HOME >/dev/null 2>&1 || true
          ;;
        *)
          ;;
      esac
      echo "cycle=${cycle} mode=${mode}" >> "${log_file}"
      cycle=$((cycle + 1))
      sleep 0.6
    done
  ) &
  DRIVER_PID="$!"
}

stop_main_driver() {
  if [[ -n "${DRIVER_STOP_FILE}" ]]; then
    touch "${DRIVER_STOP_FILE}" || true
  fi
  if [[ -n "${DRIVER_PID}" ]]; then
    wait "${DRIVER_PID}" 2>/dev/null || true
  fi
  DRIVER_PID=""
  DRIVER_STOP_FILE=""
}

cleanup_background_jobs() {
  stop_main_driver
  stop_foreground_sampler
}

cleanup_on_exit() {
  local rc="$?"
  set +e
  cleanup_background_jobs
  best_effort_stop_active
  if (( rc == 0 )); then
    return 0
  fi
  return "${rc}"
}

trap cleanup_on_exit EXIT

start_session_and_capture_ids() {
  local payload="$1"
  local display_mode="$2"
  local bootstrap_target="$3"
  local output json ok_value code status display_mode_value

  output="$(broadcast_start_session "${payload}" "${display_mode}" "${bootstrap_target}")"
  printf '%s\n' "${output}" > "${CURRENT_CASE_DIR}/start_session.stdout.log"
  json="$(extract_broadcast_json "${output}")"
  assert_non_empty "${json}" "failed to parse start_session broadcast result"

  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  status="$(extract_json_string "${json}" "status")"
  display_mode_value="$(extract_json_string "${json}" "displayMode")"
  SESSION_ID="$(extract_json_string "${json}" "sessionId")"
  EXECUTION_DISPLAY_ID="$(extract_json_number "${json}" "executionDisplayId")"

  assert_equals "${ok_value}" "true" "start_session ok" "dump_normal_manual_anomaly"
  assert_equals "${code}" "AUTOMATION_STARTED" "start_session code" "dump_normal_manual_anomaly"
  assert_equals "${status}" "RUNNING" "start_session status" "dump_normal_manual_anomaly"
  assert_equals "${display_mode_value}" "${display_mode}" "start_session displayMode" "dump_normal_manual_anomaly"
  assert_non_empty "${SESSION_ID}" "missing sessionId"
  assert_non_empty "${EXECUTION_DISPLAY_ID}" "missing executionDisplayId"
}

dump_active_session_json() {
  local output json
  output="$(broadcast_dump_session)"
  printf '%s\n' "${output}" > "${CURRENT_CASE_DIR}/dump_active_session.stdout.log"
  json="$(extract_broadcast_json "${output}")"
  assert_non_empty "${json}" "failed to parse dump_active_session result"
  printf '%s\n' "${json}"
}

capture_case_artifacts() {
  capture_device_log "${CURRENT_CASE_DIR}/device.log"
  adb_cmd shell dumpsys activity activities > "${CURRENT_CASE_DIR}/activity.dump"
  adb_cmd shell dumpsys window displays > "${CURRENT_CASE_DIR}/window.dump"
}

assert_all_execution_display_ids_match() {
  local file="$1"
  local expected="$2"
  local mismatch
  mismatch="$(awk -v expected="${expected}" -v session_id="${SESSION_ID}" '
    $0 ~ ("sessionId=" session_id) {
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^executionDisplayId=/) {
          split($i, a, "=")
          if (a[2] != "-1" && a[2] != expected) {
            print $0
            exit 0
          }
        }
      }
    }
  ' "${file}")"
  if [[ -n "${mismatch}" ]]; then
    record_failure "inject_cross_display" "executionDisplayId drifted away from original execution display ${expected}" "${CURRENT_CASE_DIR}/device.log"
  fi
}

assert_platform_token_matches() {
  local file="$1"
  local token_name="$2"
  local expected="$3"
  local action_regex="$4"
  local mismatch
  local matched_count

  matched_count="$(grep -E "sessionId=${SESSION_ID} .* actionType=${action_regex} " "${file}" | grep -c "${token_name}=" || true)"
  if [[ "${matched_count}" == "0" ]]; then
    record_failure "dump_normal_manual_anomaly" "missing ${token_name} logs for actionType=${action_regex}" "${file}"
  fi

  mismatch="$(awk -v expected="${expected}" -v session_id="${SESSION_ID}" -v token_name="${token_name}" -v action_regex="${action_regex}" '
    $0 ~ ("sessionId=" session_id) && $0 ~ ("actionType=" action_regex " ") {
      for (i = 1; i <= NF; i++) {
        if (index($i, token_name "=") == 1) {
          split($i, a, "=")
          if (a[2] != expected) {
            print $0
            exit 0
          }
        }
      }
    }
  ' "${file}")"
  if [[ -n "${mismatch}" ]]; then
    if [[ "${token_name}" == "captureDisplayId" || "${token_name}" == "observationDisplayId" ]]; then
      record_failure "capture_cross_display" "${token_name} drifted away from executionDisplayId=${expected}" "${file}"
    else
      record_failure "inject_cross_display" "${token_name} drifted away from executionDisplayId=${expected}" "${file}"
    fi
  fi
}

assert_no_log_pattern() {
  local file="$1"
  local pattern="$2"
  local class_name="$3"
  local message="$4"
  if grep -E -q "${pattern}" "${file}"; then
    record_failure "${class_name}" "${message}" "${file}"
  fi
}

assert_foreground_not_stolen() {
  local file="$1"
  local mismatch
  mismatch="$(grep -E 'displayId=0|Display #0' "${file}" | grep -E 'SessionRootActivity|com.example.agenthost/.session.SessionRootActivity' || true)"
  if [[ -n "${mismatch}" ]]; then
    record_failure "main_foreground_stolen" "main display showed AgentHost SessionRootActivity before explicit handoff" "${file}"
  fi
}

assert_manual_feel_ok() {
  local prompt="$1"
  if ! prompt_yes_no "${prompt}"; then
    record_failure "dump_normal_manual_anomaly" "operator reported focus/input/Recent anomaly while dump/logs remained nominal" "${CURRENT_CASE_DIR}"
  fi
}

assert_virtual_dump_completed() {
  local json="$1"
  local status migrated current_execution current_visible display_mode
  status="$(extract_json_string "${json}" "status")"
  migrated="$(extract_json_bool "${json}" "migratedToMain")"
  current_execution="$(extract_json_number "${json}" "currentExecutionDisplayId")"
  current_visible="$(extract_json_number "${json}" "currentVisibleDisplayId")"
  display_mode="$(extract_json_string "${json}" "displayMode")"

  assert_equals "${status}" "COMPLETED" "session should complete" "dump_normal_manual_anomaly"
  assert_equals "${display_mode}" "VIRTUAL_DISPLAY" "session should stay in VIRTUAL_DISPLAY mode" "dump_normal_manual_anomaly"
  assert_equals "${migrated}" "false" "virtual-display isolation case should not migrate to main" "main_foreground_stolen"
  assert_equals "${current_execution}" "${EXECUTION_DISPLAY_ID}" "currentExecutionDisplayId should remain original executionDisplayId" "inject_cross_display"
  assert_equals "${current_visible}" "${EXECUTION_DISPLAY_ID}" "currentVisibleDisplayId should remain executionDisplayId before handoff" "main_foreground_stolen"
}

assert_main_display_not_applicable_dump() {
  local json="$1"
  assert_equals "$(extract_json_string "${json}" "displayMode")" "MAIN_DISPLAY" "main-display case should report MAIN_DISPLAY" "main_display_not_applicable_violation"
  assert_equals "$(extract_json_number "${json}" "executionDisplayId")" "0" "main-display executionDisplayId should stay 0" "main_display_not_applicable_violation"
}

run_standard_isolation_case() {
  local payload="$1"
  local driver_mode="$2"
  local inject_action_regex="$3"
  local manual_prompt="$4"

  start_session_and_capture_ids "${payload}" "VIRTUAL_DISPLAY" "settings"
  assert_non_empty "${EXECUTION_DISPLAY_ID}" "missing executionDisplayId for ${CURRENT_CASE_NAME}"
  if [[ "${EXECUTION_DISPLAY_ID}" == "0" ]]; then
    record_failure "inject_cross_display" "expected virtual-display executionDisplayId, got 0" "${CURRENT_CASE_DIR}/start_session.stdout.log"
  fi

  capture_foreground_context_once "${CURRENT_CASE_DIR}/foreground.pre.log"
  start_foreground_sampler "${CURRENT_CASE_DIR}/foreground.samples.log"
  drive_main_actions "${driver_mode}" "${CURRENT_CASE_DIR}/main_driver.log"
  wait_for_log_pattern "event=session_completed sessionId=${SESSION_ID} " 40 || \
    record_failure "dump_normal_manual_anomaly" "session did not complete within timeout" "${CURRENT_CASE_DIR}"
  cleanup_background_jobs
  capture_foreground_context_once "${CURRENT_CASE_DIR}/foreground.post.log"
  capture_case_artifacts

  local json
  json="$(dump_active_session_json)"
  assert_virtual_dump_completed "${json}"
  assert_all_execution_display_ids_match "${CURRENT_CASE_DIR}/device.log" "${EXECUTION_DISPLAY_ID}"
  assert_platform_token_matches "${CURRENT_CASE_DIR}/device.log" "captureDisplayId" "${EXECUTION_DISPLAY_ID}" "CAPTURE"
  assert_platform_token_matches "${CURRENT_CASE_DIR}/device.log" "observationDisplayId" "${EXECUTION_DISPLAY_ID}" "NONE"
  assert_platform_token_matches "${CURRENT_CASE_DIR}/device.log" "injectDisplayId" "${EXECUTION_DISPLAY_ID}" "${inject_action_regex}"
  assert_no_log_pattern "${CURRENT_CASE_DIR}/device.log" "event=task_migration_(precheck|begin|success|back_begin|back_success|conflict|failed|task_missing) sessionId=${SESSION_ID} " "main_foreground_stolen" "unexpected task migration log appeared in non-handoff isolation case"
  assert_foreground_not_stolen "${CURRENT_CASE_DIR}/foreground.samples.log"
  assert_foreground_not_stolen "${CURRENT_CASE_DIR}/foreground.post.log"
  assert_manual_feel_ok "${manual_prompt}"
  best_effort_stop_active
  ok "case=${CURRENT_CASE_NAME} iteration=${CURRENT_ITERATION} passed"
}

case_concurrent_click() {
  local payload='{"steps":[{"action":"WAIT","durationMs":700},{"action":"CLICK","x":540,"y":960},{"action":"WAIT","durationMs":700},{"action":"CLICK","x":540,"y":960},{"action":"WAIT","durationMs":700},{"action":"CLICK","x":540,"y":960},{"action":"DONE","reason":"concurrent-click"}]}'
  run_standard_isolation_case \
    "${payload}" \
    "click" \
    "TAP" \
    "Observe the main display during concurrent_click. Did taps feel isolated and free of focus stealing?"
}

case_concurrent_swipe() {
  local payload='{"steps":[{"action":"WAIT","durationMs":700},{"action":"SWIPE","x1":540,"y1":1500,"x2":540,"y2":450,"durationMs":300},{"action":"WAIT","durationMs":700},{"action":"SWIPE","x1":900,"y1":900,"x2":180,"y2":900,"durationMs":300},{"action":"WAIT","durationMs":700},{"action":"DONE","reason":"concurrent-swipe"}]}'
  run_standard_isolation_case \
    "${payload}" \
    "swipe" \
    "SWIPE" \
    "Observe the main display during concurrent_swipe. Did swipes stay isolated without dragging the main UI unexpectedly?"
}

case_concurrent_back_home() {
  local payload='{"steps":[{"action":"WAIT","durationMs":700},{"action":"BACK"},{"action":"WAIT","durationMs":700},{"action":"DONE","reason":"concurrent-back-home"}]}'
  run_standard_isolation_case \
    "${payload}" \
    "back_home" \
    "KEY" \
    "Observe the main display during concurrent_back_home. Did main-display BACK/HOME remain usable without agent interruption?"
}

case_concurrent_text_input() {
  local payload
  payload="$(printf '{"steps":[{"action":"WAIT","durationMs":700},{"action":"CLICK","x":%s,"y":%s},{"action":"WAIT","durationMs":700},{"action":"TYPE_TEXT","text":"%s"},{"action":"WAIT","durationMs":700},{"action":"DONE","reason":"concurrent-text-input"}]}' "${TEXT_FIELD_X}" "${TEXT_FIELD_Y}" "${EXEC_TEXT_VALUE}")"
  run_standard_isolation_case \
    "${payload}" \
    "text" \
    "TAP|TEXT" \
    "Observe the main display during concurrent_text_input. Did text entry remain isolated with no IME/focus stealing?"
}

case_concurrent_capture_inject() {
  local payload='{"steps":[{"action":"WAIT","durationMs":600},{"action":"CLICK","x":540,"y":960},{"action":"WAIT","durationMs":600},{"action":"CLICK","x":540,"y":960},{"action":"WAIT","durationMs":600},{"action":"CLICK","x":540,"y":960},{"action":"WAIT","durationMs":600},{"action":"DONE","reason":"concurrent-capture-inject"}]}'
  run_standard_isolation_case \
    "${payload}" \
    "app_switch" \
    "TAP" \
    "Observe the main display during concurrent_capture_inject. Did capture+inject stay isolated while main foreground app switching remained stable?"
}

assert_ordered_log_events() {
  local file="$1"
  local first_pattern="$2"
  local second_pattern="$3"
  local first_line second_line
  first_line="$(grep -n -E "${first_pattern}" "${file}" | head -n 1 | cut -d: -f1)"
  second_line="$(grep -n -E "${second_pattern}" "${file}" | head -n 1 | cut -d: -f1)"
  if [[ -z "${first_line}" || -z "${second_line}" || "${second_line}" -le "${first_line}" ]]; then
    record_failure "main_foreground_stolen" "expected ordered events first='${first_pattern}' then='${second_pattern}'" "${file}"
  fi
}

case_migration_boundary_isolation() {
  local payload='{"steps":[{"action":"WAIT","durationMs":700},{"action":"WAIT","durationMs":700},{"action":"ASK_USER","question":"Observe explicit handoff on main display and reply YES."},{"action":"WAIT","durationMs":700},{"action":"CLICK","x":540,"y":960},{"action":"DONE","reason":"migration-boundary-isolation"}]}'
  local handoff_mode="AUTO_WAITING_USER"

  start_session_and_capture_ids "${payload}" "VIRTUAL_DISPLAY" "settings"
  if [[ "${EXECUTION_DISPLAY_ID}" == "0" ]]; then
    record_failure "inject_cross_display" "expected virtual-display executionDisplayId, got 0" "${CURRENT_CASE_DIR}/start_session.stdout.log"
  fi

  capture_foreground_context_once "${CURRENT_CASE_DIR}/foreground.pre.log"
  start_foreground_sampler "${CURRENT_CASE_DIR}/foreground.pre.samples.log"
  wait_for_log_pattern "event=session_waiting_user sessionId=${SESSION_ID} " 40 || \
    record_failure "dump_normal_manual_anomaly" "session did not enter WAITING_USER" "${CURRENT_CASE_DIR}"
  if ! wait_for_log_pattern "event=task_migration_success sessionId=${SESSION_ID} .* code=TASK_MIGRATION_OK" 5; then
    local migrate_output migrate_json migrate_ok migrate_code
    handoff_mode="EXPLICIT_DEBUG"
    migrate_output="$(broadcast_migrate_to_main "${SESSION_ID}")"
    printf '%s\n' "${migrate_output}" > "${CURRENT_CASE_DIR}/migrate_to_main.stdout.log"
    migrate_json="$(extract_broadcast_json "${migrate_output}")"
    migrate_ok="$(extract_json_bool "${migrate_json}" "ok")"
    migrate_code="$(extract_json_string "${migrate_json}" "code")"
    if [[ "${migrate_ok}" != "true" || ( "${migrate_code}" != "TASK_MIGRATION_OK" && "${migrate_code}" != "TASK_MIGRATION_ALREADY_ON_MAIN" ) ]]; then
      record_failure "main_foreground_stolen" "explicit handoff to main did not occur" "${CURRENT_CASE_DIR}"
    fi
  fi
  stop_foreground_sampler
  capture_case_artifacts
  local waiting_json
  waiting_json="$(dump_active_session_json)"
  assert_equals "$(extract_json_string "${waiting_json}" "status")" "WAITING_USER" "migration-boundary should be WAITING_USER before reply" "dump_normal_manual_anomaly"
  assert_equals "$(extract_json_bool "${waiting_json}" "migratedToMain")" "true" "migratedToMain should be true at handoff" "main_foreground_stolen"
  assert_equals "$(extract_json_number "${waiting_json}" "currentExecutionDisplayId")" "${EXECUTION_DISPLAY_ID}" "currentExecutionDisplayId must remain immutable during handoff" "inject_cross_display"
  assert_equals "$(extract_json_number "${waiting_json}" "currentVisibleDisplayId")" "0" "currentVisibleDisplayId should move to main during handoff" "main_foreground_stolen"
  assert_platform_token_matches "${CURRENT_CASE_DIR}/device.log" "captureDisplayId" "${EXECUTION_DISPLAY_ID}" "CAPTURE"
  assert_foreground_not_stolen "${CURRENT_CASE_DIR}/foreground.pre.samples.log"
  if [[ "${handoff_mode}" == "AUTO_WAITING_USER" ]]; then
    wait_for_log_pattern "event=waiting_user_after_migration sessionId=${SESSION_ID} " 20 || \
      record_failure "main_foreground_stolen" "waiting_user_after_migration log missing" "${CURRENT_CASE_DIR}"
    assert_ordered_log_events "${CURRENT_CASE_DIR}/device.log" "event=session_waiting_user sessionId=${SESSION_ID} " "event=task_migration_success sessionId=${SESSION_ID} .* code=TASK_MIGRATION_OK"
  fi
  assert_manual_feel_ok "Observe the handoff on main display now. Did the real task become visible only after WAITING_USER?"

  start_foreground_sampler "${CURRENT_CASE_DIR}/foreground.post.samples.log"
  drive_main_actions "click" "${CURRENT_CASE_DIR}/main_driver.post.log"
  local reply_output reply_json
  reply_output="$(broadcast_reply_session "${SESSION_ID}" "YES")"
  printf '%s\n' "${reply_output}" > "${CURRENT_CASE_DIR}/reply_session.stdout.log"
  reply_json="$(extract_broadcast_json "${reply_output}")"
  assert_equals "$(extract_json_bool "${reply_json}" "ok")" "true" "reply_session should succeed" "dump_normal_manual_anomaly"
  wait_for_log_pattern "event=task_migration_back_success sessionId=${SESSION_ID} .* code=TASK_MIGRATION_BACK_OK" 20 || \
    record_failure "main_foreground_stolen" "migrate-back did not succeed" "${CURRENT_CASE_DIR}"
  wait_for_log_pattern "event=automation_refresh_after_migration sessionId=${SESSION_ID} .* observationDisplayId=${EXECUTION_DISPLAY_ID}" 20 || \
    record_failure "capture_cross_display" "fresh observation after migrate-back did not target original executionDisplayId" "${CURRENT_CASE_DIR}"
  wait_for_log_pattern "event=automation_resume_after_migration sessionId=${SESSION_ID} " 20 || \
    record_failure "dump_normal_manual_anomaly" "automation_resume_after_migration log missing" "${CURRENT_CASE_DIR}"
  wait_for_log_pattern "event=session_completed sessionId=${SESSION_ID} " 40 || \
    record_failure "dump_normal_manual_anomaly" "session did not complete after reply" "${CURRENT_CASE_DIR}"
  cleanup_background_jobs
  capture_foreground_context_once "${CURRENT_CASE_DIR}/foreground.post.log"
  capture_case_artifacts

  local final_json
  final_json="$(dump_active_session_json)"
  assert_equals "$(extract_json_string "${final_json}" "status")" "COMPLETED" "migration-boundary should complete after reply" "dump_normal_manual_anomaly"
  assert_equals "$(extract_json_bool "${final_json}" "migratedToMain")" "false" "migratedToMain should be false after migrate-back" "main_foreground_stolen"
  assert_equals "$(extract_json_number "${final_json}" "currentExecutionDisplayId")" "${EXECUTION_DISPLAY_ID}" "currentExecutionDisplayId must remain immutable after migrate-back" "inject_cross_display"
  assert_equals "$(extract_json_number "${final_json}" "currentVisibleDisplayId")" "${EXECUTION_DISPLAY_ID}" "currentVisibleDisplayId should return to executionDisplayId after migrate-back" "main_foreground_stolen"
  assert_all_execution_display_ids_match "${CURRENT_CASE_DIR}/device.log" "${EXECUTION_DISPLAY_ID}"
  assert_platform_token_matches "${CURRENT_CASE_DIR}/device.log" "captureDisplayId" "${EXECUTION_DISPLAY_ID}" "CAPTURE"
  assert_platform_token_matches "${CURRENT_CASE_DIR}/device.log" "observationDisplayId" "${EXECUTION_DISPLAY_ID}" "NONE"
  assert_platform_token_matches "${CURRENT_CASE_DIR}/device.log" "injectDisplayId" "${EXECUTION_DISPLAY_ID}" "TAP"
  assert_foreground_not_stolen "${CURRENT_CASE_DIR}/foreground.post.samples.log"
  assert_manual_feel_ok "Observe after REPLY_SESSION. Did the main display stop carrying the session task and return to isolated background execution?"
  best_effort_stop_active
  ok "case=${CURRENT_CASE_NAME} iteration=${CURRENT_ITERATION} passed"
}

case_main_display_not_applicable() {
  local payload='{"steps":[{"action":"WAIT","durationMs":2500},{"action":"DONE","reason":"main-display-not-applicable"}]}'
  start_session_and_capture_ids "${payload}" "MAIN_DISPLAY" "settings"
  assert_equals "${EXECUTION_DISPLAY_ID}" "0" "MAIN_DISPLAY executionDisplayId should stay 0" "main_display_not_applicable_violation"
  wait_for_log_pattern "event=automation_start sessionId=${SESSION_ID} " 10 || \
    record_failure "main_display_not_applicable_violation" "MAIN_DISPLAY session did not start automation" "${CURRENT_CASE_DIR}"
  local json
  json="$(dump_active_session_json)"
  assert_main_display_not_applicable_dump "${json}"

  local output migrate_json
  output="$(broadcast_migrate_to_main "${SESSION_ID}")"
  printf '%s\n' "${output}" > "${CURRENT_CASE_DIR}/migrate_to_main.stdout.log"
  migrate_json="$(extract_broadcast_json "${output}")"
  assert_equals "$(extract_json_bool "${migrate_json}" "ok")" "false" "MAIN_DISPLAY migrate_to_main should not enter virtual-display migration" "main_display_not_applicable_violation"
  assert_equals "$(extract_json_string "${migrate_json}" "code")" "TASK_MIGRATION_NOT_VIRTUAL_DISPLAY" "MAIN_DISPLAY migrate_to_main code" "main_display_not_applicable_violation"

  output="$(broadcast_migrate_to_execution "${SESSION_ID}")"
  printf '%s\n' "${output}" > "${CURRENT_CASE_DIR}/migrate_to_execution.stdout.log"
  migrate_json="$(extract_broadcast_json "${output}")"
  assert_equals "$(extract_json_bool "${migrate_json}" "ok")" "false" "MAIN_DISPLAY migrate_to_execution should not enter virtual-display migration" "main_display_not_applicable_violation"
  assert_equals "$(extract_json_string "${migrate_json}" "code")" "TASK_MIGRATION_NOT_VIRTUAL_DISPLAY" "MAIN_DISPLAY migrate_to_execution code" "main_display_not_applicable_violation"

  capture_case_artifacts
  assert_no_log_pattern "${CURRENT_CASE_DIR}/device.log" "event=task_migration_(precheck|begin|success|back_begin|back_success|conflict|failed|task_missing) sessionId=${SESSION_ID} " "main_display_not_applicable_violation" "MAIN_DISPLAY session unexpectedly entered VIRTUAL_DISPLAY-only migration logic"
  wait_for_log_pattern "event=session_completed sessionId=${SESSION_ID} " 20 || \
    record_failure "main_display_not_applicable_violation" "MAIN_DISPLAY session did not complete" "${CURRENT_CASE_DIR}"
  best_effort_stop_active
  ok "case=${CURRENT_CASE_NAME} iteration=${CURRENT_ITERATION} passed"
}

run_case() {
  local case_name="$1"
  local iteration="$2"
  CURRENT_CASE_NAME="${case_name}"
  CURRENT_ITERATION="${iteration}"
  CURRENT_CASE_DIR="${LOG_DIR}/${case_name}/run_${iteration}"
  mkdir -p "${CURRENT_CASE_DIR}"
  clear_logs
  best_effort_stop_active
  cleanup_background_jobs

  log "running case=${case_name} iteration=${iteration}"
  case "${case_name}" in
    concurrent_click) case_concurrent_click ;;
    concurrent_swipe) case_concurrent_swipe ;;
    concurrent_back_home) case_concurrent_back_home ;;
    concurrent_text_input) case_concurrent_text_input ;;
    concurrent_capture_inject) case_concurrent_capture_inject ;;
    migration_boundary_isolation) case_migration_boundary_isolation ;;
    main_display_not_applicable) case_main_display_not_applicable ;;
    *) record_failure "dump_normal_manual_anomaly" "unknown case '${case_name}'" "${LOG_DIR}" ;;
  esac
}

should_run_case() {
  local case_name="$1"
  if [[ "${CASE_SELECTOR}" == "all" ]]; then
    return 0
  fi
  [[ "${CASE_SELECTOR}" == "${case_name}" ]]
}

run_default_cases() {
  local cases=(
    concurrent_click
    concurrent_swipe
    concurrent_back_home
    concurrent_text_input
    concurrent_capture_inject
    migration_boundary_isolation
    main_display_not_applicable
  )
  local case_name
  for case_name in "${cases[@]}"; do
    if should_run_case "${case_name}"; then
      run_case "${case_name}" 1
    fi
  done
}

run_stability_cases() {
  local case_name
  local iteration
  local previous_manual="${ENABLE_MANUAL_CONFIRM}"
  local previous_auto="${AUTO_CONFIRM}"
  ENABLE_MANUAL_CONFIRM=0
  AUTO_CONFIRM=YES
  log "running stability section runs=${STABILITY_RUNS} cases='${STABILITY_CASES}'"
  for case_name in ${STABILITY_CASES}; do
    if [[ "${CASE_SELECTOR}" != "all" && "${CASE_SELECTOR}" != "${case_name}" ]]; then
      continue
    fi
    for iteration in $(seq 1 "${STABILITY_RUNS}"); do
      run_case "${case_name}" "${iteration}"
    done
  done
  ENABLE_MANUAL_CONFIRM="${previous_manual}"
  AUTO_CONFIRM="${previous_auto}"
  ok "stability section passed runs=${STABILITY_RUNS}"
}

print_summary() {
  echo
  ok "task migration concurrency isolation passed"
  echo "logs saved under ${LOG_DIR}"
}

main() {
  mkdir -p "${LOG_DIR}"
  run_default_cases
  if [[ "${RUN_STABILITY}" == "1" ]]; then
    run_stability_cases
  fi
  print_summary
}

main "$@"
