# W4 学习笔记

> W4 的主线不是继续堆功能，而是把 W3 的方向性结果升级成可复现的 release 结论，并在不牺牲 recall / precision 的前提下完成 severity 调优。
>
> 一句话总结：**先让评测可信，再让调优受门槛约束，最后把结果整理成可以复现和讲清楚的交付物。**

**Spec：** [`../superpowers/specs/2026-06-05-code-review-agent-w4-design.md`](../superpowers/specs/2026-06-05-code-review-agent-w4-design.md)
**Plan：** [`../superpowers/plans/2026-06-05-code-review-agent-w4.md`](../superpowers/plans/2026-06-05-code-review-agent-w4.md)

---

## T1 · 多次运行聚合：均值不能代替波动

### 技术细节

W3 的评测每个样本只运行一次。对于包含 LLM 的系统，单次结果可能受到模型采样、服务状态和网络重试影响，因此 W4 让 `--runs` 真正控制重复次数，而不只是写进配置。

`EvaluationRunner` 的聚合层次变成：

1. 每个 run 遍历完整 sample suite。
2. 每个 sample 产生独立 `SampleMetrics`，run ID 写入结果。
3. 每个 run 先聚合 recall、precision、FP rate、severity accuracy 等指标。
4. 最终报告记录各 run 指标、跨 run 均值和标准差。

报告新增：

- `per_run_metrics`：每次完整运行的聚合指标。
- `metrics_std_dev`：各指标的 run 间标准差。
- 展平后的 per-sample run ID：便于定位某个样本在哪次运行中发生波动。

### 设计权衡

| 选择 | 原因 |
| --- | --- |
| 先按 run 聚合，再计算均值 | 避免把所有 sample-run 简单混成一池，保留“每次完整评测”的语义 |
| 同时报告均值和标准差 | 均值回答总体水平，标准差回答结果是否稳定 |
| release 默认 3 runs | 能观察波动，同时控制模型调用成本；它不是统计显著性的证明 |

### 面试 Q&A

**Q：为什么不能只报告 120 次 sample-run 的总体 recall？**

- **A**：因为那会抹掉 run 间波动。三个 run 都接近 75% 和一个 run 95%、两个 run 65%，总体均值可能相近，但系统稳定性完全不同。`per_run_metrics + metrics_std_dev` 让 release 数字不再依赖一次幸运运行。

---

## T2 · ToolStatus 三态：跳过不等于失败

### 技术细节

W3 的 `tool_success_rate` 把 SpotBugs 在不可编译样本上的预期跳过和 analyzer 真正异常混在一起。W4 引入 `ToolRunState`：

- `RAN`：工具成功执行。
- `SKIPPED_EXPECTED`：由于样本缺依赖、没有可分析字节码等已知条件而跳过。
- `FAILED`：工具执行过程中发生非预期异常。

`ToolFindingsProducer` 负责把 analyzer 结果转成明确状态，并对异常做 `FAILED` 兜底。评测只把真正失败计入 tool failure，预期跳过不再拉低成功率。

### 设计权衡

二态 `ok / failed` 简单，但无法表达 best-effort 工具链的真实语义。三态增加少量模型和报告复杂度，却能避免指标错误地惩罚合理降级。

### 面试 Q&A

**Q：为什么预期跳过不算失败？**

- **A**：SpotBugs 需要可编译字节码，而评测样本故意包含不完整源码。主链路仍然可以靠 diff、regex 和 LLM 完成 review。把这种可解释降级算成失败，会让 `tool_success_rate` 衡量样本可编译性，而不是工具可靠性。

---

## T3 · 隔离边界：评测真相不能泄漏给 Agent

### 技术细节

每个评测样本同时包含：

- `diff.patch` 和 `source-before/`：Agent 可以看到的输入。
- `annotation.json`、`source-after/`、`meta.json`：评测器使用的 ground truth 和元数据。

`IsolationBoundaryIT` 构造带有诱饵内容的样本，验证传给 `agent.review` 的请求只包含允许输入，不包含 annotation、修复后源码和标签信息。

这条测试保护的是评测可信度：即使目录结构中同时存在答案，Agent 也不能通过路径或上下文意外读到答案。

### 设计权衡

只靠代码审查确认“应该没有泄漏”不够。隔离边界属于跨模块不变量，应由集成测试直接证明。

### 面试 Q&A

**Q：为什么数据泄漏会比普通 bug 更危险？**

- **A**：普通 bug 通常让指标下降，数据泄漏反而会让指标异常变好。如果没有隔离测试，团队可能把作弊得到的高分当成架构进步，后续所有调优结论都会失真。

---

## T4 · 样本从 20 扩到 40：不仅补正例，也要给 precision 压力

### 技术细节

W4 新增 20 个样本：

- `reverse-021..030`：10 个 defect-introducing 样本，补足 hard 难度和稀缺类别。
- `synthetic-001..010`：10 个边界样本，包含 true negative、near miss、跨文件和行号复杂场景。

新增 `SampleSetValidationTest`，守住以下约束：

- release suite 恰好包含 40 个样本。
- 每个样本必需文件齐全。
- category、difficulty、severity 等字段合法。
- 分布基线可审查，避免后续无意改变评测集结构。

详细分布见 [`w4-sample-distribution.md`](w4-sample-distribution.md)。

### 设计权衡

只增加更多明显缺陷会让 recall 更容易提升，却无法检验误报。加入 true negative 和 near miss，才能对 precision 形成真实压力。

这 40 个样本仍然是手工构造集，适合项目内部回归和版本比较，不代表任意真实仓库上的生产准确率。

### 面试 Q&A

**Q：为什么 synthetic sample 仍然有价值？**

- **A**：它能精确控制单一变量，适合复现边界条件和防止回归。但它缺少真实 PR 的上下文噪声、代码风格和缺陷分布，所以必须明确它是工程回归集，不是生产泛化能力证明。

---

## T5 · Strict release 与 historical 分档：不可比的数据不要硬画在一起

### 技术细节

W4 尝试让旧版本在当前 40 样本上重跑，但旧代码触发 review-error redline，无法产出可信 strict-40 报告。最终采用两档：

- **Strict release**：当前代码、40 样本、明确 runs 配置、无 review error。
- **Historical context**：保留旧版本当时的 5/20 样本报告，仅用于解释演进历史。

`scripts/plot_metrics.py` 只把至少 40 样本的 strict 报告画入 release 曲线，historical 报告单独列出。

### 设计权衡

为了让图表“完整”而把不同样本集的数字放在一条线上，会制造虚假的版本趋势。缺失可比数据时，诚实标注不可比，比补一个看似漂亮的数字更重要。

### 面试 Q&A

**Q：为什么不强行修旧版本，让它们也跑 40 样本？**

- **A**：那会改变旧版本本身，得到的不再是原始 baseline。W4 选择保留历史报告，并把 strict release 比较限制在同一评测协议下的 v3 与 v3.1-tuned。

---

## T6 · Severity 调优：失败方案也必须受 release 门槛约束

### 技术细节

v3 strict release 的主要问题是 `severity_accuracy=50.1%`。最先尝试的是 prompt-only 校准：在 reviewer prompt 中强化 CRITICAL / WARNING / SUGGESTION 判据。

结果说明 prompt 调优不是免费收益：

- severity accuracy 可以上升；
- recall 和 precision 却可能回退；
- 更宽松的 category prompt 还产生过未知 `COMPILER_ERROR`，触发解析失败。

W4 最终采用确定性方案：

1. `Summarizer` 按 category 校准 severity：
   - `SECURITY` → `CRITICAL`
   - `PERFORMANCE / STABILITY / CONCURRENCY / TEST` → `WARNING`
   - `STYLE / OTHER` 保留原 severity
2. `Category.OTHER` 作为未知枚举的 JSON 默认值，避免模型偶发新类别击穿整个 review。
3. `LlmJudge` 判断“是否同一问题”时不再看到 Agent severity；问题匹配与严重级别评分分开。
4. `RegexAnalyzer` 回填四类高置信规则：`disabled-test`、`secret-logging`、`user-controlled-token-ttl`、`silent-null-return`。
5. `Summarizer` 去重键改为 `file | lineBucket | category`，避免同一问题因标题措辞不同重复计数。

### 为什么 Judge 必须与 severity 解耦

匹配阶段回答的是“Agent finding 与 expected issue 是否描述同一个缺陷”。severity 是否正确是另一个评分维度。

如果 Judge 在匹配时看到 severity，可能因为严重级别不同而拒绝本应匹配的问题，导致同一个错误同时变成 FN 和 FP，并污染 recall、precision 与 severity accuracy。

### 为什么只补高置信静态规则

静态规则会直接影响 precision。W4 只加入能从新增代码中较明确判断、且不依赖样本 ID 或 annotation 的通用规则。规则必须通过正常 diff 输入触发，不能针对 benchmark fixture 写特例。

### 面试 Q&A

**Q：为什么 severity 校准放在 Summarizer，而不是继续调 prompt？**

- **A**：severity 是有限类别到有限等级的映射，确定性规则更稳定、可测试、可审计。prompt 适合发现和解释开放式问题，但不适合承担本可由代码明确表达的归一化职责。

**Q：如何防止为了过 benchmark 而过拟合？**

- **A**：规则不能引用样本 ID、annotation 或 ground truth，只能基于 Agent 正常可见的 diff；每条规则都要表达可复用的代码风险，并用独立单测验证。最终还要用 recall、precision 和 redline 同时约束，不能只追一个指标。

---

## T7 · 最终 release 结果与交付

### 正式结果

| Version | Samples × Runs | Recall | Precision | FP rate | Severity acc. | Latency | Tool success |
| --- | --- | --- | --- | --- | --- | --- | --- |
| v3 / w3-pipeline | 40 × 3 | 70.3% | 61.9% | 38.1% | 50.1% | 4.89s | 100.0% |
| v3.1-tuned / w4-tuned | 40 × 3 | 75.7% | 67.7% | 32.3% | 77.3% | 5.78s | 100.0% |

`v3.1-tuned` 通过 no-review-error redline，并改善 recall、precision、FP rate 和 severity accuracy。代价是平均延迟从 4.89s 上升到 5.78s；这项回退应明确展示，不能用“所有指标都提升”概括。

### 交付物

- [`../eval-metrics.md`](../eval-metrics.md)：由报告自动生成的指标表和曲线。
- [`../architecture.md`](../architecture.md)：运行时 pipeline 与 eval 闭环架构图。
- [`../demo-script.md`](../demo-script.md)：可复现的演示命令。
- `eval/reports/v3.json` 与 `eval/reports/v3.1-tuned.json`：strict 40 × 3 正式报告。

### W4 的核心工程结论

1. **评测协议本身是产品的一部分**：重复运行、隔离边界、redline 和样本校验决定数字是否可信。
2. **主指标约束调优方向**：severity 提升不能以 recall / precision 回退为代价。
3. **能确定性表达的职责应从 LLM 收回代码**：状态分类、severity 归一化、去重和高置信规则都更适合可测试逻辑。
4. **诚实展示不可比数据和负面结果**：historical 报告、失败调优和延迟回退都应保留。

## 下一步

- 引入真实公开 PR 样本，并与 synthetic release fixtures 分开报告。
- 按 category、difficulty 和 sample source 输出分层指标，避免总体均值掩盖局部问题。
- 只有在真实数据基线稳定后，再评估 multi-reviewer `v4-stretch` 是否值得增加成本和复杂度。
