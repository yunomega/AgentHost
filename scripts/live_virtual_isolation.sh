#!/usr/bin/env bash
set -Eeuo pipefail

PKG="${PKG:-com.example.agenthost}"
RECV="${RECV:-${PKG}/.debug.SessionAuditReceiver}"
ADB_SERIAL="${ADB_SERIAL:-}"
DEFAULT_BOOTSTRAP_TARGET="${DEFAULT_BOOTSTRAP_TARGET:-settings}"

SESSION_ID=""
PAYLOAD=""

adb_cmd() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    adb -s "${ADB_SERIAL}" "$@"
  else
    adb "$@"
  fi
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/ }"
  printf '%s' "${value}"
}

extract_json_field() {
  local json="$1"
  local key="$2"
  printf '%s\n' "${json}" | sed -n "s/.*\"${key}\":\"\\([^\"]*\\)\".*/\\1/p"
}

extract_broadcast_json() {
  local output="$1"
  printf '%s\n' "${output}" | sed -n 's/.*data="\({.*}\)".*/\1/p'
}

append_step_json() {
  local spec="$1"
  local action rest
  action="$(printf '%s' "${spec}" | awk '{print toupper($1)}')"
  rest="$(printf '%s' "${spec}" | sed -E 's/^[^ ]+[ ]*//')"

  case "${action}" in
    WAIT)
      local ms
      ms="$(printf '%s' "${rest}" | awk '{print $1}')"
      [[ -n "${ms}" ]] || { echo "invalid WAIT: ${spec}" >&2; exit 1; }
      printf '{"action":"WAIT","durationMs":%s}' "${ms}"
      ;;
    CLICK)
      local x y
      x="$(printf '%s' "${rest}" | awk '{print $1}')"
      y="$(printf '%s' "${rest}" | awk '{print $2}')"
      [[ -n "${x}" && -n "${y}" ]] || { echo "invalid CLICK: ${spec}" >&2; exit 1; }
      printf '{"action":"CLICK","x":%s,"y":%s}' "${x}" "${y}"
      ;;
    SWIPE)
      local x1 y1 x2 y2 d
      x1="$(printf '%s' "${rest}" | awk '{print $1}')"
      y1="$(printf '%s' "${rest}" | awk '{print $2}')"
      x2="$(printf '%s' "${rest}" | awk '{print $3}')"
      y2="$(printf '%s' "${rest}" | awk '{print $4}')"
      d="$(printf '%s' "${rest}" | awk '{print $5}')"
      [[ -n "${x1}" && -n "${y1}" && -n "${x2}" && -n "${y2}" && -n "${d}" ]] || { echo "invalid SWIPE: ${spec}" >&2; exit 1; }
      printf '{"action":"SWIPE","x1":%s,"y1":%s,"x2":%s,"y2":%s,"durationMs":%s}' "${x1}" "${y1}" "${x2}" "${y2}" "${d}"
      ;;
    BACK)
      printf '{"action":"BACK"}'
      ;;
    HOME)
      printf '{"action":"HOME"}'
      ;;
    LONG_PRESS)
      local x y d
      x="$(printf '%s' "${rest}" | awk '{print $1}')"
      y="$(printf '%s' "${rest}" | awk '{print $2}')"
      d="$(printf '%s' "${rest}" | awk '{print $3}')"
      [[ -n "${x}" && -n "${y}" && -n "${d}" ]] || { echo "invalid LONG_PRESS: ${spec}" >&2; exit 1; }
      printf '{"action":"LONG_PRESS","x":%s,"y":%s,"durationMs":%s}' "${x}" "${y}" "${d}"
      ;;
    TEXT|TYPE_TEXT)
      [[ -n "${rest}" ]] || { echo "invalid TEXT: ${spec}" >&2; exit 1; }
      printf '{"action":"TYPE_TEXT","text":"%s"}' "$(json_escape "${rest}")"
      ;;
    ASK_USER)
      [[ -n "${rest}" ]] || { echo "invalid ASK_USER: ${spec}" >&2; exit 1; }
      printf '{"action":"ASK_USER","question":"%s"}' "$(json_escape "${rest}")"
      ;;
    DONE)
      [[ -n "${rest}" ]] || rest="done"
      printf '{"action":"DONE","reason":"%s"}' "$(json_escape "${rest}")"
      ;;
    *)
      echo "unsupported step action: ${action} (spec='${spec}')" >&2
      exit 1
      ;;
  esac
}

build_payload_from_steps() {
  local -a specs=("$@")
  local steps_json=""
  local i
  for i in "${!specs[@]}"; do
    local step_json
    step_json="$(append_step_json "${specs[$i]}")"
    if [[ -n "${steps_json}" ]]; then
      steps_json+=","
    fi
    steps_json+="${step_json}"
  done
  printf '{"steps":[%s]}' "${steps_json}"
}

start_session() {
  local bootstrap_target="$1"
  local output json session_id
  output="$(adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.START_SESSION' --es command '${PAYLOAD}' --es session_display_mode 'VIRTUAL_DISPLAY' --es bootstrap_target '${bootstrap_target}'" </dev/null)"
  printf '%s\n' "${output}"
  json="$(extract_broadcast_json "${output}")"
  session_id="$(extract_json_field "${json}" "sessionId")"
  if [[ -z "${session_id}" ]]; then
    echo "failed to get sessionId from START_SESSION output" >&2
    exit 1
  fi
  SESSION_ID="${session_id}"
  echo "sessionId=${SESSION_ID}"
}

cmd_dump() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.DUMP_ACTIVE_SESSION'" </dev/null
}

cmd_migrate_main() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.MIGRATE_TO_MAIN' --es session_id '${SESSION_ID}'" </dev/null
}

cmd_migrate_back() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.MIGRATE_TO_EXECUTION' --es session_id '${SESSION_ID}'" </dev/null
}

cmd_reply() {
  local text="$1"
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.REPLY_SESSION' --es session_id '${SESSION_ID}' --es user_text '$(json_escape "${text}")'" </dev/null
}

cmd_stop() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.STOP_SESSION' --ez migrate false" </dev/null
}

cmd_logcat() {
  adb logcat -d -v time -s SessionCoordinator SessionAuditReceiver SessionAutomationEngine TaskMigrationController AgentHostPlatformClient
}

print_repl_help() {
  cat <<'EOF'
Commands:
  dump                       dump active session json
  migrate-main               migrate bound task to main display
  migrate-back               migrate bound task back to execution display
  reply <text>               send REPLY_SESSION
  stop                       stop current session
  logcat                     show filtered logcat
  help                       show this help
  exit                       exit console (does not auto stop session)
EOF
}

repl() {
  print_repl_help
  while true; do
    printf 'agent-vd> '
    IFS= read -r line || break
    case "${line}" in
      dump)
        cmd_dump
        ;;
      migrate-main|main)
        cmd_migrate_main
        ;;
      migrate-back|back)
        cmd_migrate_back
        ;;
      reply\ *)
        cmd_reply "${line#reply }"
        ;;
      reply)
        cmd_reply "done"
        ;;
      stop)
        cmd_stop
        ;;
      logcat)
        cmd_logcat
        ;;
      help|\?)
        print_repl_help
        ;;
      exit|quit)
        break
        ;;
      "")
        ;;
      *)
        echo "unknown command: ${line}"
        print_repl_help
        ;;
    esac
  done
}

usage() {
  cat <<'EOF'
Usage:
  scripts/live_virtual_isolation.sh --payload '<json>' [--bootstrap-target settings]
  scripts/live_virtual_isolation.sh --step "WAIT 500" --step "CLICK 540 960" --step "ASK_USER 在主屏操作后回复" --step "DONE ok"

Step DSL:
  WAIT <ms>
  CLICK <x> <y>
  SWIPE <x1> <y1> <x2> <y2> <durationMs>
  BACK
  HOME
  LONG_PRESS <x> <y> <durationMs>
  TEXT <text...>
  ASK_USER <question...>
  DONE <reason...>

Notes:
  - Session starts in VIRTUAL_DISPLAY mode.
  - After start, use interactive commands to control migration timing manually.
EOF
}

main() {
  local bootstrap_target="${DEFAULT_BOOTSTRAP_TARGET}"
  local -a steps=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --payload)
        PAYLOAD="${2:-}"
        shift 2
        ;;
      --step)
        steps+=("${2:-}")
        shift 2
        ;;
      --bootstrap-target)
        bootstrap_target="${2:-}"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        echo "unknown arg: $1" >&2
        usage
        exit 1
        ;;
    esac
  done

  if [[ -z "${PAYLOAD}" ]]; then
    if [[ ${#steps[@]} -eq 0 ]]; then
      echo "provide --payload or at least one --step" >&2
      usage
      exit 1
    fi
    PAYLOAD="$(build_payload_from_steps "${steps[@]}")"
  fi

  start_session "${bootstrap_target}"
  repl
}

main "$@"
