# State Machine Consistency Audit

## Scope And Constraints

- 本文只做状态机一致性审计与最小落地设计，不改代码。
- 项目仍然是以 `SessionCoordinator` 为中心的垂直单体，不做模块化重构，不把 `SessionCoordinator`、`SessionAutomationEngine`、`SubgoalStateMachine` 合并成一个超级状态机。
- 单 active session 语义不变。
- `DONE / ASK_USER / COMPLETED / cleanup / task migration / execution-display-first` 主语义不推翻。
- 本文中的 owner 表是后续实现阶段的硬约束。后续代码修改必须先满足 owner 表，再谈映射或 helper 收口；不做只有命名变化、没有 owner 收权的整理。

## A. State Inventory

### A0. Vocabulary Owner Table

| 状态词汇类 | 当前表示 | 分类 | 唯一 owner | 允许写入者 | 禁止写入者 |
| --- | --- | --- | --- | --- | --- |
| Canonical session lifecycle | `SessionState.status` in `app/src/main/java/com/example/agenthost/session/SessionModels.kt:54-105` | canonical business state | `SessionCoordinator.activeSession` | `SessionCoordinator` 内部受锁方法与 automation callbacks (`SessionCoordinator.kt:54-58`, `96-260`, `268-917`, `2009-2378`) | `SessionAutomationEngine`, `AgentControlCoordinator`, `Overlay*`, `TaskMigrationPolicy`, `TaskRunReportHooks`, `TaskRunReportAnalyzer`, `SessionAuditReceiver` |
| Canonical session business fields | `waitingQuestion`, `visibleToUser`, `currentVisibleDisplayId`, `migratedToMain`, `lastMigration*`, `recoveryNeeded`, `postCompletion*` in `SessionState` | canonical business state | `SessionCoordinator.activeSession` | `SessionCoordinator` only | 全部非 coordinator 层 |
| Automation/control phase | `SubgoalPhase`, `SubgoalState`, `CompletionVerdict`, `GuardrailState`, `StepResultCategory` in automation 包 | derived control state | `AgentControlCoordinator.states[sessionId]` | `AgentControlCoordinator` (`AgentControlCoordinator.kt:31-35`, `37-186`, `279-363`) | `SessionCoordinator`, `PlanningAdapter`, `Overlay*`, `TaskRunReportHooks`, `TaskMigrationPolicy` |
| Planning snapshot for model | `AgentControlSnapshot.currentPhase/completionState/doneOnly` (`AgentControlSnapshot.kt:12-23`) | derived state | `AgentControlCoordinator` | `AgentControlCoordinator.buildPlanningSnapshot()` (`AgentControlCoordinator.kt:37-70`) | `ModelPlanningAdapter`, `SessionAutomationEngine`, `TaskRunReportHooks`, `Overlay*` |
| Execution display bridge state | `ExecutionDisplayPolicySync.STATE_*` (`ExecutionDisplayPolicySync.kt:10-14`) | derived bridge state | `SessionCoordinator` 决定当前值，`ExecutionDisplayPolicySync` 只负责编码与转发 | `SessionCoordinator.syncExecutionDisplayPolicy*Locked()` (`SessionCoordinator.kt:2106-2199`) | `Overlay*`, `TaskMigrationPolicy`, `TaskRunReportHooks`, platform client callers outside coordinator |
| Migration outcome taxonomy | `lastMigrationCode`, `lastMigrationDirection`, `lastMigrationConflict*`, `TaskValidationResult.code`, `TaskMigrationPolicy` code families | migration / post-completion outcome + result code taxonomy | 运行时 outcome owner 是 `SessionCoordinator`; code-family catalog owner 是 `TaskMigrationPolicy` | `SessionCoordinator` 写 runtime outcome (`919-1774`, `2289-2377`); `TaskMigrationPolicy` 只维护分类集合 (`TaskMigrationPolicy.kt:11-67`) | `Overlay*`, `TaskRunReportHooks`, `TaskRunReportAnalyzer`, `SessionAuditReceiver` 不得自行发明新业务含义 |
| Post-completion outcome | `PostCompletionDisposition`, `PostCompletionState`, `postCompletionResultCode` (`SessionModels.kt:22-34`, `87-89`) | migration / post-completion outcome | `SessionCoordinator` | `SessionCoordinator.onSessionCompleted`, `resolvePostCompletionDisposition`, `normalizeCompletedPostActionState`, `applyCompletionReturnPolicyLocked` (`SessionCoordinator.kt:155-220`, `2279-2377`) | `OverlayStateMapper`, `OverlayController`, `TaskRunReportHooks`, `TaskMigrationPolicy` |
| UI presentation mode | `OverlayMode`, `BubbleTone`, `NewTaskEntryState`, `OverlayUiState` (`OverlayUiState.kt:3-47`) | UI presentation state | `OverlayStateMapper` | `OverlayStateMapper.map*()` (`OverlayStateMapper.kt:10-205`) | `SessionCoordinator`, `OverlayController`, `OverlayEntryService`, `TaskRunReportHooks` |
| UI retained state | `OverlayPostCompletionRetention.retainedState`, `dismissedPostCompletionSessionId`, `snoozedSessionId`, `acknowledgedHandoffSessionId`, drafts, `isExpanded` (`OverlayController.kt:80-107`, `181-445`; `OverlayPostCompletionRetention.kt:11-52`) | controller local retained state | `OverlayController` | `OverlayController` on main thread only | `OverlayStateMapper`, `SessionCoordinator`, `OverlayEntryService`, `TaskRunReportHooks` |
| Session listener diff snapshot | `SessionStateListenerSnapshot` (`SessionStateListenerSnapshot.kt:3-50`) | derived state | `SessionCoordinator` | `SessionStateListenerSnapshot.from()` + `SessionCoordinator.notifySessionStateListenersIfNeeded()` (`2517-2529`) | `OverlayEntryService`, `OverlayController`, tests |
| Session API result code | `SessionResult.code` (`SessionModels.kt:114-118`) | result code | `SessionCoordinator` | `okResult()/failResult()` and public API methods (`2216-2231`, public methods) | automation/control/overlay/report layers |
| Automation engine result code | `EngineResult.code` (`SessionAutomationEngine.kt:53-57`) | result code | `SessionAutomationEngine` | `startAutomation`, `stopAutomation`, `replyToSession` (`105-188`) | `SessionCoordinator`, `Overlay*`, report layers |
| Runtime lifecycle events | `event=` strings emitted by `SessionCoordinator` / `SessionAutomationEngine` | event / trigger | emitter-specific | `SessionCoordinator.logInfo/logError`, `SessionAutomationEngine.log` | UI / report / policy layers不得把 event 当作稳定状态 |
| Report lifecycle and diagnosis | `TaskRunLifecycleEvent`, `resultTag`, `failureLayer`, `terminalDisposition`, `likelyProblemLayer` (`TaskRunReportModels.kt:114-171`, `TaskRunReportAnalyzer.kt:3-213`, `TaskRunReportHooks.kt:217-775`) | diagnostic | 原始运行采集 owner 是 `TaskRunReportHooks`; 聚合结论 owner 是 `TaskRunReportAnalyzer` | `TaskRunReportHooks` 写 mutable run/step，`TaskRunReportAnalyzer` 归因，`TaskRunReportRenderer` 只渲染 | `SessionCoordinator`, `Overlay*`, `TaskMigrationPolicy` 不得依赖这些诊断词汇反驱业务 |

### A1. File-By-File Inventory

#### `app/src/main/java/com/example/agenthost/session/SessionModels.kt`

- `SessionState` (`54-92`)
  - 类型：canonical business state 容器。
  - owner：`SessionCoordinator.activeSession`。
  - writers：仅 `SessionCoordinator` 通过 `copy(...)` 写入。
  - readers：session/overlay/report/debug 全链路。
  - 跨线程：是。coordinator 在锁内写，overlay listener、automation snapshot、report hook 在别的线程读快照。
  - 跨层重复表达：是。`status`、`migratedToMain`、`recoveryNeeded`、`postCompletion*` 被 UI、report、policy 多层再次解释。
- `SessionStatus` (`94-105`)
  - 类型：canonical lifecycle enum。
  - 写入点：`SessionCoordinator` 各生命周期入口。
  - 读取点：automation engine 只接受 `RUNNING` (`SessionAutomationEngine.kt:261-275`)，overlay mapper (`OverlayStateMapper.kt:11-40`)，report hook (`TaskRunReportHooks.kt:230-242`)，debug export (`SessionCoordinator.kt:2233-2277`)。
  - 备注：`IDLE` 在主代码未被使用；“无活动会话”实际由 `activeSession == null` 表达。
- `CompletionReturnPolicy`, `PostCompletionDisposition`, `PostCompletionState`
  - 类型：outcome policy / outcome state，不是 session lifecycle。
  - 写入点：`SessionCoordinator.resolvePostCompletionDisposition` / `applyCompletionReturnPolicyLocked` (`2279-2377`)。
  - 读取点：overlay completed UI (`OverlayStateMapper.kt:65-115`), listener snapshot (`SessionStateListenerSnapshot.kt:21-47`), state export (`2268-2270`)。
- `SessionResult` (`114-118`)
  - 类型：API result code。
  - owner：`SessionCoordinator`。
  - 风险：`code` 当前同时承载 event outcome、platform/migration error、业务拒绝。

#### `app/src/main/java/com/example/agenthost/session/SessionCoordinator.kt`

- `activeSession` (`54-58`)
  - 类型：唯一 canonical session authority。
  - 写入点：几乎所有状态转移都通过 `activeSession = state.copy(...)` 完成。
  - 读取点：automation snapshot、overlay gateway、listener、logging、state export。
  - 跨线程：是；用 `lock` 序列化。
- automation callback -> session state 转移 (`96-260`)
  - `onSessionWaitingUser`：`RUNNING -> WAITING_USER`，随后可能同步 `STATE_USER_HANDOFF` 并触发迁移到主屏 (`110-149`)。
  - `onSessionCompleted`：先把 `status=COMPLETED`、`postCompletionState=PENDING` 写入，再同步 bridge state，再跑 completion-return，再 finalize report，再 cleanup (`169-220`)。
  - `onSessionFailed`：把 `status=FAILED` 写入并 finalize report (`222-260`)。
- session public API
  - `startSession`：创建 `STARTING -> READY` (`268-461`)。
  - `startAutomation`：`READY -> RUNNING` (`463-526`)。
  - `replyToSession`：`WAITING_USER -> RUNNING`，如果 handoff 后回迁失败则仍留在 `WAITING_USER` 并打 `recoveryNeeded=true` (`528-655`)。
  - `stopSessionLocked`：`* -> STOPPING -> MIGRATED/CLOSED`，然后 cleanup (`810-917`)。
- migration runtime outcome
  - `migrateTaskToMainLocked` (`919-1192`) 和 `migrateTaskBackToExecutionLocked` (`1194-1387`) 写 `migratedToMain / lastMigration* / recoveryNeeded / visibleToUser / currentVisibleDisplayId / lastKnownTask*`。
  - `buildValidationFailureState` (`1577-1664`) 统一构造迁移失败 outcome，但 `isMigrationPrecheckFailureCode` 又单独维护一套 code family (`1758-1772`)。
- completion / cleanup outcome
  - `resolvePostCompletionDisposition`, `normalizeCompletedPostActionState`, `applyCompletionReturnPolicyLocked` (`2279-2377`) 把“任务完成”与“完成后回主屏/清理”拆成两步，但 `status` 已先置为 `COMPLETED`。
  - `cleanupCompletedSessionLocked` 和 `cleanupLocked` (`2064-2104`) 会清空 `activeSession`；overlay 只能靠 retained UI 继续展示完成态。
- bridge / export / duplicate mapping
  - `syncExecutionDisplayPolicy*Locked` (`2106-2199`) 向平台桥同步 `EXECUTION / USER_HANDOFF / COMPLETED`。
  - `stateData` (`2233-2277`) 把 canonical state 和大量 result/diagnostic 一起导出。
  - `buildLogLine` 和 `overlayUiStateLabel` (`2459-2549`) 在 coordinator 内重算一套 overlay state label，和 `OverlayStateMapper` 语义重复。

#### `app/src/main/java/com/example/agenthost/session/SessionStateListenerSnapshot.kt`

- `SessionStateListenerSnapshot` (`3-50`)
  - 类型：derived listener diff snapshot。
  - owner：`SessionCoordinator`。
  - writers：`from(state)` 生成，`notifySessionStateListenersIfNeeded()` 使用。
  - readers：`OverlayEntryService.renderOverlayIfChanged()` (`OverlayEntryService.kt:239-245`)，tests。
  - 跨线程：是。coordinator 写快照，overlay main thread 读快照。
  - 重复表达：是。选择性镜像 `SessionState` 字段，但故意不包含 `automationStep`。

#### `app/src/main/java/com/example/agenthost/automation/SessionAutomationEngine.kt`

- `loops` / `LoopControl` (`13-16`, `103-188`)
  - 类型：engine local retained state。
  - owner：`SessionAutomationEngine`。
  - writers/readers：engine 自己。
  - 跨线程：是，`ConcurrentHashMap` + worker threads。
- `AutomationSessionSnapshot` (`59-71`)
  - 类型：canonical state 的只读投影。
  - owner：`SessionCoordinator` 提供，engine 不拥有业务语义。
  - 读取点：run loop (`197-376`)。
- `EngineResult.code` (`53-57`, `105-188`)
  - 类型：automation engine result code。
- 主循环转移 (`197-420` 及后续)
  - 读取 `snapshot.status == RUNNING` 作为唯一 automation 启动条件 (`261-275`)。
  - 通过 `callbacks.onSessionWaitingUser / onSessionCompleted / onSessionFailed` 触发 session canonical 转移，不直接写 `SessionState`。
  - 同时把 `TaskRunReportHooks` 当作 side channel 记录 step、plan、control、platform 诊断。

#### `app/src/main/java/com/example/agenthost/automation/SubgoalStateMachine.kt`

- `SubgoalPhase` / `SubgoalState` (`3-19`)
  - 类型：automation/control phase，不是 session state。
  - owner：`AgentControlCoordinator`。
- 转移规则
  - `initialState()` 根据命令解析顺序目标 (`21-49`)。
  - `advancePhase()` 根据 action + observationChanged 转移 (`51-84`)。
  - `finalizePhase()` 根据 `CompletionVerdict` 把相位锁进 `DONE_ONLY` (`86-95`)。
  - `allowedActions()` / `blockedActions()` 输出 planning gate (`111-143`)。

#### `app/src/main/java/com/example/agenthost/automation/AgentControlCoordinator.kt`

- `states: ConcurrentHashMap<String, GuardrailState>` (`25-31`)
  - 类型：automation/control authority。
  - owner：`AgentControlCoordinator`。
  - 跨线程：是。
- `buildPlanningSnapshot()` (`37-70`)
  - 输出 `AgentControlSnapshot.currentPhase/completionState/doneOnly` 给 planner 和 report。
  - 这些字段只是 control snapshot，不是 canonical runtime state。
- `resolveAction()` (`72-95`)
  - 预执行 guardrail。
- `finalizeStep()` (`97-186`)
  - 统一计算 `completionVerdict`, `phaseAfter`, `resultSummary`, `shouldComplete`, `shouldAskUser`。
  - 这里产生的 `shouldComplete`/`shouldAskUser` 是 control decision，不应被提升成持久业务状态。
- `buildResultSummary()` / `updateState()` (`188-363`)
  - 写 `StepResultCategory`, `completionVerdict`, recent step records。

#### `app/src/main/java/com/example/agenthost/automation/GuardrailState.kt`

- `StepResultCategory`, `StepResultSummary`, `GuardrailState` (`11-74`)
  - 类型：control result / control memory，不是 session status，也不是 report diagnosis。
  - writers：`AgentControlCoordinator` only。
  - readers：planner snapshot, report hooks.
  - 重复表达：`COMPLETION_SATISFIED`, `LOW_YIELD_HANDOFF`, `PLATFORM_FAILED` 与 session `COMPLETED/WAITING_USER/FAILED` 有强耦合但不是同一层。

#### `app/src/main/java/com/example/agenthost/automation/CompletionEvaluator.kt`

- `CompletionVerdict` (`3-7`)
  - 类型：control terminal verdict，不是 canonical session terminal。
  - writers：`DefaultCompletionEvaluator` via `AgentControlCoordinator.finalizeStep()` (`97-186`)。
  - 读取：`SubgoalStateMachine.finalizePhase()`, report hooks step diagnostics。
  - 重复表达：`SATISFIED/UNSURE` 与 `DONE`, `COMPLETED`, `postCompletionState` 非同一概念。

#### `app/src/main/java/com/example/agenthost/automation/AgentControlSnapshot.kt`

- `currentPhase`, `completionState`, `doneOnly` (`12-23`)
  - 类型：planner-facing derived state。
  - owner：`AgentControlCoordinator`。
  - readers：`ModelPlanningAdapter.buildUserPrompt()` (`ModelPlanningAdapter.kt:181-190`), `TaskRunReportHooks.onModelInput()` (`291-320`)。
  - 禁止写入者：planner/model/report 不应回写这些字段。

#### `app/src/main/java/com/example/agenthost/migration/TaskMigrationPolicy.kt`

- `TaskMigrationPolicy` (`11-67`)
  - 类型：result code taxonomy，不是 runtime state machine。
  - owner：该对象只拥有 code family catalog。
  - readers：`SessionCoordinator.applyCompletionReturnPolicyLocked()` (`2352`), `OverlayStateMapper` (`29-33`), `OverlayRecoverySupport` (`23-43`), `overlayUiStateLabel()` (`2538-2541`)。
  - 问题：`shouldCompletionFallbackToCleanup()` 当前未被生产代码使用；`isMigrationPrecheckFailureCode()` 在 coordinator 内又维护了一套并行分类。
- `TaskValidationResult` (`3-9`)
  - 类型：migration operation result code + lookup payload。
  - owner：`SessionCoordinator.validateBoundTask()` (`1395-1447`)。

#### `app/src/main/java/com/example/agenthost/migration/TaskMigrationController.kt`

- `TaskMigrationTarget` (`3-8`)
  - 类型：migration target snapshot。
  - owner：`TaskMigrationController` 查询返回，`SessionCoordinator` 消费并镜像到 `SessionState.lastKnownTask*`。
- `TaskMigrationController` (`10-16`)
  - 类型：platform boundary，不拥有业务状态。

#### `app/src/main/java/com/example/agenthost/session/ExecutionDisplayPolicySync.kt`

- `STATE_EXECUTION / STATE_USER_HANDOFF / STATE_COMPLETED` (`10-14`)
  - 类型：bridge state strings。
  - owner：当前值的选择权在 `SessionCoordinator`，`ExecutionDisplayPolicySync` 只负责编码与调用 gateway。
  - writers：`SessionCoordinator.syncExecutionDisplayPolicyStateLocked()`。
  - readers：platform client / tests。
  - 重复表达：分别对应 `RUNNING`、`WAITING_USER + migratedToMain`、`COMPLETED` 的部分语义，但不是 canonical state。

#### `app/src/main/java/com/example/agenthost/overlay/OverlayUiState.kt`

- `OverlayMode`, `BubbleTone`, `NewTaskEntryState`, `OverlayUiState` (`3-47`)
  - 类型：UI presentation state。
  - owner：`OverlayStateMapper`。
  - readers：`OverlayController.render()`。
  - 禁止写入者：coordinator/report/policy 不应把这些词汇写回业务层。

#### `app/src/main/java/com/example/agenthost/overlay/OverlayStateMapper.kt`

- `map(session)` (`10-40`)
  - 类型：canonical state -> presentation state mapper。
  - 读取：`status`, `recoveryNeeded`, `lastMigrationCode`, `migratedToMain`, `waitingQuestion`, `postCompletion*`, `displayMode`。
  - 重复表达：与 `SessionCoordinator.overlayUiStateLabel()` 同时拥有“WAITING_USER/HANDOFF/RECOVERY/POST_COMPLETION”的判定规则。
- `mapCompleted()` (`65-115`)
  - 把 `postCompletionDisposition`, `postCompletionState`, `postCompletionResultCode` 与 `lastMigrationCode` 拼合成 UI 终态。
  - 这里的“已安全结束”“已回主屏”是 presentation 语义，不应回灌 business state。
- `mapRecovery()` (`166-205`)
  - 依赖 `OverlayRecoverySupport.shouldFallbackToIdle()` 再解释迁移码。

#### `app/src/main/java/com/example/agenthost/overlay/OverlayController.kt`

- local retained state (`80-107`)
  - `isExpanded`, `postCompletionRetention`, `dismissedPostCompletionSessionId`, `snoozedSessionId`, `acknowledgedHandoffSessionId`, drafts 等。
  - 类型：controller local retained state。
  - owner：`OverlayController`。
  - 跨线程：否，主线程 `Handler`。
- `render()` / `resolvePresentedState()` (`181-266`)
  - 先拿 mapper 输出，再叠加 retained completion 和 dismiss/snooze state。
  - 证明 overlay 存在“无 session 但仍显示 POST_COMPLETION”的 retained UI。
- `syncLocalUiState()` (`274-326`)
  - 明确了 reply draft、handoff ack、snooze 都是 controller 本地，不是业务层。
- action handlers (`347-445`)
  - 根据 `OverlayMode` 决定是否 dispatch migration/recovery/reply/cancel。
  - 这些 handler 不应自己推导业务状态，只能发控制动作。

#### `app/src/main/java/com/example/agenthost/overlay/OverlayEntryService.kt`

- `lastRenderedSessionSnapshot` (`35-39`, `239-245`)
  - 类型：listener diff cache。
  - owner：`OverlayEntryService`。
  - 作用：防止 UI 重复 render，不承载业务语义。
- `worker` + `mainHandler` (`29-30`)
  - 线程边界：overlay dispatch 在 worker，render 在 main。
- `startWithAutomation()` (`181-209`)
  - 会触发 `TaskRunReportHooks.prepareNextSessionSource("OVERLAY")`，说明 report source 也是一套 diagnostic vocabulary。

#### `app/src/main/java/com/example/agenthost/overlay/OverlayRecoverySupport.kt`

- `RecoveryPrimaryRoute` (`8-13`)
  - 类型：UI/controller local recovery route，不是 canonical state。
  - owner：`OverlayRecoverySupport`。
  - readers：`OverlayStateMapper.mapRecovery()`, `OverlayActionDispatcher.requestRecoveryPrimary()`, `OverlayStartGuard.start()`。
  - 重复表达：再次解释 `lastMigrationCode`, `migratedToMain`, `visibleToUser/surfaceDisplayId`。

#### `app/src/main/java/com/example/agenthost/report/TaskRunReportModels.kt`

- `TaskRunOverview.finalSessionState/finalOutcome` (`13-28`)
  - 类型：diagnostic snapshot。
  - owner：`TaskRunReportHooks.finalizeRun()` 提供原始值。
- `TaskRunStepReport.failureLayer/failureCode` (`30-56`)
  - 类型：diagnostic，不是 runtime state。
- `ModelInputSummary.currentPhase/completionState/doneOnly` (`72-88`)
  - 类型：automation snapshot 镜像。
- `TaskRunLifecycleEvent.status/migratedToMain/recoveryNeeded/lastMigration*` (`122-134`)
  - 类型：runtime snapshot copied into diagnostics。
- `TaskRunDiagnostics/TaskRunConclusion` (`136-171`)
  - 类型：derived diagnosis。

#### `app/src/main/java/com/example/agenthost/report/TaskRunReportAnalyzer.kt`

- `classifyResultTag()` (`22-65`)
  - 把 `finalOutcome`, `failureLayer`, `finalSessionState`, `parsedActionType` 重新拼成 `resultTag`。
  - `WAITING_USER_STALLED`、`COMPLETED_STATE_MISMATCH` 属于诊断标签，不是业务终态。
- `determineLikelyProblemLayer()` (`186-205`)
  - 根据 lifecycle flags 和 final state 推断 problem layer；存在从诊断反推业务语义的倾向。

#### `app/src/main/java/com/example/agenthost/report/TaskRunReportRenderer.kt`

- `renderMarkdown()` / `renderJson()` (`13-260+`)
  - 类型：renderer，仅 presentation。
  - 不应再新增业务判断；当前只是展示 analyzer/hook 的结果。

#### `app/src/debug/java/com/example/agenthost/debug/TaskRunReportHooks.kt`

- `MutableRun`, `MutableStep` (`50-140`)
  - 类型：debug-only runtime diagnostic buffers。
  - owner：`TaskRunReportHooks`。
  - 跨线程：是；用 `lock`。
- `onSessionEvent()` (`217-244`)
  - 把 `SessionState.status/migratedToMain/recoveryNeeded/lastMigration*` 镜像到 lifecycle event。
- `onModelInput()/onControlActionResolved()/onControlStepEvaluated()` (`291-416`)
  - 镜像 `currentPhase`, `doneOnly`, `completionVerdict`, guardrail info。
- `onPlatformResult()/onSyntheticActionResult()` (`419-496`)
  - 把 platform 和 synthetic action 混到统一 step 记录里。
- `finalizeRun()/buildReportLocked()` (`499-611`)
  - 生成 `report_finalize` synthetic event。
  - 用 lifecycle heuristics 计算 `migratedToMainOccurred`, `migrateBackOccurred`, `recoveryNeededSeen`。
  - 这些值是 debug report derived state，不是 runtime canonical state。
- `mapFailureLayer()/mapModelTerminalDisposition()` (`670-699`)
  - 维护一套 code -> diagnostic taxonomy；这是诊断 owner，不应被业务层复用。

### A2. Cross-Thread Access Summary

| 对象 | 线程模型 |
| --- | --- |
| `SessionCoordinator.activeSession` | 受 `lock` 保护，automation callback、overlay API、public API 都经 `synchronized(lock)` |
| `SessionAutomationEngine.loops` | worker threads + `ConcurrentHashMap` |
| `AgentControlCoordinator.states` | worker threads + `ConcurrentHashMap` |
| `AutomationHistory` | 自有 `lock` |
| `TaskRunReportHooks.runs` | debug threads + `lock` |
| `OverlayController` retained state | main thread + `Handler` |
| `OverlayEntryService.lastRenderedSessionSnapshot` | main thread render path |

## B. Inconsistency Report

### P0

#### P0-1. Overlay business mapping duplicated in two owners

- 现象
  - `OverlayStateMapper.map()` 用 `status/recoveryNeeded/lastMigrationCode/migratedToMain/postCompletion*` 计算 `OverlayMode` (`OverlayStateMapper.kt:11-40`)。
  - `SessionCoordinator.overlayUiStateLabel()` 又用另一套逻辑计算 `"IDLE/RUNNING/NEED_USER/HANDOFF_ACTIVE/RECOVERY/POST_COMPLETION"` (`SessionCoordinator.kt:2531-2549`)。
- 根因
  - coordinator 为了日志字段 `overlayUiState` 引入了 presentation 级判断，突破了 owner 边界。
- 影响链路
  - runtime log -> debug receiver -> report/debug 排障 -> overlay 真正 UI 显示，可能出现“日志说是 RECOVERY，UI 说 POST_COMPLETION”之类漂移。
- 最小修法
  - 让 overlay presentation mode 只有一个 owner：`OverlayStateMapper`。
  - `SessionCoordinator` 记录日志时只引用 shared derived mapper，或者直接记录 canonical state + outcome，不再本地重算。
- 风险点
  - 现有 log parser 依赖 `overlayUiState=` 字段。
- 回滚点
  - 保留旧日志字段名，但值改为来自共享 mapper 的输出；必要时保留 compatibility label helper 一版。

#### P0-2. Completion terminal semantics 被多层拆开但没有统一契约

- 现象
  - `onSessionCompleted()` 先写 `status=COMPLETED` 且 `postCompletionState=PENDING` (`SessionCoordinator.kt:169-187`)。
  - 然后 `applyCompletionReturnPolicyLocked()` 再继续迁移/清理，并把 `postCompletionState` 正规化成 `SUCCEEDED/FALLBACK_CLEANUP` (`2312-2377`)。
  - 最后 `cleanupCompletedSessionLocked()` 立刻把 `activeSession = null` (`2064-2087`)。
  - overlay 又用 `OverlayPostCompletionRetention` 保留一个“已完成” presentation state (`OverlayPostCompletionRetention.kt:14-26`, `OverlayController.kt:250-266`)。
- 根因
  - “业务完成”“完成后 side effect”“资源清理”“UI 保留展示”是四层语义，但没有被明确拆开。
- 影响链路
  - session status / postCompletion / overlay retained completion / report finalState 之间没有统一 terminal contract。
- 最小修法
  - 明确：
    - `SessionStatus.COMPLETED` 只表示业务已完成。
    - `PostCompletionDisposition/PostCompletionState` 只表示完成后的 side effect outcome。
    - cleanup 只表示 coordinator/resource teardown。
    - overlay retained completion 只表示 UI 本地保留展示。
- 风险点
  - 现有测试和日志可能默认“完成后立即空闲”。
- 回滚点
  - 保留旧的 `COMPLETION_CLEANUP_BEGIN/END` 事件与 result code，不改变外部可观测码值。

#### P0-3. Migration semantics 同时由 runtime fields、policy catalog、局部 helper 定义

- 现象
  - runtime outcome 由 `SessionCoordinator` 写 `lastMigrationCode/Direction/recoveryNeeded/migratedToMain` (`919-1774`)。
  - `TaskMigrationPolicy` 又定义 `requiresRecovery/isCompletionReturnSuccess/shouldFallbackToIdle` (`TaskMigrationPolicy.kt:38-52`)。
  - coordinator 内还保留一套 `isMigrationPrecheckFailureCode()` (`1758-1772`)。
- 根因
  - 没有一个唯一的 migration outcome 目录表。
- 影响链路
  - `replyToSession()` 的失败返回码、overlay recovery route、completion fallback、report failureLayer 都可能对同一 code family 给出不同解释。
- 最小修法
  - 保留 `SessionCoordinator` 作为 runtime outcome owner。
  - 把 migration code family catalog 收口到 `TaskMigrationPolicy` 一处，coordinator/overlay/report 只能调用 catalog，不再各自维护 `setOf(...)`。
- 风险点
  - 某些历史 code 可能未被 catalog 覆盖。
- 回滚点
  - 先保留旧 helper，改为委托 catalog；出现兼容问题时只回退 helper，不动 runtime fields。

#### P0-4. Report 层在推断业务终态，而不是消费 runtime canonical snapshot

- 现象
  - `TaskRunReportHooks.buildReportLocked()` 通过 lifecycle event heuristics 计算 `migratedToMainOccurred/recoveryNeededSeen` (`596-604`)。
  - `TaskRunReportAnalyzer.classifyResultTag()` 和 `determineLikelyProblemLayer()` 用 `finalOutcome/failureLayer/finalSessionState/parsedActionType` 重新推断业务结论 (`22-65`, `186-205`)。
- 根因
  - report pipeline 没有一份“runtime canonical snapshot -> report fields”的单向映射表。
- 影响链路
  - `COMPLETED_STATE_MISMATCH`, `WAITING_USER_STALLED`, `MIGRATION_FAILED` 等标签可能和真实 runtime final contract 漂移。
- 最小修法
  - 报告层只消费：
    - canonical final session snapshot
    - event timeline
    - result code taxonomy
  - `resultTag/failureLayer/likelyProblemLayer` 明确为 diagnostic only，不反向定义业务状态。
- 风险点
  - 既有报告分类名被使用在脚本/运营排障里。
- 回滚点
  - 先保留现有 tag 字面值，后端逻辑改为基于 canonical mapping 生成。

### P1

#### P1-1. Action/event/result/status 在 control path 里交叉复用

- 现象
  - `DONE`/`ASK_USER` 既是 `CanonicalActionType`，又会变成 synthetic code (`SessionAutomationEngine.kt` 中 `syntheticCode = "DONE"/"ASK_USER"`)，最终再触发 `SessionStatus.COMPLETED/WAITING_USER`。
  - `CompletionVerdict` 与 `StepResultCategory.COMPLETION_SATISFIED/SYNTHETIC_DONE` 又是一层。
- 根因
  - control 层词汇和 session 词汇没有被显式分层。
- 最小修法
  - 文档和后续实现严格区分 action / control verdict / session state / API result code。

#### P1-2. `ExecutionDisplayPolicySync.STATE_*` 容易被误用为业务状态

- 现象
  - bridge state 与 session lifecycle 有明显对应关系，但语义层次不同。
- 根因
  - 用裸字符串表达 bridge state。
- 最小修法
  - 后续实现中把 bridge state 视为 derived bridge state，只允许 coordinator 写入。

#### P1-3. Listener diff snapshot 语义隐式

- 现象
  - `SessionStateListenerSnapshot` 故意忽略 `automationStep`，只监听一部分字段 (`SessionStateListenerSnapshot.kt:3-50`)。
- 风险
  - 后续实现者可能把它误当成 canonical snapshot，或者误加字段导致 overlay 抖动。
- 最小修法
  - 明确它只是 UI/render diff snapshot，不是 canonical state。

#### P1-4. Recovery route 是 UI/control route，不是业务状态

- 现象
  - `RecoveryPrimaryRoute` 再次解释 `lastMigrationCode`、`migratedToMain`、`visibleToUser` (`OverlayRecoverySupport.kt:16-43`)。
- 风险
  - 容易把“该怎么处理”误当成“当前是什么状态”。
- 最小修法
  - 把 route 明确标为 controller local derived route，只消费 canonical state，不回写 business fields。

### P2

#### P2-1. `SessionStatus.IDLE` 与 `activeSession == null` 双重空闲语义

- 现象
  - `SessionStatus` 定义了 `IDLE`，但主代码不使用；空闲由 `activeSession == null` 表达。
- 最小修法
  - 后续实现把 `IDLE` 明确为不用作 live active session 的状态值；不必在第一阶段删除。

#### P2-2. `currentExecutionDisplayId` 与 `executionDisplayId` 目前没有清晰分工

- 现象
  - 代码中只看到初始化与导出/日志读取，未见业务转移中实际变更。
- 最小修法
  - 先在文档中归类为 session topology field，后续若无真实变更场景可降级为 derived mirror 或删除。

#### P2-3. `pendingReturnToVirtualDisplay` 语义弱且几乎总是被清零

- 现象
  - 生产代码只有读取一次 (`replyToSession()` 的 `584-587`)，其余几乎都在清零。
- 最小修法
  - Phase 1 不删除；Phase 2 评估是否仍有真实 owner。如果没有，就退化为临时控制 flag 或移除。

## C. Target State Model

### C0. Unified Model

1. Canonical session state
   - 只用 `SessionStatus` 表达业务生命周期。
   - owner: `SessionCoordinator.activeSession`。
   - live active session 允许值：`STARTING`, `READY`, `RUNNING`, `WAITING_USER`, `STOPPING`, `FAILED`, `COMPLETED`。
   - terminal exported states：`MIGRATED`, `CLOSED` 只在 stop 流程瞬时产出，然后 cleanup。
   - `IDLE` 不作为 live active session 状态；“空闲”由 `activeSession == null` 表达。

2. Automation/control phase
   - `SubgoalPhase`, `CompletionVerdict`, `StepResultCategory`, `GuardrailDecisionType`, `GuardrailState`。
   - owner: `AgentControlCoordinator`。
   - 只能驱动 canonical transition decision，不能直接写 canonical state。

3. Migration / post-completion outcome
   - `migratedToMain`, `lastMigration*`, `recoveryNeeded`, `postCompletionDisposition`, `postCompletionState`, `postCompletionResultCode`。
   - owner: `SessionCoordinator`。
   - 这些字段描述 side effect/outcome，不是 session lifecycle。

4. UI presentation mode
   - `OverlayMode`, `BubbleTone`, `NewTaskEntryState`, `OverlayUiState`。
   - owner: `OverlayStateMapper`。
   - 只能由 canonical state + outcome state 派生。

5. Event / trigger
   - `start_session_begin`, `session_waiting_user`, `session_completed`, `task_migration_begin`, `report_finalize` 等。
   - owner: 各自 emitter。
   - 事件只能驱动转移，不充当持久状态。

6. Result code / diagnostics
   - `SessionResult.code`, `EngineResult.code`, model/platform/migration codes, `resultTag`, `failureLayer`, `terminalDisposition`。
   - owner: 各自 API/diagnostic 层。
   - 只能解释结果，不定义 canonical state。

### C1. Mapping Rules

| 词汇 | 应映射到哪一层 |
| --- | --- |
| `SessionStatus` | canonical session state |
| `SubgoalPhase` | automation/control phase |
| `CompletionVerdict` | automation/control phase |
| `StepResultCategory` | automation/control result summary |
| `lastMigrationCode/Direction/conflict*` | migration outcome/result code |
| `recoveryNeeded` | canonical outcome flag，由 coordinator 写 |
| `PostCompletionDisposition/PostCompletionState/postCompletionResultCode` | post-completion outcome |
| `ExecutionDisplayPolicySync.STATE_*` | bridge state |
| `OverlayMode/BubbleTone/NewTaskEntryState` | UI presentation state |
| `dismissed/snoozed/retained/draft/isExpanded` | controller local retained state |
| `SessionResult.code` / `EngineResult.code` | API result code |
| `resultTag/failureLayer/terminalDisposition` | diagnostic |

### C2. Transition Matrix

说明：下表的 `terminal?` 指 canonical session lifecycle 是否已到达不再自动前进的状态；`recoverable?` 指用户或 controller 是否还能显式恢复或结束。

| Current state | Event | Next state | Side effects | terminal? | recoverable? | user-visible? |
| --- | --- | --- | --- | --- | --- | --- |
| `null` | `START_SESSION` | `STARTING` | 创建 runtime，注册 execution display policy，初始化 `SessionState` | 否 | 否 | 否 |
| `STARTING` | `SESSION_ATTACH_OK` | `READY` | 记录 root/task/display，bootstrap execution display | 否 | 否 | 通常否 |
| `READY` | `START_AUTOMATION` | `RUNNING` | 启动 automation loop，bridge state 置 `EXECUTION` | 否 | 否 | `RUNNING` UI 可见 |
| `RUNNING` | `AUTOMATION_ASK_USER` 且无需迁移 | `WAITING_USER` | 写 `waitingQuestion`，停止 automation 自动推进 | 否 | 是 | 是 |
| `RUNNING` | `AUTOMATION_ASK_USER` 且迁移成功 | `WAITING_USER` | 写 `waitingQuestion`，bridge state 置 `USER_HANDOFF`，`migratedToMain=true` | 否 | 是 | 是 |
| `RUNNING` | `AUTOMATION_ASK_USER` 且迁移失败/冲突 | `WAITING_USER` | `recoveryNeeded=true`，写 `lastMigration*` | 否 | 是 | 是 |
| `WAITING_USER` | `USER_REPLY_ACCEPTED` 且回迁成功或无需回迁 | `RUNNING` | 清 `waitingQuestion`，必要时 bridge state 置 `EXECUTION` | 否 | 否 | 是 |
| `WAITING_USER` | `USER_REPLY_ACCEPTED` 但回迁失败 | `WAITING_USER` | 保留问题，`recoveryNeeded=true`，返回 `TASK_MIGRATION_PRECHECK_FAILED` 或迁移失败码 | 否 | 是 | 是 |
| `RUNNING` | `AUTOMATION_DONE` | `COMPLETED` | `postCompletionState=PENDING`，bridge state 置 `COMPLETED`，再执行 post-completion outcome | 是 | 否 | 是 |
| `COMPLETED` | `POST_COMPLETION_RETURN_SUCCESS` | `COMPLETED` | `postCompletionDisposition=RETURN_TASK_TO_MAIN`，`postCompletionState=SUCCEEDED`，`postCompletionResultCode=TASK_MIGRATION_OK/ALREADY_ON_MAIN` | 是 | 否 | 是 |
| `COMPLETED` | `POST_COMPLETION_RETURN_FAILED_OR_SKIP` | `COMPLETED` | `postCompletionState=FALLBACK_CLEANUP` 或 `CLEANUP_IMMEDIATELY`，随后 cleanup | 是 | 否 | 是 |
| `RUNNING` / `WAITING_USER` | `PLAN_FAILURE` / `PLATFORM_FAILURE` / `AUTOMATION_CRASH` | `FAILED` | 清 `waitingQuestion`，写 `lastError`，finalize report | 是 | 是 | 是 |
| `RUNNING` / `WAITING_USER` / `FAILED` / `COMPLETED` | `STOP_SESSION(migrate=false)` | `STOPPING -> CLOSED -> null` | 停 automation，关闭 task，clear bridge，cleanup | 是 | 否 | 结束后否 |
| `RUNNING` / `WAITING_USER` / `FAILED` / `COMPLETED` | `STOP_SESSION(migrate=true)` | `STOPPING -> MIGRATED -> null` | 停 automation，迁移任务到主屏，clear bridge，cleanup | 是 | 否 | 结束后真实任务可见 |

### C3. Canonical To UI Mapping Contract

| Canonical input | Overlay output |
| --- | --- |
| `activeSession == null` 或 `status in {CLOSED, MIGRATED}` | `OverlayMode.IDLE` |
| `status == RUNNING/READY/STARTING` 且无 recovery | `OverlayMode.RUNNING` |
| `status == WAITING_USER` 且 `migratedToMain == false` | `OverlayMode.NEED_USER` |
| `status == WAITING_USER` 且 `migratedToMain == true` | `OverlayMode.HANDOFF_ACTIVE` |
| `status == FAILED` 或 `recoveryNeeded == true` 或 `TaskMigrationPolicy.requiresRecovery(lastMigrationCode)` | `OverlayMode.RECOVERY` |
| `status == COMPLETED` | `OverlayMode.POST_COMPLETION` |

补充约束：

- `OverlayController` 可以在 `activeSession == null` 时临时保留 `POST_COMPLETION` 展示，但这仍是 controller local retained state，不是 canonical completed session。
- `OverlayRecoverySupport.RecoveryPrimaryRoute` 只决定 UI 动作路由，不得进入 canonical state。

## D. Refactor Plan

### Phase 1: Owner 收权与类型梳理，不改产品行为

- 修改文件清单
  - `app/src/main/java/com/example/agenthost/session/SessionModels.kt`
  - `app/src/main/java/com/example/agenthost/session/SessionCoordinator.kt`
  - `app/src/main/java/com/example/agenthost/session/SessionStateListenerSnapshot.kt`
  - `app/src/main/java/com/example/agenthost/session/ExecutionDisplayPolicySync.kt`
  - `app/src/main/java/com/example/agenthost/automation/SubgoalStateMachine.kt`
  - `app/src/main/java/com/example/agenthost/automation/AgentControlCoordinator.kt`
  - `app/src/main/java/com/example/agenthost/automation/GuardrailState.kt`
  - `app/src/main/java/com/example/agenthost/automation/AgentControlSnapshot.kt`
  - `app/src/main/java/com/example/agenthost/automation/CompletionEvaluator.kt`
  - `app/src/main/java/com/example/agenthost/migration/TaskMigrationPolicy.kt`
- 需要新增/删除/重命名的类型
  - 新增：无强制新增类型。
  - 删除：无。
  - 重命名：无。
  - 重点不是改名，而是把写入权锁到 owner。
- 兼容策略
  - 保持现有 enum 与 code 字面值不变。
  - 先通过 helper/注释/owner 表落地写入约束。
  - `TaskMigrationPolicy` 收 catalog，不改对外 code。
- 风险点
  - 现有调用方把 diagnostic/bridge/presentation 词汇当业务真相。
- 回滚点
  - 仅回退 helper/guard，不回退 `SessionState` 字段结构与 code 字面值。

### Phase 2: Mapper / derived state / UI retained state 清理

- 修改文件清单
  - `app/src/main/java/com/example/agenthost/overlay/OverlayUiState.kt`
  - `app/src/main/java/com/example/agenthost/overlay/OverlayStateMapper.kt`
  - `app/src/main/java/com/example/agenthost/overlay/OverlayController.kt`
  - `app/src/main/java/com/example/agenthost/overlay/OverlayEntryService.kt`
  - `app/src/main/java/com/example/agenthost/overlay/OverlayRecoverySupport.kt`
  - `app/src/main/java/com/example/agenthost/overlay/OverlayStartGuard.kt`
  - `app/src/main/java/com/example/agenthost/session/SessionCoordinator.kt`
- 需要新增/删除/重命名的类型
  - 新增：无强制新增类型，优先复用 `OverlayUiState`。
  - 删除：不删除 `OverlayMode`，只删除 coordinator 内重复映射逻辑。
  - 重命名：无。
- 兼容策略
  - 保留现有 UI mode 字面值。
  - retained completion TTL、dismiss、snooze、reply draft 继续保留，但明确只存在于 `OverlayController`。
- 风险点
  - overlay 行为最容易受 listener snapshot 字段变化影响。
- 回滚点
  - 可单独回退 overlay mapper/controller，不影响 canonical session owner。

### Phase 3: Report / diagnostics / test 对齐

- 修改文件清单
  - `app/src/debug/java/com/example/agenthost/debug/TaskRunReportHooks.kt`
  - `app/src/main/java/com/example/agenthost/report/TaskRunReportModels.kt`
  - `app/src/main/java/com/example/agenthost/report/TaskRunReportAnalyzer.kt`
  - `app/src/main/java/com/example/agenthost/report/TaskRunReportRenderer.kt`
  - 相关 unit tests
- 需要新增/删除/重命名的类型
  - 新增：无强制新增类型，优先调整 field provenance。
  - 删除：无。
  - 重命名：无。
- 兼容策略
  - `resultTag`, `failureLayer`, `terminalDisposition` 字面值先保持兼容。
  - 只调整它们的来源映射，不把它们上升为业务语义。
- 风险点
  - 历史报告脚本依赖旧 heuristic。
- 回滚点
  - 允许保留旧 tag 生成逻辑 behind compatibility layer。

## E. Test Plan

目标是“状态机一致性测试”，不是补功能 happy path。

### E1. SessionCoordinator 级测试

- `SessionStatus` 只能由 coordinator 写，automation engine 只能回调触发。
- `RUNNING -> WAITING_USER -> RUNNING` 与 `RUNNING -> COMPLETED` 不应经过额外伪状态。
- `COMPLETED` 后 post-completion outcome 与 cleanup 分离验证。
- `FAILED` 是 automation terminal，但允许 recovery/cancel control action。
- `activeSession == null` 才表示空闲，不用 `SessionStatus.IDLE`。

### E2. OverlayStateMapper / retained-completion 测试

- mapper 只从 canonical state + outcome state 派生 `OverlayMode`。
- `OverlayController` retained completion 在 `activeSession == null` 时仍可显示，但必须被标记为 local retained state。
- `dismissedPostCompletionSessionId`、`snoozedSessionId`、reply draft 不得进入 `SessionState`。
- `OverlayStateMapper` 与 shared overlay label helper 必须产出相同 mode。

### E3. Completion / cleanup / fallback 测试

- `status == COMPLETED` 时：
  - `postCompletionState=PENDING` 只在 completion-return 执行窗口短暂存在。
  - return success -> `SUCCEEDED`。
  - return failed -> `FALLBACK_CLEANUP`。
- cleanup 后 coordinator 无 active session，但 report/overlay 仍能读到正确最终 outcome。

### E4. Migration + completion 交互测试

- `WAITING_USER` handoff 时主屏迁移成功 / 冲突 / task missing / query unsupported。
- `replyToSession()` 的回迁成功、回迁失败、precheck failure。
- `AUTOMATION_DONE` 后 completion-return success/failure。
- `recoveryNeeded` 只能由 coordinator 在 migration outcome 上写，不由 overlay/report 推断回写。

### E5. Automation phase 与 session status 解耦测试

- `SubgoalPhase`, `CompletionVerdict`, `StepResultCategory` 任意变化都不应直接修改 `SessionStatus`，除非经过 coordinator callback。
- `doneOnly/currentPhase/completionState` 只能出现在 planner snapshot 和 report diagnostics 中。
- `DONE` action、`CompletionVerdict.SATISFIED`、`SessionStatus.COMPLETED` 必须在测试里明确区分。

### E6. Report / diagnostics 与 runtime state 对齐测试

- `TaskRunOverview.finalSessionState/finalOutcome` 必须来自 coordinator finalization，而不是 analyzer 反推。
- `resultTag/failureLayer/likelyProblemLayer` 必须是 diagnostic-only；它们变化不应影响 runtime behavior。
- `WAITING_USER_STALLED`, `COMPLETED_STATE_MISMATCH`, `MIGRATION_FAILED` 等标签要有基于 canonical snapshot 的断言。
- `TaskRunLifecycleEvent.status/migratedToMain/recoveryNeeded` 与对应 runtime snapshot 对齐。

## F. Output Rules

- 第一轮只提交本文件：`docs/state_machine_consistency_audit.md`。
- 不写代码，不修改其它生产文件或测试文件。
- 本文完成后等待确认，再进入实现阶段。
