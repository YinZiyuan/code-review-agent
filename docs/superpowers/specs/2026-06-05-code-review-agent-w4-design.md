# W4 设计 · release 评测补足 + 调优 + 交付

> **状态**：设计稿（brainstorming 产出，待 user 审）
> **分支**：`feat/w4`（当前 `feat/w2`，实现前切）
> **承接**：[W3 spec](2026-05-31-code-review-agent-w3-design.md) / [W3 notes](../../learnings/w3-notes.md)
> **总设计**：[2026-05-17 总设计 §W4](2026-05-17-code-review-agent-design.md)

## 一句话目标

把 W3 的 `w3-pipeline`（v3）从「20 样本上的方向性结论」打磨成「40 样本 release suite 上可信、可复现、可交付的稳定发布版」，并补齐对外交付物（README / 架构图 / 评测报告 / demo 脚本）。

## 背景与现状

- 样本：当前 20 个，全部 `reverse-*` 反向构造。
- 报告：`eval/reports/` 有 v0/v1/v2/v3 四份，**均基于 20 样本**。
- W3 结论（20 样本，方向性）：recall 0.50→0.65→0.70，precision 0.31→0.37→**0.67**，fp_rate 0.69→0.63→**0.33**，latency 34.6s→8.4s→**4.5s**。
- W3 notes 自己点名的「W4 前三件事」：①40 样本 release 评测；②severity 校准（`severity_accuracy` 回退到低于 v2，是最明显调优点）；③`tool_success_rate` 语义澄清（「预期内 skip」与「analyzer 真失败」混淆）。

## 范围决策（已与 user 确认）

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 新增 20 样本怎么造 | **全合成 / 反向构造**（10 reverse + 10 合成边界） | 完全可控、快、隔离干净；如实标注「自构样本」局限 |
| v4-stretch 多 Agent | **先交付 v3，多 Agent 看余量** | 沿用 W3 风险表退路；不把交付押在新架构上 |
| 交付物 | README 重写 + 架构图 + 评测报告/曲线 + demo 脚本 + commit 整理 | 全要；录屏由 agent 出脚本、user 真录 |
| 旧 20 样本报告 | **成功复现到 40 → 覆盖 `vN.json`；复现不了 → 单列 `*-20sample-historical.json`** | strict 40 与历史值分档存，曲线不混用；历史也锁在 git tag |
| release runs | **v3/v3.1-tuned ×3，基线 v0/v1/v2 ×1**（runner 先补重复跑能力） | 方差只在决战版上花预算 |
| 结构 | **单一 W4 spec，内部分 4 阶段** | 同构 W3（一 spec 装 W3a/W3b），叙事连贯 |

## Non-Goals（YAGNI）

- 不做多 Agent（除非 Phase 1-4 全部完成后仍有余量，届时作为独立 stretch，不在本 spec 承诺范围）。
- 不引入真实开源 PR 样本（本轮全合成；真实 PR 作为后续路线写进 README）。
- 不改动 pipeline 核心架构（DiffAnalyzer→ToolFindingsProducer→LlmReviewer→Summarizer 保持不变，只做调优级修改）。

## 诚实声明（eval 局限）

本项目评测集为**全合成 / 反向构造样本**——由开发者构造 diff 与 ground truth，存在「自己造题自己考」的固有偏差：构造者对「问题在哪」的预期会渗进样本，指标偏乐观，不能直接外推到真实 PR 分布。该局限在 spec、`eval/README.md`、项目 README 的 eval 章节均如实写明。真实 PR 样本列为后续路线。

---

# Phase 1 · 样本扩充 20 → 40

## 目标

在不破坏样本隔离的前提下扩到 40 样本，且新增样本要能**压测 v3 的弱点**，而非堆同质 case。

## 新增 20 个的构成

**10 个 `reverse-021..030`** —— 延续反向构造手法，刻意补现有 20 个偏少的类目/难度：
- 优先覆盖 `CONCURRENCY`、`PERFORMANCE`、`TEST` 类目（现有集中在 SECURITY/STABILITY）。
- 至少补若干 `hard` 难度，平衡难度分布。
- 实现前先统计现有 20 样本的 category/difficulty 分布表，按缺口补，使最终 40 样本分布均衡且分布表写进 spec/README。

**10 个 `synthetic-001..010` 合成边界 case** —— 专测 precision 与边界判断，三类各约 3-4 个：
- **真阴性（clean diff）**：看似可疑实则正确的改动，`annotation.json` 的 ExpectedIssue 为空 → 测 v3 会不会过度报，直接打击 fp_rate。
- **近邻干扰**：同一处一个真问题 + 一个相似但无害的改动 → 测去重与误报边界。
- **行号 / 跨文件刁钻**：DiffParser 行号边界、跨文件上下文依赖 → 测 pipeline 输入层（DiffAnalyzer）。

## 样本目录结构（沿用现有约定）

```
eval/samples/<id>/
  meta.json          # agent 可见: id；禁止输入: category/difficulty/notes
  diff.patch         # agent 可见
  source-before/     # agent 可见
  source-after/      # 禁止输入（仅人工核对）
  annotation.json    # 禁止输入（ground truth: ExpectedIssue[]）
```

- ExpectedIssue 用固定枚举：severity `CRITICAL|WARNING|SUGGESTION`、category `SECURITY|PERFORMANCE|STABILITY|CONCURRENCY|TEST|STYLE|OTHER`。
- 隔离不变量：`Sample.load` 为评测自身会加载 ground truth（`annotation.json`）和完整 `meta.json`，但 **`EvaluationRunner` 传给 `agent.review` 的只能是 `diff.patch` + `source-before/`**——隔离边界在「runner→agent」这一步，不在 `Sample.load`。见 [eval/samples/README.md](../../../eval/samples/README.md)。
- **隔离边界测试**：新增/保留一个测试断言 `EvaluationRunner` 调 `agent.review` 时不透传 `annotation`/`source-after`/`meta` 的禁止字段（category/difficulty/notes），守住边界不被未来改动破坏。

## 验收

- 40 个样本目录齐全，每个含合法 `annotation.json`（枚举合法）。
- 新增 `diff.patch` 均被 `DiffParser` 正确解析（行号为新文件 post-change 行号）。
- category/difficulty 分布表更新进 spec 与 README。
- smoke / 子集 eval 在新样本上跑通、无 `review error`。

---

# Phase 2 · 40 样本 release 评测

## 目标

把 40 样本固化为 **release suite**，在同一套样本上 honest 复现 v0/v1/v2/v3，产出 apples-to-apples 的提升曲线——项目的「成绩单」。

## 前置任务：runner 支持重复跑 + 多 run 聚合（Finding 1）

**现状**：[EvaluationRunner](../../../src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java) 每个样本只 `evaluateOne` 一次；`runs_per_sample` 当前**仅写进 config block，并不真的重复执行**。直接出 ×3 报告等于「声称可复现却没测」。

**改法**：Phase 2 第一步实现重复跑——每个样本跑 `runs` 次，按 run 聚合（指标取均值 + 记录 min/max 或标准差，per-sample 里保留每 run 结果），report 里体现「N runs」的真实数据。需单测覆盖「runs=1 与旧行为等价」「runs=N 聚合正确」。

## release suite 定义（runs 策略，Q1）

- **决战版 v3 与 v3.1-tuned：40 样本 × 3 runs**，用于看稳定性 / 方差。
- **历史基线 v0 / v1 / v2：40 样本 × 1 run**（只需提供趋势锚点，不在它们上面花 ×3 预算）。
- report 的 config block 记录 `suite=release` / `pipeline` label / **该报告实际 runs 数**，保证「图里的数字是几次跑出来的」可追溯。
- 目标总耗时 < 90 分钟，单样本 hard timeout 180s（沿用总设计 §）。

## Honest 复现策略（关键纪律，沿用 W3）

当前代码是 `w3-pipeline`（已删 AiServices/@Tool），**v1/v2 无法在当前代码上复现**。复现方式：

1. **打 git tag 锁版本**：`eval/v0`、`eval/v1`、`eval/v2`、`eval/v3` 各指向对应里程碑 commit。
   - v0 = 纯 regex baseline；
   - v1 = `4f7469f`（SpotBugs + CodeSearch，无 hybrid）；
   - v2 = pipeline 重构前的 hybrid + rerank commit（`w2-hybrid-rerank` 路径，落 tag 前确认具体 commit）；
   - v3 = 当前 pipeline（`w3-pipeline`）。
2. **独立 worktree 复现**：每个版本在 `/tmp` 的独立 git worktree 里 checkout 对应 tag、单独 `mvn package` build jar、对 40 样本跑评测，产物 copy 回主树 `eval/reports/`。主树不被污染。
3. **接收红线**：每个版本报告**校验无 `review error`**——有则不出报告（沿用 W3 红线，不许带病出数）。
4. 旧版本 commit 早于 `--suite release` / 重复跑能力的，用旧 jar 对 40 样本目录跑 ×1（显式 `env -u DEBUG` + 手动指定样本目录），report 里 runs=1。

## 两档结果：strict 40 vs fallback 历史（Finding 2 + Q2）

复现是 best-effort，可能某个旧版本（尤其 v1/v2）在 40 样本上 build/跑不通。两档分开存、**绝不在曲线里混用**：

- **strict 40-sample（apples-to-apples）**：成功在 40 样本上跑出的版本 → 写 `eval/reports/v0..v3.json`（覆盖旧 20 样本版，历史锁在 git tag）。曲线图**只用**这一档。
- **fallback 历史**：某版本 40 样本复现不了 → 保留其 20 样本结果为 `eval/reports/<vN>-20sample-historical.json`，**带显式 label 标明「20 样本历史值，不可与 40 样本直接比较」**；只作上下文出现在表格脚注，不进曲线。

## 产出

- `eval/reports/v0..v3.json`：成功复现的版本刷新为 40 样本版（v0 ×1、v1 ×1、v2 ×1、v3 ×3）。
- 复现不了的版本：`<vN>-20sample-historical.json` + 表格脚注说明。
- v0→v3 的 recall / precision / fp_rate / latency 数据（曲线图在 Phase 4 画，仅用 strict 40 档）。

## 验收

- v3 在 40 样本 × 3 runs 上跑完、无 `review error`，report 含方差/稳定性观察。
- v0/v1/v2 要么有 40 样本 ×1 report，要么明确降级为 `*-20sample-historical.json` 并在表里标注原因——**两者必居其一，不伪造**。
- 每份 report config block 标明 suite、pipeline label、实际 runs 数、样本数。
- 指标对比表更新（替换 W3 notes 的 20 样本表），strict 40 与历史值分区呈现、互不混用。

---

# Phase 3 · 调优（产出 v3.1-tuned）

## 目标

以 Phase 2 的 40 样本 release 数据为依据做有针对性的调优，**不动 pipeline 核心架构**，产出调优版 **`v3.1-tuned`**（命名避开总设计已占用的 `v4-stretch`）。

## 调优项 1 · severity 校准（首要）

- **问题**：v3 `severity_accuracy` 回退到低于 v2——LLM 报的 severity 与 ground truth 系统性偏移。
- **诊断步骤**：先从 release 报告里拉出 severity 混淆情况（哪类问题被高估 / 低估），定位偏移模式，再决定改 prompt 还是改 Summarizer。
- **校准位置（按优先级试）**：
  1. `LlmReviewer.SYSTEM` prompt 层：给 severity 明确判定标准（CRITICAL/WARNING/SUGGESTION 各自的判据），减少模型自由发挥。
  2. `Summarizer` 后处理层：对特定 source（如 SpotBugs 高危规则）的 severity 做确定性归一化 / 钳制。
- **验收（基准随 v2 可用性而定）**：
  - 若 **v2-40** 存在（v2 成功在 40 样本上复现）→ `severity_accuracy(v3.1-40) >= severity_accuracy(v2-40)`，apples-to-apples。
  - 若 **v2-40 不存在**（仅 `v2-20sample-historical`）→ 基准改为 `severity_accuracy(v3.1-40) >= severity_accuracy(v3-40)`（同 40 样本档内提升），v2-20 仅作历史上下文引用，**不作为 40 样本的达标线**。
  - 两种情况下都附加硬约束：**precision / recall 相对 v3-40 不回退**（校准不能以伤主指标为代价）。

## 调优项 2 · tool_success_rate 语义澄清

- **问题**：该指标把「SpotBugs 在不可编译样本上的预期 skip」与「analyzer 真失败」混为一谈，指标会误导。
- **现状**：[ToolFindingsProducer](../../../src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducer.java) 当前用字符串 `"ok"` / `"skipped"`。
- **改法**：`ToolStatus` 改用状态枚举——`RAN` / `SKIPPED_EXPECTED`（如样本不可编译的预期跳过）/ `FAILED`（analyzer 真异常）。`Metrics.toolSuccessRate` 把 `SKIPPED_EXPECTED` 排除出分母（或单列），而非记为失败。
- **向后兼容（与 Finding 5 / Phase 4 联动）**：旧报告（含 worktree 里跑出的旧版本）仍是 `ok`/`skipped` 字符串词表。**枚举迁移不回填旧报告**；兼容性在读取侧解决——见 Phase 4 交付物 3 的归一化要求。
- **验收**：报告里 tool 状态可区分预期 skip 与真失败；`tool_success_rate` 反映 analyzer 真实可靠性。单测覆盖三态归类 + 旧字符串词表的读取归一化。

## 调优项 3 · 数据驱动的余量调优（仅当 release 暴露）

- 若 release 数据暴露其它明显问题（如某类 case 召回塌陷、RAG top-k / min-score / 阈值不当、dedup 桶过大/过小），按数据做小步调整并复评。
- **纪律**：每次调一个变量、重跑 release、对比指标决定保留与否；不做投机式批量调参。

## 验收（Phase 3 整体）

- `eval/reports/v3.1-tuned.json` 产出（40 样本 release suite，无 review error）。
- v3 → v3.1-tuned 的指标对比明确：severity_accuracy 提升、主指标不回退。
- 调优改动有对应单测（severity 归一化逻辑、ToolStatus 三态归类）。
- `mvn test` 全绿。

---

# Phase 4 · 交付

## 目标

把项目打磨成「能直接给人看」的状态：README、架构图、评测报告/曲线、demo 脚本、commit 整理。

## 交付物 1 · README 重写

面向使用者 / 面试官，结构：
- **是什么**：一句话定位 + 能力概述。
- **架构**：pipeline 四 stage + RAG + eval 闭环（嵌交付物 2 的图）。
- **怎么跑**：build / review 单仓库 / eval 各 suite 的可复现命令（与 `eval/README.md` 对齐，不重复维护两份真相，README 引用之）。
- **eval 成绩**：v0→v3.1-tuned 指标表 + 曲线图 + **诚实声明**（全合成样本局限）。
- **设计取舍**：为何 pipeline 而非 AiServices 自主 agent（引用 W3 notes 主线）。
- **后续路线**：真实 PR 样本、多 Agent v4-stretch。

## 交付物 2 · 架构图

- pipeline 四 stage 数据流 + RAG 检索 + eval 闭环，**Mermaid**（README 内联渲染）为主，必要时配 ASCII 兜底。
- 至少两张：①运行时 review pipeline；②eval 闭环（samples → agent → Matcher/LlmJudge → Metrics → report）。

## 交付物 3 · 评测报告 + 指标曲线

- 从 `eval/reports/*.json` 生成 v0→v3.1-tuned 的提升曲线（recall / precision / fp_rate / latency）。
- **实现方式**：写一个轻量脚本（读 report JSON → 出 Markdown 表 + 图）。图优先 Mermaid（无需额外依赖、README 可渲染）；若 Mermaid 表达力不足，退而生成静态 SVG/PNG 落 `docs/`。脚本可复现，避免手抄数字。
- 报告含每版本的关键 config（pipeline label、suite、runs、样本数），保证「图里的数字怎么来的」可追溯。
- **状态词表归一化（Finding 5）**：报告生成器读 `eval/reports/*.json` 时，旧版本是 `ok`/`skipped` 字符串、新版本是 `RAN`/`SKIPPED_EXPECTED`/`FAILED` 枚举。生成器要么**只依赖 aggregate metrics**（`tool_success_rate` 等已算好的数）不碰原始 status 词表，要么在读取时把两套词表归一到统一表示。绝不假设所有报告同词表。
- **只用 strict 40 档画曲线**：曲线/对比图只取 40 样本版报告，`*-20sample-historical.json` 仅在表格脚注作历史上下文，不进图（呼应 Phase 2 两档分离）。

## 交付物 4 · demo 脚本 + commit history 整理

- **demo 脚本**：一份 `docs/` 下的可复现命令清单（build → review 一个 sample → 看输出 → 跑 smoke eval），供 user 据此真录屏。agent **不做真录屏**。
- **commit 整理**：W4 的 commit 按阶段组织、message 讲演进叙事（沿用 W1-W3 风格）；不重写历史 tag。

## 验收（Phase 4 整体）

- README 重写完成，命令均经验证可跑，指标数字与 `eval/reports/` 一致。
- 架构图在 README 正常渲染。
- 曲线生成脚本可复现，产物入库。
- demo 脚本可照着跑通。
- W4 学习笔记 `docs/learnings/w4-notes.md` 沉淀（技术细节 / 设计权衡 / 面试 Q&A，沿用 W3 格式）。

---

# 整体验收门槛

```text
mvn test                              全绿（含重复跑聚合、隔离边界、ToolStatus 三态单测）
mvn -q clean package                  成功
40 样本目录齐全 + annotation 合法
v3 / v3.1-tuned 在 40 样本 ×3 上无 review error
v0/v1/v2 各有 40 样本 ×1 report 或明确降级为 *-20sample-historical.json
severity_accuracy(v3.1-40) >= 基准(v2-40 若有，否则 v3-40)，且 precision/recall 不回退
曲线只用 strict 40 档，历史值仅作脚注
README 命令全部验证可跑
```

# 风险与退路

| 风险 | 退路 |
| --- | --- |
| 旧版本（v1/v2）worktree 复现踩坑（依赖 / build 失败） | 优先保证 v3 / v3.1-tuned 在 40 样本上的数；v1/v2 复现不了就降级为 `*-20sample-historical.json`（带「不可与 40 样本比较」label），只作脚注、不进曲线，不伪造 |
| 40×3 release 跑太久 / 频繁超时 | 先 ×1 跑通拿趋势，×3 仅对最终版本；hard timeout + per-sample 重试兜底 |
| severity 校准伤主指标 | 校准是「不回退主指标」的硬约束；伤了就回退该改动，severity 作为已知 caveat 记录 |
| 合成样本被质疑偷看答案 / 偏乐观 | 严格隔离输入 + 诚实声明；真实 PR 样本列后续路线 |
| W4 时间不够 | 交付优先级：README + 评测报告 > 架构图 > demo 脚本；多 Agent 永远最后 |

# 版本命名约定

| 版本 | pipeline label | 含义 |
| --- | --- | --- |
| v0 | （regex baseline） | W1 单 agent + 正则 |
| v1 | `w2-spotbugs-codesearch` | SpotBugs + CodeSearch，无 hybrid |
| v2 | `w2-hybrid-rerank` | hybrid RAG + reranker，pipeline 前 |
| v3 | `w3-pipeline` | 确定性 pipeline（W3 终态） |
| **v3.1-tuned** | `w4-tuned` | W4 调优版（severity 校准 + tool_status 语义） |
| v4-stretch | `w4-multiagent` | （仅余量）多 Reviewer 并发 |
