#!/usr/bin/env bash
set -euo pipefail

PKG="com.example.agenthost"
RECV="$PKG/.debug.SessionAuditReceiver"

start_with_payload() {
  local payload="$1"
  adb shell "am broadcast -n $RECV -a $PKG.debug.START_SESSION --es command '$payload'"
}

dump_session() {
  adb shell am broadcast -n "$RECV" -a "$PKG.debug.DUMP_ACTIVE_SESSION"
}

reply_session() {
  local session_id="$1"
  local user_text="${2:-hello}"
  adb shell am broadcast -n "$RECV" \
    -a "$PKG.debug.REPLY_SESSION" \
    --es session_id "$session_id" \
    --es user_text "$user_text"
}

stop_session() {
  local migrate="${1:-true}"
  [[ "$migrate" == "true" || "$migrate" == "false" ]] || {
    echo "migrate must be true or false" >&2
    exit 1
  }
  adb shell am broadcast -n "$RECV" \
    -a "$PKG.debug.STOP_SESSION" \
    --ez migrate "$migrate"
}

logs() {
  adb logcat -v time -s SessionCoordinator SessionAutomationEngine SessionAuditReceiver OverlayEntryService
}

usage() {
  cat <<'EOF'
Usage:
  scripts/debug_session_flow.sh start-json '<json>'
  scripts/debug_session_flow.sh start-ask-done
  scripts/debug_session_flow.sh dump
  scripts/debug_session_flow.sh reply <session_id> [user_text]
  scripts/debug_session_flow.sh stop [true|false]
  scripts/debug_session_flow.sh logs

Examples:
  scripts/debug_session_flow.sh start-json '{"steps":[{"action":"CLICK","x":300,"y":500},{"action":"ASK_USER","question":"请输入文本"},{"action":"TYPE_TEXT","text":"{{last_user_input}}"},{"action":"DONE","reason":"ok"}]}'
  scripts/debug_session_flow.sh start-ask-done
  scripts/debug_session_flow.sh dump
  scripts/debug_session_flow.sh reply 11111111-2222-3333-4444-555555555555 hello
  scripts/debug_session_flow.sh stop true
  scripts/debug_session_flow.sh logs
EOF
}

main() {
  local cmd="${1:-}"
  case "$cmd" in
    start-json)
      local payload="${2:-}"
      [[ -n "$payload" ]] || { echo "missing json payload" >&2; usage; exit 1; }
      start_with_payload "$payload"
      ;;
    start-ask-done)
      start_with_payload '{"steps":[{"action":"ASK_USER","question":"请输入文本"},{"action":"DONE","reason":"ok"}]}'
      ;;
    dump)
      dump_session
      ;;
    reply)
      local session_id="${2:-}"
      [[ -n "$session_id" ]] || { echo "missing session_id" >&2; usage; exit 1; }
      reply_session "$session_id" "${3:-hello}"
      ;;
    stop)
      stop_session "${2:-true}"
      ;;
    logs)
      logs
      ;;
    *)
      usage
      ;;
  esac
}

main "$@"
