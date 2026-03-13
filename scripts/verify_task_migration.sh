#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PKG="${PKG:-com.example.agenthost}"
RECV="${RECV:-${PKG}/.debug.SessionAuditReceiver}"
ADB_SERIAL="${ADB_SERIAL:-}"
TAIL_LINES="${TAIL_LINES:-80}"
POLL_INTERVAL_SECS="${POLL_INTERVAL_SECS:-1}"
WAIT_TIMEOUT_SECS="${WAIT_TIMEOUT_SECS:-25}"
AUTO_CONFIRM="${AUTO_CONFIRM:-}"

PAYLOAD='{"steps":[{"action":"ASK_USER","question":"Please confirm the real Settings UI is visible on the main display, then reply YES."},{"action":"DONE","reason":"task-migration-smoke"}]}'

SESSION_ID=""
EXECUTION_DISPLAY_ID=""

adb_cmd() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    adb -s "${ADB_SERIAL}" "$@"
  else
    adb "$@"
  fi
}

log() {
  echo "[INFO] $*"
}

ok() {
  echo "[ OK ] $*"
}

warn() {
  echo "[WARN] $*"
}

die() {
  echo "[ERR ] $*" >&2
  exit 1
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
  if [[ -n "${AUTO_CONFIRM}" ]]; then
    answer="${AUTO_CONFIRM}"
    echo "${prompt} [YES/NO]: ${answer}"
  else
    read -r -p "${prompt} [YES/NO]: " answer
  fi
  case "${answer}" in
    YES|yes|Y|y) return 0 ;;
    NO|no|N|n) return 1 ;;
    *) die "expected YES or NO, got '${answer}'" ;;
  esac
}

clear_logs() {
  adb_cmd logcat -c || true
}

tail_logs() {
  adb_cmd logcat -d -v time -s SessionCoordinator SessionAuditReceiver TaskMigrationController AtmHiddenApi SessionRootActivity | tail -n "${TAIL_LINES}"
}

dump_displays() {
  adb_cmd shell dumpsys activity activities | grep -nE 'displayId=|taskId=|SessionRootActivity|com.android.settings' || true
  echo
  adb_cmd shell dumpsys window displays | grep -nE 'Display #[0-9]+|mCurrentFocus|SessionRootActivity|com.android.settings' || true
}

wait_for_log_pattern() {
  local pattern="$1"
  local timeout_secs="${2:-${WAIT_TIMEOUT_SECS}}"
  local elapsed=0
  while (( elapsed < timeout_secs )); do
    if adb_cmd logcat -d -v time -s SessionCoordinator SessionAuditReceiver TaskMigrationController AtmHiddenApi SessionRootActivity | grep -E -q "${pattern}"; then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECS}"
    elapsed=$((elapsed + POLL_INTERVAL_SECS))
  done
  return 1
}

broadcast_start_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.START_SESSION' --es command '${PAYLOAD}' --es session_display_mode 'VIRTUAL_DISPLAY' --es bootstrap_target 'settings'"
}

broadcast_dump_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.DUMP_ACTIVE_SESSION'"
}

broadcast_migrate_to_main() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.MIGRATE_TO_MAIN' --es session_id '${SESSION_ID}'"
}

broadcast_migrate_to_execution() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.MIGRATE_TO_EXECUTION' --es session_id '${SESSION_ID}'"
}

broadcast_reply_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.REPLY_SESSION' --es session_id '${SESSION_ID}' --es user_text 'YES'"
}

assert_equals() {
  local actual="$1"
  local expected="$2"
  local message="$3"
  if [[ "${actual}" != "${expected}" ]]; then
    die "${message}: expected='${expected}' actual='${actual}'"
  fi
}

assert_non_empty() {
  local value="$1"
  local message="$2"
  if [[ -z "${value}" ]]; then
    die "${message}"
  fi
}

start_session() {
  log "starting scripted WAITING_USER session on virtual display"
  local output json
  output="$(broadcast_start_session)"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  assert_non_empty "${json}" "failed to parse start_session broadcast result"

  local ok_value code status bootstrap_target
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  status="$(extract_json_string "${json}" "status")"
  bootstrap_target="$(extract_json_string "${json}" "bootstrapTarget")"
  SESSION_ID="$(extract_json_string "${json}" "sessionId")"
  EXECUTION_DISPLAY_ID="$(extract_json_number "${json}" "executionDisplayId")"

  assert_equals "${ok_value}" "true" "start_session ok"
  assert_equals "${code}" "AUTOMATION_STARTED" "start_session code"
  assert_equals "${status}" "RUNNING" "start_session status"
  assert_equals "${bootstrap_target}" "SETTINGS" "bootstrap target parse"
  assert_non_empty "${SESSION_ID}" "missing sessionId"
  assert_non_empty "${EXECUTION_DISPLAY_ID}" "missing executionDisplayId"

  ok "session started sessionId=${SESSION_ID} executionDisplayId=${EXECUTION_DISPLAY_ID}"
}

wait_for_waiting_user_and_migration() {
  log "waiting for WAITING_USER and auto migration"
  wait_for_log_pattern "event=session_waiting_user sessionId=${SESSION_ID} .* code=WAITING_USER" || die "session did not enter WAITING_USER"
  wait_for_log_pattern "event=task_migration_success sessionId=${SESSION_ID} .* code=TASK_MIGRATION_OK" || die "auto task migration to main did not succeed"
  wait_for_log_pattern "event=waiting_user_after_migration sessionId=${SESSION_ID}" || die "waiting_user_after_migration log missing"
  ok "auto migration logs detected"
}

assert_waiting_user_dump() {
  log "checking dump after auto migration"
  local output json status migrated visible task_id current_visible migration_code direction waiting_question recovery_needed task_alive task_display
  output="$(broadcast_dump_session)"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  assert_non_empty "${json}" "failed to parse dump_active_session result"

  status="$(extract_json_string "${json}" "status")"
  migrated="$(extract_json_bool "${json}" "migratedToMain")"
  visible="$(extract_json_bool "${json}" "visibleToUser")"
  task_id="$(extract_json_number "${json}" "lastKnownTaskId")"
  current_visible="$(extract_json_number "${json}" "currentVisibleDisplayId")"
  migration_code="$(extract_json_string "${json}" "lastMigrationCode")"
  direction="$(extract_json_string "${json}" "lastMigrationDirection")"
  waiting_question="$(extract_json_string "${json}" "waitingQuestion")"
  recovery_needed="$(extract_json_bool "${json}" "recoveryNeeded")"
  task_alive="$(extract_json_bool "${json}" "lastKnownTaskAlive")"
  task_display="$(extract_json_number "${json}" "lastKnownTaskDisplayId")"

  assert_equals "${status}" "WAITING_USER" "dump status after auto migration"
  assert_equals "${migrated}" "true" "dump migratedToMain after auto migration"
  assert_equals "${visible}" "true" "dump visibleToUser after auto migration"
  assert_equals "${current_visible}" "0" "dump currentVisibleDisplayId after auto migration"
  if [[ -z "${task_id}" || "${task_id}" == "-1" ]]; then
    die "expected lastKnownTaskId after auto migration, got '${task_id}'"
  fi
  assert_equals "${migration_code}" "TASK_MIGRATION_OK" "dump lastMigrationCode after auto migration"
  assert_equals "${direction}" "TO_MAIN" "dump lastMigrationDirection after auto migration"
  assert_equals "${recovery_needed}" "false" "dump recoveryNeeded after auto migration"
  assert_equals "${task_alive}" "true" "dump lastKnownTaskAlive after auto migration"
  assert_equals "${task_display}" "0" "dump lastKnownTaskDisplayId after auto migration"
  assert_non_empty "${waiting_question}" "missing waitingQuestion after auto migration"

  ok "dump confirms WAITING_USER on main, taskId=${task_id}"
}

debug_migrate_back() {
  log "sending explicit migrate-to-execution"
  local output json ok_value code migrated current_visible
  output="$(broadcast_migrate_to_execution)"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  assert_non_empty "${json}" "failed to parse migrate_to_execution result"

  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  migrated="$(extract_json_bool "${json}" "migratedToMain")"
  current_visible="$(extract_json_number "${json}" "currentVisibleDisplayId")"

  assert_equals "${ok_value}" "true" "migrate_to_execution ok"
  assert_equals "${code}" "TASK_MIGRATION_BACK_OK" "migrate_to_execution code"
  assert_equals "${migrated}" "false" "migrate_to_execution migratedToMain"
  assert_equals "${current_visible}" "${EXECUTION_DISPLAY_ID}" "migrate_to_execution currentVisibleDisplayId"
  wait_for_log_pattern "event=task_migration_back_success sessionId=${SESSION_ID} .* code=TASK_MIGRATION_BACK_OK" || die "task_migration_back_success log missing"

  ok "explicit migrate-to-execution succeeded"
}

debug_migrate_to_main_again() {
  log "sending explicit migrate-to-main"
  local output json ok_value code migrated current_visible
  output="$(broadcast_migrate_to_main)"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  assert_non_empty "${json}" "failed to parse migrate_to_main result"

  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  migrated="$(extract_json_bool "${json}" "migratedToMain")"
  current_visible="$(extract_json_number "${json}" "currentVisibleDisplayId")"

  assert_equals "${ok_value}" "true" "migrate_to_main ok"
  assert_equals "${code}" "TASK_MIGRATION_OK" "migrate_to_main code"
  assert_equals "${migrated}" "true" "migrate_to_main migratedToMain"
  assert_equals "${current_visible}" "0" "migrate_to_main currentVisibleDisplayId"
  wait_for_log_pattern "event=task_migration_success sessionId=${SESSION_ID} .* code=TASK_MIGRATION_OK" || die "task_migration_success log missing after explicit migrate"

  ok "explicit migrate-to-main succeeded"
}

reply_and_complete() {
  log "replying YES to resume automation"
  local output json ok_value code
  output="$(broadcast_reply_session)"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  assert_non_empty "${json}" "failed to parse reply_session result"

  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"

  assert_equals "${ok_value}" "true" "reply_session ok"
  assert_equals "${code}" "AUTOMATION_RESUMED" "reply_session code"
  wait_for_log_pattern "event=task_migration_back_success sessionId=${SESSION_ID} .* code=TASK_MIGRATION_BACK_OK" || die "reply path task_migration_back_success log missing"
  wait_for_log_pattern "event=automation_refresh_after_migration sessionId=${SESSION_ID} .* observationDisplayId=${EXECUTION_DISPLAY_ID}" || die "automation_refresh_after_migration log missing"
  wait_for_log_pattern "event=automation_resume_after_migration sessionId=${SESSION_ID}" || die "automation_resume_after_migration log missing"
  wait_for_log_pattern "event=session_completed sessionId=${SESSION_ID}" || die "session_completed log missing"

  ok "reply path completed"
}

final_dump() {
  log "final dump after completion"
  local output json status direction code recovery_needed
  output="$(broadcast_dump_session)"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  assert_non_empty "${json}" "failed to parse final dump_active_session result"

  status="$(extract_json_string "${json}" "status")"
  direction="$(extract_json_string "${json}" "lastMigrationDirection")"
  code="$(extract_json_string "${json}" "lastMigrationCode")"
  recovery_needed="$(extract_json_bool "${json}" "recoveryNeeded")"

  assert_equals "${status}" "COMPLETED" "final session status"
  assert_equals "${direction}" "TO_EXECUTION" "final migration direction"
  assert_equals "${code}" "TASK_MIGRATION_BACK_OK" "final migration code"
  assert_equals "${recovery_needed}" "false" "final recoveryNeeded"

  ok "final dump confirms COMPLETED with last migration back to execution"
}

cleanup_session() {
  local output json status
  output="$(broadcast_dump_session)"
  json="$(extract_broadcast_json "${output}")"
  if [[ -n "${json}" ]]; then
    status="$(extract_json_string "${json}" "status")"
    if [[ "${status}" != "NO_ACTIVE_SESSION" && -n "${status}" && "${status}" != "COMPLETED" && "${status}" != "FAILED" && "${status}" != "CLOSED" ]]; then
      warn "stopping leftover sessionId=${SESSION_ID}"
      adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.STOP_SESSION' --ez migrate false" >/dev/null || true
    fi
  fi
}

main() {
  cd "${PROJECT_ROOT}"
  trap cleanup_session EXIT

  clear_logs
  start_session
  wait_for_waiting_user_and_migration
  assert_waiting_user_dump
  dump_displays

  if prompt_yes_no "Is the real Settings UI visible on the main display, and not the SessionRoot placeholder?"; then
    ok "visual confirmation on main display passed"
  else
    die "visual confirmation failed: real Settings UI not visible on main display"
  fi

  debug_migrate_back
  dump_displays
  if prompt_yes_no "After migrate-to-execution, did the Settings UI leave the main display?"; then
    ok "visual confirmation after migrate-to-execution passed"
  else
    die "visual confirmation failed: Settings UI still visible on main display after migrate-to-execution"
  fi

  debug_migrate_to_main_again
  dump_displays
  if prompt_yes_no "After migrate-to-main again, is the real Settings UI back on the main display?"; then
    ok "visual confirmation after migrate-to-main passed"
  else
    die "visual confirmation failed: Settings UI did not return to main display"
  fi

  reply_and_complete
  dump_displays
  if prompt_yes_no "After reply and resume, did the Settings UI leave the main display again?"; then
    ok "visual confirmation after reply passed"
  else
    die "visual confirmation failed: Settings UI did not leave the main display after reply"
  fi

  final_dump
  echo
  ok "task migration verification passed for sessionId=${SESSION_ID}"
  echo
  tail_logs
}

main "$@"
