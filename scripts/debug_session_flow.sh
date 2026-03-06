#!/usr/bin/env bash
set -euo pipefail
if [[ "${DEBUG_FLOW_TRACE:-0}" == "1" ]]; then
  set -x
fi

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

status() {
  dump_session >/dev/null
  sleep 0.2
  local line
  line="$(adb logcat -d -v time -s SessionAuditReceiver \
    | grep 'event=debug_dump_active_session' \
    | tail -n 1 || true)"
  if [[ -z "$line" ]]; then
    echo "status: no debug_dump_active_session log found"
    return 1
  fi
  local session_id status waiting_question step
  session_id="$(echo "$line" | sed -n 's/.* sessionId=\([^ ]*\) .*/\1/p')"
  status="$(echo "$line" | sed -n 's/.* status=\([^ ]*\) .*/\1/p')"
  waiting_question="$(echo "$line" | sed -n 's/.* waitingQuestion=\([^ ]*\) code=.*/\1/p')"
  step="$(echo "$line" | sed -n 's/.* step=\([^ ]*\) .*/\1/p')"
  echo "sessionId=${session_id:-null} status=${status:-null} waitingQuestion=${waiting_question:-null} step=${step:-0}"
}

usage() {
  cat <<'EOF'
Usage:
  scripts/debug_session_flow.sh start-json '<json>'
  scripts/debug_session_flow.sh start-ask-done
  scripts/debug_session_flow.sh dump
  scripts/debug_session_flow.sh status
  scripts/debug_session_flow.sh reply <session_id> [user_text]
  scripts/debug_session_flow.sh stop [true|false]
  scripts/debug_session_flow.sh logs

Examples:
  scripts/debug_session_flow.sh start-json '{"steps":[{"action":"CLICK","x":300,"y":500},{"action":"ASK_USER","question":"请输入文本"},{"action":"TYPE_TEXT","text":"{{last_user_input}}"},{"action":"DONE","reason":"ok"}]}'
  scripts/debug_session_flow.sh start-ask-done
  scripts/debug_session_flow.sh dump
  scripts/debug_session_flow.sh status
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
    status)
      status
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
