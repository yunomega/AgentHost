#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

AOSP_ROOT="${AOSP_ROOT:-/home/alientek/rk3588_android_sdk}"
LUNCH_TARGET="${LUNCH_TARGET:-}"
ADB_SERIAL="${ADB_SERIAL:-}"
ANDROID_PRODUCT_OUT="${ANDROID_PRODUCT_OUT:-}"

PKG="com.example.agenthost"
RECV="${PKG}/.debug.SessionAuditReceiver"
DEFAULT_TEXT="${DEFAULT_TEXT:-hello123}"
TAIL_LINES="${TAIL_LINES:-60}"
MOCK_SERVER_IP="${MOCK_SERVER_IP:-}"
MOCK_SERVER_PORT="${MOCK_SERVER_PORT:-8080}"
MOCK_SERVER_BIND_HOST="${MOCK_SERVER_BIND_HOST:-0.0.0.0}"
MOCK_SERVER_LOG="${MOCK_SERVER_LOG:-/tmp/agenthost_mock_gateway.log}"
MOCK_MODEL_API_KEY="${MOCK_MODEL_API_KEY:-mock-token}"
MODEL_TIMEOUT_MS="${MODEL_TIMEOUT_MS:-10000}"
MODEL_TIMEOUT_CASE_MS="${MODEL_TIMEOUT_CASE_MS:-2000}"
MODEL_MAX_RETRIES="${MODEL_MAX_RETRIES:-0}"
MODEL_MAX_RESPONSE_CHARS="${MODEL_MAX_RESPONSE_CHARS:-4096}"
declare -a MODEL_CASES=(click ask-user home invalid timeout)

MOCK_SERVER_PID=""
MOCK_SERVER_STARTED_BY_SCRIPT=0
MOCK_SERVER_RESOLVED_IP=""
ADB_REVERSE_READY=0
ADB_REVERSE_CREATED_BY_SCRIPT=0
declare -a MODEL_LOG_TAGS=(
  SessionAuditReceiver
  SessionCoordinator
  SessionAutomationEngine
  PlannerSelector
  ModelPlanningAdapter
  HttpModelClient
)

adb_cmd() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    adb -s "${ADB_SERIAL}" "$@"
  else
    adb "$@"
  fi
}

log() {
  echo -e "\033[1;34m[INFO]\033[0m $*"
}

ok() {
  echo -e "\033[1;32m[ OK ]\033[0m $*"
}

warn() {
  echo -e "\033[1;33m[WARN]\033[0m $*"
}

die() {
  echo -e "\033[1;31m[ERR ]\033[0m $*" >&2
  exit 1
}

require_bin() {
  command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

wait_for_boot() {
  adb_cmd wait-for-device
  until [[ "$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 1
  done
}

resolve_product_out() {
  if [[ -n "${ANDROID_PRODUCT_OUT}" ]]; then
    printf '%s' "${ANDROID_PRODUCT_OUT}"
    return 0
  fi

  local product=""
  if [[ -n "${LUNCH_TARGET}" ]]; then
    product="${LUNCH_TARGET%-*}"
  else
    product="$(adb_cmd shell getprop ro.build.product 2>/dev/null | tr -d '\r')"
  fi

  [[ -n "${product}" ]] || die "failed to resolve product for ANDROID_PRODUCT_OUT"
  printf '%s/out/target/product/%s' "${AOSP_ROOT}" "${product}"
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/ }"
  printf '%s' "$value"
}

tail_platform() {
  adb_cmd shell dumpsys agenthost_platform | tail -n "${1:-${TAIL_LINES}}"
}

dump_platform() {
  log "dumpsys agenthost_platform"
  adb_cmd shell dumpsys agenthost_platform
}

service_check() {
  log "service check"
  adb_cmd shell service check agenthost_platform
}

unauthorized_check() {
  log "unauthorized service call"
  adb_cmd shell service call agenthost_platform 1 s16 smoke i32 123 || true
  sleep 1
  tail_platform 40
}

display_snapshot() {
  log "display snapshot"
  adb_cmd shell dumpsys display | grep -nE 'session-vd-|mDisplayId=' || true
  echo
  adb_cmd shell dumpsys window displays | grep -nE 'Display #[0-9]+|SessionRootActivity|mFocusedApp|mCurrentFocus' || true
  echo
  adb_cmd shell dumpsys activity activities | grep -nE 'SessionRootActivity|taskId=|displayId=' || true
}

clear_logs() {
  adb_cmd logcat -c || true
}

logs() {
  adb_cmd logcat -v time -s AgentHostPlatformService SessionCoordinator SessionAutomationEngine SessionAuditReceiver
}

model_logcat() {
  adb_cmd logcat -d -v time -s "${MODEL_LOG_TAGS[@]}"
}

send_start_json() {
  local payload="$1"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.START_SESSION' --es command '${payload}'"
}

dump_active_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.DUMP_ACTIVE_SESSION'"
}

reply_session() {
  local session_id="$1"
  local user_text="${2:-${DEFAULT_TEXT}}"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.REPLY_SESSION' --es session_id '${session_id}' --es user_text '$(json_escape "${user_text}")'"
}

stop_session() {
  local migrate="${1:-false}"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.STOP_SESSION' --ez migrate ${migrate}"
}

last_session_id() {
  local line
  line="$(adb_cmd logcat -d -v time -s SessionAuditReceiver | grep 'event=debug_start_session' | tail -n 1 || true)"
  echo "${line}" | sed -n 's/.* sessionId=\([^ ]*\) .*/\1/p'
}

start_and_dump() {
  local name="$1"
  local payload="$2"
  local sleep_s="${3:-3}"
  log "start ${name}"
  send_start_json "${payload}"
  sleep "${sleep_s}"
  tail_platform
}

smoke_capture() {
  start_and_dump capture '{"steps":[{"action":"WAIT","durationMs":1000},{"action":"DONE","reason":"capture-smoke"}]}' 3
}

smoke_click() {
  start_and_dump click '{"steps":[{"action":"WAIT","durationMs":500},{"action":"CLICK","x":540,"y":960},{"action":"DONE","reason":"click-smoke"}]}' 3
}

smoke_swipe() {
  start_and_dump swipe '{"steps":[{"action":"WAIT","durationMs":500},{"action":"SWIPE","x1":540,"y1":1500,"x2":540,"y2":500,"durationMs":400},{"action":"DONE","reason":"swipe-smoke"}]}' 3
}

smoke_back() {
  start_and_dump back '{"steps":[{"action":"WAIT","durationMs":500},{"action":"BACK"},{"action":"DONE","reason":"back-smoke"}]}' 3
}

smoke_home() {
  start_and_dump home '{"steps":[{"action":"WAIT","durationMs":500},{"action":"HOME"},{"action":"DONE","reason":"home-smoke"}]}' 3
}

smoke_text() {
  local text="$(json_escape "${1:-${DEFAULT_TEXT}}")"
  start_and_dump type_text "{\"steps\":[{\"action\":\"WAIT\",\"durationMs\":500},{\"action\":\"TYPE_TEXT\",\"text\":\"${text}\"},{\"action\":\"DONE\",\"reason\":\"text-smoke\"}]}" 3
}

smoke_long_press() {
  start_and_dump long_press '{"steps":[{"action":"WAIT","durationMs":500},{"action":"LONG_PRESS","x":540,"y":960,"durationMs":800},{"action":"DONE","reason":"long-press-smoke"}]}' 4
}

smoke_ask_reply() {
  local user_text="${1:-${DEFAULT_TEXT}}"
  log "start ask-reply"
  send_start_json '{"steps":[{"action":"ASK_USER","question":"Please provide user input text"},{"action":"TYPE_TEXT","text":"{{last_user_input}}"},{"action":"DONE","reason":"reply-smoke"}]}'
  sleep 1
  dump_active_session
  sleep 1
  local session_id
  session_id="$(last_session_id)"
  [[ -n "${session_id}" ]] || die "failed to extract sessionId from logcat"
  ok "sessionId=${session_id}"
  reply_session "${session_id}" "${user_text}"
  sleep 2
  tail_platform
}

build_fw() {
  [[ -n "${LUNCH_TARGET}" ]] || die "set LUNCH_TARGET before running build-fw"
  log "build framework targets"
  bash -lc "source '${AOSP_ROOT}/build/envsetup.sh' && lunch '${LUNCH_TARGET}' && m services framework-minus-apex selinux_policy"
}

flash_fw() {
  log "flash system"
  local product_out
  product_out="$(resolve_product_out)"
  adb_cmd root
  adb_cmd wait-for-device
  adb_cmd remount
  ANDROID_PRODUCT_OUT="${product_out}" adb_cmd sync system
  adb_cmd reboot
  wait_for_boot
  adb_cmd root
  adb_cmd wait-for-device
}

deploy_app() {
  log "deploy app"
  "${PROJECT_ROOT}/scripts/sign_deploy.sh"
}

smoke_basic() {
  service_check
  echo
  dump_platform
  echo
  unauthorized_check
  echo
  smoke_capture
  echo
  smoke_click
  echo
  smoke_swipe
  echo
  smoke_back
  echo
  smoke_home
  echo
  smoke_text "${DEFAULT_TEXT}"
  echo
  smoke_ask_reply "${DEFAULT_TEXT}"
}

smoke_extended() {
  service_check
  echo
  dump_platform
  echo
  unauthorized_check
  echo
  smoke_capture
  echo
  smoke_click
  echo
  smoke_swipe
  echo
  smoke_back
  echo
  smoke_home
  echo
  smoke_text "${DEFAULT_TEXT}"
  echo
  smoke_ask_reply "${DEFAULT_TEXT}"
  echo
  smoke_long_press
  echo
  display_snapshot
}

smoke_full() {
  smoke_extended
}

resolve_mock_server_ip() {
  if [[ -n "${MOCK_SERVER_IP}" ]]; then
    printf '%s' "${MOCK_SERVER_IP}"
    return 0
  fi

  if [[ "${ADB_REVERSE_READY}" == "1" ]]; then
    printf '%s' '127.0.0.1'
    return 0
  fi

  local detected=""
  if command -v ip >/dev/null 2>&1; then
    detected="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i <= NF; i++) if ($i == "src") {print $(i + 1); exit}}')"
  fi
  if [[ -z "${detected}" ]] && command -v hostname >/dev/null 2>&1; then
    detected="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -v '^127\.' | head -n 1 || true)"
  fi
  [[ -n "${detected}" ]] || die "failed to resolve MOCK_SERVER_IP; set MOCK_SERVER_IP=<pc-ip>"
  printf '%s' "${detected}"
}

mock_server_healthcheck() {
  local host="$1"
  local status
  status="$(curl -sS -m 2 -o /dev/null -w '%{http_code}' \
    -X POST "http://${host}:${MOCK_SERVER_PORT}/plan?mode=click" \
    -H 'Content-Type: application/json' \
    -d '{"sessionId":"health","step":1,"maxResponseChars":128,"context":{"sessionId":"health","vdDisplayId":1,"sessionTaskId":1,"step":1,"capabilityProfile":{"clickSupported":true},"observation":{"status":"OK"}}}' \
    2>/dev/null || true)"
  [[ "${status}" == "200" ]]
}

cleanup_mock_server() {
  if [[ "${MOCK_SERVER_STARTED_BY_SCRIPT}" == "1" && -n "${MOCK_SERVER_PID}" ]]; then
    log "stop mock server pid=${MOCK_SERVER_PID}"
    kill "${MOCK_SERVER_PID}" >/dev/null 2>&1 || true
    wait "${MOCK_SERVER_PID}" >/dev/null 2>&1 || true
    MOCK_SERVER_PID=""
    MOCK_SERVER_STARTED_BY_SCRIPT=0
  fi
}

cleanup_model_adb_reverse() {
  local reverse_spec="tcp:${MOCK_SERVER_PORT}"
  if [[ "${ADB_REVERSE_CREATED_BY_SCRIPT}" == "1" ]]; then
    log "remove adb reverse ${reverse_spec}"
    adb_cmd reverse --remove "${reverse_spec}" >/dev/null 2>&1 || \
      warn "failed to remove adb reverse ${reverse_spec}"
    ADB_REVERSE_CREATED_BY_SCRIPT=0
    ADB_REVERSE_READY=0
  fi
}

setup_model_cleanup_trap() {
  trap 'stop_session false >/dev/null 2>&1 || true; cleanup_mock_server; cleanup_model_adb_reverse' EXIT
}

setup_model_adb_reverse() {
  local reverse_spec="tcp:${MOCK_SERVER_PORT}"
  local reverse_list existing_remote

  reverse_list="$(adb_cmd reverse --list 2>/dev/null || true)"
  existing_remote="$(printf '%s\n' "${reverse_list}" | awk -v spec="${reverse_spec}" '$2 == spec { print $3; exit }')"

  if [[ -n "${existing_remote}" ]]; then
    if [[ "${existing_remote}" == "${reverse_spec}" ]]; then
      ADB_REVERSE_READY=1
      log "reuse adb reverse ${reverse_spec} -> ${existing_remote}"
    else
      warn "adb reverse ${reverse_spec} already maps to ${existing_remote}; skip auto reverse to avoid affecting other adb usage"
    fi
    return 0
  fi

  if adb_cmd reverse "${reverse_spec}" "${reverse_spec}" >/dev/null 2>&1; then
    ADB_REVERSE_READY=1
    ADB_REVERSE_CREATED_BY_SCRIPT=1
    ok "adb reverse ready: ${reverse_spec} -> ${reverse_spec}"
  else
    warn "adb reverse ${reverse_spec} failed; continuing without reverse"
    if [[ -z "${MOCK_SERVER_IP}" ]]; then
      warn "set MOCK_SERVER_IP=<pc-ip> if the device cannot reach this host directly"
    fi
  fi
}

start_mock_server_if_needed() {
  if mock_server_healthcheck "127.0.0.1" || mock_server_healthcheck "${MOCK_SERVER_RESOLVED_IP}"; then
    warn "mock server already reachable on port ${MOCK_SERVER_PORT}; reusing existing instance"
    return 0
  fi

  require_bin python3
  : >"${MOCK_SERVER_LOG}"
  log "start mock server on ${MOCK_SERVER_BIND_HOST}:${MOCK_SERVER_PORT}"
  python3 "${PROJECT_ROOT}/scripts/mock_generic_gateway.py" \
    --host "${MOCK_SERVER_BIND_HOST}" \
    --port "${MOCK_SERVER_PORT}" \
    >"${MOCK_SERVER_LOG}" 2>&1 &
  MOCK_SERVER_PID="$!"
  MOCK_SERVER_STARTED_BY_SCRIPT=1

  local attempt
  for attempt in $(seq 1 15); do
    if mock_server_healthcheck "127.0.0.1" || mock_server_healthcheck "${MOCK_SERVER_RESOLVED_IP}"; then
      ok "mock server ready: http://${MOCK_SERVER_RESOLVED_IP}:${MOCK_SERVER_PORT}/plan"
      return 0
    fi
    sleep 1
  done

  warn "mock server failed to start; log tail:"
  tail -n 40 "${MOCK_SERVER_LOG}" || true
  die "mock server did not become ready"
}

mock_server_log_line_count() {
  if [[ -f "${MOCK_SERVER_LOG}" ]]; then
    wc -l <"${MOCK_SERVER_LOG}"
  else
    echo 0
  fi
}

mock_server_log_since() {
  local line_count="$1"
  if [[ ! -f "${MOCK_SERVER_LOG}" ]]; then
    return 0
  fi
  sed -n "$((line_count + 1)),\$p" "${MOCK_SERVER_LOG}"
}

wait_for_mock_server_log_pattern() {
  local line_count="$1"
  local pattern="$2"
  local timeout_s="$3"
  local description="$4"

  if [[ "${MOCK_SERVER_STARTED_BY_SCRIPT}" != "1" ]]; then
    return 0
  fi

  local attempt excerpt
  for attempt in $(seq 1 "${timeout_s}"); do
    excerpt="$(mock_server_log_since "${line_count}")"
    if echo "${excerpt}" | grep -E "${pattern}" >/dev/null; then
      ok "${description}"
      return 0
    fi
    sleep 1
  done

  warn "missing mock server log pattern after ${timeout_s}s: ${description}"
  mock_server_log_since "${line_count}" || true
  die "missing mock server log pattern: ${pattern}"
}

wait_for_log_pattern() {
  local pattern="$1"
  local timeout_s="$2"
  local description="$3"

  local attempt output
  for attempt in $(seq 1 "${timeout_s}"); do
    output="$(model_logcat || true)"
    if echo "${output}" | grep -E "${pattern}" >/dev/null; then
      ok "${description}"
      return 0
    fi
    sleep 1
  done

  warn "missing log pattern after ${timeout_s}s: ${description}"
  model_logcat | tail -n 200 || true
  die "missing log pattern: ${pattern}"
}

wait_for_last_session_id() {
  local timeout_s="${1:-10}"
  local attempt session_id
  for attempt in $(seq 1 "${timeout_s}"); do
    session_id="$(last_session_id)"
    if [[ -n "${session_id}" ]]; then
      printf '%s' "${session_id}"
      return 0
    fi
    sleep 1
  done
  die "failed to extract sessionId from logcat"
}

reset_model_case_state() {
  stop_session false >/dev/null 2>&1 || true
  sleep 1
  clear_logs
}

start_model_session() {
  local mode="$1"
  local command="$2"
  local timeout_ms="${3:-${MODEL_TIMEOUT_MS}}"
  local endpoint="http://${MOCK_SERVER_RESOLVED_IP}:${MOCK_SERVER_PORT}/plan?mode=${mode}"

  log "start model session mode=${mode} endpoint=${endpoint}"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.START_SESSION' --es command '${command}' --es planner_mode 'MODEL' --es model_endpoint '${endpoint}' --es model_api_key '${MOCK_MODEL_API_KEY}' --es model_timeout_ms '${timeout_ms}' --es model_max_retries '${MODEL_MAX_RETRIES}' --es model_max_response_chars '${MODEL_MAX_RESPONSE_CHARS}'"
}

run_model_click_case() {
  log "model regression case=click"
  reset_model_case_state
  local server_log_start
  server_log_start="$(mock_server_log_line_count)"

  start_model_session click 'tap the center once'
  wait_for_log_pattern 'plannerMode=MODEL' 10 'MODEL planner enabled for click'
  wait_for_log_pattern 'event=planner_result .* actionType=CLICK code=PLANNER_ACTION' 20 'click action planned'
  wait_for_log_pattern 'event=action_executed .* actionType=CLICK' 20 'click action executed'
  wait_for_mock_server_log_pattern "${server_log_start}" 'incoming request .*mode=click' 10 'mock server received click request'
  wait_for_mock_server_log_pattern "${server_log_start}" 'request summary .*"capabilityProfile"' 10 'mock server logged capability profile'

  stop_session false >/dev/null 2>&1 || true
  sleep 1
}

run_model_ask_user_case() {
  log "model regression case=ask_user"
  reset_model_case_state
  local server_log_start
  server_log_start="$(mock_server_log_line_count)"

  start_model_session ask_user 'ask for a verification code and finish after reply'
  wait_for_log_pattern 'event=session_waiting_user .* code=WAITING_USER' 60 'session entered WAITING_USER'
  wait_for_mock_server_log_pattern "${server_log_start}" 'incoming request .*mode=ask_user' 10 'mock server received ask_user request'

  local session_id
  session_id="$(wait_for_last_session_id 10)"
  ok "sessionId=${session_id}"

  local reply_log_start
  reply_log_start="$(mock_server_log_line_count)"
  reply_session "${session_id}" "${DEFAULT_TEXT}"
  wait_for_mock_server_log_pattern "${reply_log_start}" 'request summary .*"lastUserReply":' 10 'mock server received reply context'
  wait_for_log_pattern 'event=session_completed .* code=COMPLETED' 20 'session completed after reply'

  stop_session false >/dev/null 2>&1 || true
  sleep 1
}

run_model_home_case() {
  log "model regression case=home"
  reset_model_case_state
  local server_log_start
  server_log_start="$(mock_server_log_line_count)"

  start_model_session home 'return an unsupported home action'
  wait_for_log_pattern 'event=session_failed .* code=MODEL_OUTPUT_UNSUPPORTED_ACTION' 20 'unsupported HOME action rejected'
  wait_for_mock_server_log_pattern "${server_log_start}" 'incoming request .*mode=home' 10 'mock server received home request'

  stop_session false >/dev/null 2>&1 || true
  sleep 1
}

run_model_invalid_case() {
  log "model regression case=invalid"
  reset_model_case_state
  local server_log_start
  server_log_start="$(mock_server_log_line_count)"

  start_model_session invalid 'return invalid output'
  wait_for_log_pattern 'event=session_failed .* code=MODEL_OUTPUT_INVALID' 20 'invalid model output rejected'
  wait_for_mock_server_log_pattern "${server_log_start}" 'incoming request .*mode=invalid' 10 'mock server received invalid request'

  stop_session false >/dev/null 2>&1 || true
  sleep 1
}

run_model_timeout_case() {
  log "model regression case=timeout"
  reset_model_case_state
  local server_log_start
  server_log_start="$(mock_server_log_line_count)"

  start_model_session timeout 'trigger a model timeout' "${MODEL_TIMEOUT_CASE_MS}"
  wait_for_log_pattern 'event=session_failed .* code=MODEL_TIMEOUT' 20 'model timeout surfaced as MODEL_TIMEOUT'
  wait_for_mock_server_log_pattern "${server_log_start}" 'timeout mode sleeping seconds=' 10 'mock server entered timeout sleep'

  stop_session false >/dev/null 2>&1 || true
  sleep 1
}

model_regression() {
  model_prepare_environment
  run_all_model_cases
  ok 'model regression completed'
}

model_prepare_environment() {
  require_bin curl

  setup_model_cleanup_trap
  setup_model_adb_reverse
  MOCK_SERVER_RESOLVED_IP="$(resolve_mock_server_ip)"
  start_mock_server_if_needed

  log "model regression using mock endpoint http://${MOCK_SERVER_RESOLVED_IP}:${MOCK_SERVER_PORT}/plan"
  if [[ "${ADB_REVERSE_READY}" == "1" && -z "${MOCK_SERVER_IP}" ]]; then
    log 'using adb reverse default endpoint host=127.0.0.1'
  fi
  if [[ "${MOCK_SERVER_STARTED_BY_SCRIPT}" == "1" ]]; then
    log "mock server log: ${MOCK_SERVER_LOG}"
  else
    warn "reused external mock server; local server log assertions are skipped"
  fi
}

run_model_case_by_name() {
  local case_name="$1"
  case "${case_name}" in
    click) run_model_click_case ;;
    ask-user) run_model_ask_user_case ;;
    home) run_model_home_case ;;
    invalid) run_model_invalid_case ;;
    timeout) run_model_timeout_case ;;
    *) die "unknown smoke-model case: ${case_name}; expected one of: ${MODEL_CASES[*]}" ;;
  esac
}

run_all_model_cases() {
  local case_name
  for case_name in "${MODEL_CASES[@]}"; do
    run_model_case_by_name "${case_name}"
    echo
  done
}

smoke_model() {
  local case_name="${1:-}"
  if [[ -z "${case_name}" ]]; then
    model_regression
    return 0
  fi

  model_prepare_environment
  run_model_case_by_name "${case_name}"
  echo
  ok "smoke-model case completed: ${case_name}"
}

usage() {
  cat <<'EOF'
Usage:
  scripts/verify_p25.sh build-fw
  scripts/verify_p25.sh flash-fw
  scripts/verify_p25.sh deploy-app
  scripts/verify_p25.sh logs
  scripts/verify_p25.sh service-check
  scripts/verify_p25.sh dumpsys
  scripts/verify_p25.sh unauthorized-check
  scripts/verify_p25.sh display-snapshot
  scripts/verify_p25.sh tail-platform [lines]
  scripts/verify_p25.sh capture
  scripts/verify_p25.sh click
  scripts/verify_p25.sh swipe
  scripts/verify_p25.sh back
  scripts/verify_p25.sh home
  scripts/verify_p25.sh long-press
  scripts/verify_p25.sh text [ascii_text]
  scripts/verify_p25.sh ask-reply [reply_text]
  scripts/verify_p25.sh regression
  scripts/verify_p25.sh model-regression
  scripts/verify_p25.sh smoke-model
  scripts/verify_p25.sh smoke-model <click|ask-user|home|invalid|timeout>
  scripts/verify_p25.sh smoke-basic
  scripts/verify_p25.sh smoke-extended
  scripts/verify_p25.sh smoke-full

Env:
  AOSP_ROOT=/home/alientek/rk3588_android_sdk
  LUNCH_TARGET=<your_rk3588_userdebug_target>
  ANDROID_PRODUCT_OUT=<aosp_out_product_dir>
  ADB_SERIAL=<device_serial>
  DEFAULT_TEXT=hello123
  MOCK_SERVER_IP=<pc_ip_for_rk3588_access>
  MOCK_SERVER_PORT=8080
  MOCK_SERVER_BIND_HOST=0.0.0.0
  MOCK_SERVER_LOG=/tmp/agenthost_mock_gateway.log
  MOCK_MODEL_API_KEY=mock-token
  MODEL_TIMEOUT_MS=10000
  MODEL_TIMEOUT_CASE_MS=2000

Notes:
  Local mock server is recommended via adb reverse in smoke-model/model-regression.
  Real remote gateways should use explicit MOCK_SERVER_IP and should not rely on auto reverse.

Examples:
  LUNCH_TARGET=rk3588_xxx-userdebug scripts/verify_p25.sh build-fw
  scripts/verify_p25.sh flash-fw
  scripts/verify_p25.sh deploy-app
  scripts/verify_p25.sh regression
  scripts/verify_p25.sh smoke-model click
  MOCK_SERVER_IP=192.168.1.10 scripts/verify_p25.sh model-regression
  MOCK_SERVER_IP=192.168.1.10 scripts/verify_p25.sh smoke-model click
  MOCK_SERVER_IP=192.168.1.10 scripts/verify_p25.sh smoke-model invalid
  scripts/verify_p25.sh long-press
  scripts/verify_p25.sh smoke-basic
  scripts/verify_p25.sh text hello123
  scripts/verify_p25.sh ask-reply hello123
  scripts/verify_p25.sh logs
EOF
}

main() {
  require_bin adb
  local cmd="${1:-}"
  case "${cmd}" in
    build-fw) build_fw ;;
    flash-fw) flash_fw ;;
    deploy-app) deploy_app ;;
    logs) logs ;;
    service-check) service_check ;;
    dumpsys) dump_platform ;;
    unauthorized-check) unauthorized_check ;;
    display-snapshot) display_snapshot ;;
    tail-platform) tail_platform "${2:-${TAIL_LINES}}" ;;
    capture) smoke_capture ;;
    click) smoke_click ;;
    swipe) smoke_swipe ;;
    back) smoke_back ;;
    home) smoke_home ;;
    long-press) smoke_long_press ;;
    text) smoke_text "${2:-${DEFAULT_TEXT}}" ;;
    ask-reply) smoke_ask_reply "${2:-${DEFAULT_TEXT}}" ;;
    regression) smoke_basic ;;
    model-regression) model_regression ;;
    smoke-model) smoke_model "${2:-}" ;;
    smoke-basic) smoke_basic ;;
    smoke-extended) smoke_extended ;;
    smoke-full) smoke_full ;;
    *) usage ;;
  esac
}

main "$@"
