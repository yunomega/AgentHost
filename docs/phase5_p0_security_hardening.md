# Phase 5 P0.1 Security Hardening

## 当前攻击面清单

### exported 组件

| 组件 | manifest | exported | 风险级别 | 说明 |
| --- | --- | --- | --- | --- |
| `com.example.agenthost.MainActivity` | `app/src/main/AndroidManifest.xml` | `true` | 低 | 仅 launcher 入口，不直接驱动 task migration / input inject / privileged automation。 |
| `com.example.agenthost.debug.SessionAuditReceiver` | `app/src/debug/AndroidManifest.xml` | `true` | 高 | 可被广播触发，间接驱动会话启动、task migration、点击/输入注入、debug hook/fault 注入。仅存在于 debug 构建。 |

### exported=false 组件

| 组件 | manifest | exported | 说明 |
| --- | --- | --- | --- |
| `com.example.agenthost.session.SessionRootActivity` | `app/src/main/AndroidManifest.xml` | `false` | 内部 root/mirror activity，不接受外部触发。 |
| `com.example.agenthost.overlay.OverlayEntryService` | `app/src/main/AndroidManifest.xml` | `false` | 内部 overlay/service，不接受外部触发。 |

### 未发现项

- `BootReceiver`：当前仓库未发现实现或 manifest 注册。
- 其他 exported `receiver/service/activity`：当前 manifest 未发现。

## 高权限行为入口

`SessionAuditReceiver` 当前可间接驱动的高权限行为：

- `START_SESSION`：拉起 `SessionRootActivity`，驱动 automation session 启动。
- `STOP_SESSION`：停止 session，可附带 debug forced migration fail。
- `MIGRATE_TO_MAIN` / `MIGRATE_TO_EXECUTION`：驱动 task migration。
- `REPLY_SESSION`：推进 `WAITING_USER -> REPLY_SESSION -> migrate-back` 闭环。
- `SHOW_SESSION_ON_MAIN` / `RETURN_SESSION_TO_VIRTUAL`：切换可见面和 display carry 行为。
- `ARM_MIGRATION_FAULT` / `CLEAR_DEBUG_HOOKS` / `DUMP_DEBUG_HOOKS`：仅 debug，可驱动 one-shot recovery fault 注入和诊断。
- `DUMP_ACTIVE_SESSION`：导出状态诊断字段。

结论：真正需要收口的是 `SessionAuditReceiver`，而不是主链路 session/activity 本身。

## 最小补丁点

### 1. Receiver 保留 exported=true，但加 manifest + 运行时双门

文件：`app/src/debug/java/com/example/agenthost/debug/SessionAuditReceiver.kt`

策略：

- manifest 第一层：`android:permission="android.permission.DUMP"`，先把三方 app 挡在 receiver 外。
- runtime 第二层：`same app uid` 允许；`shell/root` 仅 `SessionAuditHooks.isEnabled()==true` 且 `Build.TYPE in {userdebug, eng}` 时允许。
- caller UID 无法解析时：默认返回 `DEBUG_CALLER_IDENTITY_UNAVAILABLE`。
- 兼容放行：若 ROM 缺 sender identity API，但 receiver 已经过 `android.permission.DUMP` 这道 manifest 权限门，且设备是 `userdebug/eng`，则允许进入并标记为 `permission_gated_identity_fallback`。

### 2. caller identity 能力缺失单独打码

文件：`app/src/main/java/com/example/agenthost/security/DebugEntryAccessPolicy.kt`

新增语义：

- `DEBUG_PERMISSION_DENIED`：caller 可识别，但不在允许集合。
- `DEBUG_CALLER_IDENTITY_UNAVAILABLE`：caller UID/包名解析依赖的平台能力不可用或解析失败。
- `DEBUG_ENTRY_DISABLED`：release/disabled hooks 场景。

这条兼容性回退规则用于区分“攻击/未授权”和“ROM/版本能力缺失”。

额外兼容规则：

- 如果 manifest 权限门已经生效，且设备为 `userdebug/eng`，则可对 `DEBUG_CALLER_IDENTITY_UNAVAILABLE` 走兼容放行，避免当前 RK3588/Android 13 这类缺 sender identity API 的 ROM 把 `adb shell` 调试链路整体打死。

### 3. debug-only 能力与 release 能力分离

文件：

- `app/src/debug/java/com/example/agenthost/debug/SessionAuditHooks.kt`
- `app/src/release/java/com/example/agenthost/debug/SessionAuditHooks.kt`

策略：

- debug 变体保留 hook/fault 注入。
- release 变体返回 no-op/disabled 状态。
- `SessionAuditReceiver` 仅在 debug manifest 注册，release 无此 receiver。

### 4. 拒绝结果和日志保留最小诊断字段

拒绝日志固定字段：

- `event=debug_access_denied`
- `code=`
- `action=`
- `callerUid=`
- `callerPackage=`
- `identitySource=`
- `reason=`

约束：

- 不记录用户输入文本、模型配置、命令 payload。
- 只保安全定位必要字段。

## 测试与验证

### 自动脚本入口

- `scripts/verify_debug_receiver_security.sh`

覆盖：

- 本地单测：`DebugEntryAccessPolicyTest`
- 设备 instrumentation：`SessionAuditReceiverSecurityTest`
- `adb shell` 合法 debug broadcast 不被误伤
- denial log 可 grep
- sign_deploy 场景可用 `SKIP_TARGET_INSTALL=1` 复用已部署 priv-app

### 手工 adb 复现

合法调试链路：

```bash
adb shell am broadcast \
  -n com.example.agenthost/.debug.SessionAuditReceiver \
  -a com.example.agenthost.debug.DUMP_ACTIVE_SESSION
```

预期：

- manifest 权限门允许 `shell` 进入 receiver
- `accessCode=DEBUG_ACCESS_OK`
- 不返回 `DEBUG_PERMISSION_DENIED`
- 若 ROM 无 sender identity API，`accessReason` 允许为 `permission_gated_identity_fallback`

拒绝链路：

- 推荐通过 `scripts/verify_debug_receiver_security.sh` 里的 instrumentation helper 复现。
- grep：

```bash
adb logcat -d -v time -s SessionAuditReceiver | grep 'event=debug_access_denied'
```

## 回归影响评估

### 不变项

- 不改 `SessionRootActivity` / `OverlayEntryService` 导出语义。
- 不改 `MAIN_DISPLAY` 行为。
- 不改 task migration / concurrency isolation 主链路。
- 不引入 planner/model/gateway 新字段。

### 风险点

- 极老/定制 ROM 若无 `getSentFromUid()` 且反射也不可用，会优先走 manifest 权限门兼容放行；若权限门未生效，仍会收口为 `DEBUG_CALLER_IDENTITY_UNAVAILABLE`。
- `adb shell` 调试依赖 `userdebug/eng`。`user` build 上会被拒绝，这是预期收口。

### 回滚点

最小回滚只需回退以下文件：

- `app/src/debug/java/com/example/agenthost/debug/SessionAuditReceiver.kt`
- `app/src/main/java/com/example/agenthost/security/DebugEntryAccessPolicy.kt`
- `app/src/debug/java/com/example/agenthost/debug/SessionAuditHooks.kt`
- `app/src/release/java/com/example/agenthost/debug/SessionAuditHooks.kt`

不需要回滚 session/task migration 主逻辑。
