# Overlay Visual Manual Checklist

## Purpose

This checklist is for manual, eyes-on verification of every visible overlay state and its key transitions. The driver script stages each state through the existing debug receiver so you can focus on:

- what changed on screen
- whether the visible CTA matches the intended semantics
- whether the next visible state is correct after you tap

Reference semantics live in `docs/overlay_ui_interaction_semantics.md`.

## Prerequisites

- Install a debug build with the debug receiver enabled.
- Grant overlay permission in `MainActivity`.
- Keep the device screen visible before running a case.
- Connect `adb`, then reset the environment:

```bash
scripts/overlay_visual_case_driver.sh reset
```

## Driver

Run cases with:

```bash
scripts/overlay_visual_case_driver.sh <case-name>
```

Available cases:

- `idle`
- `running`
- `need-user-already-on-main`
- `need-user-requires-move-to-main`
- `handoff-phase1`
- `handoff-phase2`
- `handoff-phase2-submit-reply`
- `recovery-to-main`
- `recovery-to-execution`
- `recovery-to-idle`
- `recovery-already-on-main`
- `post-cleanup`
- `post-return-main`
- `post-return-fallback`

## Checklist

### `idle`

- Expected entry: no active session.
- Observe:
  - Bubble text is `空闲`.
  - Expanded state shows the new-task panel.
  - Empty input keeps `开始` disabled.
  - `收起` only collapses the panel.
- Capture:
  - Bubble collapsed.
  - Bubble expanded.

### `running`

- Expected entry: active session in `RUNNING`.
- Observe:
  - Bubble text is `执行中`.
  - Expanded state still uses the new-task panel.
  - Input is disabled.
  - `开始` stays disabled.
- Capture:
  - Bubble collapsed.
  - Expanded disabled input state.

### `need-user-already-on-main`

- Expected entry: `WAITING_USER`, `alreadyOnMain == true`, not migrated handoff.
- Observe:
  - CTA semantics are "already on main", not "move to main".
  - Primary label is `继续处理`.
  - Tapping primary collapses the panel only.
  - Session remains the same waiting session after tap.
- Capture:
  - Before primary tap.
  - After primary tap.

### `need-user-requires-move-to-main`

- Expected entry: `WAITING_USER`, virtual-display session, auto-migrate intentionally skipped.
- Observe:
  - CTA semantics are "requires real move to main".
  - Primary label is `去主屏处理`.
  - Tapping primary triggers a real move-to-main transition.
  - UI leaves the pre-migration NEED_USER branch and enters the migrated handoff path.
- Capture:
  - Before primary tap.
  - Immediately after migration completes.

### `handoff-phase1`

- Expected entry: `WAITING_USER`, `migratedToMain == true`, first-phase handoff card.
- Observe:
  - Bubble detail reads `主屏处理中`.
  - Expanded state is still the card, not the reply strip.
  - Primary button is local acknowledgement only.
- Capture:
  - Bubble collapsed.
  - First-phase card.

### `handoff-phase2`

- Expected entry: same waiting session as `handoff-phase1`.
- Observe:
  - After one primary tap, the UI switches from the card to `HandoffStripView`.
  - Default reply text is `已处理完成`.
  - Secondary button becomes `取消任务`.
  - New-task panel does not appear.
- Capture:
  - First-phase card.
  - Second-phase strip.

### `handoff-phase2-submit-reply`

- Expected entry: second-phase handoff strip for the current waiting session.
- Manual action:
  - Enter the reply token printed by the script.
  - Tap `提交并返回后台`.
- Verify:
  - The script confirms the action was `reply_to_session` for the current waiting session id.
  - No `start_session` action is emitted during the submit flow.
  - Resume logs reference the current session id, not the previous baseline session id.
  - After resume, the same session must reach a second handoff question with the marker `SECOND_CONTINUATION_CHECK_CURRENT_WAITING_SESSION`.
- UI observe:
  - Submission must continue the current waiting session.
  - It must not open the new-task panel.
  - It must not resume an older session context.
  - If the overlay returns to handoff, the question copy must be the continuation marker above, not the generic completion-confirmation copy.
- Capture:
  - Strip before submit.
  - Immediate state after submit.
  - Second handoff state with the continuation marker.

### `recovery-to-main`

- Expected entry: recovery on a virtual-display session that is not already on main.
- Observe:
  - Overlay mode is `RECOVERY`.
  - Primary label is `去主屏处理`.
  - Secondary label is `取消任务`.
- Capture:
  - Recovery card.

### `recovery-to-execution`

- Expected entry: recovery after reply tries to return to execution and fails.
- Observe:
  - Overlay mode is `RECOVERY`.
  - Primary label is `返回后台执行`.
  - Session is still the same waiting session, now marked as recovery-needed.
- Capture:
  - Handoff strip before submit.
  - Recovery card after failure.

### `recovery-to-idle`

- Expected entry: recovery on missing-target fallback path.
- Observe:
  - Primary label is `返回空闲`.
  - Tapping primary ends the session.
  - Overlay returns to `IDLE`.
- Capture:
  - Recovery card before tap.
  - IDLE after tap.

### `recovery-already-on-main`

- Expected entry: failed main-display session, already-on-main recovery semantics.
- Observe:
  - Primary label is `继续处理`.
  - Primary tap only collapses the panel.
  - No migration action is implied by the UI.
- Capture:
  - Recovery card.
  - After primary tap.

### `post-cleanup`

- Expected entry: completed session with cleanup-immediately outcome.
- Important:
  - This retained completion UI lasts about 3 seconds only.
- Observe:
  - Bubble detail matches `会话已清理`.
  - Primary label is `知道了`.
  - If untouched, it returns to `IDLE` after TTL expiry.
- Capture:
  - Completion card during retention.
  - IDLE after TTL expiry.

### `post-return-main`

- Expected entry: completed session with `RETURN_TASK_TO_MAIN` success.
- Important:
  - This retained completion UI lasts about 3 seconds only.
- Observe:
  - Bubble detail matches `已回主屏`.
  - Primary label is `查看主屏`.
  - Primary tap still only dismisses retained completion UI.
- Capture:
  - Completion card during retention.

### `post-return-fallback`

- Expected entry: completed session with `RETURN_TASK_TO_MAIN` requested but fallback cleanup used.
- Important:
  - This retained completion UI lasts about 3 seconds only.
- Observe:
  - Bubble detail matches `已安全结束`.
  - Message includes the failure code.
  - Primary label is `知道了`.
- Capture:
  - Completion card during retention.

## Common Defect Notes

When you report a problem back, include:

- case name
- whether the bug happens before tap or after tap
- visible bubble title/detail
- visible card title/message
- visible primary/secondary labels
- whether the new-task panel appeared unexpectedly
- whether the session looked stale, mismatched, or resumed the wrong context
- screenshot or screen recording timestamp
