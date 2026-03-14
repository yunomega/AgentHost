# Recent Visibility Phase-2 Strategy

## 结论先行

当前 Phase 4B/5P0 路线可以稳定做到：

- `VIRTUAL_DISPLAY` session 不抢主屏焦点
- `WAITING_USER` 时只把真实 task handoff 到主屏
- 迁移/恢复/冲突状态可诊断

但它不能自然保证“虚拟屏 task 彻底不出现在 Recent”。这是 `Task/RecentTasks` 归档路径决定的，不是现有 session orchestration 自身的 bug。

本轮保持 `design-only`，不改 framework/system_server。

## 根因分析

### task 为什么会进入 Recent

系统层面上，Recent 关注的是 `Task`，不是我们自己的 session 对象。

核心关系：

- `ActivityRecord` 属于 `Task`
- `Task` 归属于某个 `DisplayContent`
- `WindowManager/ATMS` 在 task 可见、切前台、切回栈时更新 task 状态
- `RecentTasks` 维护最近任务列表，依据 task identity、root activity、affinity、visibility、lifecycle 状态来决定是否出现在 Recent

一旦被迁移和展示的是“真实业务 task”本身，例如 `Settings` task，而不是单纯的 `SessionRootActivity` placeholder，那么 Recent 记录的对象就是那个真实 task。

### 为什么当前方案只能保证“不抢主屏焦点”

当前 `SessionRoot / task migration` 方案做的是：

- 在虚拟屏创建和承载 session 执行环境
- 在 `WAITING_USER` 时把绑定 task handoff 到主屏
- 回复后再迁回 execution display

这套机制控制的是：

- task 运行在哪个 display
- task 当前是否在主屏可见
- 是否保留可诊断的迁移/恢复状态

它没有控制的是：

- `RecentTasks` 是否收录该 task
- 该 task 被收录后是否应从系统 Recent UI 隐藏
- 真实 task 在 framework 内部被视为“应当排除的 recent candidate”

也就是说，Phase 4B/5P0 的 task migration 只解决 display/focus 问题，不解决 recent indexing/policy 问题。

## 为什么 app/task flag 不能根治

这是下阶段最容易被误判成“实现 bug”的点，需要单独写死：

- `excludeFromRecents` 只能直接作用于对应 activity/task 的创建语义。
- 当前真正被展示和迁移到主屏的，往往是外部业务 task，例如 `Settings` task，而不是 AgentHost 自己的 `SessionRootActivity`。
- 我们给 `SessionRootActivity` 加 flag，并不会改写外部 task 已经建立的 recent 属性。
- 即使通过 intent/task flag 改善了部分创建路径，也通常只能做到“减少出现概率”或“改善某些入口”，不能保证“永不出现在 Recent”。
- 所以下阶段如果 PoC 呈现为“有所改善但不彻底消失”，应视为路线本身上限，而不是默认判定成实现缺陷。

## 候选方案对比

### 方案 A: app/task flag 层

示例：

- `excludeFromRecents`
- `FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS`
- task affinity / launchMode 调整

优点：

- 成本最低
- 不需要改 framework
- 适合快速 PoC

缺点：

- 只能影响我们自己创建/控制的 activity/task 入口
- 对被迁移的外部真实 task 控制力弱
- 很难覆盖 Recent 的所有系统路径

回归风险：

- 可能误伤 task reuse、handoff、一部分前台体验
- 容易让任务栈行为变得难以预测

结论：

- 适合做最小验证
- 不适合作为根治路线

### 方案 B: Activity/Task policy 层

示例：

- 基于 display / task affinity / windowing mode 建专用策略
- 在任务切前台、切 display 时加 policy allowlist/blocklist

优点：

- 比纯 app flag 更接近根因
- 可以把“虚拟屏承载任务”当成一类任务做治理

缺点：

- 仍可能受 ATMS/RecentTasks 既有行为约束
- 兼容性依赖 ROM 行为，容易出现边角回归

回归风险：

- 可能误伤主屏正常 task 迁移
- 可能误伤用户显式切回应用的前台体验

结论：

- 可做 OEM policy 定制预研
- 仍需验证与 `WAITING_USER -> migrate-back` 闭环的兼容性

### 方案 C: framework/system_server 定制

示例：

- `RecentTasks` 过滤策略
- `Task` / `ActivityRecord` recent eligibility 定制
- 基于 display、uid、package、task annotation 的 recent suppression

优点：

- 最接近根因
- 最适合 RK3588 私有系统定制
- 可以显式定义“虚拟屏承载任务不进 Recent”规则

缺点：

- 成本最高
- 需要 framework 验证矩阵
- 一旦策略过宽，容易误伤用户正常 recent 行为

回归风险：

- 可能误伤 task migration / handoff
- 可能误伤用户主动切回历史任务
- 需要严格白名单和 feature flag

结论：

- 这是最可能的根治路线
- 但必须在独立分支保存现场后做 framework PoC

## 推荐路线

### 先做什么最小验证

先做最小诊断和 PoC，不改 framework 主链路：

1. 保持当前 Phase 4B/5P0 行为不变。
2. 用 `scripts/diagnose_recent_visibility.sh` 在三个位点抓快照：
   - handoff 前
   - `WAITING_USER` 主屏展示中
   - migrate-back 后
3. 先验证 app/task flag 路线的上限，只期待“改善”，不承诺“根治”。

### 对 RK3588 私有系统更合适的路线

- 推荐最终走 `framework/system_server` 定制。
- 策略粒度建议至少包含：
  - display 类型或 display id 范围
  - package / uid allowlist
  - task annotation 或 session tag
  - 默认关闭的 feature flag

### 哪种路线最可能误伤现有主链路

误伤概率从高到低大致是：

1. 粗暴 app/task flag 改动
2. 未加白名单的 task policy 改动
3. 带 feature flag、带 allowlist 的 framework 定制

原因：

- 现有 task migration/handoff 已经依赖真实 task 的正常生命周期。
- 越早在 app 层“改任务属性”，越容易把正常 handoff/reuse 一并打坏。

## 本轮最小诊断开关

已补：`scripts/diagnose_recent_visibility.sh`

特点：

- 只抓 `dumpsys activity recents / activities / window / containers`
- 默认关闭，不影响运行时行为
- 适合作为 framework/policy PoC 前的基线采样

## 本轮边界

- `design-only`
- 不改 framework
- 不改 system_server
- 如果下轮要动 framework，先 `git` 保存现场，再在独立分支做 PoC
