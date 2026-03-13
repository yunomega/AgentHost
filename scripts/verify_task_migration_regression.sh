#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PKG="${PKG:-com.example.agenthost}"
ADB_SERIAL="${ADB_SERIAL:-}"
TAIL_LINES="${TAIL_LINES:-120}"
AUTO_CONFIRM="${AUTO_CONFIRM:-}"
LOG_DIR="${LOG_DIR:-$(mktemp -d /tmp/agenthost-task-migration-regression.XXXXXX)}"

POSITIVE_SCRIPT="${SCRIPT_DIR}/verify_task_migration.sh"
NEGATIVE_SCRIPT="${SCRIPT_DIR}/verify_task_migration_negative.sh"
CONCURRENCY_SCRIPT="${SCRIPT_DIR}/verify_task_migration_concurrency.sh"
RUN_CONCURRENCY="${RUN_CONCURRENCY:-0}"

POSITIVE_STDOUT_LOG="${LOG_DIR}/positive.stdout.log"
POSITIVE_DEVICE_LOG="${LOG_DIR}/positive.device.log"
NEGATIVE_STDOUT_LOG="${LOG_DIR}/negative.stdout.log"
NEGATIVE_DEVICE_LOG="${LOG_DIR}/negative.device.log"
CONCURRENCY_STDOUT_LOG="${LOG_DIR}/concurrency.stdout.log"
CONCURRENCY_DEVICE_LOG="${LOG_DIR}/concurrency.device.log"

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

die() {
  echo "[ERR ] $*" >&2
  exit 1
}

assert_file_contains() {
  local file="$1"
  local pattern="$2"
  local message="$3"
  if ! grep -E -q "${pattern}" "${file}"; then
    die "${message}: pattern='${pattern}' file='${file}'"
  fi
}

assert_line_has_tokens() {
  local file="$1"
  local event="$2"
  shift 2
  local line
  line="$(grep "event=${event} " "${file}" | tail -n 1 || true)"
  [[ -n "${line}" ]] || die "missing log event=${event} in ${file}"
  local token
  for token in "$@"; do
    [[ "${line}" == *"${token}"* ]] || die "event=${event} missing token='${token}' line='${line}'"
  done
}

capture_device_log() {
  local dest="$1"
  adb_cmd logcat -d -v time -s \
    SessionCoordinator \
    SessionAuditReceiver \
    TaskMigrationController \
    AtmHiddenApi \
    SessionRootActivity \
    SessionAutomationEngine \
    AgentHostPlatformClient \
    PlatformDisplayExecutor \
    PlatformObservationProvider > "${dest}"
}

clear_device_log() {
  adb_cmd logcat -c || true
}

run_child_script() {
  local child_script="$1"
  local stdout_log="$2"
  shift 2
  (
    cd "${PROJECT_ROOT}"
    env AUTO_CONFIRM="${AUTO_CONFIRM}" ADB_SERIAL="${ADB_SERIAL}" TAIL_LINES="${TAIL_LINES}" "$@" "${child_script}"
  ) | tee "${stdout_log}"
}

verify_positive_stdout_fields() {
  log "verifying positive-script dump fields"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"currentExecutionDisplayId":' "positive stdout missing currentExecutionDisplayId"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"currentVisibleDisplayId":' "positive stdout missing currentVisibleDisplayId"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"lastMigrationCode":' "positive stdout missing lastMigrationCode"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"lastMigrationDirection":' "positive stdout missing lastMigrationDirection"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"lastMigrationConflictCode":' "positive stdout missing lastMigrationConflictCode"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"migratedToMain":' "positive stdout missing migratedToMain"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"lastKnownTaskId":' "positive stdout missing lastKnownTaskId"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"lastKnownTaskAlive":' "positive stdout missing lastKnownTaskAlive"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"lastKnownTaskDisplayId":' "positive stdout missing lastKnownTaskDisplayId"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"userForegroundTaskId":' "positive stdout missing userForegroundTaskId"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"recoveryNeeded":' "positive stdout missing recoveryNeeded"
  assert_file_contains "${POSITIVE_STDOUT_LOG}" '"bootstrapTarget":"SETTINGS"' "positive stdout missing bootstrapTarget SETTINGS"
  ok "positive stdout fields verified"
}

verify_positive_device_logs() {
  log "verifying positive-script migration logs"
  assert_line_has_tokens "${POSITIVE_DEVICE_LOG}" "task_migration_precheck" "expectedTaskId=" "actualForegroundTaskId=" "sourceDisplayId=" "targetDisplayId=" "package=" "topActivity=" "reason="
  assert_line_has_tokens "${POSITIVE_DEVICE_LOG}" "task_migration_begin" "sourceDisplayId=" "targetDisplayId=" "taskId=" "topActivity=" "package=" "reason="
  assert_line_has_tokens "${POSITIVE_DEVICE_LOG}" "task_migration_success" "sourceDisplayId=" "targetDisplayId=" "taskId=" "topActivity=" "package=" "reason="
  assert_line_has_tokens "${POSITIVE_DEVICE_LOG}" "task_migration_back_begin" "sourceDisplayId=" "targetDisplayId=" "taskId=" "topActivity=" "package=" "reason="
  assert_line_has_tokens "${POSITIVE_DEVICE_LOG}" "task_migration_back_success" "sourceDisplayId=" "targetDisplayId=" "taskId=" "topActivity=" "package=" "reason="
  assert_line_has_tokens "${POSITIVE_DEVICE_LOG}" "waiting_user_after_migration" "sourceDisplayId=" "targetDisplayId=" "taskId=" "topActivity=" "package=" "reason="
  assert_line_has_tokens "${POSITIVE_DEVICE_LOG}" "automation_refresh_after_migration" "sourceDisplayId=" "targetDisplayId=" "observationDisplayId=" "expectedTaskId=" "actualForegroundTaskId=" "package=" "topActivity=" "reason="
  assert_line_has_tokens "${POSITIVE_DEVICE_LOG}" "automation_resume_after_migration" "sourceDisplayId=" "targetDisplayId=" "taskId=" "topActivity=" "package=" "reason="
  assert_file_contains "${POSITIVE_DEVICE_LOG}" 'event=session_completed .* code=COMPLETED' "positive device log missing session_completed COMPLETED"
  ok "positive device logs verified"
}

verify_negative_stdout_fields() {
  log "verifying negative-script result codes"
  assert_file_contains "${NEGATIVE_STDOUT_LOG}" '"code":"TARGET_TASK_NOT_FOUND"' "negative stdout missing TARGET_TASK_NOT_FOUND"
  assert_file_contains "${NEGATIVE_STDOUT_LOG}" '"code":"TASK_MIGRATION_BACK_SKIPPED"' "negative stdout missing TASK_MIGRATION_BACK_SKIPPED"
  assert_file_contains "${NEGATIVE_STDOUT_LOG}" '"code":"TASK_MIGRATION_ALREADY_ON_MAIN"' "negative stdout missing TASK_MIGRATION_ALREADY_ON_MAIN"
  assert_file_contains "${NEGATIVE_STDOUT_LOG}" '"code":"TASK_MIGRATION_NO_ACTIVE_SESSION"' "negative stdout missing TASK_MIGRATION_NO_ACTIVE_SESSION"
  assert_file_contains "${NEGATIVE_STDOUT_LOG}" '"code":"SESSION_ID_MISMATCH"' "negative stdout missing SESSION_ID_MISMATCH"
  assert_file_contains "${NEGATIVE_STDOUT_LOG}" '"recoveryNeeded":true' "negative stdout missing recoveryNeeded true"
  ok "negative stdout codes verified"
}

verify_negative_device_logs() {
  log "verifying negative-script failure logs"
  assert_line_has_tokens "${NEGATIVE_DEVICE_LOG}" "task_migration_precheck" "expectedTaskId=" "actualForegroundTaskId=" "sourceDisplayId=" "targetDisplayId=" "package=" "topActivity=" "reason="
  assert_line_has_tokens "${NEGATIVE_DEVICE_LOG}" "task_migration_failed" "sourceDisplayId=" "targetDisplayId=" "taskId=" "topActivity=" "package=" "reason="
  assert_file_contains "${NEGATIVE_DEVICE_LOG}" 'event=task_migration_failed .* code=TARGET_TASK_NOT_FOUND' "negative device log missing TARGET_TASK_NOT_FOUND failure"
  assert_file_contains "${NEGATIVE_DEVICE_LOG}" 'event=task_migration_back_success .* code=TASK_MIGRATION_BACK_OK' "negative device log missing explicit back success"
  assert_file_contains "${NEGATIVE_DEVICE_LOG}" 'event=session_completed .* code=COMPLETED' "negative device log missing completion after failure path"
  assert_file_contains "${NEGATIVE_DEVICE_LOG}" 'event=session_closed .* code=CLOSED' "negative device log missing cleanup close"
  ok "negative device logs verified"
}

verify_concurrency_stdout_fields() {
  log "verifying concurrency-script summary"
  assert_file_contains "${CONCURRENCY_STDOUT_LOG}" 'case=concurrent_click iteration=1 passed' "concurrency stdout missing concurrent_click pass"
  assert_file_contains "${CONCURRENCY_STDOUT_LOG}" 'case=concurrent_swipe iteration=1 passed' "concurrency stdout missing concurrent_swipe pass"
  assert_file_contains "${CONCURRENCY_STDOUT_LOG}" 'case=concurrent_back_home iteration=1 passed' "concurrency stdout missing concurrent_back_home pass"
  assert_file_contains "${CONCURRENCY_STDOUT_LOG}" 'case=concurrent_text_input iteration=1 passed' "concurrency stdout missing concurrent_text_input pass"
  assert_file_contains "${CONCURRENCY_STDOUT_LOG}" 'case=concurrent_capture_inject iteration=1 passed' "concurrency stdout missing concurrent_capture_inject pass"
  assert_file_contains "${CONCURRENCY_STDOUT_LOG}" 'case=migration_boundary_isolation iteration=1 passed' "concurrency stdout missing migration_boundary_isolation pass"
  assert_file_contains "${CONCURRENCY_STDOUT_LOG}" 'case=main_display_not_applicable iteration=1 passed' "concurrency stdout missing main_display_not_applicable pass"
  assert_file_contains "${CONCURRENCY_STDOUT_LOG}" 'task migration concurrency isolation passed' "concurrency stdout missing summary"
  ok "concurrency stdout verified"
}

verify_concurrency_device_logs() {
  log "recording concurrency device log snapshot"
  assert_file_contains "${CONCURRENCY_STDOUT_LOG}" 'logs saved under /tmp/agenthost-task-migration-concurrency' "concurrency stdout missing artifact directory"
  ok "concurrency wrapper snapshot recorded"
}

print_summary() {
  echo
  ok "task migration regression passed"
  echo "logs saved under ${LOG_DIR}"
  echo
  echo "positive stdout: ${POSITIVE_STDOUT_LOG}"
  echo "positive device log: ${POSITIVE_DEVICE_LOG}"
  echo "negative stdout: ${NEGATIVE_STDOUT_LOG}"
  echo "negative device log: ${NEGATIVE_DEVICE_LOG}"
}

main() {
  [[ -x "${POSITIVE_SCRIPT}" ]] || die "missing executable script ${POSITIVE_SCRIPT}"
  [[ -x "${NEGATIVE_SCRIPT}" ]] || die "missing executable script ${NEGATIVE_SCRIPT}"

  log "running positive task-migration verification"
  clear_device_log
  run_child_script "${POSITIVE_SCRIPT}" "${POSITIVE_STDOUT_LOG}"
  capture_device_log "${POSITIVE_DEVICE_LOG}"
  verify_positive_stdout_fields
  verify_positive_device_logs

  log "running negative task-migration verification"
  clear_device_log
  run_child_script "${NEGATIVE_SCRIPT}" "${NEGATIVE_STDOUT_LOG}" CLEAR_LOGS_PER_CASE=0
  capture_device_log "${NEGATIVE_DEVICE_LOG}"
  verify_negative_stdout_fields
  verify_negative_device_logs

  if [[ "${RUN_CONCURRENCY}" == "1" ]]; then
    log "running concurrency-isolation verification"
    [[ -x "${CONCURRENCY_SCRIPT}" ]] || die "missing executable script ${CONCURRENCY_SCRIPT}"
    clear_device_log
    run_child_script "${CONCURRENCY_SCRIPT}" "${CONCURRENCY_STDOUT_LOG}"
    capture_device_log "${CONCURRENCY_DEVICE_LOG}"
    verify_concurrency_stdout_fields
    verify_concurrency_device_logs
  fi

  print_summary
}

main "$@"
