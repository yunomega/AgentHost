#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PKG="${PKG:-com.example.agenthost}"
RECV="${RECV:-${PKG}/.debug.SessionAuditReceiver}"
ADB_SERIAL="${ADB_SERIAL:-}"
ROUND_LIMIT="${ROUND_LIMIT:-3}"
DURATION_SECS="${DURATION_SECS:-0}"
INCLUDE_ASK_USER="${INCLUDE_ASK_USER:-1}"
INCLUDE_CONCURRENCY="${INCLUDE_CONCURRENCY:-1}"
CASE_TIMEOUT_SECS="${CASE_TIMEOUT_SECS:-180}"
LOG_DIR="${LOG_DIR:-$(mktemp -d /tmp/agenthost-task-migration-soak.XXXXXX)}"
TAIL_LINES="${TAIL_LINES:-120}"
COLLECT_DUMPSYS="${COLLECT_DUMPSYS:-1}"
CASE_SELECTOR="${CASE_SELECTOR:-all}"

MIGRATION_SCRIPT="${SCRIPT_DIR}/verify_task_migration.sh"
CONCURRENCY_SCRIPT="${SCRIPT_DIR}/verify_task_migration_concurrency.sh"
RECOVERY_SCRIPT="${SCRIPT_DIR}/verify_task_migration_recovery.sh"

DETAILS_CSV="${LOG_DIR}/rounds.csv"
SUMMARY_CSV="${LOG_DIR}/summary.csv"
FAILURE_SUMMARY="${LOG_DIR}/failure_summary.txt"
RUN_LOG="${LOG_DIR}/run.log"

SOAK_LAST_RC=0
SOAK_LAST_TIMEOUT=0
SOAK_LAST_ELAPSED_MS=0

adb_cmd() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    adb -s "${ADB_SERIAL}" "$@"
  else
    adb "$@"
  fi
}

log() {
  echo "[INFO] $*" | tee -a "${RUN_LOG}"
}

ok() {
  echo "[ OK ] $*" | tee -a "${RUN_LOG}"
}

warn() {
  echo "[WARN] $*" | tee -a "${RUN_LOG}" >&2
}

die() {
  echo "[ERR ] $*" | tee -a "${RUN_LOG}" >&2
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

count_pattern_in_files() {
  local pattern="$1"
  shift
  local total=0
  local file
  for file in "$@"; do
    [[ -f "${file}" ]] || continue
    total=$((total + $(grep -E -c "${pattern}" "${file}" || true)))
  done
  echo "${total}"
}

run_child_with_timeout() {
  local timeout_secs="$1"
  local stdout_log="$2"
  shift 2
  local start_ms end_ms rc
  start_ms="$(date +%s%3N)"
  set +e
  timeout "${timeout_secs}s" env "$@" > "${stdout_log}" 2>&1
  rc=$?
  set -e
  end_ms="$(date +%s%3N)"
  SOAK_LAST_RC="${rc}"
  SOAK_LAST_TIMEOUT=0
  if [[ "${rc}" == "124" ]]; then
    SOAK_LAST_TIMEOUT=1
  fi
  SOAK_LAST_ELAPSED_MS=$((end_ms - start_ms))
}

broadcast_dump_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.DUMP_ACTIVE_SESSION'"
}

broadcast_stop_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.STOP_SESSION' --ez migrate false"
}

sample_residual_state() {
  local round_dir="$1"
  local dump_output dump_json dump_code dump_status session_residual task_residual display_residual
  dump_output="$(broadcast_dump_session 2>/dev/null || true)"
  dump_json="$(extract_broadcast_json "${dump_output}")"
  dump_code="$(extract_json_string "${dump_json}" "code")"
  dump_status="$(extract_json_string "${dump_json}" "status")"
  session_residual=0
  if [[ -n "${dump_code}" && "${dump_code}" != "NO_ACTIVE_SESSION" ]]; then
    session_residual=1
  fi
  printf '%s\n' "${dump_output}" > "${round_dir}/dump_active_session.stdout.log"

  if [[ "${COLLECT_DUMPSYS}" == "1" ]]; then
    adb_cmd shell dumpsys meminfo "${PKG}" > "${round_dir}/meminfo.dump" || true
    adb_cmd shell dumpsys activity activities > "${round_dir}/activity.dump" || true
    adb_cmd shell dumpsys window displays > "${round_dir}/window.dump" || true
  else
    : > "${round_dir}/meminfo.dump"
    : > "${round_dir}/activity.dump"
    : > "${round_dir}/window.dump"
  fi

  task_residual=0
  display_residual=0
  if grep -E -q 'SessionRootActivity' "${round_dir}/activity.dump" 2>/dev/null; then
    task_residual=1
  fi
  if grep -E -q 'displayId=[1-9][0-9]*' "${round_dir}/activity.dump" 2>/dev/null; then
    display_residual=1
  fi

  printf 'dump_code=%s\ndump_status=%s\nsession_residual=%s\ndisplay_residual=%s\ntask_residual=%s\n' \
    "${dump_code:-}" "${dump_status:-}" "${session_residual}" "${display_residual}" "${task_residual}" > "${round_dir}/residual_summary.txt"

  if [[ "${session_residual}" == "1" ]]; then
    broadcast_stop_session > /dev/null || true
    sleep 1
  fi

  echo "${session_residual},${display_residual},${task_residual},${dump_code:-},${dump_status:-}"
}

case_should_run() {
  local case_name="$1"
  if [[ "${CASE_SELECTOR}" == "all" ]]; then
    return 0
  fi
  [[ "${CASE_SELECTOR}" == "${case_name}" ]]
}

selected_cases() {
  local cases=()
  if [[ "${INCLUDE_ASK_USER}" == "1" ]]; then
    cases+=(repeated_migration_loop recovery_loop)
  fi
  if [[ "${INCLUDE_CONCURRENCY}" == "1" ]]; then
    cases+=(repeated_capture_inject_loop)
  fi
  cases+=(mixed_session_loop)
  local case_name
  for case_name in "${cases[@]}"; do
    if case_should_run "${case_name}"; then
      echo "${case_name}"
    fi
  done
}

run_repeated_migration_loop() {
  local round_dir="$1"
  run_child_with_timeout \
    "${CASE_TIMEOUT_SECS}" \
    "${round_dir}/stdout.log" \
    "ADB_SERIAL=${ADB_SERIAL}" \
    "AUTO_CONFIRM=YES" \
    "${MIGRATION_SCRIPT}"
}

run_repeated_capture_inject_loop() {
  local round_dir="$1"
  run_child_with_timeout \
    "${CASE_TIMEOUT_SECS}" \
    "${round_dir}/stdout.log" \
    "ADB_SERIAL=${ADB_SERIAL}" \
    "AUTO_CONFIRM=YES" \
    "ENABLE_MANUAL_CONFIRM=0" \
    "CASE=concurrent_capture_inject" \
    "LOG_DIR=${round_dir}/child" \
    "${CONCURRENCY_SCRIPT}"
}

run_recovery_loop() {
  local round_dir="$1"
  run_child_with_timeout \
    "${CASE_TIMEOUT_SECS}" \
    "${round_dir}/stdout.log" \
    "ADB_SERIAL=${ADB_SERIAL}" \
    "LOG_DIR=${round_dir}/child" \
    "IDEMPOTENT_ROUNDS=2" \
    "${RECOVERY_SCRIPT}"
}

run_mixed_session_loop() {
  local round_dir="$1"
  local total_elapsed=0
  run_child_with_timeout \
    "${CASE_TIMEOUT_SECS}" \
    "${round_dir}/virtual.stdout.log" \
    "ADB_SERIAL=${ADB_SERIAL}" \
    "AUTO_CONFIRM=YES" \
    "${MIGRATION_SCRIPT}"
  total_elapsed=$((total_elapsed + SOAK_LAST_ELAPSED_MS))
  if [[ "${SOAK_LAST_RC}" != "0" ]]; then
    SOAK_LAST_ELAPSED_MS="${total_elapsed}"
    return 0
  fi
  run_child_with_timeout \
    "${CASE_TIMEOUT_SECS}" \
    "${round_dir}/main.stdout.log" \
    "ADB_SERIAL=${ADB_SERIAL}" \
    "AUTO_CONFIRM=YES" \
    "ENABLE_MANUAL_CONFIRM=0" \
    "CASE=main_display_not_applicable" \
    "LOG_DIR=${round_dir}/main_child" \
    "${CONCURRENCY_SCRIPT}"
  total_elapsed=$((total_elapsed + SOAK_LAST_ELAPSED_MS))
  SOAK_LAST_ELAPSED_MS="${total_elapsed}"
}

execute_case_round() {
  local case_name="$1"
  local round="$2"
  local round_dir="${LOG_DIR}/${case_name}/round_${round}"
  mkdir -p "${round_dir}"
  log "running case=${case_name} round=${round}"

  case "${case_name}" in
    repeated_migration_loop) run_repeated_migration_loop "${round_dir}" ;;
    repeated_capture_inject_loop) run_repeated_capture_inject_loop "${round_dir}" ;;
    recovery_loop) run_recovery_loop "${round_dir}" ;;
    mixed_session_loop) run_mixed_session_loop "${round_dir}" ;;
    *) die "unknown case ${case_name}" ;;
  esac

  local timed_out="${SOAK_LAST_TIMEOUT}"
  local elapsed_ms="${SOAK_LAST_ELAPSED_MS}"
  local rc="${SOAK_LAST_RC}"
  local passed=1
  if [[ "${rc}" != "0" ]]; then
    passed=0
  fi

  local device_log="${round_dir}/device.log"
  adb_cmd logcat -d -v time -s SessionCoordinator SessionAuditReceiver TaskMigrationController AtmHiddenApi SessionRootActivity > "${device_log}" || true

  local stdout_files=("${round_dir}/stdout.log" "${round_dir}/virtual.stdout.log" "${round_dir}/main.stdout.log")
  local display_drift=0
  if grep -E -q 'inject_cross_display|capture_cross_display|main_foreground_stolen|main_display_not_applicable_violation|executionDisplayId drifted|captureDisplayId drifted|injectDisplayId drifted|currentExecutionDisplayId must remain immutable' "${device_log}" "${stdout_files[@]}" 2>/dev/null; then
    display_drift=1
  fi

  local conflict_count recovery_count exception_count
  conflict_count="$(count_pattern_in_files 'TASK_CONFLICT_ON_MAIN|TASK_MOVE_BACK_CONFLICT|USER_OWNS_MAIN_INSTANCE' "${device_log}" "${stdout_files[@]}")"
  recovery_count="$(count_pattern_in_files 'TARGET_TASK_REMOVED|TASK_ID_STALE|TARGET_TASK_NOT_FOUND|TASK_NOT_ON_EXPECTED_DISPLAY|TASK_QUERY_UNSUPPORTED|TASK_MIGRATION_PRECHECK_FAILED|recoveryNeeded=true' "${device_log}" "${stdout_files[@]}")"
  exception_count="$(count_pattern_in_files 'TASK_MOVE_FAILED|TASK_MOVE_BACK_FAILED|MIGRATION_FAILED|SESSION_ATTACH_TIMEOUT|SESSION_DISPLAY_MISMATCH|INVALID_DEBUG_MIGRATION_FAULT|DEBUG_PERMISSION_DENIED|DEBUG_CALLER_IDENTITY_UNAVAILABLE' "${device_log}" "${stdout_files[@]}")"

  local residual_summary session_residual display_residual task_residual dump_code dump_status
  residual_summary="$(sample_residual_state "${round_dir}")"
  IFS=',' read -r session_residual display_residual task_residual dump_code dump_status <<< "${residual_summary}"

  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "${case_name}" "${round}" "${passed}" "${timed_out}" "${display_drift}" "${elapsed_ms}" \
    "${conflict_count}" "${recovery_count}" "${exception_count}" "${session_residual}" "${display_residual}" "${task_residual}" \
    "${dump_code}" "${dump_status}" >> "${DETAILS_CSV}"

  if [[ "${passed}" == "1" ]]; then
    ok "case=${case_name} round=${round} passed elapsedMs=${elapsed_ms}"
  else
    warn "case=${case_name} round=${round} failed rc=${rc} timeout=${timed_out} drift=${display_drift}"
    {
      echo "case=${case_name} round=${round} rc=${rc} timeout=${timed_out} display_drift=${display_drift}"
      echo "stdout=${round_dir}/stdout.log"
      echo "virtual_stdout=${round_dir}/virtual.stdout.log"
      echo "main_stdout=${round_dir}/main.stdout.log"
      echo "device_log=${device_log}"
      echo
    } >> "${FAILURE_SUMMARY}"
  fi

  echo "${passed},${timed_out},${display_drift},${elapsed_ms},${conflict_count},${recovery_count},${exception_count},${session_residual},${display_residual},${task_residual}"
}

main() {
  mkdir -p "${LOG_DIR}"
  : > "${RUN_LOG}"
  printf 'case_name,round,passed,timeout,display_drift,elapsed_ms,conflict_code_count,recovery_code_count,exception_code_count,session_residual,display_residual,task_residual,dump_code,dump_status\n' > "${DETAILS_CSV}"
  : > "${FAILURE_SUMMARY}"

  mapfile -t cases < <(selected_cases)
  [[ "${#cases[@]}" -gt 0 ]] || die "no soak cases selected"
  [[ -x "${MIGRATION_SCRIPT}" ]] || die "missing executable ${MIGRATION_SCRIPT}"
  [[ -x "${CONCURRENCY_SCRIPT}" ]] || die "missing executable ${CONCURRENCY_SCRIPT}"
  [[ -x "${RECOVERY_SCRIPT}" ]] || die "missing executable ${RECOVERY_SCRIPT}"

  declare -A rounds passes failures timeouts drifts elapsed conflict recovery exception session_residuals display_residuals task_residuals first_failure_round
  local overall_rounds=0 overall_passes=0 overall_failures=0 overall_timeouts=0 overall_drifts=0 overall_elapsed=0
  local overall_conflict=0 overall_recovery=0 overall_exception=0 overall_session_residual=0 overall_display_residual=0 overall_task_residual=0
  local global_first_failure_case=""
  local global_first_failure_round=""
  local start_epoch now_epoch deadline=0
  start_epoch="$(date +%s)"
  if [[ "${DURATION_SECS}" != "0" ]]; then
    deadline=$((start_epoch + DURATION_SECS))
  fi

  local case_name case_round metrics passed timeout drift elapsed_ms conflict_count recovery_count exception_count session_residual display_residual task_residual
  while :; do
    local made_progress=0
    for case_name in "${cases[@]}"; do
      if [[ "${ROUND_LIMIT}" != "0" && "${ROUND_LIMIT}" != "" ]]; then
        if (( ${rounds[${case_name}]:-0} >= ROUND_LIMIT )); then
          continue
        fi
      fi
      now_epoch="$(date +%s)"
      if (( deadline > 0 && now_epoch >= deadline )); then
        break 2
      fi
      case_round=$(( ${rounds[${case_name}]:-0} + 1 ))
      metrics="$(execute_case_round "${case_name}" "${case_round}" | tail -n 1)"
      IFS=',' read -r passed timeout drift elapsed_ms conflict_count recovery_count exception_count session_residual display_residual task_residual <<< "${metrics}"

      rounds[${case_name}]="${case_round}"
      passes[${case_name}]=$(( ${passes[${case_name}]:-0} + passed ))
      failures[${case_name}]=$(( ${failures[${case_name}]:-0} + (1 - passed) ))
      timeouts[${case_name}]=$(( ${timeouts[${case_name}]:-0} + timeout ))
      drifts[${case_name}]=$(( ${drifts[${case_name}]:-0} + drift ))
      elapsed[${case_name}]=$(( ${elapsed[${case_name}]:-0} + elapsed_ms ))
      conflict[${case_name}]=$(( ${conflict[${case_name}]:-0} + conflict_count ))
      recovery[${case_name}]=$(( ${recovery[${case_name}]:-0} + recovery_count ))
      exception[${case_name}]=$(( ${exception[${case_name}]:-0} + exception_count ))
      session_residuals[${case_name}]=$(( ${session_residuals[${case_name}]:-0} + session_residual ))
      display_residuals[${case_name}]=$(( ${display_residuals[${case_name}]:-0} + display_residual ))
      task_residuals[${case_name}]=$(( ${task_residuals[${case_name}]:-0} + task_residual ))
      if (( passed == 0 )) && [[ -z "${first_failure_round[${case_name}]:-}" ]]; then
        first_failure_round[${case_name}]="${case_round}"
      fi
      if (( passed == 0 )) && [[ -z "${global_first_failure_case}" ]]; then
        global_first_failure_case="${case_name}"
        global_first_failure_round="${case_round}"
      fi

      overall_rounds=$((overall_rounds + 1))
      overall_passes=$((overall_passes + passed))
      overall_failures=$((overall_failures + (1 - passed)))
      overall_timeouts=$((overall_timeouts + timeout))
      overall_drifts=$((overall_drifts + drift))
      overall_elapsed=$((overall_elapsed + elapsed_ms))
      overall_conflict=$((overall_conflict + conflict_count))
      overall_recovery=$((overall_recovery + recovery_count))
      overall_exception=$((overall_exception + exception_count))
      overall_session_residual=$((overall_session_residual + session_residual))
      overall_display_residual=$((overall_display_residual + display_residual))
      overall_task_residual=$((overall_task_residual + task_residual))
      made_progress=1
    done

    if [[ "${ROUND_LIMIT}" != "0" && "${ROUND_LIMIT}" != "" ]]; then
      local pending=0
      for case_name in "${cases[@]}"; do
        if (( ${rounds[${case_name}]:-0} < ROUND_LIMIT )); then
          pending=1
          break
        fi
      done
      if (( pending == 0 )); then
        break
      fi
    fi
    if (( deadline > 0 )); then
      now_epoch="$(date +%s)"
      if (( now_epoch >= deadline )); then
        break
      fi
    fi
    if (( made_progress == 0 )); then
      break
    fi
  done

  printf 'case_name,rounds_total,passes,failures,failure_rate,timeouts,timeout_rate,display_drift_count,first_failure_case,first_failure_round,avg_elapsed_ms,conflict_code_count,recovery_code_count,exception_code_count,session_residual_count,display_residual_count,task_residual_count,pass\n' > "${SUMMARY_CSV}"
  for case_name in "${cases[@]}"; do
    local case_rounds case_passes case_failures case_timeouts case_drifts case_elapsed case_avg case_failure_rate case_timeout_rate
    local case_first_failure_case=""
    case_rounds="${rounds[${case_name}]:-0}"
    case_passes="${passes[${case_name}]:-0}"
    case_failures="${failures[${case_name}]:-0}"
    case_timeouts="${timeouts[${case_name}]:-0}"
    case_drifts="${drifts[${case_name}]:-0}"
    case_elapsed="${elapsed[${case_name}]:-0}"
    case_avg=0
    case_failure_rate="0.0000"
    case_timeout_rate="0.0000"
    if (( case_rounds > 0 )); then
      case_avg=$((case_elapsed / case_rounds))
      case_failure_rate="$(awk -v a="${case_failures}" -v b="${case_rounds}" 'BEGIN { printf "%.4f", (b == 0 ? 0 : a / b) }')"
      case_timeout_rate="$(awk -v a="${case_timeouts}" -v b="${case_rounds}" 'BEGIN { printf "%.4f", (b == 0 ? 0 : a / b) }')"
    fi
    if (( case_failures > 0 )); then
      case_first_failure_case="${case_name}"
    fi
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "${case_name}" "${case_rounds}" "${case_passes}" "${case_failures}" "${case_failure_rate}" \
      "${case_timeouts}" "${case_timeout_rate}" "${case_drifts}" \
      "${case_first_failure_case}" "${first_failure_round[${case_name}]:-}" "${case_avg}" \
      "${conflict[${case_name}]:-0}" "${recovery[${case_name}]:-0}" "${exception[${case_name}]:-0}" \
      "${session_residuals[${case_name}]:-0}" "${display_residuals[${case_name}]:-0}" "${task_residuals[${case_name}]:-0}" \
      "$( (( case_failures == 0 && case_timeouts == 0 && case_drifts == 0 )) && echo true || echo false )" >> "${SUMMARY_CSV}"
  done

  local overall_avg=0 overall_failure_rate="0.0000" overall_timeout_rate="0.0000"
  if (( overall_rounds > 0 )); then
    overall_avg=$((overall_elapsed / overall_rounds))
    overall_failure_rate="$(awk -v a="${overall_failures}" -v b="${overall_rounds}" 'BEGIN { printf "%.4f", (b == 0 ? 0 : a / b) }')"
    overall_timeout_rate="$(awk -v a="${overall_timeouts}" -v b="${overall_rounds}" 'BEGIN { printf "%.4f", (b == 0 ? 0 : a / b) }')"
  fi
  printf 'overall,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "${overall_rounds}" "${overall_passes}" "${overall_failures}" "${overall_failure_rate}" \
    "${overall_timeouts}" "${overall_timeout_rate}" "${overall_drifts}" \
    "${global_first_failure_case}" "${global_first_failure_round}" "${overall_avg}" \
    "${overall_conflict}" "${overall_recovery}" "${overall_exception}" \
    "${overall_session_residual}" "${overall_display_residual}" "${overall_task_residual}" \
    "$( (( overall_failures == 0 && overall_timeouts == 0 && overall_drifts == 0 )) && echo true || echo false )" >> "${SUMMARY_CSV}"

  echo >> "${FAILURE_SUMMARY}"
  echo "summary_csv=${SUMMARY_CSV}" >> "${FAILURE_SUMMARY}"
  echo "details_csv=${DETAILS_CSV}" >> "${FAILURE_SUMMARY}"

  echo
  ok "task migration soak completed"
  echo "logs saved under ${LOG_DIR}"
  echo "round details: ${DETAILS_CSV}"
  echo "summary: ${SUMMARY_CSV}"
  echo "failure summary: ${FAILURE_SUMMARY}"
  echo
  tail -n "${TAIL_LINES}" "${RUN_LOG}" || true

  if (( overall_failures > 0 || overall_timeouts > 0 || overall_drifts > 0 )); then
    exit 1
  fi
}

main "$@"
