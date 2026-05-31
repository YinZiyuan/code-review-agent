# Code Review Agent · W3 设计

**日期**：2026-05-31
**作者**：yzy
**分支**：`feat/w2`（W3 实际工作将在新分支上进行，命名由 plan 决定）
**前置 spec**：[`2026-05-17-code-review-agent-design.md`](2026-05-17-code-review-agent-design.md) §1 W3、§2 (agents/pipeline)、§3 流程 2A
**前置 plan**：[`2026-05-22-code-review-agent-w2.md`](../plans/2026-05-22-code-review-agent-w2.md)
**前置笔记**：[`docs/learnings/w2-notes.md`](../../learnings/w2-notes.md)

---

## 目标

把 W2 在单 Agent + AiServices + 工具自决形态下打开的能力（SpotBugs、CodeSearchTool、Hybrid RAG、Reranker、Citation 元数据），落到两个可信交付点：

1. **W3a**：在不动 W2 架构形状的前提下，补齐 W2 笔记 §W2 总结里列的"W3 前最该补的三件事"，产出 `v1-spotbugs-search.json` 和 `v2-rag-hybrid.json` 两份干净 eval 报告。
2. **W3b**：执行 spec §1 W3 的默认目标——把单 Agent 拆成显式 pipeline（`DiffAnalyzer → ToolFindings → LlmReviewer → Summarizer`），产出 `v3-pipeline.json`。

不做 v4-stretch 多 Agent。`agents/reviewers/` 和 `orchestration/` 包不建。多 Agent 在 README 里作为后续路线说明，符合源 spec §1 风险表的退路。

---

## 非目标

- 不扩样本量（保持 20 个，40 个样本是 W4）。
- 不引入新模型 / 新 retriever / 新静态分析器。
- 不重构 W1 `DiffParser` / `Matcher` / `Metrics` / `EvaluationRunner` 的契约——`ReviewResult` schema 不变，eval 报告 schema 不变。
- 不把 eval 接进 CI。

---

## §1 · 总体架构与路线

### 1.1 两阶段路线

```
W3a · 单 Agent 上的稳定性补丁（出 v1 / v2 干净 report）
  └─ JsonRepair · Citation 后处理 minimal · Eval 环境硬化 · 复现命令固化

W3b · pipeline 化（出 v3 report）
  └─ DiffAnalyzer  →  ToolFindings  →  LlmReviewer  →  Summarizer
       (吸收           (Regex +         (纯 ChatModel       (确定性去重 +
        CodeSearch)     SpotBugs)        + RAG context)      补 citation)
```

W3a 先于 W3b，理由：

- W2 的真实指标先有交代，pipeline 化的代价不和 JSON 修复纠缠。
- W3a 写的 `JsonRepair`（Java 侧 capture + retry）和 citation 后处理逻辑，在 W3b 时被吸收进 `LlmReviewer` 和 `Summarizer`，不是重复劳动。
- 中断风险更低：W3a 完成时仓库本身已是一次可对外交付的进展（v1/v2 报告齐了），W3b 即便延期也不会回退已交付的指标。

### 1.2 包结构（新增）

```
src/main/java/dev/langchain4j/example/codereview/
├── agents/
│   ├── CodeReviewAgent.java          W3 保留为 façade interface，对外签名不变
│   └── pipeline/                     ◀── W3b
│       ├── PipelineCodeReviewer.java   pipeline 入口；CodeReviewAgent 委托
│       ├── DiffAnalyzer.java
│       ├── ToolFindings.java
│       ├── LlmReviewer.java
│       ├── Summarizer.java
│       └── ReviewContext.java          不可变快照
└── infra/
    └── JsonRepair.java               ◀── W3a 引入，W3b LlmReviewer 复用
```

不建 `orchestration/`、`agents/reviewers/`。

### 1.3 版本对照

| Report 文件 | version | pipeline label | 产出阶段 |
| --- | --- | --- | --- |
| `eval/reports/v1-spotbugs-search.json` | `v1-spotbugs-search` | `w2-spotbugs-codesearch` | W3a |
| `eval/reports/v2-rag-hybrid.json` | `v2-rag-hybrid` | `w2-hybrid-rerank` | W3a |
| `eval/reports/v3-pipeline.json` | `v3-pipeline` | `w3-pipeline` | W3b |

`v0-baseline.json` 已在 W1 提交，不重跑。

---

## §2 · W3a 单 Agent 稳定性补丁

目标：保留 W2 的单 Agent + AiServices + 工具自决形状，只在外围加四块补丁。

### 2.1 补丁 1 · JsonRepair —— 拦截 OutputParsingException

**接入点**：不动 `CodeReviewAgent` 接口。在 `AgentConfig` 里给 `AiServices` 包一层 façade，调用 review 时 try/catch；捕到 `dev.langchain4j.service.output.OutputParsingException` 时，取原始 LLM 输出，发一次小 prompt 修复成合法 `ReviewResult` JSON。

```
review(diff)
    │
    ├── 成功 → 返回 ReviewResult
    └── OutputParsingException
            │
            ▼
        JsonRepair.repair(rawOutput)
            │ 单次小 prompt：
            │ "下面这段输出本应是 ReviewResult JSON，请修成合法 JSON，
            │  不要新增 findings，不要改语义。"
            │
            ├── repair 成功 → 反序列化 → 返回 ReviewResult
            └── repair 仍失败 → 回抛原异常给 EvaluationRunner（记 review error）
```

**关键约束**：

- repair prompt 明确禁止"补内容"，只能"修格式"。
- temperature 0、只重试一次。
- 用同一个 ChatModel（不引入 judge model），避免引入新的 dep。

**为什么不上 JSON mode**：Moonshot OpenAI-compat 对 `response_format` 支持不稳，W3a 不动模型层。JSON mode 留到 W3b 的 `LlmReviewer` 直接用 ChatModel 时再决定。

### 2.2 补丁 2 · Citation 后处理 minimal 版

**问题**：W2 已经有 `CitationTracker` 把 `Content.metadata` 转成 `Citation`，但 `ReviewResult.findings[].citations` 是否填写仍依赖 LLM 在 prompt 引导下自觉填，不可审计。

**补丁**：在 `JsonRepair` 同一层 façade 里，review 完成后做后处理：

- 从这一次 review 的 RAG 检索记录里拿到所有命中的 citation（`CitationTracker` 已经能给）。
- 对每个 finding，如果 `citations` 为空，但 description/title 文本里包含某个 citation section 的关键词，就把这个 citation 注入 `finding.citations`。
- 不做替换，只做补。LLM 已经填的尊重。

**边界**：W3a 这版用关键词包含做匹配，朴素但可复现；W3b 时 `Summarizer` 接管这段，可以升级为结构化链路（`LlmReviewer` prompt 里直接收到带 ID 的 citation 候选列表）。

### 2.3 补丁 3 · Eval 运行环境硬化

集中在 `EvalCommand`：

| 项 | 处理 |
| --- | --- |
| `DEBUG` 环境变量污染 Spring 的 `debug` property | `EvalCommand` 入口检测并显式 `System.clearProperty("debug")`，同时打 warn 日志告诉用户；不依赖用户记得 `env -u DEBUG` |
| Moonshot 超时 | `application.yml` 把 `langchain4j.open-ai.chat-model.timeout` 从 60s 提到 90s |
| 长跑被网络波动击穿 | `EvaluationRunner` 单 sample 内部加一次重试（仅对超时/连接异常，不对业务异常） |
| Sample 子集 | `EvalCommand` 加 `--samples reverse-001,reverse-002` 和 `--suite smoke\|dev\|release`；默认行为不变 |

### 2.4 补丁 4 · v1 / v2 复现命令固化

把 W2 笔记里散落的命令写进 `eval/README.md`（如果不存在则新建最小版本），并在 `EvalCommand --help` 里给例子。

### 2.5 验收

W3a 结束时仓库里有：

- `eval/reports/v1-spotbugs-search.json`
- `eval/reports/v2-rag-hybrid.json`

两份都带 pipeline label、tool_status，且 per-sample 里没有"因 JSON 解析失败 review error"的样本。如果还有，回到补丁 1 加强 repair。

---

## §3 · W3b pipeline 组件接口与数据流

四个组件全部 `@Component`，`PipelineCodeReviewer` 作为 pipeline 入口，直接实现 `CodeReviewAgent` 接口（不再由 LangChain4j `AiServices` 动态代理生成）。对外签名保持不变（`review(diff) → ReviewResult`），CLI 和 `EvaluationRunner` 都不用动。`@SystemMessage` 注解从 `CodeReviewAgent` 上移除（不再由 AiServices 消费），其内容作为 prompt 常量提供给 `LlmReviewer`。

### 3.1 数据形状

**`ReviewContext`**（不可变，DiffAnalyzer 产出）：

```java
record ReviewContext(
    String rawDiff,
    List<FileDiff> fileDiffs,                          // 复用 W1 DiffParser.FileDiff
    Map<String, List<CodeSnippet>> contextByFile,      // CodeSearch 拉到的上下文
    Path sourceRoot                                    // sample = source-before/，real = repo root
) {}

record CodeSnippet(String file, int line, String text) {}
```

`contextByFile` 的 value 是 DiffAnalyzer 用变更函数/类名 grep 出来的命中行，每文件上限 20 条（收紧自 W2 CodeSearchTool 的 50）。

**`ToolFindings`**（不可变，ToolFindings 组件产出）：

```java
record ToolFindings(
    List<Violation> violations,       // Regex + SpotBugs 合并去重
    List<ToolStatus> statuses         // 直接是 ReviewResult.tool_status 的形态
) {}
```

`Violation` 复用 W1/W2 的 `StaticAnalyzer.Violation`，不重新定义。

### 3.2 组件职责

**DiffAnalyzer**（确定性，无 LLM）

- 输入：`String rawDiff`, `Path sourceRoot`
- 步骤：
  1. `DiffParser.parse(rawDiff)` → `List<FileDiff>`
  2. 从 fileDiff 的新增行里抽出 Java identifier 候选（简单正则：方法名 `\w+\s*\(`、首字母大写的类名 token）
  3. 对每个 candidate 用 `CodeSearchTool` 的底层方法（不再作为 `@Tool` 暴露给 LLM）grep 一遍 sourceRoot
  4. 装进 `contextByFile`
- 失败策略：candidate 抽取失败 / source 目录不存在 → `contextByFile` 留空，不抛
- 输出：`ReviewContext`

**ToolFindings**（确定性，无 LLM）

- 输入：`ReviewContext`
- 步骤：
  1. 跑 `RegexAnalyzer.analyze(fileDiffs)`
  2. 尝试 `SourceCompiler.compile(sourceRoot)` → `Optional<Path>`，能编就跑 `SpotBugsAnalyzer.analyzeWithSource(fileDiffs, classesDir)`，不能编就标 skipped
  3. 合并 violations，按 `file+line+rule` 去重
  4. 产出 `List<ToolStatus>` 直接形态化（不再依赖 LLM 转写 `[tool_status]` 文本）
- 输出：`ToolFindings`
- 这里替代 W2 `RuleCheckerTool` 作为 `@Tool` 给 LLM 调用的形态——逻辑不变，调用方从 LLM 变成 pipeline。

**LlmReviewer**（单次 LLM 调用，不调 tool）

- 输入：`ReviewContext`, `ToolFindings`
- 步骤：
  1. 用 `HybridRetriever` + `LlmReranker`（复用 W2）从 `rawDiff + violations` 拼出的 query 拉 top-k RAG chunks，连带 citation_id 列出
  2. 拼 prompt：四块——diff、tool violations、code context snippets、RAG citations（带 ID 编号）
  3. 调 `ChatModel.chat(messages)` 一次，取回原始文本响应
  4. 把响应文本交给 `JsonRepair.parseOrRepair(rawText) → ReviewResult`：先直接 Jackson 反序列化，失败时走 repair retry（同 W3a 路径），再失败回抛
- prompt 关键约束：finding 里的 `citations[].id` 必须从给定 RAG citation 候选 ID 选；不得新发明 citation。这是 W3a 补丁 2 → W3b 升级版的 citation 治理。
- 输出：`ReviewResult` 草稿（未经 Summarizer 清洗）

**Summarizer**（确定性，无 LLM）

- 输入：LlmReviewer 草稿 `ReviewResult` + `ToolFindings` + 本次 RAG citation 候选列表
- 步骤：
  1. **去重**：finding 按 `(file, line/5 取整, severity, title 前 30 字符)` 分桶，同桶保留 severity 最高的一条
  2. **补 finding**：对 `ToolFindings.violations` 里 LlmReviewer 草稿未覆盖到的高优先级 violation（`CRITICAL`/`WARNING`），机械生成 finding 补进去，`source` 字段标 `regex` 或 `spotbugs`
  3. **补 citation**：对 `finding.citations` 为空的 finding，沿用 W3a 的关键词匹配从候选 list 补
  4. **排序**：按 severity (`CRITICAL` → `SUGGESTION`) → file → line
  5. **写 tool_status**：直接用 `ToolFindings.statuses`，不再依赖 LLM 转写
- 输出：最终 `ReviewResult`

### 3.3 端到端数据流

```
review(diff, repoOrSampleRoot)
    │
    ▼
DiffAnalyzer ──► ReviewContext
    │
    ▼
ToolFindings ──► ToolFindings(violations, statuses)
    │
    ▼                                                ┌── HybridRetriever ── LlmReranker
LlmReviewer ─── prompt(ReviewContext, ToolFindings,◄┤
    │                  RAG citations)                └── CitationTracker 给候选 ID
    │
    │ ChatModel.chat()
    │ JsonRepair.guard()
    ▼
ReviewResult 草稿
    │
    ▼
Summarizer ──► 最终 ReviewResult
```

**LLM 调用次数（每个 review）**：

- 默认 1 次（LlmReviewer）
- + rerank 1 次（W2 已有，配置项可关）
- + JsonRepair 失败时再 1 次（极少触发）

比 W2 的"LLM 自决多轮 tool"路径调用次数下降，更可预测。

### 3.4 与 W2 工具的关系

| W2 组件 | W3b 命运 |
| --- | --- |
| `GitDiffTool`（`@Tool`） | 不再暴露给 LLM；保留为工具类，`ReviewCommand` 自己调它拿 diff |
| `RuleCheckerTool`（`@Tool`） | 删除 `@Tool` 注解，逻辑搬进 `ToolFindings` |
| `CodeSearchTool`（`@Tool`） | 删除 `@Tool` 注解，逻辑搬进 `DiffAnalyzer` |
| `RegexAnalyzer` / `SpotBugsAnalyzer` / `SourceCompiler` | 原样复用，由 `ToolFindings` 调 |
| `HybridRetriever` / `LlmReranker` / `CitationTracker` | 原样复用，由 `LlmReviewer` 调 |
| `AgentConfig` 里的 `AiServices.builder(...).tools(...).contentRetriever(...)` | 删除整段；保留 `ChatModel` Bean 和 `HybridRetriever` Bean |
| `CodeReviewAgent`（`@SystemMessage`） | 保留为接口（去掉 `@SystemMessage`，由 `PipelineCodeReviewer` 直接实现）；prompt 字符串作为常量被 `LlmReviewer` 复用 |

`@Tool` 注解全部移除是 pipeline 化的核心信号——LLM 不再有"决定调谁、调几次"的权力。

---

## §4 · eval、版本管理、测试

### 4.1 eval 与版本对照

W3 一共产出 3 份新报告：

| 文件 | version | pipeline label | 怎么跑 |
| --- | --- | --- | --- |
| `eval/reports/v1-spotbugs-search.json` | `v1-spotbugs-search` | `w2-spotbugs-codesearch` | W3a 收尾，单 Agent + W3a 补丁；20 个样本 × 1 次 |
| `eval/reports/v2-rag-hybrid.json` | `v2-rag-hybrid` | `w2-hybrid-rerank` | 同上，rerank/RAG 全开 |
| `eval/reports/v3-pipeline.json` | `v3-pipeline` | `w3-pipeline` | W3b 收尾，pipeline 入口；20 个样本 × 1 次 |

**不跑 release 档 (40 × 3) 的理由**：源 spec §5.7 的 release 档要 40 个样本，W2 只有 20 个，40 是 W4 才补的。W3 跑 dev 档（20 × 1）足够支持 v0 → v3 的演进对比，README 表写 "20-sample mid-evaluation"，W4 再补 release 档。

**复现协议**：每个 report 必须能从命令复现，命令固化在 `eval/README.md`。所有 eval 命令都走新版 `EvalCommand`，DEBUG 清理走代码、不靠用户记得 `env -u DEBUG`。

### 4.2 版本管理

每个 report 已经记录 git commit / config hash / pipeline label（W2 已有），W3 不新增 schema 字段。三个 report 的复现性靠：

- **v1 / v2**：W3a 完成时（包含补丁但还没拆 pipeline）的 commit，对应 git tag `w3a-eval-baseline`
- **v3**：W3b 完成时的 commit，对应 git tag `w3-pipeline`

不打 tag 也能复现（commit hash 已存进 report），tag 让 README 引用更友好。

### 4.3 测试策略

**单元测试**（新增，约 8 个）：

| 测试 | 测什么 |
| --- | --- |
| `JsonRepairTest` | 合法 JSON 直通；缺逗号/未转义的 JSON 调 mock LLM 修复成功；repair 仍不合法时回抛 |
| `DiffAnalyzerTest` | identifier 抽取正确；source root 缺失时 contextByFile 为空但不抛 |
| `ToolFindingsTest` | Regex + SpotBugs 合并去重；SpotBugs 跳过时 statuses 里有 skipped 项 |
| `LlmReviewerTest` | mock ChatModel 返回 JSON → 反序列化成草稿；返回坏 JSON → 触发 JsonRepair |
| `SummarizerTest` | 三种核心行为各一条：去重、补 finding、补 citation；外加 severity 排序 |
| `ReviewContextTest` | 不可变性（试图修改 list 抛 `UnsupportedOperationException`） |
| `CitationInjectionMinimalTest`（W3a） | keyword-match injection：finding 文本含 section keyword → citation 被注入；无关 finding 保持空 citations |

**集成测试**（新增 2 个）：

| 场景 | 验证 |
| --- | --- |
| `PipelineCodeReviewerIT` | mock ChatModel + 真实 RAG/分析器，跑一个 fixture diff，从 façade `CodeReviewAgent.review(diff)` 进，验最终 `ReviewResult` 含 tool_status、finding 来自 LLM + ToolFindings 合并、citation 已填 |
| `EvalCommandIT` | mock ChatModel，跑 `eval --suite smoke --samples reverse-001`，验报告文件生成且 pipeline label 正确 |

**不动的测试**：W1/W2 的 `DiffParserTest` / `RegexAnalyzerTest` / `SpotBugsAnalyzerTest` / `HybridRetrieverTest` / `LlmRerankerTest` / `MatcherTest` / `MetricsTest` 全部保留不改。

**eval 不进 CI**（沿用 W1/W2 决策，源 spec §6.5）。

### 4.4 文档产物

W3 结束时仓库新增/更新：

- `docs/superpowers/specs/2026-05-31-code-review-agent-w3-design.md`（本 spec）
- `docs/superpowers/plans/2026-05-31-code-review-agent-w3.md`（writing-plans 产出）
- `docs/learnings/w3-notes.md`（task-by-task 学习笔记）
- `README.md` 指标表加 v3 行；架构图替换为 pipeline 版
- `CLAUDE.md` Roadmap 段把 W2 (current) 改为 W3 (current)，pipeline 一段从"未来"改为"现状"

---

## §5 · 风险与退路

| 风险 | 退路 |
| --- | --- |
| W3a 的 `JsonRepair` 仍救不回 sample（repair 输出仍坏） | 把 repair prompt 改为更激进的 schema-by-schema 修复；最坏情况承认该 sample review error，不算 FN，per-sample 报告里显式标 |
| W3b pipeline 化后 v3 指标反而比 v2 差 | 原因多半是 Summarizer 补 finding 引入了机械 FP；调高 violation 进入 finding 的阈值（只补 CRITICAL，不补 WARNING）；最坏 v3 不交付，README 用 v2 收尾 |
| Moonshot timeout 提到 90s 仍打不动 20 个样本 | EvalCommand 支持 `--samples` 子集分批，分两次合并 report |
| DiffAnalyzer 的 identifier 抽取漏召回 | 不阻塞 pipeline；contextByFile 为空时 LlmReviewer 仍可工作，只是 prompt 少一块上下文 |

---

## §6 · 验收

W3 结束时满足：

- `eval/reports/v1-spotbugs-search.json` / `v2-rag-hybrid.json` / `v3-pipeline.json` 三份齐全
- per-sample 报告里没有"JSON parse error"导致 sample 失败
- 所有 finding 的 citations 字段：要么来自 LLM 填写并被 prompt 限制在候选 ID 集合内，要么由 Summarizer 从命中 RAG 候选补入；不存在"LLM 凭空编 citation"
- pipeline 化后 `@Tool` 注解仅保留在不参与 review 主链路的地方（即：全部移除）
- 单元测试 + 集成测试通过，CI 绿
- README、CLAUDE.md、w3-notes.md 同步更新
