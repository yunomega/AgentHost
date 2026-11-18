# AgentHost

**RK3588 定制 Android 上的 AI 智能体宿主系统。**

一个在定制 Android（AOSP）系统里运行的 AI agent 宿主：它能"看"屏幕（显示截屏）、能"动手"（注入输入事件）、并在独立虚拟显示上执行任务——主屏不受影响。宿主应用与 Android Framework 两侧协同：应用侧负责任务编排与模型调用，Framework 侧提供受权限管控的平台能力。

> ⚠️ 本仓为**应用侧**源码（宿主 App + 设计文档 + 测试脚本）。Framework 侧改动位于 RK3588 vendor AOSP 源码树，随系统镜像构建；改动摘录见 [`patches/`](patches/README.md)（新增文件为完整源码，改动文件为 `.snippet` 片段），改动面见下文「Framework 侧」。

## 架构总览

```
┌─ 宿主 App（com.example.agenthost，平台签名特权应用）────────────┐
│  会话引擎 · 子目标状态机 · 双通道规划器（模型 / 脚本兜底）        │
│  观察（截屏/视图变化检测）→ 规划 → 动作下发                       │
│  悬浮窗监管 UI：气泡 / 接管条 / 求助卡                           │
└──────────────┬────────────────────────────────────────────────┘
               │ AIDL（签名权限 ACCESS_PLATFORM_AUTOMATION 门禁）
┌──────────────▼────────────────────────────────────────────────┐
│ system_server · AgentHostPlatformService                       │
│  · captureDisplay           显示截屏（SurfaceControl/HardwareBuffer）│
│  · register/update/clear ExecutionDisplaySession  会话生命周期  │
│  · startExecutionDisplayStartupTarget  执行显示启动目标         │
│  · injectTap/Swipe/Key/Text/LongPress  输入注入（InputManager） │
└──────────────┬────────────────────────────────────────────────┘
               │ LocalServices
┌──────────────▼────────────────────────┐  ┌─────────────────────┐
│ WindowManager ·                        │  │ VirtualDisplay 运行时│
│ ExecutionDisplayPolicyController       │  │ 任务执行显示隔离      │
│ 单会话互斥 · 显示归属校验 · 启动路由    │  │ Launcher3 副屏 Home  │
└───────────────────────────────────────┘  └─────────────────────┘
```

模型侧：应用经 HTTP 网关（OpenAI 兼容接口）接入视觉语言模型，解析屏幕观察并下发 GUI 动作；另保留脚本化规划器作无模型兜底与回归基线。

## Framework 侧改动（vendor 源码树）

源码摘录见 [`patches/`](patches/README.md)：新增文件为完整源码，改动文件为 `.snippet` 片段（标注原文件近似行号）。

| 位置 | 改动 |
| --- | --- |
| `frameworks/base/services/core/java/com/android/server/agenthost/AgentHostPlatformService.java` | **新增系统服务**（约 1000 行）：`SystemServer` 启动，`ServiceManager` 发布为 `agenthost_platform`；统一 Bundle 返回码，保留操作历史供 dump |
| `frameworks/base/services/core/java/com/android/server/wm/ExecutionDisplayPolicyController.java` | **WMS 新增执行显示策略控制器**（约 1000 行）：单一活跃执行显示会话、状态机（EXECUTION / USER_HANDOFF / COMPLETED）、显示归属与 UID 校验、启动目标路由（`SECONDARY_HOME` / `EXPLICIT_COMPONENT_ON_DISPLAY`） |
| `frameworks/base/services/core/java/com/android/server/wm/ActivityTaskManagerInternal.java` | 新增 4 个 internal 方法（执行显示会话注册/更新/清理/启动目标），由 ATMS 实现委派策略控制器 |
| `frameworks/base/services/java/com/android/server/SystemServer.java` | 启动 `AgentHostPlatformService` |
| `frameworks/base/core/java/android/app/SystemServiceRegistry.java` | 注册 `agenthost_platform`，供客户端 `ServiceManager.getService` 获取 |
| `frameworks/base/core/java/com/example/agenthost/platform/IAgentHostPlatformService.aidl` | **AIDL 编入 boot jar**；`soong` 侧 `check_boot_jars/package_allowed_list.txt` 放行 `com.example.agenthost` 包 |
| `frameworks/base/core/res/AndroidManifest.xml` | 声明签名级自定义权限 `com.example.agenthost.permission.ACCESS_PLATFORM_AUTOMATION` |
| `system/sepolicy/private/service.te` + `service_contexts` | SELinux 新增 `agenthost_platform_service` 类型与 contexts 映射 |
| `vendor/rockchip/AgentHost/` | 应用以 `android_app_import` 集成：`certificate: platform`、`privileged: true`，随系统镜像预置；`privapp-permissions-agenthost.xml` 白名单授予 `INJECT_EVENTS`、`CAPTURE_VIDEO_OUTPUT`、`READ_FRAME_BUFFER`、`MANAGE_ACTIVITY_TASKS`、`REMOVE_TASKS`、`STATUS_BAR_SERVICE`、`ADD_TRUSTED_DISPLAY`、`ASSOCIATE_INPUT_DEVICE_TO_DISPLAY` |
| `frameworks/base/services/tests/wmtests/` | 新增 `ExecutionDisplayPolicyControllerTests`，扩展 `TaskLaunchParamsModifierTests` |

**为什么做成 Framework 改造而不是普通应用**：显示截屏、跨显示输入注入、任务跨显示迁移都需要 signature 级特权接口；把能力收敛到 system_server 内的平台服务，配合自定义签名权限 + SELinux + 包名校验，形成"一个入口、一处门禁"的模型，而不是把特权散落给普通应用。

## 应用侧（本仓）

- **任务编排**：会话引擎 + 子目标状态机；`PlannerSelector` 双通道——`ModelPlanningAdapter`（模型驱动）/ `ScriptedStepPlanningAdapter`（脚本兜底）
- **观察**：`PlatformObservationProvider` / `VdObservationProvider` + `ObservationChangeDetector` 视图变化检测
- **动作**：`StrictJsonActionParser` 解析模型输出为规范动作，`DisplayActionExecutor`（平台服务通道，shell 通道兜底）执行
- **悬浮窗监管**：`SYSTEM_ALERT_WINDOW` + 前台服务，气泡/接管条/求助卡多状态 UI（idle / running / need_user / recovery），开机自启
- **运行隔离**：`VirtualDisplayRuntime` 创建执行显示，任务在活动虚拟显示内运行；`TaskMigrationController` 经 ActivityTaskManager 隐藏 API 做任务跨显示迁移与回收
- **模型接入**：`HttpModelClient` 走 OpenAI 兼容 HTTP 网关；`scripts/proxy_server.py` 为 PC 端 Flask 代理/模拟网关

## 测试

- **JUnit 单元测试**：automation / overlay / session / migration / platform / report / security 各域覆盖（状态机、动作解析、权限策略等）
- **androidTest**：调试入口与权限边界校验
- **framework wmtests**：`ExecutionDisplayPolicyControllerTests` + `TaskLaunchParamsModifierTests`
- **scripts/**：`verify_*` 系列设备端回归（任务迁移/并发/恢复/回归/soak 压测）、`overlay_visual_case_driver.sh` 视觉用例驱动、`proxy_server.py` 契约 pytest
- **docs/**：模型回归 runbook、Phase5 安全加固、状态机一致性审计、提示词设计评估、悬浮窗人工核查清单

## 仓库结构

```
app/        宿主应用（Kotlin + Jetpack Compose，minSdk 24 / targetSdk 35）
app/src/main/aidl/   平台服务 AIDL 接口
docs/       设计、审计与评估文档
patches/    Framework 侧改动摘录（新增文件完整源码 + 改动文件 .snippet 片段）
scripts/    签名部署、回归、压测、代理与冒烟脚本
```

## 许可

本仓未附带许可证；应用侧代码仅供查阅与评估。
