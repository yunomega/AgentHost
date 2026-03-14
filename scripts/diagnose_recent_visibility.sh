#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PKG="${PKG:-com.example.agenthost}"
RECV="${RECV:-${PKG}/.debug.SessionAuditReceiver}"
ADB_SERIAL="${ADB_SERIAL:-}"
LOG_DIR="${LOG_DIR:-$(mktemp -d /tmp/agenthost-recent-visibility.XXXXXX)}"
SNAPSHOT_LABEL="${SNAPSHOT_LABEL:-manual}"

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

broadcast_dump_session() {
  adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.DUMP_ACTIVE_SESSION'"
}

main() {
  mkdir -p "${LOG_DIR}"
  cd "${PROJECT_ROOT}"

  log "capturing recent-visibility diagnostics label=${SNAPSHOT_LABEL}"
  broadcast_dump_session > "${LOG_DIR}/${SNAPSHOT_LABEL}.session.stdout.log" || true
  adb_cmd shell dumpsys activity recents > "${LOG_DIR}/${SNAPSHOT_LABEL}.recents.dump" || true
  adb_cmd shell dumpsys activity activities > "${LOG_DIR}/${SNAPSHOT_LABEL}.activities.dump" || true
  adb_cmd shell dumpsys window displays > "${LOG_DIR}/${SNAPSHOT_LABEL}.window.dump" || true
  adb_cmd shell dumpsys activity containers > "${LOG_DIR}/${SNAPSHOT_LABEL}.containers.dump" || true

  {
    echo "label=${SNAPSHOT_LABEL}"
    echo "recents_hits=$(grep -cE 'Recent|Task\{|SessionRootActivity|com.android.settings|${PKG}' "${LOG_DIR}/${SNAPSHOT_LABEL}.recents.dump" 2>/dev/null || true)"
    echo "activities_hits=$(grep -cE 'displayId=|Task\{|SessionRootActivity|com.android.settings|${PKG}' "${LOG_DIR}/${SNAPSHOT_LABEL}.activities.dump" 2>/dev/null || true)"
    echo "window_hits=$(grep -cE 'Display #[0-9]+|mCurrentFocus|mFocusedApp|SessionRootActivity|com.android.settings|${PKG}' "${LOG_DIR}/${SNAPSHOT_LABEL}.window.dump" 2>/dev/null || true)"
  } > "${LOG_DIR}/${SNAPSHOT_LABEL}.summary.txt"

  echo "logs saved under ${LOG_DIR}"
  echo "summary: ${LOG_DIR}/${SNAPSHOT_LABEL}.summary.txt"
  echo "recommended sequence: run once before handoff, once during WAITING_USER on main, once after migrate-back"
}

main "$@"
