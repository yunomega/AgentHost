# Overlay UI Interaction Semantics

## Scope

本文只梳理 UI 层的用户交互语义，不重新定义 session canonical state。

覆盖范围：

- `OverlayStateMapper`
- `OverlayController`
- `OverlayPostCompletionRetention`
- `OverlayActionDispatcher`
- `OverlayStartGuard`

目标是回答两个问题：

1. 用户在不同状态下会看到什么。
2. 用户点击主按钮、次按钮、气泡时，实际会发生什么。

## 总体原则

- UI mode 唯一来源是 `OverlayStateMapper.map(session)`。
- `OverlayController` 可以叠加本地 retained state，但不会回写业务状态。
- `activeSession == null` 时，基础 UI 是 `OverlayMode.IDLE`。
- `POST_COMPLETION` 是唯一允许在 `activeSession == null` 后短暂 retained 展示的模式。
- `HANDOFF_ACTIVE` 有两段式交互：
  先展示提示卡片，再展示带回复输入框的 handoff strip。

## 状态矩阵

| OverlayMode | 进入条件 | 气泡展示 | 展开后展示 | 主按钮语义 | 次按钮语义 | 新任务入口 |
| --- | --- | --- | --- | --- | --- | --- |
| `IDLE` | `session == null` 或 `status in {CLOSED, MIGRATED}` | `空闲 / 无活动会话 / IDLE` | 新任务输入面板 | 开始新任务 | 收起面板 | `AVAILABLE` |
| `RUNNING` | `STARTING/READY/RUNNING` 且无 recovery | `执行中 / 主屏执行中` 或 `执行中 / 虚拟屏后台执行 / RUNNING` | 仅新任务面板，但输入被禁用 | 无独立主按钮 | 收起面板 | `BLOCKED` |
| `NEED_USER` | `WAITING_USER` 且 `migratedToMain == false` | `等你 / 主屏等待处理` 或 `等你 / 需要切到主屏处理 / NEED_USER` | NeedUser 卡片 | 已在主屏则只收起；否则迁移到主屏 | 稍后提醒，进入 snooze | `HIDDEN` |
| `HANDOFF_ACTIVE` | `WAITING_USER` 且 `migratedToMain == true` | `等你 / 主屏处理中 / NEED_USER` | 未确认前是 NeedUser 卡片；确认后是 HandoffStrip | 第一阶段：确认接手；第二阶段：提交回复并恢复后台执行 | 第一阶段：稍后；第二阶段：取消任务 | `HIDDEN` |
| `RECOVERY` | `status == FAILED` 或 `recoveryNeeded == true` 或 migration code 需要 recovery | `异常 / 需要人工接手 / RECOVERY` | NeedUser 卡片 | 按 recovery route 执行动作 | 取消任务 | `HIDDEN` |
| `POST_COMPLETION` | `status == COMPLETED` | `已完成 / 已回主屏`、`已完成 / 已安全结束` 或 `已完成 / 会话已清理 / IDLE` | NeedUser 卡片 | 仅关闭 retained completion 展示 | 同样关闭 retained completion 展示 | `AVAILABLE` |

## 各状态详细语义

### IDLE

- 气泡文案：
  `bubbleText=空闲`
  `bubbleDetail=无活动会话`
  `title=当前空闲`
  `message=主屏保持给用户使用，AgentHost 暂无后台任务。`
- 展开行为：
  不显示 NeedUser 卡片，显示新任务输入面板。
- 新任务面板：
  `title=新任务`
  `message=当前空闲，可直接发起新的后台任务。`
  主按钮 `开始`
  次按钮 `收起`
- 用户点击 `开始`：
  走 `OverlayActionDispatcher.submitNewTask()`。
  空命令会立即返回 `COMMAND_EMPTY`。

### RUNNING

- 气泡文案：
  虚拟屏会话显示 `执行中 / 虚拟屏后台执行`
  主屏会话显示 `执行中 / 主屏执行中`
- 展开行为：
  不显示 NeedUser 卡片，仍显示新任务面板。
- 新任务面板：
  `message=当前已有任务在执行或处理中，完成或取消后再发起新任务。`
  输入框禁用，主按钮不可用。
- 用户交互：
  主要只有查看与收起，不会出现直接控制当前任务的按钮。

### NEED_USER

- 触发场景：
  会话进入 `WAITING_USER`，但尚未 handoff 到主屏。
- 气泡文案：
  `bubbleText=等你`
  若会话已在主屏：`bubbleDetail=主屏等待处理`
  否则：`bubbleDetail=需要切到主屏处理`
- 卡片文案：
  `title=需要你继续处理` 或 `需要你接手`
  `message` 优先使用 `waitingQuestion`，否则使用默认提示。
  虚拟屏场景会附加一行：`去主屏处理后，处理完成再返回这里继续。`
- 按钮：
  主按钮：`继续处理` 或 `去主屏处理`
  次按钮：`稍后`
- 主按钮行为：
  如果 `alreadyOnMain == true`，只把面板收起。
  否则走 `requestMoveToMain()`，把真实任务迁移到主屏。
- 次按钮行为：
  把当前 `sessionId` 标记到 `snoozedSessionId`，卡片收起，但气泡仍保留。

### HANDOFF_ACTIVE

- 触发场景：
  会话在 `WAITING_USER`，且任务已迁到主屏。
- 气泡文案：
  `bubbleText=等你`
  `bubbleDetail=主屏处理中`
- 第一阶段：
  展示 NeedUser 卡片。
  `title=主屏接手中`
  `message=等待问题 + 处理完成后在这里提交验证码或简短回复，让后台继续执行。`
  主按钮 `继续处理`
  次按钮 `稍后`
- 第一阶段主按钮行为：
  只做本地确认，不调用 session API。
  具体表现是：
  把 `acknowledgedHandoffSessionId` 设为当前会话。
  初始化 reply draft。
  保持展开。
- 第二阶段：
  展示 `HandoffStripView`，不再显示 NeedUser 卡片。
  `title=处理完成后返回后台执行`
  主按钮 `提交并返回后台`
  次按钮 `取消任务`
  默认回复文案：`已处理完成`
- 第二阶段主按钮行为：
  走 `submitReply()`，调用 `replyToSession(sessionId, replyText)`。
- 第二阶段次按钮行为：
  走 `cancelSession()`，调用 `stopSession(migrate=false)`。

### RECOVERY

- 触发场景：
  `status == FAILED`
  或 `recoveryNeeded == true`
  或 `TaskMigrationPolicy.requiresRecovery(lastMigrationCode) == true`
- 气泡文案：
  `bubbleText=异常`
  `bubbleDetail=需要人工接手`
- 卡片文案：
  `title=需要人工恢复`
  `message` 按场景切换：
  目标任务丢失：`后台任务目标已失效，当前无法恢复到原任务。返回空闲后重新发起。`
  已迁主屏但异常：`任务切换出现异常，当前真实任务仍在主屏。`
  其他：`后台任务状态异常，需要人工确认后再继续。`
  如有 migration code / conflict reason，会追加展示。
- 按钮：
  次按钮固定为 `取消任务`
  主按钮依 recovery route 变化：
  `END_SESSION_TO_IDLE` -> `返回空闲`
  非虚拟屏 -> `继续处理`
  `migratedToMain == true` -> `返回后台执行`
  `alreadyOnMain == true` -> `继续处理`
  其他 -> `去主屏处理`
- 主按钮实际动作：
  `NONE` -> 只收起
  `SURFACE_ON_MAIN` -> `showSessionOnMain()`
  `MIGRATE_TO_MAIN` -> `migrateTaskToMain()`
  `MIGRATE_TO_EXECUTION` -> `migrateTaskToExecution()`
  `END_SESSION_TO_IDLE` -> `stopSession(migrate=false)`
- 备注：
  当前 `derivePrimaryRoute()` 实现不会产出 `SURFACE_ON_MAIN`，它只是保留路由值。

### POST_COMPLETION

- 触发场景：
  `status == COMPLETED`
- 气泡文案分三类：
  回主屏成功：`已完成 / 已回主屏`
  回主屏失败但安全结束：`已完成 / 已安全结束`
  直接清理：`已完成 / 会话已清理`
- 卡片文案：
  `title=任务已完成`
  消息会按 outcome 拼装，必要时追加失败码。
- 按钮：
  主按钮：`查看主屏` 或 `知道了`
  次按钮：`收起`
- 当前实际交互：
  无论点主按钮还是次按钮，都只会调用 `dismissPostCompletion()`。
  也就是说：
  `查看主屏` 当前只是关闭 retained completion UI，不会额外发起导航动作。
- retained 行为：
  只要 session 仍在且 mode 是 `POST_COMPLETION`，就刷新 retained state。
  session 清掉后，UI 还会保留最多 3 秒。
  TTL 过期后自动回到 `IDLE`。

## 本地 retained UI 语义

### `isExpanded`

- 控制面板是否展开。
- `IDLE` 和 `RUNNING` 展开后只影响新任务面板。
- `NEED_USER/HANDOFF_ACTIVE/RECOVERY/POST_COMPLETION` 展开后决定是否显示交互卡片。

### `snoozedSessionId`

- 用于 `NEED_USER`、`HANDOFF_ACTIVE` 第一阶段、`RECOVERY` 之外的提醒收起。
- 命中 snooze 后：
  卡片不再展示。
  气泡保留。
  同一 session 下用户仍可再次点气泡展开。

### `acknowledgedHandoffSessionId`

- 只用于 `HANDOFF_ACTIVE`。
- 表示用户已经确认“我去主屏处理了”，此时 UI 从 NeedUser 卡片切到 HandoffStrip。

### `replyDraftSessionId` / `replyDraft`

- 只用于 `HANDOFF_ACTIVE` 第二阶段。
- 默认文案是 `已处理完成`。
- session 切换时会清空并重置。

### `dismissedPostCompletionSessionId`

- 防止同一个 completed session 的 retained completion 在本地被反复弹回。
- 用户主动 dismiss 后，同 session 的 post-completion 展示不再重现。

### `OverlayPostCompletionRetention`

- TTL 固定为 `3000ms`。
- 只有 `POST_COMPLETION` 会被 retained。
- 有 live session 且已不再是 `POST_COMPLETION` 时，retained state 会立即清空。

### `newTaskDraft`

- 只服务于 `IDLE/RUNNING` 时的新任务面板。
- session 切换时清空。

## 交互路由总表

| UI 操作 | 触发条件 | 实际调用 |
| --- | --- | --- |
| 开始新任务 | `IDLE` 且输入非空 | `OverlayActionDispatcher.submitNewTask()` |
| 去主屏处理 | `NEED_USER` 且不在主屏 | `requestMoveToMain()` |
| 继续处理 | `NEED_USER` 且已在主屏 | 仅收起面板 |
| 确认 handoff | `HANDOFF_ACTIVE` 第一阶段 | 仅切换本地 UI 到 HandoffStrip |
| 提交并返回后台 | `HANDOFF_ACTIVE` 第二阶段 | `submitReply()` |
| Recovery 主动作 | `RECOVERY` | 由 `RecoveryPrimaryRoute` 决定 |
| 取消任务 | `RECOVERY` 或 HandoffStrip 第二阶段 | `cancelSession()` |
| 稍后 | `NEED_USER`、`HANDOFF_ACTIVE` 第一阶段 | 记录 snooze 并收起 |
| 完成态主/次按钮 | `POST_COMPLETION` | 仅 dismiss retained completion |

## 当前 UI 语义上的关键结论

- `RUNNING` 不是“可操作态”，只是“可观察态”。
- `NEED_USER` 的主按钮语义是“把用户带到真实任务”，不是“提交回复”。
- `HANDOFF_ACTIVE` 才是唯一带回复输入框、能恢复自动化的 UI 模式。
- `RECOVERY` 的主按钮语义不是固定文案动作，而是 route-driven。
- `POST_COMPLETION` 当前更像“完成通知”而不是“完成后导航器”；`查看主屏` 只是文案，不带额外跳转。
- `POST_COMPLETION`、`snooze`、`handoff ack` 全部是 controller local retained state，不属于业务状态。
