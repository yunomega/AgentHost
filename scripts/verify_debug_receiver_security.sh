#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PKG="${PKG:-com.example.agenthost}"
RECV="${RECV:-${PKG}/.debug.SessionAuditReceiver}"
ADB_SERIAL="${ADB_SERIAL:-}"
LOG_DIR="${LOG_DIR:-$(mktemp -d /tmp/agenthost-debug-receiver-security.XXXXXX)}"
SKIP_TARGET_INSTALL="${SKIP_TARGET_INSTALL:-0}"
TEST_PKG="${TEST_PKG:-${PKG}.test}"
ANDROID_TEST_APK="${ANDROID_TEST_APK:-${PWD}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
AOSP_ROOT="${AOSP_ROOT:-/home/alientek/rk3588_android_sdk}"
BUILD_TOOLS_DIR="${BUILD_TOOLS_DIR:-/home/alientek/Android/Sdk/build-tools/36.0.0}"
APKSIGNER="${APKSIGNER:-${BUILD_TOOLS_DIR}/apksigner}"
ZIPALIGN="${ZIPALIGN:-${BUILD_TOOLS_DIR}/zipalign}"
KEY_DIR="${KEY_DIR:-${AOSP_ROOT}/device/rockchip/common/security}"
PLATFORM_KEY_PK8="${PLATFORM_KEY_PK8:-${KEY_DIR}/platform.pk8}"
PLATFORM_KEY_PEM="${PLATFORM_KEY_PEM:-${KEY_DIR}/platform.x509.pem}"
SIGNED_ANDROID_TEST_APK="${SIGNED_ANDROID_TEST_APK:-/tmp/AgentHost-debug-androidTest-signed.apk}"
ALIGNED_ANDROID_TEST_APK="${ALIGNED_ANDROID_TEST_APK:-/tmp/AgentHost-debug-androidTest-aligned.apk}"

UNIT_TEST_CLASS="*DebugEntryAccessPolicyTest"
ANDROID_TEST_CLASS="com.example.agenthost.debug.SessionAuditReceiverSecurityTest"

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

cleanup() {
  rm -f "${ALIGNED_ANDROID_TEST_APK}" "${SIGNED_ANDROID_TEST_APK}" 2>/dev/null || true
}
trap cleanup EXIT

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

assert_contains() {
  local file="$1"
  local pattern="$2"
  local message="$3"
  if ! grep -E -q "${pattern}" "${file}"; then
    die "${message}: pattern='${pattern}' file='${file}'"
  fi
}

sign_and_install_android_test() {
  [[ -f "${ANDROID_TEST_APK}" ]] || die "missing androidTest APK: ${ANDROID_TEST_APK}"
  [[ -f "${APKSIGNER}" ]] || die "missing apksigner: ${APKSIGNER}"
  [[ -f "${ZIPALIGN}" ]] || die "missing zipalign: ${ZIPALIGN}"
  [[ -f "${PLATFORM_KEY_PK8}" ]] || die "missing platform key: ${PLATFORM_KEY_PK8}"
  [[ -f "${PLATFORM_KEY_PEM}" ]] || die "missing platform cert: ${PLATFORM_KEY_PEM}"

  rm -f "${ALIGNED_ANDROID_TEST_APK}" "${SIGNED_ANDROID_TEST_APK}"
  "${ZIPALIGN}" -f -p 4 "${ANDROID_TEST_APK}" "${ALIGNED_ANDROID_TEST_APK}"
  "${APKSIGNER}" sign \
    --key "${PLATFORM_KEY_PK8}" \
    --cert "${PLATFORM_KEY_PEM}" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "${SIGNED_ANDROID_TEST_APK}" \
    "${ALIGNED_ANDROID_TEST_APK}"
  adb_cmd uninstall "${TEST_PKG}" >/dev/null 2>&1 || true
  adb_cmd install -r -t "${SIGNED_ANDROID_TEST_APK}" | tee "${LOG_DIR}/install_android_test.log"
}

main() {
  mkdir -p "${LOG_DIR}"
  cd "${PROJECT_ROOT}"

  log "running debug receiver policy unit tests"
  ./gradlew :app:testDebugUnitTest --tests "${UNIT_TEST_CLASS}" --rerun-tasks | tee "${LOG_DIR}/unit_tests.log"
  ok "unit tests passed"

  if ! adb_cmd get-state >/dev/null 2>&1; then
    warn "no adb device available, skipping connected verification"
    echo "logs saved under ${LOG_DIR}"
    exit 0
  fi

  if [[ "${SKIP_TARGET_INSTALL}" == "1" ]]; then
    log "building androidTest APK only; target package is expected to already be deployed"
    ./gradlew :app:assembleDebugAndroidTest | tee "${LOG_DIR}/install.log"
    log "platform-signing androidTest APK to match deployed priv-app"
    sign_and_install_android_test
  else
    log "building and installing debug + androidTest APKs"
    set +e
    ./gradlew :app:installDebug :app:installDebugAndroidTest | tee "${LOG_DIR}/install.log"
    local install_rc=$?
    set -e
    if [[ "${install_rc}" != "0" ]]; then
      if grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' "${LOG_DIR}/install.log"; then
        die "device already has com.example.agenthost with a different signature; uninstall that package on the test device, then rerun this script or use SKIP_TARGET_INSTALL=1 after sign_deploy"
      fi
      die "failed to install debug artifacts onto device"
    fi
  fi

  log "running receiver instrumentation security test"
  adb_cmd logcat -c || true
  local instr_output
  instr_output="$(adb_cmd shell am instrument -w -r \
    -e class "${ANDROID_TEST_CLASS}" \
    "${TEST_PKG}/androidx.test.runner.AndroidJUnitRunner" || true)"
  printf '%s\n' "${instr_output}" | tee "${LOG_DIR}/connected_tests.log"
  if ! printf '%s\n' "${instr_output}" | grep -qE 'OK \([0-9]+ test'; then
    die "instrumentation test failed, see ${LOG_DIR}/connected_tests.log"
  fi
  adb_cmd logcat -d -v time -s SessionAuditReceiver > "${LOG_DIR}/instrumentation.logcat"
  assert_contains "${LOG_DIR}/instrumentation.logcat" 'event=debug_access_denied code=DEBUG_PERMISSION_DENIED .*reason=caller_not_allowed' "missing denial log from instrumentation test"
  assert_contains "${LOG_DIR}/instrumentation.logcat" 'event=debug_access_denied code=DEBUG_CALLER_IDENTITY_UNAVAILABLE .*reason=caller_identity_capability_missing' "missing identity-unavailable denial log from instrumentation test"
  ok "instrumentation denial path verified"

  log "verifying adb shell keeps authorized debug path"
  local output json ok_value access_code code
  output="$(adb_cmd shell "am broadcast -n '${RECV}' -a '${PKG}.debug.DUMP_ACTIVE_SESSION'")"
  printf '%s\n' "${output}" > "${LOG_DIR}/authorized_dump.stdout.log"
  json="$(extract_broadcast_json "${output}")"
  [[ -n "${json}" ]] || die "failed to parse authorized DUMP_ACTIVE_SESSION output"
  ok_value="$(extract_json_bool "${json}" "ok")"
  access_code="$(extract_json_string "${json}" "accessCode")"
  code="$(extract_json_string "${json}" "code")"
  [[ "${access_code}" == "DEBUG_ACCESS_OK" ]] || die "authorized adb shell path lost accessCode, got '${access_code}'"
  [[ "${code}" != "DEBUG_PERMISSION_DENIED" && "${code}" != "DEBUG_CALLER_IDENTITY_UNAVAILABLE" ]] || die "authorized adb shell path was denied unexpectedly, got '${code}'"
  ok "authorized adb shell debug path preserved"

  echo
  ok "debug receiver security verification passed"
  echo "logs saved under ${LOG_DIR}"
  echo "adb replay: adb shell am broadcast -n ${RECV} -a ${PKG}.debug.DUMP_ACTIVE_SESSION"
}

main "$@"
