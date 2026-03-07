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
  scripts/verify_p25.sh smoke-basic
  scripts/verify_p25.sh smoke-extended
  scripts/verify_p25.sh smoke-full

Env:
  AOSP_ROOT=/home/alientek/rk3588_android_sdk
  LUNCH_TARGET=<your_rk3588_userdebug_target>
  ANDROID_PRODUCT_OUT=<aosp_out_product_dir>
  ADB_SERIAL=<device_serial>
  DEFAULT_TEXT=hello123

Examples:
  LUNCH_TARGET=rk3588_xxx-userdebug scripts/verify_p25.sh build-fw
  scripts/verify_p25.sh flash-fw
  scripts/verify_p25.sh deploy-app
  scripts/verify_p25.sh regression
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
    smoke-basic) smoke_basic ;;
    smoke-extended) smoke_extended ;;
    smoke-full) smoke_full ;;
    *) usage ;;
  esac
}

main "$@"
