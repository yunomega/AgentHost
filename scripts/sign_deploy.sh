#!/usr/bin/env bash
set -Eeuo pipefail

# ================= 基本配置 =================
AOSP_ROOT="/home/alientek/rk3588_android_sdk"
SOURCE_APK="/home/alientek/AndroidStudioProjects/AgentHost/app/build/outputs/apk/debug/app-debug.apk"
SIGNED_APK="/tmp/AgentHost-signed.apk"
ALIGNED_APK="/tmp/AgentHost-aligned.apk"

PKG_NAME="com.example.agenthost"
PRIVAPP_DIR="/system/priv-app/AgentHost"
TARGET_APK_PATH="${PRIVAPP_DIR}/AgentHost.apk"

# 组件名：按你的 manifest 实际情况改
BOOT_SERVICE_COMPONENT="${PKG_NAME}/.overlay.OverlayEntryService"
DEBUG_RECEIVER_COMPONENT="${PKG_NAME}/.debug.SessionAuditReceiver"

# Android SDK / Build Tools
BUILD_TOOLS_DIR="/home/alientek/Android/Sdk/build-tools/36.0.0"
APKSIGNER="${BUILD_TOOLS_DIR}/apksigner"
ZIPALIGN="${BUILD_TOOLS_DIR}/zipalign"

# AOSP 平台签名
KEY_DIR="${AOSP_ROOT}/device/rockchip/common/security"
PLATFORM_KEY_PK8="${KEY_DIR}/platform.pk8"
PLATFORM_KEY_PEM="${KEY_DIR}/platform.x509.pem"

# 可选：指定设备序列号；为空则用默认 adb 目标
ADB_SERIAL="${ADB_SERIAL:-}"

# 部署后是否自动触发 debug 验证
AUTO_VALIDATE="${AUTO_VALIDATE:-1}"

# ================= adb 包装 =================
adb_cmd() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    adb -s "${ADB_SERIAL}" "$@"
  else
    adb "$@"
  fi
}

# ================= 日志函数 =================
log()  { echo -e "\033[1;34m[INFO]\033[0m $*"; }
ok()   { echo -e "\033[1;32m[ OK ]\033[0m $*"; }
warn() { echo -e "\033[1;33m[WARN]\033[0m $*"; }
err()  { echo -e "\033[1;31m[ERR ]\033[0m $*" >&2; }

cleanup() {
  rm -f "${ALIGNED_APK}" 2>/dev/null || true
}
trap cleanup EXIT

# ================= 检查函数 =================
require_file() {
  local f="$1"
  [[ -f "$f" ]] || { err "文件不存在: $f"; exit 1; }
}

require_bin() {
  local b="$1"
  command -v "$b" >/dev/null 2>&1 || { err "命令不存在: $b"; exit 1; }
}

wait_for_boot() {
  log "等待设备连接..."
  adb_cmd wait-for-device

  log "等待系统启动完成..."
  local boot=""
  for _ in $(seq 1 120); do
    boot="$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    [[ "${boot}" == "1" ]] && break
    sleep 2
  done

  [[ "${boot}" == "1" ]] || { err "设备未在预期时间内完成启动"; exit 1; }
  ok "设备已启动完成"
}

ensure_adb_root() {
  log "尝试 adb root..."
  adb_cmd root >/dev/null 2>&1 || warn "adb root 失败，继续尝试后续步骤"
  adb_cmd wait-for-device
  sleep 2
}

ensure_remount() {
  log "尝试 remount..."
  local remount_out
  remount_out="$(adb_cmd remount 2>&1 || true)"
  echo "${remount_out}"

  if echo "${remount_out}" | grep -qi "succeeded"; then
    ok "remount 成功"
    return 0
  fi

  warn "首次 remount 未成功，尝试 disable-verity -> reboot -> remount"
  adb_cmd disable-verity || true
  sleep 2

  adb_cmd reboot
  wait_for_boot
  ensure_adb_root

  remount_out="$(adb_cmd remount 2>&1 || true)"
  echo "${remount_out}"
  echo "${remount_out}" | grep -qi "succeeded" || {
    err "remount 仍失败，请手动检查设备是否允许修改 system 分区"
    exit 1
  }

  ok "二次 remount 成功"
}

verify_tools() {
  require_file "${SOURCE_APK}"
  require_file "${PLATFORM_KEY_PK8}"
  require_file "${PLATFORM_KEY_PEM}"
  require_file "${APKSIGNER}"
  require_file "${ZIPALIGN}"
  require_bin adb
  require_bin openssl
  ok "本地文件检查通过"
}

show_apk_cert() {
  local apk="$1"
  "${APKSIGNER}" verify --print-certs "${apk}" || true
}

zipalign_and_sign() {
  log "源 APK 签名信息（如有）:"
  show_apk_cert "${SOURCE_APK}"

  log "平台证书指纹:"
  openssl x509 -in "${PLATFORM_KEY_PEM}" -noout -fingerprint -sha256 || true

  log "先 zipalign，再签名"
  rm -f "${ALIGNED_APK}" "${SIGNED_APK}"
  "${ZIPALIGN}" -f -p 4 "${SOURCE_APK}" "${ALIGNED_APK}"

  "${APKSIGNER}" sign \
    --key "${PLATFORM_KEY_PK8}" \
    --cert "${PLATFORM_KEY_PEM}" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "${SIGNED_APK}" \
    "${ALIGNED_APK}"

  ok "签名完成: ${SIGNED_APK}"

  log "验证签名:"
  "${APKSIGNER}" verify --print-certs -v "${SIGNED_APK}"
  ok "APK 签名校验通过"
}

prepare_target_dir() {
  log "准备目标目录 ${PRIVAPP_DIR}"
  adb_cmd shell "mkdir -p '${PRIVAPP_DIR}'"
  adb_cmd shell "chmod 755 '${PRIVAPP_DIR}'"
}

backup_old_apk() {
  log "备份旧 APK（如存在）"
  adb_cmd shell "if [ -f '${TARGET_APK_PATH}' ]; then cp '${TARGET_APK_PATH}' '${TARGET_APK_PATH}.bak'; fi" || true
}

stop_old_processes() {
  log "停止旧进程/服务"
  adb_cmd shell "am force-stop '${PKG_NAME}'" || true
  local pid
  pid="$(adb_cmd shell "pidof '${PKG_NAME}'" 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "${pid}" ]]; then
    adb_cmd shell "kill -9 ${pid}" || true
    sleep 1
  fi
  ok "旧进程处理完成"
}

deploy_apk() {
  log "推送 APK 到 ${TARGET_APK_PATH}"
  adb_cmd push "${SIGNED_APK}" "${TARGET_APK_PATH}"

  log "设置权限与 SELinux 上下文"
  adb_cmd shell "chmod 644 '${TARGET_APK_PATH}'"
  adb_cmd shell "chmod 755 '${PRIVAPP_DIR}'"
  adb_cmd shell "chcon u:object_r:system_file:s0 '${PRIVAPP_DIR}'" || true
  adb_cmd shell "chcon u:object_r:system_file:s0 '${TARGET_APK_PATH}'" || true
  adb_cmd shell sync
  ok "APK 已推送完成"
}

refresh_package_state() {
  log "查看包路径"
  adb_cmd shell "pm path '${PKG_NAME}'" || true

  log "尝试触发包编译/加载刷新"
  adb_cmd shell "cmd package compile -m speed -f '${PKG_NAME}'" || true

  log "检查包信息"
  adb_cmd shell "dumpsys package '${PKG_NAME}' | grep -E 'versionName|versionCode|lastUpdateTime|codePath|resourcePath'" || true
}

start_service_if_present() {
  log "尝试启动主服务: ${BOOT_SERVICE_COMPONENT}"
  adb_cmd shell "am startservice -n '${BOOT_SERVICE_COMPONENT}'" || true
}

validate_debug_receiver_if_present() {
  [[ "${AUTO_VALIDATE}" == "1" ]] || return 0

  log "尝试通过 debug receiver 做最小验证"
  adb_cmd shell "am broadcast -n '${DEBUG_RECEIVER_COMPONENT}' -a '${PKG_NAME}.debug.DUMP_ACTIVE_SESSION'" || true
  adb_cmd shell "am broadcast -n '${DEBUG_RECEIVER_COMPONENT}' -a '${PKG_NAME}.debug.START_SESSION' --es command 'smoke'" || true
  sleep 2
  adb_cmd shell "am broadcast -n '${DEBUG_RECEIVER_COMPONENT}' -a '${PKG_NAME}.debug.DUMP_ACTIVE_SESSION'" || true
}

print_post_checks() {
  log "建议继续观察以下命令输出："
  cat <<EOF
adb logcat -v time -s SessionAuditReceiver SessionCoordinator SessionRootActivity SessionBridge VirtualDisplayRuntime TaskMigrationController AtmHiddenApi
adb shell dumpsys package ${PKG_NAME} | grep -E "versionName|versionCode|lastUpdateTime|codePath|resourcePath"
adb shell dumpsys activity activities | grep -nE "SessionRootActivity|taskId=|displayId="
adb shell dumpsys display | grep -nE "session-vd-|mDisplayId="
adb shell dumpsys window displays | grep -nE "Display #[0-9]+|SessionRootActivity|taskId="
EOF
}

main() {
  verify_tools

  log "检查 adb 设备"
  adb_cmd devices -l

  wait_for_boot
  ensure_adb_root
  ensure_remount

  stop_old_processes
  prepare_target_dir
  backup_old_apk
  zipalign_and_sign
  deploy_apk
  refresh_package_state
  start_service_if_present
  validate_debug_receiver_if_present
  print_post_checks

  ok "部署流程完成"
}

main "$@"
