# Code Review Agent — 一个月演进方案设计

**日期**：2026-05-17
**作者**：yzy
**状态**：Draft → Pending user review

---

## 目标与约束

| 维度 | 设定 |
| --- | --- |
| **核心目标** | 求职作品 / 面试展示（Java 后端转 AI 应用方向） |
| **时间窗口** | 1 个月 |
| **覆盖范围** | 广度优先，跑完 `doc/source.md` 全部 5 个阶段 |
| **展示形式** | GitHub README + 架构图 + 评测报告 + 录屏（asciinema），CLI 即可 |
| **技术栈基线** | Java 17 + LangChain4j（版本由 pom 属性锁定）+ Kimi（moonshot-v1-8k）+ BGE-small-en-v15-quantized |
| **新增基线** | Spring Boot 3.5.x + picocli + SpotBugs + Lucene |

**最大差异化**：**评测驱动开发（Eval-first development）**——W1 末尾就有第一个 baseline 数字，之后每加一个能力跑一次评测，所有改进都有数字背书。

**交付优先级**：

1. **必须交付**：结构化 review 输出、可复现评测框架、20+ 样本 baseline/v1/v2 指标、README 展示。
2. **强烈建议交付**：40 样本 release 评测、Hybrid RAG + 引用溯源、SpotBugs 可降级集成。
3. **可选增强**：W3 多 Agent。多 Agent 是展示亮点，不是核心价值；如果挤压评测可信度，优先砍多 Agent。

---

## §1 · 整体路线 + 周计划

### W1（基础 + 评测启动）

**前 2 天 · 阶段 1 收尾（5 个小问题）**

1. `RuleCheckerTool` 行号错（用 diff 行号当文件行号）→ 引入 `DiffParser` 解析 hunk header
2. `GitDiffTool` 与 `RuleCheckerTool` 重复 fork `git` → 抽 `GitClient`
3. Diff 硬截断 8000 字符 → 改"按文件切分 + 大文件摘要"
4. RAG 启动慢且不持久化 → `EmbeddingCache` 序列化到本地
5. Agent prompt 与 tool 签名不一致 → 修 prompt

**后 5 天 · 阶段 5 启动（评测先行的核心，收窄版）**

- 写评测框架：`EvaluationRunner` + `Metrics`
- 定义结构化输出：`ReviewFinding` JSON 作为内部唯一评测格式，Markdown 只做最终渲染
- 采集并人工标注 5 个反向构造 PR 样本
- 跑出 baseline → README 写「v0 baseline: 召回 X%, 准确 Y%」

W1 不追求样本量，追求评测链路可信：`DiffParser` 行号、`Metrics` 公式、`EvaluationRunner` report 必须先稳定。

### W2（工具 + RAG 强化）

**前 3 天 · 阶段 2**

- 集成 SpotBugs（可构建则运行；不可构建则降级 regex / 简单 AST，不阻塞 review）
- `CodeSearchTool`：基于本地仓库做 grep + AST 简化版
- 样本补到 20 个反向构造，人工审核 annotation
- 跑评测 → 记录 v1 指标

**后 4 天 · 阶段 3**

- 知识库扩到 8-10 篇（Java/SQL/安全/性能/接口/异常/并发/测试）
- 混合检索（BM25 + 向量）
- LLM-as-reranker
- 引用溯源：报告每条建议附「依据：xxx 规范 §X」
- 跑评测 → 记录 v2 指标

### W3（Pipeline 化 + 可选多 Agent）

默认目标：先把单 Agent pipeline 化，形成稳定 v3：

- `DiffAnalyzer`：确定性解析 diff、文件、变更行、周边上下文
- `ToolFindings`：汇总 Regex / SpotBugs / CodeSearch 的确定性发现
- `LlmReviewer`：基于 diff + tool findings + RAG 引用做 review
- `Summarizer`：去重、排序、补引用、输出 `ReviewFinding`

可选增强：如果 W1-W2 没有延期，再做 5 个 Agent：

- `DiffAnalyzer` · 理解变更，产出 ReviewContext
- `SecurityReviewer` · 查安全
- `PerformanceReviewer` · 查性能
- `TestReviewer` · 查测试覆盖
- `Summarizer` · 汇总 + 去重 + 排序

3 个 Reviewer 并发，超时降级。跑评测 → 记录 v4-stretch 指标。若延期，v3 就是 tuned pipeline，README 解释多 Agent 是后续路线。

### W4（评测补足 + 调优 + 交付）

**前 3 天**：补 10 真实手工 + 10 合成边界 → 40 个样本。用 git tag 或 pipeline feature flag 重跑全部主线版本（v0/v1/v2/v3）→ 画指标提升曲线图；若实现了多 Agent，再额外跑 v4-stretch。

**中 2 天**：针对评测中暴露的问题做调优（prompt / 工具阈值 / RAG 参数）

**后 2 天 · 交付**：README 重写 + 架构图 + 评测报告 + 录屏 + commit history 整理

### 风险与退路

| 风险 | 退路 |
| --- | --- |
| W1 评测脚本写得慢 | 砍到 5 个样本先跑通 baseline，W2/W4 再补 |
| W2 SpotBugs 集成有坑 | 改为“可构建才跑”的可选工具，退回正则 + 简单 AST |
| W3 多 Agent 编排复杂度爆炸 | 不做多 Agent，保留单 Agent pipeline + tuned v3 |
| W4 时间不够 | 优先 README + 评测报告，录屏放 W5 |
| 历史版本不可复现 | 每个里程碑打 git tag；或者用 `--pipeline` 显式开关锁能力集 |
| 评测被质疑“偷看答案” | 严格隔离 agent 输入，只给 `diff.patch` + `source-before`，禁止读取 annotation/meta/category/source-after |

---

## §2 · 系统组件 & 包结构

### 设计原则

1. 按"职责"分包，不按"阶段"分包
2. 现有代码尽量保留，做"原地升级"，commit history 讲演进故事
3. 静态分析做成可插拔策略（regex / SpotBugs 都是 `StaticAnalyzer` 的不同实现）
4. 评测代码独立成包，既是工具也是质检员

### 目标包结构

```
dev.langchain4j.example.codereview/
├── CodeReviewApplication.java        @SpringBootApplication 主类
├── config/
│   ├── CodeReviewProperties.java     @ConfigurationProperties("code-review")
│   ├── RagConfig.java                @Bean: HybridRetriever, EmbeddingCache
│   ├── AgentConfig.java              @Bean: CodeReviewAgent, Orchestrator
│   └── ToolConfig.java               @Bean: GitDiffTool, SpotBugsTool, ...
├── cli/
│   ├── ReviewCommand.java            @Command(name = "review")
│   ├── EvalCommand.java              @Command(name = "eval")
│   └── SampleCommand.java            @Command(name = "sample")
├── agents/
│   ├── CodeReviewAgent.java          W1-W2 单 agent 入口；W3 改为 pipeline 入口
│   ├── pipeline/                     ◀── W3 默认（必交付）
│   │   ├── DiffAnalyzer.java         解析 diff、定位文件、找上下文
│   │   ├── ToolFindings.java         汇总 Regex/SpotBugs/CodeSearch 确定性发现
│   │   ├── LlmReviewer.java          基于 diff + tool findings + RAG 引用做 review
│   │   └── Summarizer.java           去重、排序、补引用、产出 ReviewResult
│   └── reviewers/                    ◀── W3 可选增强（stretch）
│       ├── SecurityReviewer.java
│       ├── PerformanceReviewer.java
│       └── TestReviewer.java
├── orchestration/                    ◀── v4-stretch 多 Agent 编排
│   ├── Orchestrator.java
│   └── ReviewContext.java
├── model/
│   ├── ReviewFinding.java            结构化 review 输出，评测唯一输入
│   ├── ReviewResult.java
│   └── Severity.java
├── tools/
│   ├── GitDiffTool.java              W1 改：复用 GitClient
│   ├── RuleCheckerTool.java          W1 改：行号正确 + 走 StaticAnalyzer
│   ├── CodeSearchTool.java           ◀── W2
│   └── SpotBugsTool.java             ◀── W2
├── analyzer/                         ◀── W2（策略模式）
│   ├── StaticAnalyzer.java           interface { List<Violation> analyze(Diff) }
│   ├── RegexAnalyzer.java
│   └── SpotBugsAnalyzer.java
├── rag/
│   ├── KnowledgeBaseIndexer.java     W1 拆出
│   ├── HybridRetriever.java          ◀── W2 (BM25 + 向量)
│   ├── LlmReranker.java              ◀── W2
│   └── CitationTracker.java          ◀── W2
├── eval/                             ◀── W1 启动，W4 完善
│   ├── Sample.java
│   ├── Annotation.java
│   ├── EvaluationRunner.java
│   ├── Metrics.java
│   └── SampleCollector.java
├── infra/
│   ├── GitClient.java                ◀── W1 抽（唯一 git 子进程封装）
│   ├── DiffParser.java               ◀── W1 抽（hunk header 解析，修行号）
│   └── EmbeddingCache.java           ◀── W1 抽（向量持久化）
└── reporting/
    └── MarkdownReporter.java
```

### 资源文件结构

```
src/main/resources/
├── application.yml
├── application-eval.yml
├── application-sample.yml
├── review-guidelines/                W2 扩到 8-10 篇
│   ├── java-best-practices.txt       已有
│   ├── security-checklist.txt        已有
│   ├── sql-guidelines.txt            ◀── W2
│   ├── performance.txt               ◀── W2
│   ├── api-design.txt                ◀── W2
│   ├── exception-handling.txt        ◀── W2
│   ├── concurrency.txt               ◀── W2
│   └── testing.txt                   ◀── W2
```

评测数据不放 `src/main/resources`，避免运行时写源码目录：

```
eval/
├── samples/                           40 个样本（每个一个子目录）
│   ├── reverse-001/
│   │   ├── meta.json                  仅评测 runner 读取，不传给 agent
│   │   ├── diff.patch                 agent 输入
│   │   ├── source-before/             agent 工具可读
│   │   ├── source-after/              仅人工对照，agent 禁止读取
│   │   └── annotation.json            ground truth，agent 禁止读取
│   ├── real-001/
│   └── synthetic-001/
└── reports/
    ├── v0-baseline.json
    ├── v1-spotbugs-search.json
    ├── v2-rag-hybrid.json
    ├── v3-pipeline-tuned.json
    └── v4-multi-agent-stretch.json   可选
```

### 关键技术选型

| 决策点 | 选型 | 理由 |
| --- | --- | --- |
| 应用框架 | **Spring Boot 3.5.x** + `langchain4j-spring-boot-starter` | LangChain4j Spring Boot starter 官方要求 Spring Boot 3.5+；DI / config / profile 一体 |
| CLI | **picocli + spring-boot-starter** | 事实标准的 Spring Boot 多子命令方案 |
| 静态分析 | **SpotBugs** | Java 生态最成熟；XML 输出方便解析 |
| 关键词检索 | **Apache Lucene** | 业界标准，BM25 现成，不引入额外服务 |
| 向量持久化 | **序列化 InMemoryEmbeddingStore 到本地 JSON** | 不引入 Qdrant/Chroma，避免过度工程 |
| Reranker | **LLM-as-reranker（让 Kimi 给候选片段打分）** | 没有方便的中文 reranker；LLM 灵活，故事性强 |
| 并发编排 | **`CompletableFuture` + `Executors`** | JDK 17 原生够用 |
| 样本存储 | **每样本一个目录 + JSON annotation** | 便于 diff、手工编辑 |
| Review 输出 | **结构化 JSON + Markdown 渲染** | 评测解析稳定，展示格式可独立演进 |
| 版本复现 | **git tag + report 记录配置** | 防止 v0/v1/v2/v3 / v4-stretch 被当前代码污染 |

### "原地升级"演进路径

| 文件 | W1 | W2 | W3 |
| --- | --- | --- | --- |
| `CodeReviewAgent` | 修 prompt 一致性 | 注入 hybrid retriever | 改为 pipeline 入口（v3），可选包多 Agent Orchestrator（v4-stretch） |
| `RuleCheckerTool` | 修行号 + 走 GitClient | 内部委托 `StaticAnalyzer` | 不变 |
| `KnowledgeBaseLoader` | 拆为 Indexer + Cache | 加 hybrid + rerank | 不变 |
| `GitDiffTool` | 复用 GitClient | 不变 | 不变 |

---

## §3 · 数据流

### 流程 1：W1-W2 单 Agent 流

```
CodeReviewApp (review 命令)
   │
   ▼
CodeReviewAgent (LangChain4j AiServices)
   │ 工具调用循环（LLM 自决）
   ├── GitDiffTool
   ├── RuleCheckerTool → StaticAnalyzer (regex + SpotBugs)
   └── CodeSearchTool (W2)
        │
        │ 自动注入 RAG (content retriever)
        ▼
   HybridRetriever (BM25 + 向量)
     → LlmReranker (LLM 给候选打分)
     → CitationTracker (记录引用映射)
        │
        ▼
   ReviewResult (结构化 JSON)
        │
        ▼
   MarkdownReporter (输出含引用的 Markdown)
```

### 流程 2A：W3 默认 Pipeline 流（v3 必交付）

把单 Agent 拆成 4 个职责清晰的串行组件，便于评测、调试、replace；不引入并发复杂度。

```
CodeReviewApp
   │
   ▼
CodeReviewAgent (pipeline 入口)
   │
   ▼
DiffAnalyzer (确定性，非 LLM)
   - 解析 diff、定位变更文件 / 变更行
   - 调 CodeSearchTool 拉周边上下文
   - 产出不可变 ReviewContext
   │
   ▼
ToolFindings (确定性，非 LLM)
   - 跑 RegexAnalyzer
   - 跑 SpotBugsAnalyzer（可降级）
   - 产出 List<Violation>
   │
   ▼
LlmReviewer (单次 LLM 调用)
   - 输入：ReviewContext + ToolFindings + RAG 引用片段
   - 产出结构化 ReviewFinding[] (JSON)
   │
   ▼
Summarizer (确定性 + 可选 LLM 兜底)
   - 去重 / 排序 / 严重度归一
   - 整合引用映射
   - 产出最终 ReviewResult
   │
   ▼
MarkdownReporter (展示层)
```

**关键工程决策**：

| 问题 | 决策 |
| --- | --- |
| 为什么不让单 Agent + 工具自决？ | 工具调用顺序不稳定 → 评测数字抖；显式 pipeline 让每一步可测、可 replace |
| LLM 调用几次？ | 默认 1 次（LlmReviewer），Summarizer 兜底再 1 次只在去重冲突时启用 → 成本可控 |
| RAG 在哪一步触发？ | LlmReviewer 调用前，由 `HybridRetriever` 根据 ReviewContext 拉 top-k 注入 prompt |
| Tool failure 怎么办？ | ToolFindings 内部降级（SpotBugs 跑不起来就只用 Regex），不阻塞 LlmReviewer |

### 流程 2B：W3 多 Agent 编排流（v4-stretch / 可选增强）

仅当 W1-W2 没有延期、且 pipeline v3 评测可信时启用。引入 Orchestrator 并发跑多个领域 Reviewer。

```
CodeReviewApp
   │
   ▼
Orchestrator
   │
   ▼
DiffAnalyzer (复用 pipeline 组件，串行)
   - 产出不可变 ReviewContext
   │
   ▼
并发执行 (CompletableFuture.allOf)
   ┌─────────────────┬─────────────────┬─────────────────┐
   │ SecurityReviewer│ PerfReviewer    │ TestReviewer    │
   │  超时 60s       │  超时 60s       │  超时 60s       │
   │  失败降级:跳过  │  失败降级:跳过  │  失败降级:跳过  │
   └─────────────────┴─────────────────┴─────────────────┘
   │   每个 Reviewer 独立访问 ReviewContext
   │   + 可调 SpotBugs / RuleChecker 工具
   │   产出各自的 ReviewFinding[]
   ▼
Summarizer (复用 pipeline 组件)
   - 跨 Reviewer 去重（同问题多人报）
   - 严重度归一、按文件/行排序
   - 引用整合
   │
   ▼
MarkdownReporter
```

**关键工程决策**：

| 问题 | 决策 |
| --- | --- |
| 共享上下文 | `ReviewContext` 不可变快照，避免并发修改 |
| Reviewer 超时 | 降级跳过，报告中标 `[skipped: timeout]`，不阻塞其他 |
| 重复报问题 | Summarizer 按"问题描述 + 文件 + 行号"模糊匹配去重 |
| Reviewer 间通信 | **不允许**，避免复杂度爆炸；交叉问题由 Summarizer 处理 |
| 与 pipeline 共享什么？ | DiffAnalyzer / Summarizer / ToolFindings 都复用，Orchestrator 只替换"中间的 LlmReviewer"一段 |

### 流程 3：评测流水线

```
SampleCollector (CLI)
   - GitHub API → fix commit message 过滤
   - 反向构造 sample
   │
   ▼
eval/samples/

─────────────────────────────────────

EvaluationRunner (CLI: app eval --version v1 --pipeline rag)
   │
   ▼
遍历 40 样本：
   1. 加载 sample
   2. 构造隔离工作目录，只暴露 diff.patch + source-before
   3. 调 CodeReviewAgent / Orchestrator 跑 review
   4. 读取结构化 ReviewResult
   5. 对比 annotation（ground truth）
   6. 记录 TP / FP / FN
   7. 记录 token / 耗时
   │
   ▼
Metrics 聚合
   - 召回率 / 准确率 / 误报率
   - 严重度准确率
   - 平均耗时 / 平均 token / 工具调用成功率
   │
   ▼
eval/reports/v{N}-{tag}.json
   │
   ▼ (W4)
多 report 聚合 → 指标对比表 + 曲线图 → README
```

---

## §4 · 评测框架详细设计

### 4.1 样本数据模型

```
samples/reverse-001/
├── meta.json           样本元信息
├── diff.patch          输入
├── source-before/      上下文（agent 工具可读）
├── source-after/       仅人工对照
└── annotation.json     ground truth
```

**`meta.json`**

```json
{
  "id": "reverse-001",
  "source_type": "reverse_constructed",
  "source_url": "https://github.com/spring-projects/spring-framework/commit/abc123",
  "language": "java",
  "category": "security",
  "difficulty": "medium",
  "diff_size_lines": 47,
  "collected_at": "2026-05-20"
}
```

**`annotation.json`**

```json
{
  "expected_issues": [
    {
      "id": "I-001",
      "file": "src/main/java/com/example/UserController.java",
      "line": 42,
      "line_range": [40, 45],
      "category": "security",
      "subcategory": "sql_injection",
      "severity": "critical",
      "description": "User input concatenated directly into SQL query",
      "must_detect": true,
      "alternative_descriptions": [
        "SQL injection vulnerability",
        "Unparameterized query with user input"
      ]
    }
  ],
  "should_not_report": [
    {
      "pattern": "missing javadoc on private method",
      "reason": "private 方法不强制要求 javadoc"
    }
  ],
  "notes": "fix commit message: 'Fix SQL injection in UserController'"
}
```

**`severity` 枚举**：`critical | warning | suggestion`，与 agent 输出（`CodeReviewAgent` system prompt 中定义的 `[CRITICAL|WARNING|SUGGESTION]`）一致，大小写无关匹配。
**`category` 枚举**：`security | performance | stability | concurrency | test | style | other`，与多 Agent 模式下 Reviewer 的职责划分对齐。

### 4.1.1 Review 输出模型

Agent 内部输出必须先落成结构化 `ReviewResult`，再由 `MarkdownReporter` 渲染成人看的 Markdown。评测框架只读取 `ReviewResult`，不解析 Markdown。

**`ReviewResult` 示例**

```json
{
  "summary": "Found 2 high-value review findings.",
  "findings": [
    {
      "id": "F-001",
      "file": "src/main/java/com/example/UserController.java",
      "line": 42,
      "line_range": [40, 45],
      "severity": "critical",
      "category": "security",
      "title": "SQL injection via string concatenation",
      "description": "User-controlled input is concatenated into a SQL query.",
      "suggestion": "Use a prepared statement or parameterized query.",
      "evidence": "The new query appends request.getParameter(\"id\") directly into SQL.",
      "citations": [
        {
          "id": "security-checklist#sql-001",
          "source": "security-checklist.txt",
          "section": "SQL Injection"
        }
      ],
      "source": "llm_reviewer"
    }
  ],
  "tool_status": [
    { "tool": "spotbugs", "status": "skipped", "reason": "project did not compile" },
    { "tool": "regex", "status": "ok" }
  ]
}
```

**字段约束**

| 字段 | 约束 |
| --- | --- |
| `id` | 单次 review 内唯一，格式 `F-001` |
| `file` | 仓库相对路径，必须能在 diff 或 `source-before/` 中定位 |
| `line` / `line_range` | 新文件行号；无法定位时 `line=null`，但必须给 `evidence` |
| `severity` | `critical | warning | suggestion` |
| `category` | `security | performance | stability | concurrency | test | style | other` |
| `title` | 一句话问题标题，<= 80 字符 |
| `description` | 问题解释，评测语义匹配主要字段 |
| `suggestion` | 可执行修改建议 |
| `evidence` | 说明为什么认为这是问题，避免空泛建议 |
| `citations` | RAG 命中依据；无依据时为空数组 |
| `source` | `regex | spotbugs | codesearch | llm_reviewer | summarizer | security_reviewer | performance_reviewer | test_reviewer` （v4-stretch 多 Agent 模式新增后三者） |

**样本隔离规则**：

- Agent 只能读取 `diff.patch` 和 `source-before/`。
- `source-after/`、`annotation.json`、`meta.json` 的 `category/difficulty/notes` 不进入 prompt，也不允许被工具读取。
- EvaluationRunner 为每个样本创建临时工作目录，只复制允许输入，避免“偷看答案”。
- 报告中记录 `allowed_inputs`，方便面试展示评测可信度。

### 4.2 样本采集策略

**最终目标：40 个样本 = 10 真实手工 + 20 反向构造 + 10 合成边界**

**反向构造（W1-W2 主力，共 20 个：W1 采 5 个跑通 baseline，W2 补到 20）**

`SampleCollector` 独立 CLI：

```bash
java -jar app.jar sample \
    --repos spring-projects/spring-framework,apache/dubbo \
    --keywords "fix sql injection,fix npe,fix race condition,fix memory leak,fix n+1" \
    --max-per-keyword 5 \
    --out eval/samples/
```

5 类关键词覆盖 5 类问题：

| 关键词 | 问题类型 | 期望样本数 |
| --- | --- | --- |
| `fix sql injection`, `parameterize query` | 安全：SQL 注入 | 4 |
| `fix npe`, `null pointer` | 稳定性：空指针 | 4 |
| `fix race condition`, `synchronize` | 并发：竞态 | 4 |
| `fix memory leak`, `close resource` | 资源：泄漏 | 4 |
| `fix n+1`, `eager fetch` | 性能：N+1 | 4 |

**人工审核 annotation**——不能完全自动，否则 ground truth 不可信。

**真实手工（W4 补，10 个）**：挑同样仓库的"有讨论的 PR"，review comments 当 ground truth。

**合成边界（W4 补，10 个）**：

| 子类型 | 数量 | 目的 |
| --- | --- | --- |
| 完全正常的 PR | 4 | 测误报率 |
| 只有格式问题 | 2 | 测严重度判断 |
| 同时多种问题 | 2 | 测完整性 |
| 极小 diff（1-3 行）| 1 | 测最小输入 |
| 极大 diff（500+ 行）| 1 | 测上下文窗口边界 |

### 4.3 指标定义

**质量指标**

| 指标 | 定义 | 目标 |
| --- | --- | --- |
| 召回率 | 命中的 must_detect 问题数 / 总 must_detect 问题数 | baseline 50% → 终态 75%+ |
| 准确率 | 命中的真问题数 / agent 报告的总问题数 | baseline 40% → 终态 65%+ |
| 误报率 | FP 数 / agent 报告的总问题数；另记录命中 should_not_report 的严重 FP | < 20% |
| 严重度准确率 | 严重度标对的问题数 / 命中的问题数 | > 70% |

**工程指标**

| 指标 | 定义 | 用途 |
| --- | --- | --- |
| 平均耗时 | 每样本 wall clock 时间 | 评估生产可用性 |
| 平均 token 成本 | input + output token | 评估推广成本 |
| 工具调用成功率 | 工具未抛异常的比例 | 评估鲁棒性 |

### 4.4 匹配算法

两层匹配：

```
对每个 expected_issue:
  Layer 1: 位置匹配
    - 找 agent 报告中 (file 相同 AND line 在 line_range±5) 的项
    - 没有 → False Negative
    - 有 1+ → 进 Layer 2

  Layer 2: 语义匹配（LLM-as-judge）
    - 候选项 description 和 expected.description + alternatives 一起给 judge
    - 是 → True Positive
    - 否 → 继续算 False Negative

对每个 agent 报告但未匹配的项:
  - 命中 should_not_report.pattern → False Positive（严重）
  - 否则 → False Positive（轻微，reason=extra）
```

**LLM-as-judge prompt 草案**：

```
You are evaluating whether two code review findings describe the SAME issue.

Expected issue (ground truth):
  Description: {expected.description}
  Category: {expected.category}
  Alternative phrasings: {expected.alternative_descriptions}

Agent's finding:
  Description: {agent.description}
  Severity: {agent.severity}

Question: Do these describe the SAME underlying problem? Answer with JSON:
{"match": true|false, "confidence": 0.0-1.0, "reason": "..."}
```

固定 `temperature=0`，每个匹配跑 1 次。

**两层保护 LLM judge 自身的不准**：

1. W4 抽样 10% 人工抽检；分歧 >20% 则调 judge prompt 或换更强模型
2. 所有匹配结果归档到 `eval/reports/v{N}.json`，README 可佐证

### 4.5 报告格式

**单次评测报告**（`eval/reports/v2-rag-hybrid.json`）：

```json
{
  "version": "v2-rag-hybrid",
  "commit": "abc1234",
  "timestamp": "2026-06-01T10:00:00Z",
  "config": {
    "model": "moonshot-v1-8k",
    "temperature": 0,
    "rag_top_k": 3,
    "rerank_enabled": true,
    "pipeline": "rag",
    "pipeline_config_hash": "sha256:..."
  },
  "allowed_inputs": ["diff.patch", "source-before/"],
  "metrics": {
    "recall": 0.68,
    "precision": 0.55,
    "fp_rate": 0.25,
    "severity_accuracy": 0.71,
    "avg_latency_ms": 18400,
    "avg_input_tokens": 4200,
    "avg_output_tokens": 850,
    "tool_success_rate": 0.97
  },
  "per_sample": [
    { "sample_id": "reverse-001", "tp": 1, "fp": 0, "fn": 0, "matches": [...] }
  ]
}
```

**README 最终交付**：

```markdown
## Evaluation Results

40 PR samples (20 reverse-constructed + 10 real + 10 synthetic edge cases).

| Version | Recall | Precision | FP Rate | Avg Latency | Avg Cost (CNY) |
|---------|--------|-----------|---------|-------------|----------------|
| v0 baseline (single agent, regex only)   | 42% | 38% | 35% | 8s  | 0.02 |
| v1 + SpotBugs + CodeSearch               | 55% | 51% | 28% | 12s | 0.04 |
| v2 + Hybrid RAG + LLM Rerank             | 68% | 55% | 25% | 18s | 0.06 |
| v3 + Explicit Pipeline + Tuning           | 72% | 60% | 21% | 22s | 0.08 |
| v4-stretch + Multi-Agent                  | 76% | 63% | 18% | 35s | 0.15 |

[曲线图：召回率 / 准确率 / 成本随版本演进]
```

---

## §5 · 工程化（Spring Boot + 失败处理）

### 5.1 失败分级

| 失败类型 | 例子 | 处理 |
| --- | --- | --- |
| 致命 | API key 缺失、git 仓库不存在 | 立即退出，明确错误 |
| 可重试 | LLM 超时、429 限流 | 指数退避 3 次（1s → 2s → 4s） |
| 可降级 | Reviewer agent 失败、SpotBugs 崩溃 | 跳过该组件，标 `[skipped: reason]` |
| 可忽略 | 单条工具调用失败、RAG 检索 0 结果 | 记 warning，继续 |

**评测要能跑完 40 个样本不中断**——单样本失败也要有占位结果。

### 5.2 超时设置

| 调用层 | 超时 |
| --- | --- |
| 单次 LLM 调用 | 60s |
| 单个 Reviewer agent | 60s |
| 整个 Orchestrator | 300s |
| SpotBugs 子进程 | 120s |
| Git 子进程 | 30s |
| 单个评测样本 | release 180s；debug 600s |

### 5.3 配置管理（Spring Boot）

**pom 关键依赖**

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.6</version>
</parent>

<properties>
    <langchain4j.version>1.15.0-beta25</langchain4j.version>
</properties>

<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai-spring-boot-starter</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-spring-boot-starter</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
<dependency>
    <groupId>info.picocli</groupId>
    <artifactId>picocli-spring-boot-starter</artifactId>
    <version>4.7.6</version>
</dependency>
```

实现前用 `mvn dependency:tree` 验证 Spring Boot starter、core、embedding 相关依赖版本兼容。LangChain4j Spring Boot 3 starter 采用 beta 版本号（例如 `1.15.0-beta25`），不要写成不存在的 `1.15.0` 或混用不同 beta 代。

**`application.yml`**

```yaml
langchain4j:
  open-ai:
    chat-model:
      base-url: https://api.moonshot.cn/v1
      api-key: ${MOONSHOT_API_KEY}
      model-name: moonshot-v1-8k
      temperature: 0
      max-tokens: 4096
      timeout: 60s

code-review:
  rag:
    embedding-cache-dir: ${user.home}/.code-review-agent/cache
    top-k: 3
    min-score: 0.4
    rerank-enabled: true
  orchestration:
    reviewer-timeout: 60s
    parallelism: 3
  eval:
    judge-model: moonshot-v1-8k
    runs-per-sample: 3
    samples-dir: eval/samples
    report-dir: eval/reports
```

**Profile 拆分**

| Profile | 用途 |
| --- | --- |
| default | 日常 review |
| eval | judge 用便宜模型，强制 INFO 日志，自动写 report |
| sample | 需 `GITHUB_TOKEN`，连 GitHub API |

启动时 `--spring.profiles.active=eval` 切换。

### 5.4 密钥 / 敏感信息

| 项 | 处理 |
| --- | --- |
| `MOONSHOT_API_KEY` | 仅 env，`.gitignore` 加 `.env` |
| `GITHUB_TOKEN` | 同上 |
| 评测样本中的真实代码 | 反向构造来自开源项目，许可证标注在 `samples/LICENSE.md` |
| Demo 代码 | README 中用合成代码 |

### 5.5 日志

| 级别 | 内容 |
| --- | --- |
| INFO | 用户视角进度（"Reviewing PR... Found 5 files changed"） |
| DEBUG | 每次 LLM 调用 token、每次工具调用入参出参 |

评测强制 INFO 级，单样本详细 trace 单独存 `eval/reports/v{N}-traces/sample-{id}.log`。

### 5.6 成本控制

| 控制点 | 措施 |
| --- | --- |
| 避免重复嵌入 | `EmbeddingCache` 序列化到本地 |
| 大 diff 分文件处理 | 单文件 > 500 行走"摘要 + 关键片段" |
| 评测分批跑 | `--samples reverse-001,reverse-002` 指定子集 |
| 评测分层跑 | `--suite smoke|dev|release|debug` 控制样本数、runs 和 timeout |
| token 追踪 | `Metrics` 记 input/output token |
| judge 用便宜模型 | review 用强模型；评测自身用便宜模型 |

### 5.7 可重现性

1. `temperature=0`；release 评测每样本跑 3 次取**中位数**（不是平均）
2. `eval/reports/v{N}.json` 记 git commit hash、git tag、pipeline 名称、pipeline 配置 hash
3. 评测脚本本身有版本号 `eval-runner-version: 1.0`
4. 评测分四档：
   - `smoke`：2 个样本 × 1 次，用于本地快速验证
   - `dev`：10 个样本 × 1 次，用于调 prompt / 工具
   - `release`：40 个样本 × 3 次，用于 README 指标，目标总耗时 < 90 分钟，单样本 hard timeout 180s
   - `debug`：指定样本 × 1 次，允许单样本 timeout 600s，用于排查复杂失败
5. **v4-stretch 多 Agent 评测只跑 `dev` / `debug` 档**，不参与 `release`：Orchestrator 端到端最坏 ~300s，与 release 单样本 180s 上限冲突；多 Agent 是展示亮点而非主线产品，`dev` 档拿到的数字已足够进 README 对比表。

---

## §6 · 测试策略

### 6.1 测试金字塔

```
              [评测集 40 PRs]    ← 业务正确性最终判官（§4 框架）
                    ▲
          [集成测试 ~10 个]      ← 关键链路打通
                    ▲
        [单元测试 ~30 个]        ← 纯函数 / 解析逻辑
```

**关键认知**：评测集替代了业务 E2E 测试。普通后端项目用断言锁业务，AI 应用因 LLM 输出不确定，用评测集（语义匹配 + 指标阈值）替代。

### 6.2 单元测试范围（~30 个，JUnit 5）

只测确定性纯函数，不调 LLM，不起 Spring。

| 模块 | 测什么 |
| --- | --- |
| `DiffParser` | hunk header 解析、行号映射、文件名提取 |
| `RegexAnalyzer` | 各条正则规则的命中/不命中 |
| `SpotBugsAnalyzer` | XML 解析、bug 类型映射（用 fixture） |
| `Metrics` | 召回/准确/误报公式 |
| `ReviewFinding` parser/validator | LLM JSON 输出 schema 校验、字段缺失降级 |
| `CitationTracker` | 引用 ID 分配、去重 |
| `MarkdownReporter` | Markdown 拼接、严重度排序 |
| `SampleCollector` | commit message 关键词匹配、annotation 草稿（mock GitHub API） |
| `EmbeddingCache` | 序列化/反序列化、缓存命中判断 |

测试数据放 `src/test/resources/fixtures/`。

### 6.3 集成测试范围（~10 个，`@SpringBootTest`）

测组件之间的连接和降级行为，**mock 掉 LLM 调用**（自定义 `ChatModel` Bean，test profile 下返回预设响应）。

| 场景 | 验证 |
| --- | --- |
| Spring context 起得来 | 所有 Bean 装配，无循环依赖 |
| `review` 命令冒烟 | mock LLM 返回固定 review，从 CLI 到 Markdown 输出 |
| `eval` 命令冒烟 | mock LLM，跑 2 个 fixture 样本，验 report 文件生成 |
| `sample` 命令冒烟 | mock GitHub API，验样本目录创建 |
| Tool 自动发现 | LangChain4j 识别所有 @Tool 方法 |
| RAG retriever 装配 | KnowledgeBaseIndexer 启动后能查到结果 |
| Orchestrator 超时降级（W3） | 让 Reviewer sleep 70s，验 Orchestrator 60s 后跳过 |
| Orchestrator 失败降级（W3） | Reviewer 抛异常，验其他不受影响 |
| EmbeddingCache 持久化 | 第二次启动命中缓存，不重算 |
| Profile 切换 | `eval` profile 起来后用 judge model 配置 |
| 样本隔离 | EvaluationRunner 临时目录不包含 annotation/source-after/meta category |

### 6.4 评测集 = 业务 E2E

| 维度 | 传统 E2E | 评测集 |
| --- | --- | --- |
| 断言方式 | `assertEquals` | LLM-as-judge 语义匹配 |
| 通过标准 | 100% pass | 召回 ≥ 70%，准确 ≥ 60% |
| 失败处理 | 阻塞合并 | 调 prompt / 加规则 / 改 RAG |
| 频率 | 每次 commit | 每次发版 |
| 成本 | 秒级、免费 | 分钟级、有 token 成本 |

**评测不进 CI**：只在版本切换时手动跑，结果归档。

### 6.5 CI 配置

```yaml
# .github/workflows/ci.yml
- mvn test                                    # 单元测试
- mvn verify -Dspring.profiles.active=test    # 集成测试（mock LLM）
```

不在 CI 跑评测的理由：

- 需 `MOONSHOT_API_KEY`，每个 PR 都花钱
- release 评测 40 样本 × 3 次，耗时和成本都不适合每次 PR 触发
- LLM 不稳定可能造成 flaky CI

替代方案：Release 时手动触发，结果 commit 到 `eval/reports/`。

### 6.6 W1 必须配套写的测试

为让评测可信：

| 测试 | 为什么必须 W1 写 |
| --- | --- |
| `DiffParser` 单测 | 行号正确性是评测匹配的基础 |
| `Metrics` 单测 | 召回率算错整个评测就废 |
| `ReviewFinding` schema 校验单测 | 结构化输出不稳，后面评测全会抖 |
| `EvaluationRunner` 集成测（mock LLM 跑 2 个样本）| 保证评测框架自身不出 bug |

---

## 验收标准

W4 末尾交付时满足：

- [ ] GitHub 公开仓库，README 含架构图、五阶段演进故事、评测指标表 + 曲线图
- [ ] `eval/reports/` 含 v0/v1/v2/v3 四个主线评测报告；若实现多 Agent，额外包含 v4-stretch
- [ ] 召回率 ≥ 70%，准确率 ≥ 60%（终态目标）
- [ ] 每个 report 记录 git commit/tag、pipeline、配置 hash、allowed inputs
- [ ] Agent 输出有结构化 `ReviewResult`，Markdown 报告由 reporter 渲染生成
- [ ] 录屏一段：CLI 跑一个真实样本的完整过程
- [ ] commit history 干净，能看出五阶段演进
- [ ] 单元测试 + 集成测试通过，CI 绿
