# W2 学习笔记

> 这份文档跟随 W2 实现 task-by-task 累积。每个任务沉淀三段：技术细节、设计权衡、面试 Q&A。
> 目标：把 W2 从“多加几个功能”讲成“工具增强 + RAG 增强 + 评测扩展”的完整工程演进。

**Spec：** [`../superpowers/specs/2026-05-17-code-review-agent-design.md`](../superpowers/specs/2026-05-17-code-review-agent-design.md)
**Plan：** [`../superpowers/plans/2026-05-22-code-review-agent-w2.md`](../superpowers/plans/2026-05-22-code-review-agent-w2.md)

---

## T1 · SourceCompiler — best-effort javac 编译到临时 classes 目录

### 技术细节

1. **为什么 W2 需要 SourceCompiler**
   - SpotBugs 分析的是 `.class` 字节码，不是 `.java` 源码。
   - Eval sample 里只有 `source-before/`，所以要先尝试把 sample 的 Java 源码编译到临时 `classes/` 目录。
   - 这个编译不能成为主流程的硬依赖：样本可能引用外部类型、真实仓库也可能需要 Maven/Gradle classpath，所以这里必须是 best-effort。

2. **`javax.tools.JavaCompiler` 的用法**
   - `ToolProvider.getSystemJavaCompiler()` 只能在 JDK 下拿到；如果运行环境是 JRE，会返回 null。
   - `StandardJavaFileManager` 负责把 `Path` 转成 `JavaFileObject`。
   - `fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()))` 指定 `.class` 输出目录。
   - 编译参数用了 `-nowarn -proc:none`：
     - `-nowarn`：避免 warning 污染输出。
     - `-proc:none`：禁用 annotation processor，避免 sample 编译时触发额外依赖。

3. **失败策略**
   - 空目录：返回 `Optional.empty()`。
   - 没有系统 compiler：返回 empty。
   - Java 源码引用缺失类型：compiler task 返回 false，返回 empty。
   - I/O 异常：记录 warn，返回 empty。
   - 这里不抛异常，是因为 SpotBugs 是“增强信号”，不是 review 主链路的必要条件。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 直接调用 Maven/Gradle 编译整个仓库 | 更接近真实项目，但慢、依赖重、失败面大。W2 先做 sample/轻量仓库可用。 |
| 只对 eval sample 做编译 | 会让生产 review 路径和 eval 路径不一致。SourceCompiler 抽成通用组件后两边都能用。 |
| 编译失败直接报错 | 不合适。静态分析失败不应让 LLM review 整体失败。 |
| 编译到项目 target 目录 | 会污染工作区。临时目录更干净。 |

### 面试 Q&A

**Q1：为什么 SpotBugs 前面要先加 SourceCompiler？**
- **A**：SpotBugs 的输入是字节码，不是源码。W2 的样本和普通 diff 都是源码层面的，所以要先把 source tree 尽量编译成 `.class`。但真实项目 classpath 复杂，不一定编得过，所以 SourceCompiler 的定位是 best-effort：能编译就给 SpotBugs 多一层信号，不能编译就跳过，不能影响主链路。

**Q2：为什么不用 Maven/Gradle，而是直接用 JavaCompiler？**
- **A**：W2 的目标是给代码评审增加一个低成本静态信号，不是完整复刻项目构建系统。直接 JavaCompiler 对自包含 sample 和简单类足够快、足够稳定；Maven/Gradle 会引入下载依赖、profile、插件、网络等复杂度。后续如果要支持真实企业仓库，可以让 SourceCompiler 先探测 Maven/Gradle，再 fallback 到 javac。

**Q3：编译失败返回 empty 会不会隐藏问题？**
- **A**：不会，因为这个组件的语义不是“编译器”，而是“SpotBugs 前置准备”。失败信息会通过 SpotBugs 的 skipped/tool_status 暴露给报告和 eval，而不是让整个 review 中断。这个取舍符合 W2 的目标：增强，不阻断。

### Commit

```text
feat(analyzer): SourceCompiler best-effort javac to temp classes dir
```

---

## T2 · SpotBugsAnalyzer — XML 解析 + graceful skip

### 技术细节

1. **SpotBugs 为什么作为 `StaticAnalyzer` 的第二个实现**
   - W1 只有 `RegexAnalyzer`，适合查简单文本模式，比如 hardcoded credential、printStackTrace。
   - SpotBugs 是字节码级分析，可以覆盖一些 regex 很难稳定判断的问题，比如潜在 NPE、错误 API 使用、坏实践。
   - W2 没有把 SpotBugs 写进 `RuleCheckerTool` 里，而是作为 `StaticAnalyzer` 生态的一员，保持策略模式。

2. **XML 输出解析**
   - SpotBugs XML 里的核心节点是 `BugInstance`。
   - 重要字段：
     - `type`：规则 ID，比如 `NP_NULL_ON_SOME_PATH`
     - `priority`：优先级，W2 映射到 severity
     - `SourceLine sourcepath/start`：文件路径和行号
     - `LongMessage`：可读说明
   - 解析采用 StAX：`XMLInputFactory + XMLEventReader`。
   - 不用 DOM 的原因：XML 可能较大，StAX 流式读更轻。

3. **只保留 changed lines**
   - SpotBugs 会扫描整个 classes 目录，但 code review 只应该报告本次 diff 引入/触碰的问题。
   - 实现里先把 `DiffParser.FileDiff.addedLines()` 转成 `file:line` set。
   - SpotBugs 命中后只保留 `shortFile + ":" + line` 在 changed set 里的 violation。
   - 这避免“历史问题”污染 PR review。

4. **severity 映射**
   - `priority=1` → `CRITICAL`
   - `priority=2` → `WARNING`
   - 其他 → `SUGGESTION`
   - 这是简化映射。真实生产里还可以结合 `rank`、`category`、`type` 做更细分。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| SpotBugs 不可用时失败 | 不合适，会让没装 SpotBugs 的环境无法跑 demo。 |
| 报告全仓库 SpotBugs 结果 | 会把历史问题算到当前 PR，precision 下降。 |
| 直接解析文本输出 | XML 更稳定，字段更明确。 |
| 只在 eval 跑 SpotBugs | review 路径和 eval 路径不一致，指标会失真。 |

### 面试 Q&A

**Q1：为什么 SpotBugsAnalyzer 的 `analyze(files)` 直接返回空？**
- **A**：`StaticAnalyzer.analyze(files)` 这个接口只传 diff，不传源码目录；SpotBugs 需要源码目录先编译，所以单靠这个入口无法工作。为了兼容接口，它在无 sourceDir 时 graceful skip；真正执行通过 `analyzeWithSource(files, sourceDir)`。这是 W2 为了不破坏 W1 analyzer 接口做的折中。

**Q2：为什么 SpotBugs 结果要过滤到新增行？**
- **A**：代码评审只应该关注本次改动引入的问题。如果 SpotBugs 扫全仓，把老问题都报出来，开发者会觉得工具噪音大，评测里也会制造 FP。过滤到 diff added lines 是把静态分析信号对齐到 PR review 语义。

**Q3：SpotBugs 装不上怎么办？**
- **A**：runner 返回 false，`RuleCheckerTool` 会追加 `[tool_status] spotbugs=skipped (...)`。LLM 最终应该把它写入 `ReviewResult.tool_status`。这样使用者知道工具没跑，不会误以为 SpotBugs 跑了但没发现问题。

### Commit

```text
feat(analyzer): SpotBugsAnalyzer with XML parse and graceful skip
feat(tools): wire SpotBugs runner and tool status footer
```

### 踩坑实录

**坑 1：测试 runner 写 XML 时 `Files.copy` 撞上已存在的临时文件**
- 现象：`SpotBugsAnalyzerTest` 期望解析 fixture XML，但返回空；日志里有 `FileAlreadyExistsException`。
- 原因：生产代码先 `Files.createTempFile(...)` 创建 output，再把这个 path 交给 runner。测试 runner 用 `Files.copy(xml, output)`，默认不覆盖。
- 修复：测试里改成 `Files.copy(xml, output, StandardCopyOption.REPLACE_EXISTING)`。
- 教训：fixture runner 要模拟“工具写入已有 output path”的语义，而不是假设 path 不存在。

---

## T3 · RuleCheckerTool tool_status — 静态分析结果可观测

### 技术细节

1. **为什么要加 `[tool_status]` footer**
   - W1 的 `checkRules` 只返回 violation 文本。
   - W2 增加 SpotBugs 后，工具可能有三种状态：
     - regex ok
     - spotbugs ok
     - spotbugs skipped（未安装 / 不可编译 / 超时）
   - 如果不把状态返回给 LLM，最终报告里会缺少“哪些工具没跑”的可观测信息。

2. **为什么用文本 footer 而不是结构化对象**
   - LangChain4j `@Tool` 方法返回值通常作为 tool message 文本喂给模型。
   - 直接返回 `String` 最简单，和现有工具接口兼容。
   - `[tool_status] spotbugs=skipped (...)` 是一种轻量协议，模型能读，人也能读。

3. **Agent prompt 的配合**
   - 仅工具输出状态不够，system prompt 还要告诉 LLM：
     - 看到 `[tool_status] X=skipped (...)` 时写入 `ReviewResult.tool_status`
     - `ok` 工具写 status `ok`
   - 这是软约束。W3 pipeline 化后可以把 tool_status 从 Java 侧直接塞进结构化结果，减少对 LLM 的依赖。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| Java 侧直接构造 ReviewResult.toolStatus | W1/W2 单 Agent 结构里最终 JSON 由 LLM 产出，强塞需要改更大架构。 |
| tool 输出纯文本状态 | 简单、兼容，但依赖 LLM 按 prompt 回填。 |
| 单个 analyzer 抛异常就失败 | 不符合“评测可恢复 > 单 sample 完美”的原则。 |

### 面试 Q&A

**Q1：为什么 tool_status 很重要？**
- **A**：LLM review 很容易让人误以为所有工具都成功跑了。tool_status 能区分“没发现问题”和“工具没跑成功”。这对 CI、评测和用户信任都重要。

**Q2：这个状态为什么不算 finding？**
- **A**：finding 是代码问题，tool_status 是分析过程元数据。两者消费者不同：finding 给开发者修代码，tool_status 给系统判断这次 review 的可信度。

### Commit

```text
feat(tools): wire SpotBugs runner and tool status footer
```

---

## T4 · CodeSearchTool — 本地 grep + identifier lookup

### 技术细节

1. **CodeSearchTool 的定位**
   - `GitDiffTool` 只能看当前 diff。
   - `RuleCheckerTool` 只能跑静态规则。
   - `CodeSearchTool` 让 Agent 在需要时搜索本地 Java 文件，找调用方、定义、同名方法、配置常量等上下文。
   - 这是从“只看 patch”迈向“理解仓库上下文”的第一步。

2. **实现边界**
   - 只搜索 `.java` 文件。
   - 只做 literal substring，大小写敏感。
   - 返回格式：`relative/path.java:LINE: snippet`。
   - 最多 50 条命中，避免把大量上下文塞进 prompt。
   - 单行最多 200 字符，避免超长 minified/generated 行撑爆上下文。

3. **为什么不用 regex / ripgrep / AST**
   - regex 对 LLM tool 参数不友好，模型容易传错转义。
   - 调 `rg` 子进程更强，但跨平台和错误处理更复杂。
   - AST 能做符号级搜索，但 W2 目标是轻量补上下文，不做语言服务器。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| literal search | 稳定、简单、容易让 LLM 使用。 |
| regex search | 能力强，但参数转义和 ReDoS 风险更高。 |
| 调用 ripgrep | 性能最好，但引入外部 binary 依赖。 |
| JavaParser/AST | 语义强，但实现复杂，不适合 W2。 |

### 面试 Q&A

**Q1：CodeSearchTool 和 RAG 有什么区别？**
- **A**：RAG 检索的是“规范知识库”，比如安全/性能/异常处理 checklist；CodeSearchTool 搜的是“当前仓库代码”。一个回答“应该怎么写”，一个回答“项目里其他地方怎么写/谁在调用”。两者互补。

**Q2：为什么只返回 50 条？**
- **A**：工具返回会进入 LLM context，命中太多会稀释重点、增加 token 成本，也可能让模型抓错上下文。50 是一个保守上限，真实项目可以按文件类型、路径、调用深度再排序。

### Commit

```text
feat(tools): CodeSearchTool grep and identifier lookup
```

---

## T5 · Knowledge Base 2 → 8 — RAG 覆盖面扩展

### 技术细节

1. **新增 6 个领域**
   - SQL：参数化查询、N+1、事务、连接池
   - Performance：复杂度、分配、阻塞调用、缓存
   - API Design：资源建模、幂等、状态码、分页
   - Exception Handling：上下文、吞异常、边界转换、重试
   - Concurrency：共享状态、锁、ConcurrentHashMap、volatile、Executor
   - Testing：AAA、单测/集成测试、test doubles、flaky、regression

2. **为什么用英文**
   - W1 已有 `java-best-practices.txt` / `security-checklist.txt` 是英文。
   - Kimi/embedding 对英文技术 checklist 的检索效果相对稳定。
   - 面试回答也可以说：知识库文档语言统一，减少 chunk 质量差异。

3. **知识库不是越多越好**
   - 文档变多后，top-K 可能召回无关 chunk。
   - 所以 W2 同时引入 hybrid retrieval 和 reranker，而不是只堆文档。
   - 每加知识域都应该用 eval 观察 recall/precision 是否改善。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 只扩安全知识库 | 安全题容易出效果，但项目会显得单一。 |
| 一次扩很多官方文档 | 噪音大，版权/引用边界也麻烦。 |
| 自写短 checklist | 可控、稳定、适合 eval baseline。 |

### 面试 Q&A

**Q1：为什么 W2 要扩知识库？**
- **A**：W1 的知识库只有 Java best practices 和 security checklist，覆盖不了 SQL、并发、性能、测试等样本。W2 样本扩到 20 个后，RAG 也要覆盖这些类别，否则检索出的规范和问题不匹配。

**Q2：怎么判断知识库扩展有没有收益？**
- **A**：理想做法是 A/B：同一组 samples，关 RAG 跑一次、开 RAG 跑一次，看 recall/precision 和 citation 命中情况。W2 已经把 hybrid RAG 接上，但正式 eval 还没干净跑完，所以目前只能说“能力已接入，指标待验证”。

### Commit

```text
docs(rag): expand guidelines from 2 to 8 review domains
```

---

## T6 · 20 reverse-style samples — 评测集扩容

### 技术细节

1. **新增样本分布**
   - `reverse-006..008`：security（SQL injection、weak crypto、hardcoded secret）
   - `reverse-009..011`：stability（collection guard、null switch、unsafe cast）
   - `reverse-012..014`：concurrency（SimpleDateFormat、lazy init race、HashMap race）
   - `reverse-015..017`：performance（N+1、O(n²)、parallelStream blocking IO）
   - `reverse-018..020`：resource/test（unclosed reader/stream、swallowed assertion）

2. **每个 sample 的标准结构**

   ```text
   reverse-NNN/
   ├── meta.json
   ├── diff.patch
   ├── source-before/
   ├── source-after/
   └── annotation.json
   ```

   - Agent 只允许看 `diff.patch` 和 `source-before/`。
   - `annotation.json` 是 ground truth，只给 eval matcher 用。
   - `source-after/` 只是固定样本构造来源，不能泄露给 Agent。

3. **行号校验**
   - annotation 的 `line` 必须是 post-change 文件行号。
   - 这和 W1 `DiffParser` 的行号语义一致。
   - 通过 `jq` 快速扫了 15 个新增 annotation 的 file/category/severity。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 直接收集真实 PR | 更真实，但标注成本高，W2 时间不够。 |
| reverse-style | ground truth 清晰、构造快，适合扩 baseline。 |
| synthetic | 污染风险低，但可能太“教科书”。 |
| 一次上 40 个 | 标注和 debug 压力太大，W2 先到 20。 |

### 面试 Q&A

**Q1：20 个样本能说明什么？**
- **A**：仍然不能说明模型在真实世界“统计显著”地好，但比 W1 的 5 个样本更适合做版本间对比。W2 的重点是覆盖类别扩展：安全、稳定性、并发、性能、资源/test。真正 release 级评测要到 W4 的 40 样本。

**Q2：reverse-style 会不会不真实？**
- **A**：会。它的优点是 ground truth 明确、标注成本低；缺点是 diff 可能不像真实开发者写出来的 PR。W2 接受这个取舍，因为目标是扩充 baseline。W3/W4 应该补 real PR 和 synthetic edge cases。

### Commit

```text
eval(samples): add 3 reverse-style security samples
eval(samples): add 3 reverse-style stability samples
eval(samples): add 3 reverse-style concurrency samples
eval(samples): add 3 reverse-style performance samples
eval(samples): add 3 reverse-style resource and test samples
```

---

## T7 · Lucene BM25 + ChunkMetadata

### 技术细节

1. **为什么引入 BM25**
   - 向量检索擅长语义相似，但对精确关键词不一定稳，比如 `MD5`、`SQL injection`、`ConcurrentHashMap`。
   - BM25 是传统 lexical retrieval，关键词匹配强。
   - Hybrid RAG 的核心思路：向量召回语义相关，BM25 召回关键词相关，两者互补。

2. **Lucene 依赖**
   - `lucene-core`
   - `lucene-analysis-common`
   - `lucene-queryparser`
   - 计划里漏了 `lucene-queryparser`，实际实现 `QueryParser` 时编译失败后补上。

3. **`ChunkMetadata`**
   - 字段：`sourceFile`、`section`、`snippet`
   - `citationId()` 生成类似：`sql-guidelines#parameterized-queries`
   - 这个 ID 后续会进入 `TextSegment.metadata()`，用于 citation、dedupe、RRF 融合。

4. **BM25 索引内容**
   - W2 不只索引正文，还把 `sourceFile` 和 `section` 拼进 searchable text。
   - 原因：测试里 query `sql injection` 时，有些 chunk 正文不含 `sql`，但来源文件是 `sql.txt`。把 metadata 纳入索引能提高同域召回。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 只用向量检索 | 语义召回好，但技术关键词不稳。 |
| 只用 BM25 | 关键词准，但同义表达召回弱。 |
| Lucene 内存索引 | 简单、快，适合小知识库。 |
| 持久化 Lucene index | 大知识库更好，但 W2 规模不需要。 |

### 面试 Q&A

**Q1：BM25 和向量检索有什么区别？**
- **A**：BM25 是词项匹配，关键词越稀有、出现越集中，分越高；向量检索是语义相似，能匹配同义表达。代码评审知识库里两者都需要：`SQL injection`、`MD5` 这类关键词 BM25 强；“资源没关闭”这种语义表达向量更强。

**Q2：为什么要给 chunk 加 metadata？**
- **A**：metadata 有三层用途：第一，citation 可以告诉用户规则来自哪个文件/章节；第二，HybridRetriever 可以用 citation_id 去重；第三，eval 时可以审计 RAG 是否真的引用了存在的知识库片段。

### Commit

```text
build(rag): add Lucene 9.11 and ChunkMetadata record
feat(rag): Bm25Retriever backed by Lucene index
```

### 踩坑实录

**坑 2：计划漏了 `lucene-queryparser`**
- 现象：`org.apache.lucene.queryparser.classic` 不存在。
- 原因：`QueryParser` 不在 `lucene-core` 或 `lucene-analysis-common`，需要单独依赖。
- 修复：补 `lucene-queryparser:9.11.1`。
- 教训：Lucene 模块拆得很细，看到 package 不存在先查对应 artifact。

**坑 3：`Query.from("")` 直接在 LangChain4j 层抛异常**
- 现象：BM25 empty query 测试没进 retriever，就在 `Query.from("")` 抛 `text cannot be null or blank`。
- 修复：测试改成 unmatched query，比如 `not-present`。
- 教训：测试输入要符合上游类型约束，不要测试一个上游 API 根本不允许构造的状态。

---

## T8 · HybridRetriever — Reciprocal Rank Fusion

### 技术细节

1. **RRF 公式**

   ```text
   score(doc) = Σ 1 / (rrfK + rank(doc))
   ```

   - rank 从 1 开始。
   - 同一个 doc 如果同时出现在 vector 和 BM25 结果里，分数会叠加。
   - `rrfK=60` 是常见经验值：让前几名差异存在，但不会过度放大单一路召回。

2. **去重 key**
   - 优先用 `citation_id`。
   - 如果没有 citation_id，fallback 到 text hash。
   - W2 的知识库 chunk 都有 citation_id，所以正常路径稳定。

3. **为什么 ContentRetriever 包装**
   - LangChain4j 的 RAG 注入点就是 `ContentRetriever`。
   - `HybridRetriever` 实现这个接口，就能直接替换原来的 `EmbeddingStoreContentRetriever`。
   - 这让 `RagConfig` 里只需要组装 `vector + bm25 + reranker`，Agent 层完全不用改。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 简单拼接 vector + bm25 | 会重复，且排序不可控。 |
| 按原始分数加权 | vector score 和 BM25 score 量纲不同，难比较。 |
| RRF | 不依赖原始分数，只依赖排名，简单稳定。 |
| LLM 直接从全部 chunk 选 | 成本高，且候选太多。 |

### 面试 Q&A

**Q1：为什么用 RRF 而不是归一化后加权？**
- **A**：BM25 分数和向量相似度不是同一个尺度，强行归一化会引入很多经验参数。RRF 只看排名，不看原始分数，因此更稳，也更适合 W2 这种小规模 hybrid 检索。

**Q2：同一个 chunk 怎么识别？**
- **A**：通过 metadata 里的 `citation_id`。它由 source file + section slug 生成，能在同一次索引中稳定表示一个知识片段。没有 citation_id 时才 fallback 到文本 hash。

### Commit

```text
feat(rag): fuse vector and BM25 results with RRF
```

---

## T9 · LlmReranker — 用 LLM 重排候选 chunks

### 技术细节

1. **为什么 RRF 后还要 rerank**
   - RRF 能融合两个召回源，但它不知道 query 和 chunk 的深层相关性。
   - LLM reranker 让模型给每个候选打 0.0-1.0 分，再取 top-K。
   - 这一步适合候选少的场景：先用 hybrid 把候选压到小集合，再让 LLM 判断。

2. **实现方式**
   - `LlmReranker` 也是 `ContentRetriever`。
   - 它包一层 upstream retriever。
   - prompt 要求模型返回：

     ```json
     {"scores":[0.2,0.9,0.5]}
     ```

   - 按 score 降序排序；同分按原始顺序保持稳定。

3. **失败 fallback**
   - LLM reranker 失败时，不让整个 review 失败。
   - 直接返回 upstream 原顺序的 top-K。
   - 这是 RAG 系统里常见设计：rerank 是增强，不是硬依赖。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 不做 rerank | 成本低，但 top-K 可能被弱相关 chunk 占掉。 |
| 所有 chunk 都给 LLM 打分 | 成本高、慢，不适合 CLI。 |
| 只 rerank hybrid 后的小候选集 | 成本和效果折中。 |
| reranker 失败就报错 | 不值得，fallback 更符合可用性。 |

### 面试 Q&A

**Q1：LLM reranker 会不会增加很多延迟？**
- **A**：会增加一次模型调用，所以 W2 把它放在候选集已经被 hybrid 压缩之后。配置里有 `rerank-enabled` 和 `rerank-top-k`，可以在成本和效果之间调。生产上可以对小 diff 关闭，或只在高风险 category 开启。

**Q2：reranker 输出 JSON 也可能不合法，怎么办？**
- **A**：W2 实现了 fallback：解析失败、调用异常都返回 upstream 原顺序。这样 reranker 不会让 review 主链路失败。后续可以加 JSON mode 或更严格的 repair/retry。

### Commit

```text
feat(rag): rerank hybrid retrieval results with LLM scores
```

---

## T10 · CitationTracker + KnowledgeBaseIndexer v2

### 技术细节

1. **KnowledgeBaseIndexer 从 W1 到 W2 的变化**
   - W1：用 `EmbeddingStoreIngestor + recursive splitter` 直接把 docs ingest 到 vector store。
   - W2：自己读文件、按 `##` section 切块、生成 `ChunkMetadata`、同时构建 vector store 和 BM25 index。
   - 原因：W2 需要 metadata 和 BM25，easy-rag 的默认 ingestor 不够可控。

2. **cache key 从 `review-guidelines` 变成 `review-guidelines-v2`**
   - W1 cache 里的 TextSegment 没有 citation metadata。
   - 如果复用旧 cache，RAG 结果里没有 `citation_id`，Hybrid/Reranker/Citation 都会缺信息。
   - 所以必须 bump cache key，让 indexer 重新嵌入。

3. **52 chunks 的意义**
   - 8 个 guideline docs 经过 section/chunk 切分后得到 52 个 chunk。
   - 这是 W2 的知识库规模基线。
   - 后续调 top-K/min-score/rrfK 时，这个 chunk 数是背景信息。

4. **CitationTracker**
   - 把 `Content.textSegment().metadata()` 转成 `Citation(id, source, section)`。
   - 同一个 citation_id 去重。
   - W2 还没有把它强行后处理到 ReviewResult，主要是先把 metadata 链路打通。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 继续用 easy-rag ingestor | 简单，但 metadata 和 BM25 不好控制。 |
| 自己写 indexer | 代码多一点，但 chunk/citation 可控。 |
| 复用旧 cache | 会丢 metadata，破坏 citation。 |
| Java 侧强制补 citations | 更可靠，但要改 AiServices 输出后处理，W2 暂缓。 |

### 面试 Q&A

**Q1：为什么 W2 要自己写 KnowledgeBaseIndexer？**
- **A**：因为 W2 不只是“把文档嵌入进去”，还要让每个 chunk 带 source/section/citation_id，并且同一批 chunk 同时进入向量索引和 BM25 索引。默认 ingestor 很方便，但隐藏了 chunk metadata 生成过程，不适合 W2 的 hybrid + citation 需求。

**Q2：citation tracking 现在真的生效了吗？**
- **A**：底层 metadata 链路已经生效：chunk 里有 citation_id，HybridRetriever 用它去重，CitationTracker 能把 Content 转成 Citation。但最终 LLM 是否在 `ReviewResult.findings[].citations` 里正确输出，W2 仍依赖 prompt；这也是 W2 notes 里记录的后续风险。W3 可以在 Summarizer/post-processing 层强制注入 citation。

### Commit

```text
feat(rag): wire hybrid retrieval and citation metadata
```

---

## T11 · Eval pipeline label + tool status 入报告

### 技术细节

1. **`--pipeline` flag**
   - `EvalCommand` 新增 `--pipeline`，写入 report config。
   - 例如：

     ```bash
     java -jar target/code-review-agent-1.0.0.jar eval \
       --version v2-rag-hybrid \
       --pipeline w2-hybrid-rerank
     ```

   - 这样同一个 version/report 能记录“这次跑的是哪条能力路径”。

2. **SampleMetrics 增加 toolStatuses**
   - 原来只有 `toolCallsTotal` / `toolCallsFailed`。
   - W2 增加 `List<ToolStatus> toolStatuses`，保留具体工具状态。
   - 为了不改爆旧测试，加了一个向后兼容构造器，旧的 `new SampleMetrics(...)` 仍能编译。

3. **tool success rate 的计算**
   - `toolCallsTotal = result.toolStatus().size()`
   - `toolCallsFailed = status != ok` 的数量
   - 这不是 LangChain4j 底层 tool call 次数，而是 ReviewResult 暴露的工具状态数量。
   - 命名上未来可以更精确，比如 `reportedToolsTotal`。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| pipeline 写死在代码里 | W1 可以，W2/W3 多版本对比不够灵活。 |
| SampleMetrics 只存数字 | 指标好算，但排查某个 sample 工具为什么失败不方便。 |
| 改所有测试构造器 | 机械改动多，兼容构造器更稳。 |

### 面试 Q&A

**Q1：为什么 EvalReport 要记录 pipeline？**
- **A**：因为同一个代码版本可能跑不同组合：regex-only、spotbugs-search、hybrid-rag、rerank on/off。只记录 version 不够，pipeline 能让报告可复现、可比较。

**Q2：tool status 入报告有什么用？**
- **A**：如果某个 sample 没抓到 bug，我们要知道是 LLM 判断错了，还是 SpotBugs 没跑、CodeSearch 没用、RAG 没召回。tool status 是排查 eval 失败的关键证据。

### Commit

```text
feat(eval): record tool statuses and pipeline label
```

---

## T12 · W2 Eval 尝试与真实问题

### 技术细节

1. **v2 eval 尝试**
   - 命令：

     ```bash
     rm -f ~/.code-review-agent/cache/review-guidelines-v2.json
     java -jar target/code-review-agent-1.0.0.jar eval \
       --version v2-rag-hybrid \
       --pipeline w2-hybrid-rerank
     ```

   - 环境里存在真实 `MOONSHOT_API_KEY`，所以 eval 实际开始调用 Moonshot。
   - indexer 首次重建了 `review-guidelines-v2` cache，并加载 52 chunks。

2. **为什么没有提交 v1/v2 report**
   - 运行过程中发现 shell 里有 `DEBUG=release`。
   - Spring Boot 会把 `debug` property 视为开启 debug 模式，导致 condition report 和 RestClient request body 被打出来。
   - 这会把完整 prompt、diff、RAG context 打到日志里，噪音太大，也不适合保留为正式 eval 运行记录。
   - 因此中止了长跑，没有把半成品 report 当成指标。

3. **LLM 输出解析问题**
   - 运行中出现过一次 `OutputParsingException`。
   - 模型返回看似 JSON 的内容，但内部 evidence 字符串/字段格式导致 LangChain4j 无法反序列化成 `ReviewResult`。
   - `EvaluationRunner` 已按设计 catch 异常，把 sample 记为 review error，但这会影响指标。
   - 这说明 W3 前必须加强 JSON 输出约束或修复策略。

4. **Moonshot 超时**
   - 长 eval 过程中出现 `ResourceAccessException` / `HttpTimeoutException`，LangChain4j 自动 retry。
   - Hybrid RAG + reranker 增加了请求次数和上下文长度，20 samples 变得更慢、更容易遇到网络波动。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 强行跑完并提交指标 | 不诚实，日志环境和解析失败都未清理。 |
| 中止并记录 caveat | 指标 pending，但工程状态可信。 |
| 关闭 reranker 再跑 | 可以作为 A/B，但不能冒充 `v2-rag-hybrid`。 |
| 先修 JSON mode/repair 再跑 | 更稳，是 W3/W2.1 的合理后续。 |

### 面试 Q&A

**Q1：W2 为什么没有正式 v1/v2 指标？**
- **A**：代码能力和测试已经完成，但正式 eval 暴露了两个运行环境/稳定性问题：一个是 `DEBUG=release` 导致 Spring 打完整请求体，另一个是 LLM 输出偶发无法解析。我没有伪造指标，而是把这两个问题记录下来。对 eval-driven 项目来说，不可信的指标比没有指标更糟。

**Q2：你怎么修 LLM JSON 不合法？**
- **A**：三层方案。短期：加强 prompt，要求 evidence 不要包含未转义多行 diff，或让 evidence 用数组。中期：加 retry/repair，捕获 `OutputParsingException` 后把原始输出交给小 prompt 修成 schema。长期：不用 AiServices 自动 parse，改 lower-level `ChatModel` + JSON mode + 显式 ObjectMapper 校验，失败时可控重试。

**Q3：为什么 `DEBUG=release` 会影响 Spring Boot？**
- **A**：Spring Boot 会读取环境变量并 relaxed binding 到 `debug` property。`DEBUG` 不是 false/空时，就可能启用 debug 模式，打印 condition evaluation report 和更多底层日志。CLI 跑 eval 时应该用 `env -u DEBUG ...` 清掉它。

### 正式 eval 建议命令

```bash
env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval \
  --version v2-rag-hybrid \
  --pipeline w2-hybrid-rerank
```

如果要严格生成 v1：

```bash
git checkout 4f7469f
mvn -q clean package -DskipTests
env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval \
  --version v1-spotbugs-search \
  --pipeline w2-spotbugs-codesearch
```

### Commit

```text
docs(w2): record implementation status and eval caveats
```

---

## W2 总结

### 已完成

- SpotBugs 工具链：`SourceCompiler` → `SpotBugsAnalyzer` → `RuleCheckerTool`
- CodeSearchTool：本地 Java 文件 substring search
- RAG 扩展：8 篇 guideline、52 chunks、BM25 + vector hybrid、LLM rerank
- Citation 链路：`ChunkMetadata`、metadata-bearing `TextSegment`、`CitationTracker`
- Eval 扩展：20 reverse-style samples、pipeline label、tool status 入 per-sample report
- 文档：README / CLAUDE / 本 W2 notes

### 验证结果

```text
mvn -q test                         PASS
mvn -q clean package -DskipTests    PASS
sample_count=20                     PASS
KnowledgeBaseIndexer chunks=52      PASS
```

### 未完成 / 不伪装完成

- `eval/reports/v1-spotbugs-search.json` 未提交。
- `eval/reports/v2-rag-hybrid.json` 未提交。
- 原因不是代码没编过，而是正式 eval 运行发现 debug 环境污染、模型输出解析失败、Moonshot 超时，需要清理后重跑。

### W3 前最该补的三件事

1. **JSON 稳定性**
   - 加 JSON mode / repair retry / lower-level parser，避免单个 sample 因格式问题直接变 FN。

2. **Eval 运行配置**
   - 清理 `DEBUG` 环境变量。
   - 调整 timeout。
   - 必要时支持 sample subset，先跑 smoke，再跑全量。

3. **Citation 后处理**
   - 不只相信 LLM 写 citation。
   - W3 pipeline/Summarizer 可以从检索结果中自动补 citation，保证 citation 可审计。
