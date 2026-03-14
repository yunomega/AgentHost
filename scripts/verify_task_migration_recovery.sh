#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PKG="${PKG:-com.example.agenthost}"
RECV="${RECV:-${PKG}/.debug.SessionAuditReceiver}"
ADB_SERIAL="${ADB_SERIAL:-}"
WAIT_TIMEOUT_SECS="${WAIT_TIMEOUT_SECS:-30}"
POLL_INTERVAL_SECS="${POLL_INTERVAL_SECS:-1}"
LOG_DIR="${LOG_DIR:-$(mktemp -d /tmp/agenthost-task-migration-recovery.XXXXXX)}"
FAULT_CASES="${FAULT_CASES:-TARGET_TASK_REMOVED TASK_ID_STALE TASK_MOVE_BACK_CONFLICT}"
IDEMPOTENT_ROUNDS="${IDEMPOTENT_ROUNDS:-2}"
TAIL_LINES="${TAIL_LINES:-160}"

PAYLOAD='{"steps":[{"action":"ASK_USER","question":"Recovery loop fault injection. Reply YES to continue."},{"action":"DONE","reason":"recovery-loop"}]}'

SESSION_ID=""
EXECUTION_DISPLAY_ID=""
CURRENT_FAULT=""
CURRENT_ROUND=0
CURRENT_DIR=""

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
  printf '%s\n' "${json}" | sed -n "s/.*\"${key}\":\"\([^\"]*\)\".*/\1/p"
}

extract_json_bool() {
  local json="$1"
  local key="$2"
  printf '%s\n' "${json}" | sed -n "s/.*\"${key}\":\(true\|false\).*/\1/p"
}

extract_json_number() {
  local json="$1"
  local key="$2"
  printf '%s\n' "${json}" | sed -n "s/.*\"${key}\":\(-\{0,1\}[0-9][0-9]*\).*/\1/p"
}

assert_equals() {
  local actual="$1"
  local expected="$2"
  local message="$3"
  if [[ "${actual}" != "${expected}" ]]; then
    die "${message}: expected='${expected}' actual='${actual}' fault=${CURRENT_FAULT} round=${CURRENT_ROUND}"
  fi
}

assert_non_empty() {
  local value="$1"
  local message="$2"
  if [[ -z "${value}" ]]; then
    die "${message}: fault=${CURRENT_FAULT} round=${CURRENT_ROUND}"
  fi
}

clear_logs() {
  adb_cmd logcat -c || true
}

capture_logs() {
  local dest="$1"
  adb_cmd logcat -d -v time -s SessionCoordinator SessionAuditReceiver TaskMigrationController AtmHiddenApi SessionRootActivity > "${dest}"
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

broadcast_reply_session() {
  local session_id="$1"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.REPLY_SESSION' --es session_id '${session_id}' --es user_text 'YES'"
}

broadcast_stop_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.STOP_SESSION' --ez migrate false"
}

broadcast_migrate_to_execution() {
  local session_id="$1"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.MIGRATE_TO_EXECUTION' --es session_id '${session_id}'"
}

broadcast_arm_fault() {
  local direction="$1"
  local code="$2"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.ARM_MIGRATION_FAULT' --es fault_direction '${direction}' --es fault_code '${code}'"
}

broadcast_dump_debug_hooks() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.DUMP_DEBUG_HOOKS'"
}

broadcast_clear_debug_hooks() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.CLEAR_DEBUG_HOOKS'"
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

reset_debug_hooks() {
  local output json ok_value code
  output="$(broadcast_clear_debug_hooks)"
  printf '%s\n' "${output}" > "${CURRENT_DIR}/clear_debug_hooks.stdout.log"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${ok_value}" "true" "clear_debug_hooks ok"
  assert_equals "${code}" "DEBUG_HOOKS_CLEARED" "clear_debug_hooks code"
}

assert_hooks_cleared() {
  local output json ok_value code fault_code fault_direction
  output="$(broadcast_dump_debug_hooks)"
  printf '%s\n' "${output}" > "${CURRENT_DIR}/dump_debug_hooks.stdout.log"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  fault_code="$(extract_json_string "${json}" "migrationFaultCode")"
  fault_direction="$(extract_json_string "${json}" "migrationFaultDirection")"
  assert_equals "${ok_value}" "true" "dump_debug_hooks ok"
  assert_equals "${code}" "DEBUG_HOOKS_STATE" "dump_debug_hooks code"
  if [[ -n "${fault_code}" || -n "${fault_direction}" ]]; then
    die "expected one-shot debug fault to be cleared, found direction='${fault_direction}' code='${fault_code}' fault=${CURRENT_FAULT} round=${CURRENT_ROUND}"
  fi
}

start_session_and_wait_handoff() {
  local output json ok_value code status
  output="$(broadcast_start_session)"
  printf '%s\n' "${output}" > "${CURRENT_DIR}/start_session.stdout.log"
  json="$(extract_broadcast_json "${output}")"
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
  wait_for_log_pattern "event=session_waiting_user sessionId=${SESSION_ID} .* code=WAITING_USER" || die "session did not enter WAITING_USER"
  wait_for_log_pattern "event=task_migration_success sessionId=${SESSION_ID} .* code=TASK_MIGRATION_OK" || die "session did not auto-migrate to main"
}

arm_fault_and_assert() {
  local output json ok_value code armed_fault armed_direction
  output="$(broadcast_arm_fault TO_EXECUTION "${CURRENT_FAULT}")"
  printf '%s\n' "${output}" > "${CURRENT_DIR}/arm_fault.stdout.log"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  armed_fault="$(extract_json_string "${json}" "migrationFaultCode")"
  armed_direction="$(extract_json_string "${json}" "migrationFaultDirection")"
  assert_equals "${ok_value}" "true" "arm_fault ok"
  assert_equals "${code}" "DEBUG_MIGRATION_FAULT_ARMED" "arm_fault code"
  assert_equals "${armed_fault}" "${CURRENT_FAULT}" "arm_fault reported code"
  assert_equals "${armed_direction}" "TO_EXECUTION" "arm_fault reported direction"
}

reply_and_expect_precheck_failure() {
  local output json ok_value code precheck_code
  output="$(broadcast_reply_session "${SESSION_ID}")"
  printf '%s\n' "${output}" > "${CURRENT_DIR}/reply_failure.stdout.log"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  precheck_code="$(extract_json_string "${json}" "precheckCode")"
  assert_equals "${ok_value}" "false" "reply should fail on injected migration fault"
  assert_equals "${code}" "TASK_MIGRATION_PRECHECK_FAILED" "reply failure code"
  assert_equals "${precheck_code}" "${CURRENT_FAULT}" "reply precheckCode"
}

assert_waiting_recovery_state() {
  local output json status migrated visible_display migration_code direction recovery_needed
  output="$(broadcast_dump_session)"
  printf '%s\n' "${output}" > "${CURRENT_DIR}/dump_after_failure.stdout.log"
  json="$(extract_broadcast_json "${output}")"
  status="$(extract_json_string "${json}" "status")"
  migrated="$(extract_json_bool "${json}" "migratedToMain")"
  visible_display="$(extract_json_number "${json}" "currentVisibleDisplayId")"
  migration_code="$(extract_json_string "${json}" "lastMigrationCode")"
  direction="$(extract_json_string "${json}" "lastMigrationDirection")"
  recovery_needed="$(extract_json_bool "${json}" "recoveryNeeded")"
  assert_equals "${status}" "WAITING_USER" "post-failure status"
  assert_equals "${migrated}" "true" "post-failure migratedToMain"
  assert_equals "${visible_display}" "0" "post-failure currentVisibleDisplayId"
  assert_equals "${migration_code}" "${CURRENT_FAULT}" "post-failure lastMigrationCode"
  assert_equals "${direction}" "TO_EXECUTION" "post-failure lastMigrationDirection"
  assert_equals "${recovery_needed}" "true" "post-failure recoveryNeeded"
}

recover_without_rearming() {
  local output json ok_value code migrated visible_display recovery_needed
  output="$(broadcast_migrate_to_execution "${SESSION_ID}")"
  printf '%s\n' "${output}" > "${CURRENT_DIR}/migrate_back_recovery.stdout.log"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  migrated="$(extract_json_bool "${json}" "migratedToMain")"
  visible_display="$(extract_json_number "${json}" "currentVisibleDisplayId")"
  recovery_needed="$(extract_json_bool "${json}" "recoveryNeeded")"
  assert_equals "${ok_value}" "true" "manual migrate_to_execution recovery ok"
  assert_equals "${code}" "TASK_MIGRATION_BACK_OK" "manual migrate_to_execution recovery code"
  assert_equals "${migrated}" "false" "manual migrate_to_execution recovery migratedToMain"
  assert_equals "${visible_display}" "${EXECUTION_DISPLAY_ID}" "manual migrate_to_execution recovery currentVisibleDisplayId"
  assert_equals "${recovery_needed}" "false" "manual migrate_to_execution recoveryNeeded"
  wait_for_log_pattern "event=task_migration_back_success sessionId=${SESSION_ID} .* code=TASK_MIGRATION_BACK_OK" || die "manual migrate_to_execution recovery log missing"
}

reply_and_complete_cleanly() {
  local output json ok_value code
  output="$(broadcast_reply_session "${SESSION_ID}")"
  printf '%s\n' "${output}" > "${CURRENT_DIR}/reply_resume.stdout.log"
  json="$(extract_broadcast_json "${output}")"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${ok_value}" "true" "reply after recovery ok"
  assert_equals "${code}" "AUTOMATION_RESUMED" "reply after recovery code"
  wait_for_log_pattern "event=automation_resumed_after_reply sessionId=${SESSION_ID} .* code=AUTOMATION_RESUMED" || die "resume-after-reply log missing after recovery"
  wait_for_log_pattern "event=session_completed sessionId=${SESSION_ID} .* code=COMPLETED" || die "session did not complete after recovery"
}

assert_no_active_session_after_cleanup() {
  local output json code
  output="$(broadcast_dump_session)"
  printf '%s\n' "${output}" > "${CURRENT_DIR}/dump_after_cleanup.stdout.log"
  json="$(extract_broadcast_json "${output}")"
  code="$(extract_json_string "${json}" "code")"
  assert_equals "${code}" "NO_ACTIVE_SESSION" "post-cleanup code"
}

capture_case_artifacts() {
  capture_logs "${CURRENT_DIR}/device.log"
  adb_cmd shell dumpsys activity activities > "${CURRENT_DIR}/activity.dump"
  adb_cmd shell dumpsys window displays > "${CURRENT_DIR}/window.dump"
}

run_fault_round() {
  CURRENT_FAULT="$1"
  CURRENT_ROUND="$2"
  CURRENT_DIR="${LOG_DIR}/${CURRENT_FAULT}/round_${CURRENT_ROUND}"
  mkdir -p "${CURRENT_DIR}"

  log "running recovery fault=${CURRENT_FAULT} round=${CURRENT_ROUND}"
  clear_logs
  best_effort_stop_active
  reset_debug_hooks
  start_session_and_wait_handoff
  arm_fault_and_assert
  reply_and_expect_precheck_failure
  assert_waiting_recovery_state
  assert_hooks_cleared
  recover_without_rearming
  assert_hooks_cleared
  reply_and_complete_cleanly
  capture_case_artifacts
  broadcast_stop_session >/dev/null
  sleep 1
  assert_no_active_session_after_cleanup
  reset_debug_hooks
  ok "recovery fault=${CURRENT_FAULT} round=${CURRENT_ROUND} passed"
}

main() {
  mkdir -p "${LOG_DIR}"
  cd "${PROJECT_ROOT}"
  local fault_code round
  for fault_code in ${FAULT_CASES}; do
    for round in $(seq 1 "${IDEMPOTENT_ROUNDS}"); do
      run_fault_round "${fault_code}" "${round}"
    done
  done

  echo
  ok "task migration recovery loop passed"
  echo "logs saved under ${LOG_DIR}"
  echo
  adb_cmd logcat -d -v time -s SessionCoordinator SessionAuditReceiver TaskMigrationController AtmHiddenApi SessionRootActivity | tail -n "${TAIL_LINES}"
}

main "$@"
