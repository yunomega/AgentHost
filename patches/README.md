# Framework 改动摘录（RK3588 定制 Android）

本目录摘录 AgentHost 在 RK3588 Android 源码树中的 Framework 侧改动，供查阅与评估。

- 目录结构按源码树相对路径组织（`frameworks/base/...`、`system/sepolicy/...` 等）。
- **新增文件**为完整源码；**改动文件**以 `.snippet` 后缀保存，仅保留 AgentHost 相关改动片段，文件头注释注明原文件中的近似行号。
- 改动基于厂商 RK3588 Android（API 35 时代）源码树；上游基线版本以厂商 BSP 为准，未做逐文件 diff 对齐。

## 新增文件（完整）

| 路径 | 说明 |
|---|---|
| `frameworks/base/core/java/com/example/agenthost/platform/IAgentHostPlatformService.aidl` | 平台服务 AIDL：截屏、执行显示会话、输入注入 |
| `frameworks/base/services/core/java/com/android/server/agenthost/AgentHostPlatformService.java` | 新增 system_server 系统服务（997 行），注册为 `agenthost_platform` Binder 服务，签名校验 + 会话归属门禁 |
| `frameworks/base/services/core/java/com/android/server/wm/ExecutionDisplayPolicyController.java` | WMS 新增执行显示策略控制器（986 行）：会话状态机 EXECUTION → USER_HANDOFF → COMPLETED，启动模式 SECONDARY_HOME / EXPLICIT_COMPONENT_ON_DISPLAY，显式错误码返回 |
| `frameworks/base/services/tests/wmtests/src/com/android/server/wm/ExecutionDisplayPolicyControllerTests.java` | 控制器单测（197 行） |
| `vendor/rockchip/AgentHost/Android.bp` | 平台签名特权应用预置（Soong） |
| `vendor/rockchip/AgentHost/privapp-permissions-agenthost.xml` | privapp 白名单：INJECT_EVENTS、CAPTURE_VIDEO_OUTPUT、READ_FRAME_BUFFER、MANAGE_ACTIVITY_TASKS、REMOVE_TASKS、STATUS_BAR_SERVICE、ADD_TRUSTED_DISPLAY、ASSOCIATE_INPUT_DEVICE_TO_DISPLAY 等 |

## 改动文件（片段，`.snippet`）

| 路径 | 改动内容 |
|---|---|
| `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerInternal.java` | +4 个 internal 方法：registerExecutionDisplaySession / updateExecutionDisplaySessionState / clearExecutionDisplaySession / startExecutionDisplayStartupTarget |
| `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerService.java` | 持有 `mExecutionDisplayPolicyController`；LocalService 将上述 internal 方法转发给控制器 |
| `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java` | 启动门禁 `isLaunchAllowedOnExecutionDisplay`：放行已注册执行显示会话的 UID |
| `frameworks/base/services/core/java/com/android/server/wm/RootWindowContainer.java` | 显示访问白名单 `appendAdditionalDisplayAccessUids` 追加执行显示 UID；白名单同步日志带 sessionId/displayId |
| `frameworks/base/services/core/java/com/android/server/wm/TaskLaunchParamsModifier.java` | `adjustLaunchTaskDisplayArea`：按执行显示策略路由任务落点 |
| `frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java` | 私有显示访问日志/门禁接入执行显示策略 |
| `frameworks/base/services/java/com/android/server/SystemServer.java` | `startService(AgentHostPlatformService.class)`，在 DisplayManager 之后启动 |
| `frameworks/base/core/java/android/app/SystemServiceRegistry.java` | `registerService("agenthost_platform", ...)` 供客户端按名查询 Binder |
| `frameworks/base/core/res/AndroidManifest.xml` | 声明 `com.example.agenthost.permission.ACCESS_PLATFORM_AUTOMATION`（signature\|privileged） |
| `system/sepolicy/private/service.te` | `type agenthost_platform_service, system_api_service, system_server_service, service_manager_type;` |
| `system/sepolicy/private/service_contexts` | `agenthost_platform → u:object_r:agenthost_platform_service:s0` |
| `build/soong/scripts/check_boot_jars/package_allowed_list.txt` | 放行 `com\.example\.agenthost\.platform` 及子包入 boot jar |
| `frameworks/base/services/tests/wmtests/src/com/android/server/wm/TaskLaunchParamsModifierTests.java` | +3 测试：路由到执行显示 / 尊重显式主屏 / USER_HANDOFF 停止路由 |
| `frameworks/base/services/tests/wmtests/src/com/android/server/wm/ActivityTaskSupervisorTests.java` | +2 测试：注册 UID 获准 / 未注册私有显示拒绝 |

## 数据流

```
App (com.example.agenthost)
  └─ AIDL IAgentHostPlatformService      [ACCESS_PLATFORM_AUTOMATION 签名校验]
       └─ AgentHostPlatformService       [system_server; 会话归属校验; 截屏/输入注入]
            └─ ActivityTaskManagerInternal (4 方法)
                 └─ ExecutionDisplayPolicyController  [WMS 内]
                      ├─ TaskLaunchParamsModifier    启动路由：任务落到执行显示
                      ├─ ActivityTaskSupervisor      启动门禁：会话 UID 放行
                      ├─ RootWindowContainer         显示访问白名单追加
                      └─ WindowManagerService        私有显示访问日志/门禁
```
