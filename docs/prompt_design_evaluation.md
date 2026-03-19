# AgentHost 提示词设计全链路评估

## 结论摘要

这个项目的提示词设计不是单一 prompt，而是一套“提示词 + 状态机 + 能力矩阵 + 严格解析 + 输出修复”的联合系统。  
如果按全链路工程质量评估，我给它 **7.6 / 10**；如果只看 prompt 文案本身，我给 **6.3 / 10**。

核心判断：

- **优点明显**：它不是把希望全押在模型自觉上，而是把协议、阶段、能力、输出格式和后处理都做成了约束层，工程意识是对的。
- **主要问题也明显**：提示词职责过重，层次重复，真正的“权威指令源”不够清晰；很多规则既写在 prompt 里，又由解析器、validator、guardrail 重复执行，导致可维护性一般。
- **最值得保留的部分**：单步动作协议、动态能力约束、阶段化控制、输出 `_meta` 追踪。
- **最需要重构的部分**：Android 侧 prompt 与代理侧 prompt 的重复拼装、代理 system prompt 的信息过载、子目标解析对命令句式的脆弱依赖。

## 一、当前提示词系统的真实结构

### 1. Android 侧不是最终 prompt，而是 prompt 原材料生产者

默认 Overlay 直接把模型规划指向本地代理 `/plan`，而不是上游模型地址：

- `app/src/main/java/com/example/agenthost/overlay/OverlayModelPlannerConfig.kt:7-25`

Android 侧 `ModelPlanningAdapter` 会先生成一版轻量 `systemPrompt` 和 `userPrompt`，再和完整 `context` 一起发给网关：

- `app/src/main/java/com/example/agenthost/automation/ModelPlanningAdapter.kt:27-34`
- `app/src/main/java/com/example/agenthost/automation/ModelPlanningAdapter.kt:169-191`
- `app/src/main/java/com/example/agenthost/automation/ModelPlanningAdapter.kt:194-220`
- `app/src/main/java/com/example/agenthost/model/HttpModelClient.kt:164-275`

这说明 Android 侧提示词主要承担两件事：

- 给没有代理时的模型调用保底。
- 给代理提供“原始意图”和上下文材料。

### 2. 真正控制上游模型行为的是 Python 代理

代理会读取 `context.capabilityProfile`、`context.agentControl`、`context.observation`、`recentActions` 等字段，重新组装为真正发给上游模型的 `messages`：

- `scripts/proxy_server.py:329-414`
- `scripts/proxy_server.py:417-494`
- `scripts/proxy_server.py:513-537`

这个 system prompt 明确规定了：

- 只能输出一个 JSON object。
- 禁止 markdown、解释、数组、多动作容器。
- 坐标必须是截图千分位。
- 支持动作集合固定。
- 优先以截图为主事实来源。
- 已完成时直接 `DONE`。
- 结合阶段与能力动态限制动作。

所以从实际执行路径看，**代理层才是提示词设计的主战场**。

### 3. 提示词系统还依赖多层非 prompt 防御

上游输出并不会被直接执行，而是继续经过：

- 严格 JSON 单动作解析：
  - `app/src/main/java/com/example/agenthost/automation/StrictJsonActionParser.kt:3-114`
- 能力校验：
  - `app/src/main/java/com/example/agenthost/automation/CapabilityValidator.kt:12-52`
- 阶段机限流：
  - `app/src/main/java/com/example/agenthost/automation/SubgoalStateMachine.kt:21-164`
- 输出修复和元信息追踪：
  - `scripts/proxy_server.py:622-853`
  - `app/src/main/java/com/example/agenthost/automation/CanonicalActionMapper.kt:3-35`

因此这个项目的本质不是“prompt 驱动”，而是“协议驱动 + prompt 辅助决策”。

## 二、做得好的地方

### 1. 协议优先，而不是自然语言优先

代理 system prompt 一开始就把输出协议说死了：

- 一个 JSON object。
- 无 markdown。
- 无数组。
- 无多步容器。
- 固定动作名。
- 固定坐标语义。

证据：

- `scripts/proxy_server.py:345-367`
- `app/src/main/java/com/example/agenthost/automation/StrictJsonActionParser.kt:29-49`
- `app/src/test/java/com/example/agenthost/automation/StrictJsonActionParserTest.kt:17-42`

这类约束非常适合 GUI agent，因为动作空间是离散的、解析失败代价高、执行层需要稳定输入。

### 2. prompt 明确吸收了平台能力约束

代理会把 capability profile 动态转成 allowed / blocked / unknown actions，再写入 system prompt。  
Android 侧执行前还会再走一次能力校验。

证据：

- `scripts/proxy_server.py:281-319`
- `scripts/proxy_server.py:358-362`
- `app/src/main/java/com/example/agenthost/automation/CapabilityValidator.kt:12-52`

这比“让模型自己猜平台能不能做”要成熟得多。

### 3. prompt 已经和阶段机联动，不是纯自由规划

代理 system prompt 会根据 `NEED_OPEN_PARENT`、`NEED_CLICK_TARGET`、`TARGET_CLICKED_PENDING_SETTLE`、`DONE_ONLY` 注入不同约束。  
阶段本身由状态机从用户命令和执行结果中演化得出。

证据：

- `scripts/proxy_server.py:370-386`
- `app/src/main/java/com/example/agenthost/automation/SubgoalStateMachine.kt:51-143`
- `scripts/test_proxy_server.py:80-152`
- `app/src/test/java/com/example/agenthost/automation/ModelPlanningAdapterTest.kt:104-188`

这说明设计者已经意识到 GUI 任务不是单轮问答，而是一个受上下文和进展影响的过程控制问题。

### 4. 输出修复不是黑盒，且保留了可追踪元信息

代理会：

- 提取第一个合法 JSON object。
- 修复一种常见 broken click JSON。
- 兼容旧式 `action: CLICK point: x, y` 文本。
- 把坐标归一化到像素。
- 附加 `_meta.coordSpace/sourceModel/sourceMode/rawModelAction`。

证据：

- `scripts/proxy_server.py:622-853`
- `scripts/test_proxy_server.py:154-184`
- `scripts/test_proxy_server_sanitizer.py:22-73`
- `app/src/main/java/com/example/agenthost/automation/CanonicalActionMapper.kt:10-23`

这对线上调试非常重要，因为你能区分“模型原始输出是什么”和“系统最终执行了什么”。

### 5. 有针对 prompt 合同的测试，不只是跑通

这个项目对以下内容都有测试：

- prompt 是否注入阶段约束。
- `DONE_ONLY` 是否收紧输出。
- 非法数组输出是否拒绝。
- `HOME` / 非 ASCII `TYPE_TEXT` 是否被拦截。
- sanitizer 是否修复坐标与遗留格式。

证据：

- `scripts/test_proxy_server.py:80-196`
- `scripts/test_proxy_server_sanitizer.py:22-73`
- `app/src/test/java/com/example/agenthost/automation/ModelPlanningAdapterTest.kt:40-188`
- `app/src/test/java/com/example/agenthost/automation/StrictJsonActionParserTest.kt:17-42`

这意味着它的提示词系统是“可验证的”，不是纯靠人工感受。

## 三、主要问题与风险

### P0：双层 prompt 重复，权威指令源不够清晰

Android 侧已经生成一版 `systemPrompt/userPrompt`，代理又重新生成一版更强的 prompt。  
更关键的是，代理没有忽略 Android prompt，而是把它们再次拼进自己的 system prompt：

- `app/src/main/java/com/example/agenthost/automation/ModelPlanningAdapter.kt:169-191`
- `scripts/proxy_server.py:400-405`
- `app/src/main/java/com/example/agenthost/model/HttpModelClient.kt:181-223`

问题在于：

- 规则来源有两个层级。
- Android prompt 和代理 prompt 的职责并不严格区分。
- 如果以后两边都继续迭代，很容易出现冲突、重复、优先级不清。

这会带来维护成本和回归风险。  
当前还能运行，是因为代理层更强，且下游还有 deterministic guardrail 在兜底。

### P0：代理 system prompt 信息过载，职责塞得太满

同一段 system prompt 同时承担：

- 角色定义。
- 输出协议。
- 坐标语义。
- 完成判定。
- 能力矩阵。
- 阶段约束。
- 文本输入字符集约束。
- Android prompt 透传。
- 上一步失败与用户回复提示。

证据：

- `scripts/proxy_server.py:345-412`

这会导致几个问题：

- prompt 太像“规则堆栈”，不利于长期维护。
- 一旦需要改某条规则，很难判断它属于协议层、任务层还是恢复层。
- token 利用率一般，尤其是 system 与 user 两边都有 allowed/blocked/phase/history 信息时。

### P0：prompt 与 deterministic guardrail 的职责边界不够清晰

很多规则既写在 prompt 里，又由代码硬性约束：

- prompt 说“不要输出数组”，parser 也强拒绝。
- prompt 说“不要用 HOME”，validator 和 phase gate 也会拒绝。
- prompt 说“不要重复点击”，guardrail 也会替换成 `WAIT` 或 `ASK_USER`。
- prompt 说“已完成就 DONE”，completion evaluator 也会直接推进 `DONE_ONLY`。

证据：

- `scripts/proxy_server.py:347-357`
- `app/src/main/java/com/example/agenthost/automation/StrictJsonActionParser.kt:29-49`
- `app/src/main/java/com/example/agenthost/automation/CapabilityValidator.kt:12-52`
- `app/src/main/java/com/example/agenthost/automation/SubgoalStateMachine.kt:111-143`

这并不是说多层兜底不好，而是：

- 当前写法容易让人误判“到底是谁在负责正确性”。
- 以后调 prompt 时，很难区分失败是 prompt 不行，还是后处理在掩盖 prompt 问题。

### P1：子目标解析能力偏脆弱，prompt 的阶段控制建立在有限正则上

状态机对顺序任务的解析主要依赖几条“然后/再点击/点开后点击”的正则：

- `app/src/main/java/com/example/agenthost/automation/SubgoalStateMachine.kt:31-48`
- `app/src/main/java/com/example/agenthost/automation/SubgoalStateMachine.kt:155-162`

这对“打开设置里的应用选项，再点击语燕输入法”这种句式有效，但对更自由的表达可能失效：

- 多于两段的任务。
- 没有“再/然后”的省略句。
- 中英文混合复杂描述。
- 目标不是点击链路，而是开关、输入、确认、返回等组合操作。

一旦 `parentHint/targetHint` 提取错误，prompt 反而会被错误上下文误导。

### P1：sanitizer 虽然实用，但会掩盖 prompt 质量

代理会主动：

- 抽取文本中的第一个 JSON object。
- 修复缺少 `y` 键的 `CLICK`。
- 兼容旧格式动作文本。

证据：

- `scripts/proxy_server.py:622-853`
- `scripts/test_proxy_server_sanitizer.py:22-73`

这对线上容错有价值，但如果把“最终能执行”直接当成 prompt 成功，就会高估提示词质量。  
因为有一部分成功，其实是修复器的成功，不是 prompt 的成功。

### P1：system / user prompt 存在重复注入

当前至少存在这些重复：

- allowed / blocked actions 同时出现在 system 和 user。
- 当前 phase 同时出现在 system 和 user。
- `userPrompt` 既被当作 `User command`，又被追加进 `Android userPrompt`。
- 能力既有 summary，也有完整 JSON。

证据：

- `scripts/proxy_server.py:358-362`
- `scripts/proxy_server.py:372-386`
- `scripts/proxy_server.py:400-405`
- `scripts/proxy_server.py:427-494`

这不是致命问题，但说明 prompt 结构还没有彻底抽象干净。

### P2：完成判定在 prompt 中偏激进，任务成功语义还不够类型化

代理 prompt 明确要求“目标已可见就立即 DONE”：

- `scripts/proxy_server.py:354-356`

同时 completion evaluator 在某些阶段会因“点击后界面变化”直接判满意：

- `app/src/main/java/com/example/agenthost/automation/CompletionEvaluator.kt:34-61`

这对“打开某页面后再点某项”的场景合理，但对需要进一步验证状态的任务可能偏乐观。  
比如“打开设置并启用某开关”与“只导航到某页面”需要不同的成功语义。

### P2：多语言和文本约束策略还不够统一

系统指令大多是英文，任务与目标提示往往是中文，`ASK_USER` 问题可能中英混用。  
`TYPE_TEXT` 又可能在 `ASCII_BASIC` 下被限制为纯 ASCII：

- `scripts/proxy_server.py:388-398`
- `app/src/main/java/com/example/agenthost/automation/CapabilityValidator.kt:38-52`

这在当前场景能工作，但对于验证码、联系人、中文搜索词等任务，会让“什么时候该 ASK_USER，什么时候该 TYPE_TEXT”依赖隐式理解，而不是更清晰的策略表达。

## 四、分项评分

| 维度 | 评分 | 说明 |
| --- | --- | --- |
| 协议清晰度 | 9/10 | 单动作 JSON、坐标语义、动作集合、解析规则都很清楚。 |
| 视觉 grounding | 8/10 | 明确要求以截图为主事实来源，并结合尺寸做坐标变换。 |
| 动态约束能力 | 8.5/10 | capability + phase + history 注入较完整。 |
| prompt 文案纯度 | 6/10 | 规则很多，但有重复和职责混杂。 |
| 可维护性 | 5.5/10 | 双层 prompt、system 信息过载、边界不清。 |
| 容错性 | 9/10 | parser、validator、sanitizer、guardrail 都在兜底。 |
| 可测试性 | 8.5/10 | 已有较多协议与回归测试。 |
| 可扩展性 | 6/10 | 增加新动作/新阶段/新任务类型时，提示词与代码会同步膨胀。 |

## 五、我建议保留的设计

下面这些不建议推翻，应该保留并继续强化：

- **单步动作协议**：GUI agent 极适合一步一决策，不要退回多步计划 JSON。
- **坐标千分位约定**：让模型输出与截图尺寸解耦，再由代理换算像素是对的。
- **阶段化控制**：`NEED_OPEN_PARENT -> NEED_CLICK_TARGET -> TARGET_CLICKED_PENDING_SETTLE -> DONE_ONLY` 的思路值得保留。
- **能力矩阵注入**：比把支持能力硬写进自然语言更稳。
- **`_meta` 追踪**：这是调试模型输出最有价值的设计之一。

## 六、优先级最高的重构建议

### P0：明确单一权威 prompt 层

建议把体系明确成：

- Android 侧只负责发送结构化上下文，不再承担主 prompt 设计。
- 代理层成为唯一权威 prompt 生成器。
- Android 的 `systemPrompt/userPrompt` 如果保留，也只作为调试字段，不再原样拼进上游 system prompt。

目标是把“谁定义模型行为”这件事讲清楚。

### P0：把代理 system prompt 拆成稳定协议层

建议代理 system prompt 固定只保留四类稳定内容：

- 角色与单步规划边界。
- JSON 输出格式。
- 动作 schema。
- 坐标与字符集等硬协议。

不要把阶段历史、失败上下文、Android prompt、任务细节都塞进 system prompt。  
这些应该归到 user/context prompt。

### P0：把“硬约束”从 prompt 中降级为提示，把真正执行权交给代码

建议把下面这类规则视为代码负责、prompt 只做提醒：

- 不允许数组。
- 不允许 blocked action。
- 非 ASCII 文字不可输入。
- `DONE_ONLY` 阶段只允许 `DONE/ASK_USER`。

这样做的好处是：

- prompt 更短。
- 失败归因更清晰。
- 后续调 prompt 时不会因为重复约束而混淆。

### P1：重写 user prompt 为固定结构模板

建议 user prompt 统一成固定顺序的几个区块：

1. Task  
2. Observation  
3. Current phase  
4. Allowed / blocked actions  
5. Recent actions  
6. Failure or user reply context  
7. Decision instruction

当前 user prompt 已经接近这个方向，但还夹杂重复字段和来源不统一的问题。

### P1：把完成判定类型化

建议区分至少两类任务：

- `NAVIGATE_TO_TARGET`
- `CHANGE_STATE_OR_SUBMIT`

前者允许“目标可见即 DONE”，后者要明确要求“状态已改变/提交已成功”才能 DONE。  
否则“看到目标”与“完成目标”容易混淆。

### P1：提高子目标解析的鲁棒性

至少要补：

- 更多中文连接词与省略句。
- 超过两段的顺序命令。
- 点击 + 输入 + 确认类任务。
- 对解析失败时的降级策略，而不是直接退回泛导航。

### P2：把 sanitizer 修复率当成 prompt 质量指标

建议新增统计：

- 直接合法 JSON 比例。
- 通过 `extract_first_json_object` 才恢复的比例。
- 通过 repair / legacy parser 才恢复的比例。
- 被 parser/validator 拒绝的比例。

如果 repair 占比高，说明问题不在执行层，而在 prompt 质量。

## 七、建议补的测试

当前测试不错，但还缺几类高价值场景：

- Android prompt 与代理 prompt 冲突时，最终上游 system prompt 是否仍可预测。
- `allowedActions` 与 capability profile 不一致时，谁优先。
- 长历史、多轮 `lastUserReply` 下 prompt 是否变得冗长或自相矛盾。
- 多语言命令下 `ASK_USER` 的问句是否稳定。
- “目标可见但未真正完成”的误判 `DONE` 场景。
- 子目标正则解析失败时，是否错误限制动作。

## 八、最终判断

这个项目的提示词设计方向是对的，工程化程度也明显高于常见“拼一段 system prompt 然后祈祷模型听话”的做法。  
它最大的价值不在 prompt 文案华丽，而在于把模型放进了一个被协议、阶段、能力和后处理层层约束的系统里。

但从设计成熟度看，它现在更像 **“能跑且相对稳的第一代工程实现”**，还不是 **“职责清晰、可长期扩展的第二代体系”**。  
如果只做小修小补，它还能继续工作；如果想支持更多任务类型、更多模型、更多端侧能力，就需要先把 prompt 的层次和职责重新切干净。

一句话总结：

> 这套设计的强项是“约束充分”，短板是“边界不够干净”；当前靠多层防御获得稳定性，下一步应该靠职责重构获得可维护性。
