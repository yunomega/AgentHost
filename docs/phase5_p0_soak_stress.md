# Phase 5 P0.2 Soak / Stress Runbook

## 脚本入口

- `scripts/verify_task_migration_soak.sh`
- `scripts/verify_task_migration_recovery.sh`

## 覆盖场景

- `repeated_migration_loop`
  - 复用 `verify_task_migration.sh`
  - 验证 handoff / reply / migrate-back 闭环稳定
- `repeated_capture_inject_loop`
  - 复用 `verify_task_migration_concurrency.sh CASE=concurrent_capture_inject`
  - 验证 capture / inject 长时稳定
- `mixed_session_loop`
  - 交替跑 `VIRTUAL_DISPLAY` 正向迁移和 `MAIN_DISPLAY` not-applicable case
  - 验证 MAIN_DISPLAY 语义不漂移
- `recovery_loop`
  - 复用 `verify_task_migration_recovery.sh`
  - 覆盖 `TARGET_TASK_REMOVED` / `TASK_ID_STALE` / `TASK_MOVE_BACK_CONFLICT`
  - 对同一 one-shot fault 连续触发两次，要求 hook 自动清空且第二次不残留脏状态

## 典型用法

固定轮次：

```bash
scripts/verify_task_migration_soak.sh ROUND_LIMIT=3 INCLUDE_ASK_USER=1 INCLUDE_CONCURRENCY=1
```

30 分钟 soak：

```bash
scripts/verify_task_migration_soak.sh ROUND_LIMIT=0 DURATION_SECS=1800 INCLUDE_ASK_USER=1 INCLUDE_CONCURRENCY=1
```

只跑 recovery：

```bash
scripts/verify_task_migration_recovery.sh IDEMPOTENT_ROUNDS=2
```

## 输出目录结构

```text
${LOG_DIR}/
  run.log
  rounds.csv
  summary.csv
  failure_summary.txt
  repeated_migration_loop/
    round_N/
      stdout.log
      device.log
      dump_active_session.stdout.log
      residual_summary.txt
      meminfo.dump
      activity.dump
      window.dump
  repeated_capture_inject_loop/
    round_N/
      stdout.log
      child/
      device.log
      ...
  mixed_session_loop/
    round_N/
      virtual.stdout.log
      main.stdout.log
      main_child/
      ...
  recovery_loop/
    round_N/
      stdout.log
      child/
      ...
```

## 自动采样字段

`rounds.csv` 每轮采样：

- `passed`
- `timeout`
- `display_drift`
- `elapsed_ms`
- `conflict_code_count`
- `recovery_code_count`
- `exception_code_count`
- `session_residual`
- `display_residual`
- `task_residual`
- `dump_code`
- `dump_status`

`summary.csv` case 级汇总：

- `failure_rate`
- `timeout_rate`
- `display_drift_count`
- `first_failure_case`
- `first_failure_round`
- `avg_elapsed_ms`
- `conflict_code_count`
- `recovery_code_count`
- `exception_code_count`
- `session_residual_count`
- `display_residual_count`
- `task_residual_count`

## Pass / Fail 标准

默认 pass 条件：

- `failures == 0`
- `timeouts == 0`
- `display_drift_count == 0`

默认不把 `session/display/task residual` 直接记为 fail，只做采样和汇总，避免误伤现阶段已知的 completed-session 留存行为。
