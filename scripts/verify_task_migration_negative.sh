#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PKG="${PKG:-com.example.agenthost}"
RECV="${RECV:-${PKG}/.debug.SessionAuditReceiver}"
ADB_SERIAL="${ADB_SERIAL:-}"
TAIL_LINES="${TAIL_LINES:-120}"
POLL_INTERVAL_SECS="${POLL_INTERVAL_SECS:-1}"
WAIT_TIMEOUT_SECS="${WAIT_TIMEOUT_SECS:-25}"
CLEAR_LOGS_PER_CASE="${CLEAR_LOGS_PER_CASE:-1}"

SESSION_ID=""
EXECUTION_DISPLAY_ID=""

PAYLOAD_NO_TARGET='{"steps":[{"action":"ASK_USER","question":"No target should be found. Reply YES to continue."},{"action":"DONE","reason":"negative-no-target"}]}'
PAYLOAD_IDEMPOTENT='{"steps":[{"action":"ASK_USER","question":"Idempotent migration test. Reply YES to continue."},{"action":"DONE","reason":"negative-idempotent"}]}'

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
  echo "[WARN] $*" >&2
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

tail_logs() {
  adb_cmd logcat -d -v time -s SessionCoordinator SessionAuditReceiver TaskMigrationController AtmHiddenApi SessionRootActivity | tail -n "${TAIL_LINES}"
}

clear_logs() {
  if [[ "${CLEAR_LOGS_PER_CASE}" == "0" ]]; then
    return 0
  fi
  adb_cmd logcat -c || true
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
  local payload="$1"
  local bootstrap_target="${2:-}"
  if [[ -n "${bootstrap_target}" ]]; then
    adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.START_SESSION' --es command '${payload}' --es session_display_mode 'VIRTUAL_DISPLAY' --es bootstrap_target '${bootstrap_target}'"
  else
    adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.START_SESSION' --es command '${payload}' --es session_display_mode 'VIRTUAL_DISPLAY'"
  fi
}

broadcast_dump_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.DUMP_ACTIVE_SESSION'"
}

broadcast_migrate_to_main() {
  local session_id="$1"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.MIGRATE_TO_MAIN' --es session_id '${session_id}'"
}

broadcast_migrate_to_execution() {
  local session_id="$1"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.MIGRATE_TO_EXECUTION' --es session_id '${session_id}'"
}

broadcast_reply_session() {
  local session_id="$1"
  local reply_text="${2:-YES}"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.REPLY_SESSION' --es session_id '${session_id}' --es user_text '${reply_text}'"
}

broadcast_stop_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.STOP_SESSION' --ez migrate false"
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

start_session_and_capture_ids() {
  local payload="$1"
  local bootstrap_target="${2:-}"
  local output json ok_value code status
  output="$(broadcast_start_session "${payload}" "${bootstrap_target}")"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  assert_non_empty "${json}" "failed to parse start_session result"

  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  status="$(extract_json_string "${json}" "status")"
  SESSION_ID="$(extract_json_string "${json}" "sessionId")"
  EXECUTION_DISPLAY_ID="$(extract_json_number "${json}" "executionDisplayId")"

  assert_equals "${ok_value}" "true" "start_session ok"
  assert_equals "${code}" "AUTOMATION_STARTED" "start_session code"
  assert_equals "${status}" "RUNNING" "start_session status"
  assert_non_empty "${SESSION_ID}" "missing sessionId"
  assert_non_empty "${EXECUTION_DISPLAY_ID}" "missing executionDisplayId"

  ok "started sessionId=${SESSION_ID} executionDisplayId=${EXECUTION_DISPLAY_ID}"
}

dump_and_assert() {
  local expected_status="$1"
  local expected_migrated="$2"
  local expected_visible_display="$3"
  local expected_migration_code="$4"
  local expected_migration_direction="$5"
  local expected_last_known_task_id="$6"
  local expected_recovery_needed="$7"
  local output json status migrated visible_display migration_code direction task_id recovery_needed

  output="$(broadcast_dump_session)"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  assert_non_empty "${json}" "failed to parse dump_active_session result"

  status="$(extract_json_string "${json}" "status")"
  migrated="$(extract_json_bool "${json}" "migratedToMain")"
  visible_display="$(extract_json_number "${json}" "currentVisibleDisplayId")"
  migration_code="$(extract_json_string "${json}" "lastMigrationCode")"
  direction="$(extract_json_string "${json}" "lastMigrationDirection")"
  task_id="$(extract_json_number "${json}" "lastKnownTaskId")"
  recovery_needed="$(extract_json_bool "${json}" "recoveryNeeded")"

  assert_equals "${status}" "${expected_status}" "dump status"
  assert_equals "${migrated}" "${expected_migrated}" "dump migratedToMain"
  assert_equals "${visible_display}" "${expected_visible_display}" "dump currentVisibleDisplayId"
  assert_equals "${migration_code}" "${expected_migration_code}" "dump lastMigrationCode"
  assert_equals "${direction}" "${expected_migration_direction}" "dump lastMigrationDirection"
  assert_equals "${task_id}" "${expected_last_known_task_id}" "dump lastKnownTaskId"
  assert_equals "${recovery_needed}" "${expected_recovery_needed}" "dump recoveryNeeded"
}

case_no_target_is_non_terminal() {
  log "case=no-target-non-terminal"
  clear_logs
  best_effort_stop_active
  start_session_and_capture_ids "${PAYLOAD_NO_TARGET}"

  wait_for_log_pattern "event=session_waiting_user sessionId=${SESSION_ID} .* code=WAITING_USER" || die "no-target case did not enter WAITING_USER"
  wait_for_log_pattern "event=task_migration_failed sessionId=${SESSION_ID} .* code=TARGET_TASK_NOT_FOUND" || die "no-target case missing task_migration_failed TARGET_TASK_NOT_FOUND"
  dump_and_assert "WAITING_USER" "false" "${EXECUTION_DISPLAY_ID}" "TARGET_TASK_NOT_FOUND" "TO_MAIN" "-1" "true"

  local output json ok_value code

  output="$(broadcast_migrate_to_main "${SESSION_ID}")"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${ok_value}" "false" "migrate_to_main without target ok"
  assert_equals "${code}" "TARGET_TASK_NOT_FOUND" "migrate_to_main without target code"

  output="$(broadcast_migrate_to_execution "${SESSION_ID}")"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${ok_value}" "true" "migrate_to_execution when not migrated ok"
  assert_equals "${code}" "TASK_MIGRATION_BACK_SKIPPED" "migrate_to_execution when not migrated code"

  output="$(broadcast_reply_session "${SESSION_ID}" "YES")"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${ok_value}" "true" "reply_session after migration failure ok"
  assert_equals "${code}" "AUTOMATION_RESUMED" "reply_session after migration failure code"
  wait_for_log_pattern "event=session_completed sessionId=${SESSION_ID}" || die "no-target case did not complete after reply"

  output="$(broadcast_dump_session)"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  assert_equals "$(extract_json_string "${json}" "status")" "COMPLETED" "no-target final status"
  assert_equals "$(extract_json_bool "${json}" "migratedToMain")" "false" "no-target final migratedToMain"
  assert_equals "$(extract_json_number "${json}" "currentVisibleDisplayId")" "${EXECUTION_DISPLAY_ID}" "no-target final currentVisibleDisplayId"
  assert_equals "$(extract_json_bool "${json}" "recoveryNeeded")" "false" "no-target final recoveryNeeded"

  ok "case=no-target-non-terminal passed"
}

case_idempotent_paths() {
  log "case=idempotent-paths"
  clear_logs
  best_effort_stop_active
  start_session_and_capture_ids "${PAYLOAD_IDEMPOTENT}" "settings"

  wait_for_log_pattern "event=task_migration_success sessionId=${SESSION_ID} .* code=TASK_MIGRATION_OK" || die "idempotent case missing initial task_migration_success"
  dump_and_assert "WAITING_USER" "true" "0" "TASK_MIGRATION_OK" "TO_MAIN" "$(extract_json_number "$(extract_broadcast_json "$(broadcast_dump_session)")" "lastKnownTaskId")" "false"

  local output json ok_value code

  output="$(broadcast_migrate_to_main "${SESSION_ID}")"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${ok_value}" "true" "migrate_to_main already on main ok"
  assert_equals "${code}" "TASK_MIGRATION_ALREADY_ON_MAIN" "migrate_to_main already on main code"

  output="$(broadcast_migrate_to_execution "${SESSION_ID}")"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${ok_value}" "true" "first migrate_to_execution ok"
  assert_equals "${code}" "TASK_MIGRATION_BACK_OK" "first migrate_to_execution code"

  output="$(broadcast_migrate_to_execution "${SESSION_ID}")"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${ok_value}" "true" "second migrate_to_execution ok"
  assert_equals "${code}" "TASK_MIGRATION_BACK_SKIPPED" "second migrate_to_execution code"

  broadcast_stop_session >/dev/null
  wait_for_log_pattern "event=session_closed sessionId=${SESSION_ID}" || die "idempotent case did not stop cleanly"
  ok "case=idempotent-paths passed"
}

case_invalid_session_id() {
  log "case=invalid-session-id"
  clear_logs
  best_effort_stop_active
  start_session_and_capture_ids "${PAYLOAD_IDEMPOTENT}" "settings"

  wait_for_log_pattern "event=session_waiting_user sessionId=${SESSION_ID}" || die "invalid-session-id case did not enter WAITING_USER"
  local bad_session_id="00000000-0000-0000-0000-000000000000"
  local output json ok_value code

  output="$(broadcast_migrate_to_main "${bad_session_id}")"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${ok_value}" "false" "migrate_to_main wrong session ok"
  assert_equals "${code}" "TASK_MIGRATION_NO_ACTIVE_SESSION" "migrate_to_main wrong session code"

  output="$(broadcast_migrate_to_execution "${bad_session_id}")"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${ok_value}" "false" "migrate_to_execution wrong session ok"
  assert_equals "${code}" "TASK_MIGRATION_NO_ACTIVE_SESSION" "migrate_to_execution wrong session code"

  output="$(broadcast_reply_session "${bad_session_id}" "YES")"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${ok_value}" "false" "reply_session wrong session ok"
  assert_equals "${code}" "SESSION_ID_MISMATCH" "reply_session wrong session code"

  broadcast_stop_session >/dev/null
  wait_for_log_pattern "event=session_closed sessionId=${SESSION_ID}" || die "invalid-session-id case did not stop cleanly"
  ok "case=invalid-session-id passed"
}

main() {
  cd "${PROJECT_ROOT}"

  case_no_target_is_non_terminal
  case_idempotent_paths
  case_invalid_session_id

  echo
  ok "all negative migration checks passed"
  echo
  tail_logs
}

main "$@"
