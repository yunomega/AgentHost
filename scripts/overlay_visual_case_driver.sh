#!/usr/bin/env bash
set -Eeuo pipefail

PKG="${PKG:-com.example.agenthost}"
RECV="${RECV:-${PKG}/.debug.SessionAuditReceiver}"
ADB_SERIAL="${ADB_SERIAL:-}"
WAIT_TIMEOUT_SECS="${WAIT_TIMEOUT_SECS:-35}"
POLL_INTERVAL_SECS="${POLL_INTERVAL_SECS:-1}"

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

clear_logs() {
  adb_cmd logcat -c || true
}

collect_logs() {
  adb_cmd logcat -d -v time -s OverlayActionDispatcher SessionCoordinator SessionAuditReceiver OverlayEntryService
}

broadcast_dump_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.DUMP_ACTIVE_SESSION'"
}

broadcast_stop_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.STOP_SESSION' --ez migrate false"
}

broadcast_clear_debug_hooks() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.CLEAR_DEBUG_HOOKS'"
}

broadcast_arm_fault() {
  local direction="$1"
  local code="$2"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.ARM_MIGRATION_FAULT' --es fault_direction '${direction}' --es fault_code '${code}'"
}

broadcast_start_session() {
  local payload="$1"
  local display_mode="$2"
  local bootstrap_target="${3:-}"
  local completion_return_policy="${4:-}"
  local skip_waiting_user_auto_migrate="${5:-false}"
  local debug_force_main_display="${6:-false}"
  local cmd="am broadcast -n '${RECV}' -a '${PKG}.debug.START_SESSION' --es command '${payload}' --es session_display_mode '${display_mode}' --ez skip_waiting_user_auto_migrate ${skip_waiting_user_auto_migrate} --ez debug_force_main_display ${debug_force_main_display}"
  if [[ -n "${bootstrap_target}" ]]; then
    cmd="${cmd} --es bootstrap_target '${bootstrap_target}'"
  fi
  if [[ -n "${completion_return_policy}" ]]; then
    cmd="${cmd} --es completion_return_policy '${completion_return_policy}'"
  fi
  adb_cmd shell "${cmd}"
}

active_session_json() {
  local output json
  output="$(broadcast_dump_session)"
  json="$(extract_broadcast_json "${output}")"
  [[ -n "${json}" ]] || die "failed to parse active session json"
  printf '%s\n' "${json}"
}

best_effort_stop_active() {
  local json status
  json="$(active_session_json)"
  status="$(extract_json_string "${json}" "status")"
  if [[ -n "${status}" && "${status}" != "NO_ACTIVE_SESSION" ]]; then
    warn "stopping leftover active session"
    broadcast_stop_session >/dev/null || true
    sleep 1
  fi
}

reset_environment() {
  clear_logs
  best_effort_stop_active
  broadcast_clear_debug_hooks >/dev/null || true
  SESSION_ID=""
  EXECUTION_DISPLAY_ID=""
}

wait_for_dump_state() {
  local timeout_secs="$1"
  local expected_status="$2"
  local expected_migrated="$3"
  local expected_recovery="$4"
  local elapsed=0
  while (( elapsed < timeout_secs )); do
    local json status migrated recovery
    json="$(active_session_json)"
    status="$(extract_json_string "${json}" "status")"
    migrated="$(extract_json_bool "${json}" "migratedToMain")"
    recovery="$(extract_json_bool "${json}" "recoveryNeeded")"
    if [[ "${status}" == "${expected_status}" &&
      ( "${expected_migrated}" == "*" || "${migrated}" == "${expected_migrated}" ) &&
      ( "${expected_recovery}" == "*" || "${recovery}" == "${expected_recovery}" ) ]]; then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECS}"
    elapsed=$((elapsed + POLL_INTERVAL_SECS))
  done
  return 1
}

wait_for_no_active_session() {
  local elapsed=0
  while (( elapsed < WAIT_TIMEOUT_SECS )); do
    local json code
    json="$(active_session_json)"
    code="$(extract_json_string "${json}" "code")"
    if [[ "${code}" == "NO_ACTIVE_SESSION" ]]; then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECS}"
    elapsed=$((elapsed + POLL_INTERVAL_SECS))
  done
  return 1
}

wait_for_log_pattern() {
  local pattern="$1"
  local elapsed=0
  while (( elapsed < WAIT_TIMEOUT_SECS )); do
    if collect_logs | grep -E -q "${pattern}"; then
      return 0
    fi
    sleep "${POLL_INTERVAL_SECS}"
    elapsed=$((elapsed + POLL_INTERVAL_SECS))
  done
  return 1
}

start_session_and_capture() {
  local payload="$1"
  local display_mode="$2"
  local bootstrap_target="${3:-}"
  local completion_return_policy="${4:-}"
  local skip_waiting_user_auto_migrate="${5:-false}"
  local debug_force_main_display="${6:-false}"
  local output json ok_value code status
  output="$(broadcast_start_session \
    "${payload}" \
    "${display_mode}" \
    "${bootstrap_target}" \
    "${completion_return_policy}" \
    "${skip_waiting_user_auto_migrate}" \
    "${debug_force_main_display}")"
  json="$(extract_broadcast_json "${output}")"
  [[ -n "${json}" ]] || die "failed to parse start_session result"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  status="$(extract_json_string "${json}" "status")"
  SESSION_ID="$(extract_json_string "${json}" "sessionId")"
  EXECUTION_DISPLAY_ID="$(extract_json_number "${json}" "executionDisplayId")"
  [[ "${ok_value}" == "true" ]] || die "start_session failed: ${code}"
  [[ "${code}" == "AUTOMATION_STARTED" ]] || die "unexpected start_session code: ${code}"
  [[ "${status}" == "RUNNING" ]] || die "unexpected start_session status: ${status}"
  [[ -n "${SESSION_ID}" ]] || die "missing sessionId"
  log "sessionId=${SESSION_ID} executionDisplayId=${EXECUTION_DISPLAY_ID}"
}

start_session_expect_failure() {
  local expected_code="$1"
  local payload="$2"
  local display_mode="$3"
  local bootstrap_target="${4:-}"
  local output json ok_value code
  output="$(broadcast_start_session "${payload}" "${display_mode}" "${bootstrap_target}")"
  json="$(extract_broadcast_json "${output}")"
  [[ -n "${json}" ]] || die "failed to parse start_session failure result"
  ok_value="$(extract_json_bool "${json}" "ok")"
  code="$(extract_json_string "${json}" "code")"
  SESSION_ID="$(extract_json_string "${json}" "sessionId")"
  [[ "${ok_value}" == "false" ]] || die "expected start_session to fail, got ok=true"
  [[ "${code}" == "${expected_code}" ]] || die "unexpected failure code: ${code}"
  [[ -n "${SESSION_ID}" ]] || die "missing sessionId on failed start_session"
  log "failed sessionId=${SESSION_ID} code=${code}"
}

prompt_manual() {
  local message="$1"
  printf '\n%s\n' "${message}"
  read -r -p "Press Enter after you finish the UI check... " _
}

print_snapshot() {
  local json status migrated recovery code last_migration_code
  json="$(active_session_json)"
  status="$(extract_json_string "${json}" "status")"
  migrated="$(extract_json_bool "${json}" "migratedToMain")"
  recovery="$(extract_json_bool "${json}" "recoveryNeeded")"
  code="$(extract_json_string "${json}" "code")"
  last_migration_code="$(extract_json_string "${json}" "lastMigrationCode")"
  echo "status=${status:-null} migratedToMain=${migrated:-null} recoveryNeeded=${recovery:-null} code=${code:-null} lastMigrationCode=${last_migration_code:-null}"
}

case_idle() {
  reset_environment
  log "case=idle"
  print_snapshot
  cat <<'EOF'
Observe now:
- Bubble text should show IDLE semantics: "空闲".
- Expanding the bubble should show the new-task panel.
- Empty input keeps the "开始" button disabled.
- "收起" only collapses the panel.
EOF
}

case_running() {
  reset_environment
  log "case=running"
  start_session_and_capture \
    '{"steps":[{"action":"WAIT","durationMs":12000},{"action":"DONE","reason":"overlay-running"}]}' \
    'VIRTUAL_DISPLAY'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "RUNNING" "*" "*" || die "RUNNING state did not stabilize"
  print_snapshot
  cat <<'EOF'
Observe now:
- Bubble should read "执行中".
- Expanding the bubble should show the new-task panel, but the input must be disabled.
- The "开始" button must remain disabled even if you tap the input field.
EOF
}

case_need_user_already_on_main() {
  reset_environment
  log "case=need-user-already-on-main"
  start_session_and_capture \
    '{"steps":[{"action":"ASK_USER","question":"Main display handoff check. Tap continue, but no migration should happen."},{"action":"DONE","reason":"need-user-already-on-main"}]}' \
    'MAIN_DISPLAY'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "WAITING_USER" "false" "false" || die "WAITING_USER on main did not stabilize"
  print_snapshot
  prompt_manual $'Observe the overlay.\n- The case name is need-user-already-on-main.\n- Primary button should mean "只收起/继续处理", not migration.\n- Tap the primary button once, then confirm the panel only collapses and the active session stays WAITING_USER on the same session.'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "WAITING_USER" "false" "false" || die "session changed unexpectedly after already-on-main primary action"
  print_snapshot
}

case_need_user_requires_move_to_main() {
  reset_environment
  log "case=need-user-requires-move-to-main"
  start_session_and_capture \
    '{"steps":[{"action":"ASK_USER","question":"This case must require a real move to main."},{"action":"WAIT","durationMs":8000},{"action":"DONE","reason":"need-user-requires-move-to-main"}]}' \
    'VIRTUAL_DISPLAY' \
    'SETTINGS' \
    '' \
    'true'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "WAITING_USER" "false" "false" || die "virtual WAITING_USER without auto-migrate did not stabilize"
  print_snapshot
  prompt_manual $'Observe the overlay.\n- The case name is need-user-requires-move-to-main.\n- Primary button should mean a real migration to main.\n- Tap the primary button once, then confirm the UI leaves NEED_USER and moves into the migrated handoff path.'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "WAITING_USER" "true" "false" || die "session did not migrate to main after primary action"
  print_snapshot
}

case_handoff_phase1() {
  reset_environment
  log "case=handoff-phase1"
  start_session_and_capture \
    '{"steps":[{"action":"ASK_USER","question":"Handoff phase 1. Do not submit reply yet."},{"action":"WAIT","durationMs":12000},{"action":"DONE","reason":"handoff-phase1"}]}' \
    'VIRTUAL_DISPLAY' \
    'SETTINGS'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "WAITING_USER" "true" "false" || die "HANDOFF_ACTIVE phase 1 did not stabilize"
  print_snapshot
  cat <<'EOF'
Observe now:
- Bubble should show NEED_USER tone with "主屏处理中".
- Expanded state should still be the first-phase card, not the reply strip.
- Primary button should only acknowledge handoff locally.
EOF
}

case_handoff_phase2() {
  reset_environment
  log "case=handoff-phase2"
  start_session_and_capture \
    '{"steps":[{"action":"ASK_USER","question":"Handoff phase 2. Acknowledge once to reach the reply strip."},{"action":"WAIT","durationMs":12000},{"action":"DONE","reason":"handoff-phase2"}]}' \
    'VIRTUAL_DISPLAY' \
    'SETTINGS'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "WAITING_USER" "true" "false" || die "handoff phase 2 setup did not stabilize"
  print_snapshot
  prompt_manual $'Observe the first-phase card, then tap the primary button once.\n- The card should switch to the reply strip.\n- The default reply text should be "已处理完成".\n- The secondary button should become "取消任务".'
  print_snapshot
}

case_handoff_phase2_submit_reply() {
  local previous_session_id reply_token continuation_question
  reset_environment
  log "case=handoff-phase2-submit-reply"

  start_session_and_capture \
    '{"steps":[{"action":"WAIT","durationMs":2000},{"action":"DONE","reason":"old-session-baseline"}]}' \
    'VIRTUAL_DISPLAY'
  previous_session_id="${SESSION_ID}"
  broadcast_stop_session >/dev/null || true
  wait_for_no_active_session || die "baseline session did not stop cleanly"

  continuation_question="SECOND_CONTINUATION_CHECK_CURRENT_WAITING_SESSION"
  start_session_and_capture \
    "{\"steps\":[{\"action\":\"ASK_USER\",\"question\":\"Reply must continue the current waiting session, not a new task.\"},{\"action\":\"WAIT\",\"durationMs\":1500},{\"action\":\"ASK_USER\",\"question\":\"${continuation_question}\"}]}" \
    'VIRTUAL_DISPLAY' \
    'SETTINGS'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "WAITING_USER" "true" "false" || die "handoff submit-reply setup did not stabilize"
  reply_token="CONTINUE_${SESSION_ID%%-*}"
  clear_logs
  prompt_manual "Current waiting session: ${SESSION_ID}
Previous session for stale-context check: ${previous_session_id}

Do this now:
- Tap the first-phase primary button to enter the HANDOFF_ACTIVE second phase.
- Replace the reply input with: ${reply_token}
- Tap \"提交并返回后台\" once.
- Confirm the overlay does not open the new-task panel."

  wait_for_log_pattern "event=overlay_action_click .* action=reply_to_session .* sessionId=${SESSION_ID}" || die "missing overlay reply_to_session click for current waiting session"
  if collect_logs | grep -E -q "event=overlay_action_click .* action=reply_to_session .* sessionId=${previous_session_id}"; then
    die "reply used the previous session id instead of the current waiting session"
  fi
  if collect_logs | grep -E -q "action=start_session"; then
    die "unexpected start_session log detected during handoff reply"
  fi
  wait_for_log_pattern "event=(automation_resumed_after_reply|automation_resume_after_migration) sessionId=${SESSION_ID} .* code=AUTOMATION_RESUMED" || die "current waiting session did not resume after reply"
  wait_for_log_pattern "event=session_waiting_user sessionId=${SESSION_ID} .* question=${continuation_question}" || die "current waiting session did not reach the expected continuation handoff question"
  log "handoff-phase2-submit-reply verified for sessionId=${SESSION_ID} reply=${reply_token} continuationQuestion=${continuation_question}"
}

case_recovery_to_main() {
  reset_environment
  log "case=recovery-to-main"
  broadcast_arm_fault 'TO_MAIN' 'TASK_CONFLICT_ON_MAIN' >/dev/null
  start_session_and_capture \
    '{"steps":[{"action":"ASK_USER","question":"Recovery route should ask to move to main."},{"action":"WAIT","durationMs":10000},{"action":"DONE","reason":"recovery-to-main"}]}' \
    'VIRTUAL_DISPLAY' \
    'SETTINGS'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "WAITING_USER" "false" "true" || die "recovery-to-main did not stabilize"
  print_snapshot
  cat <<'EOF'
Observe now:
- The overlay should be in RECOVERY.
- Primary action should read "去主屏处理".
- This is the non-main, non-idle-fallback recovery route.
EOF
}

case_recovery_to_execution() {
  reset_environment
  log "case=recovery-to-execution"
  start_session_and_capture \
    '{"steps":[{"action":"ASK_USER","question":"Reply once to trigger migrate-back recovery."},{"action":"WAIT","durationMs":10000},{"action":"DONE","reason":"recovery-to-execution"}]}' \
    'VIRTUAL_DISPLAY' \
    'SETTINGS'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "WAITING_USER" "true" "false" || die "recovery-to-execution setup did not stabilize"
  broadcast_arm_fault 'TO_EXECUTION' 'TASK_MOVE_BACK_CONFLICT' >/dev/null
  prompt_manual $'Do this now:\n- Tap the first-phase primary button to enter phase 2.\n- Leave the default reply text or enter a short reply.\n- Tap "提交并返回后台".\n- Wait for RECOVERY to appear.'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "WAITING_USER" "true" "true" || die "recovery-to-execution did not reach RECOVERY"
  print_snapshot
  cat <<'EOF'
Observe now:
- RECOVERY primary action should read "返回后台执行".
- Secondary action should read "取消任务".
EOF
}

case_recovery_to_idle() {
  reset_environment
  log "case=recovery-to-idle"
  start_session_and_capture \
    '{"steps":[{"action":"ASK_USER","question":"Missing target should force idle fallback recovery."},{"action":"WAIT","durationMs":10000},{"action":"DONE","reason":"recovery-to-idle"}]}' \
    'VIRTUAL_DISPLAY'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "WAITING_USER" "false" "true" || die "recovery-to-idle did not stabilize"
  print_snapshot
  prompt_manual $'Observe the RECOVERY card.\n- Primary action should read "返回空闲".\n- Tap the primary button once.\n- The session should end and the overlay should return to IDLE.'
  wait_for_no_active_session || die "recovery-to-idle did not end the session"
  print_snapshot
}

case_recovery_already_on_main() {
  reset_environment
  log "case=recovery-already-on-main"
  start_session_expect_failure \
    'SCRIPT_PARSE_FAILED' \
    'not-json' \
    'MAIN_DISPLAY'
  wait_for_dump_state "${WAIT_TIMEOUT_SECS}" "FAILED" "*" "*" || die "main-display failure did not stabilize"
  print_snapshot
  cat <<'EOF'
Observe now:
- RECOVERY should present the already-on-main semantics.
- Primary action should read "继续处理".
- Tapping it should only collapse the panel, not migrate.
EOF
}

case_post_cleanup() {
  reset_environment
  log "case=post-cleanup"
  cat <<'EOF'
Prepare to watch the overlay now. This completion state is retained for about 3 seconds only.
EOF
  start_session_and_capture \
    '{"steps":[{"action":"WAIT","durationMs":1500},{"action":"DONE","reason":"post-cleanup"}]}' \
    'VIRTUAL_DISPLAY'
  wait_for_log_pattern "event=session_completed sessionId=${SESSION_ID} .* code=COMPLETED" || die "post-cleanup completion did not occur"
  cat <<'EOF'
Observe immediately:
- Bubble should show POST_COMPLETION with "会话已清理" semantics.
- Primary action should read "知道了".
- If you do nothing, the retained completion UI should disappear after about 3 seconds and return to IDLE.
EOF
}

case_post_return_main() {
  reset_environment
  log "case=post-return-main"
  cat <<'EOF'
Prepare to watch the overlay now. This completion state is retained for about 3 seconds only.
EOF
  start_session_and_capture \
    '{"steps":[{"action":"WAIT","durationMs":1500},{"action":"DONE","reason":"post-return-main"}]}' \
    'VIRTUAL_DISPLAY' \
    'SETTINGS' \
    'RETURN_TASK_TO_MAIN'
  wait_for_log_pattern "event=session_completed_return_to_main sessionId=${SESSION_ID} .* code=(TASK_MIGRATION_OK|TASK_MIGRATION_ALREADY_ON_MAIN)" || die "return-to-main completion did not succeed"
  cat <<'EOF'
Observe immediately:
- Bubble should show POST_COMPLETION with "已回主屏".
- Primary action should read "查看主屏".
- Dismiss still only closes retained completion UI.
EOF
}

case_post_return_fallback() {
  reset_environment
  log "case=post-return-fallback"
  cat <<'EOF'
Prepare to watch the overlay now. This completion state is retained for about 3 seconds only.
EOF
  broadcast_arm_fault 'TO_MAIN' 'TASK_ID_STALE' >/dev/null
  start_session_and_capture \
    '{"steps":[{"action":"WAIT","durationMs":1500},{"action":"DONE","reason":"post-return-fallback"}]}' \
    'VIRTUAL_DISPLAY' \
    'SETTINGS' \
    'RETURN_TASK_TO_MAIN'
  wait_for_log_pattern "event=session_completed_return_to_main_failed sessionId=${SESSION_ID} .* code=TASK_ID_STALE" || die "return-to-main fallback did not occur"
  cat <<'EOF'
Observe immediately:
- Bubble should show POST_COMPLETION with "已安全结束".
- The card message should include the failure code.
- Primary action should read "知道了".
EOF
}

usage() {
  cat <<'EOF'
Usage:
  scripts/overlay_visual_case_driver.sh <case-name>

Cases:
  idle
  running
  need-user-already-on-main
  need-user-requires-move-to-main
  handoff-phase1
  handoff-phase2
  handoff-phase2-submit-reply
  recovery-to-main
  recovery-to-execution
  recovery-to-idle
  recovery-already-on-main
  post-cleanup
  post-return-main
  post-return-fallback
  reset
EOF
}

main() {
  local case_name="${1:-}"
  case "${case_name}" in
    idle) case_idle ;;
    running) case_running ;;
    need-user-already-on-main) case_need_user_already_on_main ;;
    need-user-requires-move-to-main) case_need_user_requires_move_to_main ;;
    handoff-phase1) case_handoff_phase1 ;;
    handoff-phase2) case_handoff_phase2 ;;
    handoff-phase2-submit-reply) case_handoff_phase2_submit_reply ;;
    recovery-to-main) case_recovery_to_main ;;
    recovery-to-execution) case_recovery_to_execution ;;
    recovery-to-idle) case_recovery_to_idle ;;
    recovery-already-on-main) case_recovery_already_on_main ;;
    post-cleanup) case_post_cleanup ;;
    post-return-main) case_post_return_main ;;
    post-return-fallback) case_post_return_fallback ;;
    reset) reset_environment ;;
    *) usage; exit 1 ;;
  esac
}

main "$@"
