# W3 学习笔记

> 这份文档跟随 W3 实现 task-by-task 累积。每个任务沉淀三段：技术细节、设计权衡、面试 Q&A。
> W3 的叙事主线是：**先把 W2 的单 Agent 在真实 eval 上稳住（W3a），再把它拆成确定性 pipeline（W3b）**。
> 一句话总结整周：从「让 LLM 自己决定调哪个工具、自己拼 JSON」演进到「确定性编排 + 一次有界 LLM 调用 + 确定性后处理」。

**Spec：** [`../superpowers/specs/2026-05-31-code-review-agent-w3-design.md`](../superpowers/specs/2026-05-31-code-review-agent-w3-design.md)
**Plan：** [`../superpowers/plans/2026-05-31-code-review-agent-w3.md`](../superpowers/plans/2026-05-31-code-review-agent-w3.md)

> **两阶段的关系**：W3a 的四个组件（`JsonRepair`、`RetrievalRecorder`、`CitationKeywordInjector`、`GuardedCodeReviewAgent`）是**为了把 W2 单 Agent 跑出可信指标**而临时加的护栏；其中 `JsonRepair` 和 `CitationKeywordInjector` 在 W3b 被 pipeline 复用并保留，`RetrievalRecorder` 和 `GuardedCodeReviewAgent` 在 W3b 被**故意删除** —— pipeline 化后不再需要 ThreadLocal 抓取和 AiServices 包装。这是有意的「先搭脚手架跑通指标，再拆掉脚手架重构架构」。

---

# Phase 1 · W3a — 单 Agent 稳定化（T1-T7）

## T1 · JsonRepair — LLM JSON 的 parse-or-repair 护栏

### 技术细节

1. **为什么 W3 开篇就做 JsonRepair**
   - W2 eval 暴露的头号问题：模型偶发返回「看起来像 JSON 但解析不了」的内容（多行 evidence 没转义、尾逗号、markdown fence 包裹），导致 LangChain4j 抛 `OutputParsingException`，`EvaluationRunner` 把整个 sample 记成 review error → 直接变成 FN，污染指标。
   - eval-driven 项目里，**不可信的指标比没有指标更糟**。所以 W3a 第一刀就是给 JSON 解析加护栏，否则后面 v1/v2 跑出来的数字都不能信。

2. **两段式 API**
   - `parseOrRepair(raw, type)`：先直接 `mapper.readValue`，失败才走 LLM 修复 —— **happy path 零额外成本**。
   - `repairThenParse(raw, type)`：跳过直接解析，强制走修复（用于已知一定坏的场景）。
   - 修复失败抛 `RepairFailedException`（自定义 RuntimeException），不静默吞掉。

3. **修复 prompt 的约束设计**
   - prompt 明确写「**只修语法（引号、逗号、转义），不要增删或改语义内容**」—— 防止修复模型顺手「补全」或「改写」findings，破坏评测公平性。
   - 要求「Return ONLY the corrected JSON - no prose, no markdown fences」。

4. **`extractJson` —— 从模型啰嗦输出里抠 JSON**
   - 模型即使被要求「只返回 JSON」也常包一层 ```` ```json ```` 或前后缀解说。
   - `extractJson` 取 `indexOf('{')` 到 `lastIndexOf('}')` 的子串 —— 简单粗暴但对单 JSON 对象足够稳。

5. **双 ObjectMapper：camelCase + snake_case fallback**
   - 主 mapper 用默认命名；`snakeCaseMapper` 是它的 `copy()` 加 `SNAKE_CASE` 策略。
   - 原因：模型有时输出 `lineRange`，有时输出 `line_range`（schema 里写的是 snake_case）。`readValue` 先试主 mapper，失败再试 snake_case mapper，**两种都不行才抛原始异常**。
   - 这是对「模型不严格遵守字段命名」的容错。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 加强 prompt 让模型永远输出合法 JSON | prompt 工程能降低概率，但**消不掉**偶发坏 JSON；护栏是兜底，二者叠加 |
| 用 OpenAI JSON mode / structured output | Moonshot 端点的 JSON mode 支持不稳定，且 LangChain4j beta 封装层未必透传；先做框架无关的修复护栏 |
| 修复失败就当 FN（W2 行为） | 单个格式抖动直接变召回损失，指标失真。**值得花一次额外 LLM 调用救回来** |
| 修复时允许模型改语义 | 会让评测作弊（模型借修复之名补 finding）。**强约束只修语法** |

### 面试 Q&A

**Q1：为什么不直接用模型的 JSON mode，而是自己写 parse-or-repair？**
- **A**：三层考虑。①**可移植性**：JsonRepair 不依赖任何特定端点的 structured-output 能力，Moonshot/OpenAI/本地模型都能用。②**beta 封装风险**：LangChain4j 1.15 beta 对 JSON mode 的透传不保证，我不想把指标可信度押在一个 beta 特性上。③**happy path 零成本**：`parseOrRepair` 先直接解析，绝大多数请求根本不触发修复调用。生产上当然可以叠加 JSON mode 进一步降概率，但护栏作为最后一道防线始终该在。

**Q2：修复 prompt 为什么强调「只修语法不改语义」？这有什么坑？**
- **A**：因为修复也是一次 LLM 调用，模型有「帮忙」的倾向 —— 如果不约束，它可能在修复时补全它觉得缺的 finding、改写 description，这在 eval 场景下等于**让模型借修复通道作弊**，破坏指标公平。约束语义不变后，修复退化成纯语法工具。坑在于：约束是软的，模型仍可能轻微改写，所以 W3b 的 pipeline 路径更进一步 —— 把去重/补全/排序全部交给确定性的 `Summarizer`，**不再信任 LLM 做这些**。

**Q3：你的 `extractJson` 用 `indexOf('{')..lastIndexOf('}')`，遇到 JSON 里嵌了 `}` 怎么办？**
- **A**：对**单个顶层 JSON 对象**这个策略是正确的 —— 最外层一定是第一个 `{` 和最后一个 `}`，内部嵌套的 `}` 都在这个范围内，截出来的子串包含完整结构。它会失败的场景是：模型返回了多个独立 JSON 对象，或在 JSON 之后又跟了带 `}` 的解说文本。前者我们的 schema 不会出现（始终单 `ReviewResult`），后者截出来的串会包含尾巴 → 解析失败 → 进入修复。所以最坏情况是多走一次修复，不会静默产出错误结果。

### Commit

```text
feat(infra): JsonRepair parse-or-repair guard for LLM JSON drift
fix(infra): decode LangChain4j base64 payload before JSON repair
```

### 踩坑实录

**坑 1：LangChain4j 把原始模型输出 base64 编码进 `OutputParsingException` 消息**
- 现象：v1/v2 正式 eval 时，JsonRepair 拿到的「原始 JSON」根本不是 JSON，而是形如 `... (base64: "eyJ...")` 的异常消息文本，修复始终失败。
- 原因：LangChain4j 的 `OutputParsingException` 在某些路径下把原始模型输出**base64 编码**后塞进 message，而不是明文。`e.text()` 拿到的是这层包装。
- 修复：加 `normalize(raw)` —— 用正则 `\(base64: "([A-Za-z0-9+/=]+)"\)` 抓出 payload，`Base64.getDecoder().decode` 还原成 UTF-8 明文，再交给解析/修复。
- 教训：**beta 框架的异常对象不要假设里面是「人类可读的原始输出」**，先打出来看真实格式。这个 fix 是 v1/v2 eval 真正跑通的前提，单独一个 commit。

---

## T2 · RetrievalRecorder — per-review 抓取 RAG 命中（W3a 临时，W3b 删除）

### 技术细节

1. **要解决的问题**
   - W2 的痛点之一：citation 完全靠 LLM 在 `findings[].citations` 里自己填，经常空着或编造不存在的 id。
   - 要做「Java 侧补 citation」就得知道**这次 review 到底检索到了哪些 chunk**，但 LangChain4j 的 `ContentRetriever` 注入是隐式的，agent 内部调用，外面拿不到命中结果。

2. **`ThreadLocal<List<Content>>` 抓取**
   - `RetrievalRecorder` 持一个 `ThreadLocal<List<Content>>`，在 `RagConfig` 里把最终 retriever 包一层 lambda：检索后 `recorder.record(hits)` 再返回。
   - `GuardedCodeReviewAgent` 在一次 review 前 `clear()`、review 后 `snapshot()` 拿候选 citation、用完再 `clear()`。
   - 用 ThreadLocal 是因为 review 可能并发（eval 多 sample），每个线程的命中要隔离。

3. **为什么 W3b 把它删了**
   - pipeline 化后，`LlmReviewer` **自己显式调用** `retriever.retrieve(query)` 并拿到 `hits`，直接转成 citation candidates 往下传 —— 检索结果是方法局部变量，不再需要 ThreadLocal 这种「侧信道」抓取。
   - 这是脚手架的典型命运：它存在的唯一理由是「AiServices 把检索藏起来了」，一旦检索回到显式调用，它就没有存在意义。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| ThreadLocal 抓取 | 在不改 AiServices 内部的前提下唯一能拿到命中的方式；但本质是侧信道，有生命周期管理负担（必须 clear） |
| 改用显式 retriever 调用 | W3a 还在 AiServices 架构里，检索由框架触发，做不到 —— 这正是 W3b 要根治的 |
| 全局 static 收集 | 并发下串台，pass |

### 面试 Q&A

**Q1：为什么 W3a 要用 ThreadLocal 这种「侧信道」抓 RAG 命中？**
- **A**：因为 W2/W3a 还是 AiServices 架构 —— 检索是框架根据 `@SystemMessage` 和 contentRetriever 隐式触发的，agent 方法返回的只有最终 `ReviewResult`，中间检索到哪些 chunk 外部根本看不到。要在 Java 侧补 citation，就必须旁路抓取，ThreadLocal 能保证并发 review 之间隔离。这是**在不重写架构的约束下**的合理 hack。但它有生命周期负担（用前 clear、用后 clear，漏一次就串数据），所以 W3b 一旦把检索改成 `LlmReviewer` 显式调用，我立刻删掉了它 —— **侧信道是架构不透明的症状，根因解决后症状就该消失**。

**Q2：ThreadLocal 用完不 `remove()` 会怎样？**
- **A**：两个风险。①**内存泄漏**：线程池复用线程，ThreadLocal 的 value 挂在线程上不释放，长跑服务会累积。②**数据串台**：复用线程的下一次 review 会看到上一次的残留命中，补出错误 citation。所以代码里 review 前后都 `clear()`（内部 `hits.remove()`）。这也是为什么我更喜欢 W3b 的显式方案 —— 局部变量随栈自动回收，没有这类隐患。

### Commit

```text
feat(rag): RetrievalRecorder ThreadLocal for per-review RAG hits
```

---

## T3 · CitationKeywordInjector — 空 citation 的关键词回填

### 技术细节

1. **定位：Java 侧的 citation 兜底**
   - 不再只信 LLM 写 citation。对**每条 citations 为空的 finding**，用它的 `title + description` 去和检索候选的 `section` 做关键词匹配，命中就回填。
   - 已有 citation 的 finding **原样保留，绝不替换** —— 只补空的，不覆盖 LLM 已经做对的。

2. **匹配算法（双向 token 包含）**
   - 把 finding 文本和候选 section 都转小写、按 `[^a-z0-9]+` 切词。
   - 命中条件：section 的某个 ≥4 字符的词出现在 finding 文本里，**或**反过来 finding 的某个 ≥4 字符词出现在 section 里。
   - ≥4 字符门槛是为了滤掉 `the`/`use`/`sql`(3字符也滤) 这类噪音词导致的误匹配。

3. **不可变重建**
   - `ReviewFinding` 是 record，回填 citation 要 `new ReviewFinding(...)` 整体重建（只换 citations 字段，其余照搬）。
   - 返回新 list，不原地改。

4. **W3b 的复用**
   - 这个组件**活到了 W3b** —— `Summarizer` 直接注入它做 citation 回填。因为「关键词回填」是确定性逻辑，正是 pipeline 后处理想要的。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 只信 LLM 填 citation | W2 已证明不可靠（空/编造），必须 Java 侧兜底 |
| 用 embedding 语义匹配回填 | 更准，但要再跑一轮向量计算，成本/复杂度高；关键词匹配对「section 名 vs finding 描述」这种短文本够用 |
| 覆盖 LLM 已有 citation | LLM 选对时不该被规则推翻；**只补空** |
| 门槛设 3 字符 | `sql`/`api` 这类真关键词会漏，但 `the`/`use` 噪音也进来；4 字符是召回/噪音的折中（`sql` 用其他词如 `injection`/`query` 命中） |

### 面试 Q&A

**Q1：citation 回填为什么用关键词而不是语义相似度？**
- **A**：匹配的是「finding 描述」对「知识库 section 标题」，这是短文本对短标题，关键词重叠的信号已经很强（比如 finding 提到 "parameterized queries"，section 就叫 "Parameterized Queries"）。语义匹配要再跑 embedding，对每条空 citation × 每个候选算相似度，成本和延迟都上去了，收益不明显。**先用便宜确定的方案，eval 看回填准确率不够再上语义**。而且关键词匹配是确定性的，可复现、好调试，符合 W3 「把不确定性赶出后处理」的主线。

**Q2：为什么只回填空 citation，不覆盖已有的？**
- **A**：分工原则。LLM 在 prompt 里被明确告知「只能引用候选列表里的 id」，当它真的填了 citation，说明它做了判断且 id 合法，规则不该推翻它。回填的职责是**兜底**而非**仲裁** —— 只管 LLM 漏掉的空位。如果连已有的也覆盖，相当于让一个粗糙的关键词规则去否决模型的语义判断，多半是降质。

### Commit

```text
feat(rag): CitationKeywordInjector minimal back-fill for empty citations
```

---

## T4 · GuardedCodeReviewAgent — 包装 AiServices（W3a 临时，W3b 删除）

### 技术细节

1. **装饰器模式包住 W2 agent**
   - `GuardedCodeReviewAgent implements CodeReviewAgent`，内部持有真正的 AiServices 代理（`inner`），加上 `JsonRepair`、`RetrievalRecorder`、`CitationTracker`、`CitationKeywordInjector`。
   - 它是 `@Primary` bean，AiServices 代理降级成 `aiServicesCodeReviewAgent`（非 primary）—— CLI/eval 注入的是包装后的 guard。

2. **一次 review 的护栏流程**
   ```
   recorder.clear()
   try inner.review(req)
   catch OutputParsingException → jsonRepair.parseOrRepair(e.text(), ReviewResult)
   candidates = tracker.toCitations(recorder.snapshot())
   updated = injector.inject(result.findings(), candidates)
   recorder.clear()
   return new ReviewResult(summary, updated, toolStatus)
   ```
   - 即：**JSON 修复兜底 + citation 回填**两件事一次包进去。

3. **为什么 W3b 删除**
   - guard 是「把护栏硬塞进一个我不能改其内部的 AiServices 黑盒外面」的折中。W3b 直接把黑盒拆了 —— `JsonRepair` 进 `LlmReviewer`，citation 回填进 `Summarizer`，护栏从「外包装」变成「pipeline 里的显式 stage」。装饰器没有存在必要了。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 装饰器包 AiServices | W3a 不想动 W2 架构就能加护栏，最小侵入；但护栏和业务揉在一个 try/catch，职责不清 |
| 直接改 AiServices agent | AiServices 是框架生成的代理，没法插逻辑 |
| 等 W3b 一步到位 pipeline | 那样 v1/v2 指标就跑不出来；先用 guard 稳住单 Agent，**才能拿到可对比的 baseline** |

### 面试 Q&A

**Q1：为什么用装饰器而不是直接改 agent？这是不是过度设计？**
- **A**：不是过度设计，是**约束下的最优**。AiServices 的 agent 是 LangChain4j 用动态代理生成的，我没有它的源码，插不进 try/catch 和后处理。装饰器（同样实现 `CodeReviewAgent` 接口、内部委托）是在「不能改被装饰对象」时加横切逻辑的标准手段，配合 Spring 的 `@Primary` 让注入点无感切换。它的代价是护栏逻辑和「调用 inner」耦合在一个类里，职责不够单一 —— 这也是为什么它是**临时**的：W3b 拆开黑盒后，每个护栏回到它该在的 pipeline stage。能识别「什么时候装饰、什么时候该重构掉装饰」比单纯会用模式更重要。

**Q2：W3a 加了一堆「临时会删」的代码，值得吗？**
- **A**：值得，因为它们解锁了**可信的 v1/v2 指标**。eval-driven 项目里，v1→v2→v3 的纵向对比是核心产出；如果 W2 单 Agent 跑不出干净数字，v3 pipeline 的提升就无从证明。W3a 这层脚手架的 ROI 就是「让三个版本能在同一套 20 样本上公平对比」。而且其中 `JsonRepair`/`CitationKeywordInjector` 是永久资产，真正纯临时的只有 `RetrievalRecorder` 和这个 guard 两个类。**先搭脚手架跑通度量，再拆脚手架重构** —— 这是有纪律的演进，不是浪费。

### Commit

```text
feat(agents): GuardedCodeReviewAgent wraps AiServices with JsonRepair + citation backfill
```

---

## T5 · EvalCommand 环境加固 — 清 DEBUG、--samples、--suite

### 技术细节

1. **清 `debug` 系统属性**
   - W2 eval 翻车的直接原因之一：shell 里 `DEBUG=release`，Spring Boot relaxed binding 把它当成开启 debug 模式，把完整 prompt/diff/RAG context 全打进日志。
   - `EvalCommand.call()` 开头检测 `System.getProperty("debug")`，非空就 warn + `clearProperty` —— 从代码侧兜底，不再依赖用户记得 `env -u DEBUG`。

2. **`--samples` 子集过滤**
   - 接受逗号分隔 sample id（`reverse-001,reverse-002`），传给 `EvaluationRunner.run(...)` 的新 `Set<String> filter` 参数。
   - 用途：先跑 2 个 smoke，再跑全量；调试单个失败 sample 不用等 20 个跑完。

3. **`--suite smoke|dev|release`**
   - `smoke`：前 2 个样本（按目录名排序 limit 2）。
   - `dev`：全部样本 × 1 次。
   - `release`：全部样本 × `runs_per_sample`（来自 properties，不靠 sample filter 区分）。
   - suite/pipeline 都写进 report 的 config block，让报告**可复现可比较**。

4. **重命名：旧 `--samples`（路径）→ `--samples-dir`**
   - 为了腾出 `--samples` 给 CSV 过滤，原本的目录覆盖选项改名 `--samples-dir`。

5. **`EvaluationRunner` 的 per-sample 超时重试**
   - `run(...)` 加 `Set<String> sampleIdFilter` 重载（旧 4 参签名转发 null，向后兼容）。
   - `callAgentWithRetry`：捕获 `HttpTimeoutException`/`SocketTimeoutException`/`ResourceAccessException`（沿 cause 链扫类名），超时重试一次。
   - 重试只对超时类异常，不对解析失败 —— 解析失败由 JsonRepair 管。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 靠用户记得 `env -u DEBUG` | W2 已经证明会忘；代码侧 clearProperty 才稳 |
| 不做 suite，全靠手敲 `--samples` | suite 让 smoke/dev/release 语义显式、命令可复现 |
| 重试所有异常 | 解析失败重试无意义（确定性坏），只重试网络超时 |
| 无限重试 | eval 要可终结；一次重试覆盖瞬时抖动，仍失败就记录 |

### 面试 Q&A

**Q1：为什么 `DEBUG` 环境变量会污染 Spring Boot 的日志？**
- **A**：Spring Boot 有 relaxed binding —— 它会把环境变量/系统属性映射到 property，`DEBUG`/`debug` 只要不是 false/空就可能开启 debug 模式，打印 condition evaluation report 和底层 HTTP request body。我们的端点会把完整 prompt、diff、RAG context 全打出来，噪音淹没真信号，也不适合留作正式 eval 记录。W2 是手动 `env -u DEBUG` 绕，W3a 在 `EvalCommand` 入口直接 `clearProperty("debug")` 兜底 —— **把「记得清环境」从人的纪律变成代码保证**。

**Q2：为什么超时重试只重试一次，且只对超时类异常？**
- **A**：两个判断。①**只对超时**：网络超时是瞬时、可恢复的（Moonshot 偶发抖动 + hybrid RAG 拉长了请求）；而 JSON 解析失败是确定性的，重试同样的输入还是失败，该交给 JsonRepair 而不是傻重试。沿 cause 链扫异常类名是因为框架会层层包装，顶层 catch 不到原始超时类型。②**只重试一次**：eval 必须能终结，不能因为一个 sample 卡住无限重跑；一次重试足够吃掉绝大多数瞬时抖动，仍失败就如实记录为 error，让指标反映真实稳定性。

### Commit

```text
feat(eval): clear DEBUG sysprop, --samples filter, --suite flag
```

---

## T6 · chat-model timeout 60s → 90s

### 技术细节

1. **为什么调大超时**
   - W3a 的 v2 路径是 hybrid RAG + LLM reranker —— 比 W1 多了 BM25 检索 + 一次额外的 rerank LLM 调用 + 更长的上下文。单次 review 的端到端 LLM 耗时明显上升，60s 在 20 样本里会偶发触顶。
   - 改 `application.yml` 的 `langchain4j.open-ai.chat-model.timeout` 60s → 90s，给 hybrid 路径留余量，配合 T5 的重试一起降低 eval 中断率。

2. **W3b 的反向变化**
   - W3b pipeline 化后**砍掉了 reranker 的强依赖、收窄了编排**，平均延迟反而从 v2 的 ~8.3s 降到 v3 的 ~4.5s（见总结表）。所以 90s 在 W3b 是宽松上限，不再是瓶颈。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 维持 60s | hybrid + rerank 会偶发触顶，制造假 FN |
| 调到 90s | 给慢路径余量；正常请求远没那么久，不影响 happy path |
| 配置化按 suite 调 | over-engineering，一个全局上限够用 |

### 面试 Q&A

**Q1：超时从 60 调到 90 是不是在「掩盖慢」？**
- **A**：不是掩盖，是**对齐真实成本**。v2 路径客观上多了 BM25 + reranker 调用 + 更长上下文，端到端就是更慢，60s 会把「本来能成功只是慢」的请求误判成失败，制造假 FN，污染指标。调大超时是让上限匹配该路径的真实分布。但这确实暴露了 v2 架构「为了召回堆了太多 LLM 调用」的问题 —— 所以 W3b 的回答不是继续调大超时，而是**重构掉冗余调用**，v3 延迟直接腰斩到 4.5s。超时调整是止血，架构重构才是治本。

### Commit

```text
chore(config): bump chat-model timeout 60s -> 90s for hybrid RAG eval
```

---

## T7 · W3a eval — 产出 v1 / v2 报告 + eval/README

### 技术细节

1. **v2 先跑（当前代码路径）**
   - `v2-rag-hybrid` / pipeline label `w2-hybrid-rerank`，20 dev 样本。先删 `~/.code-review-agent/cache/review-guidelines-v2.json` 强制重建索引（52 chunks）。
   - 验收门槛：per-sample 列表里**不能有 `review error`** —— 有的话说明 JsonRepair 还没兜住，得回 T1 继续加固，不许带病出报告。

2. **v1 从 pre-hybrid commit 跑**
   - v1 代表「SpotBugs + CodeSearch，无 hybrid RAG」能力。干净复现的方式是 checkout 那个 commit（`4f7469f`）单独 build jar 再跑 —— 因为靠 config 关 RAG 很脆（W2 retriever 链路仍会触发向量检索）。
   - 用 `git worktree` 在 `/tmp` 起独立工作树跑，产物 copy 回 W3 树。那个 commit 早于 `--suite`，所以显式 `env -u DEBUG`。

3. **eval/README.md**
   - 把 v0/v1/v2/v3 的可复现命令固化成文档，含 smoke check —— 让「怎么重跑某版本」不再是口口相传。

4. **v1/v2/v3 指标对比**（完整见文末总结表）
   - v1 recall 0.50 / precision 0.31 / fp_rate 0.69
   - v2 recall 0.65 / precision 0.37 / fp_rate 0.63
   - 结论：hybrid RAG 把召回从 0.50 提到 0.65，但 precision 仍低、误报率高 —— **这正是 W3b pipeline 要解决的「LLM 自由发挥导致噪音」**。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 强行跑完带 review error 的报告 | 不诚实，W2 已经栽过；**有 error 就不出报告** |
| v1 靠 config 关 RAG | 脆，向量检索仍会跑，不是干净的「无 hybrid」 |
| v1 checkout 旧 commit + worktree | 干净复现该能力的真实代码路径 |
| 只跑 v2 不跑 v1 | 失去纵向对比，v3 的提升无从锚定 |

### 面试 Q&A

**Q1：v1 为什么不在当前代码上用配置关掉 RAG 来跑，而要 checkout 旧 commit？**
- **A**：因为「用配置关 RAG」关不干净。W2 的 retriever 链路即使 `top-k: 0`、`rerank-enabled: false`，向量 retriever 可能仍被构造并执行，跑出来的根本不是「无 hybrid」的真实路径，那 v1 指标就名不副实。最忠实的复现是 checkout 引入 hybrid 之前的那个 commit（`4f7469f`），它的代码本身就只有 SpotBugs + CodeSearch。我用 `git worktree` 在 `/tmp` 起独立工作树编译运行，产物 copy 回来，主工作树不受污染。**指标的诚实性 > 复现的便利性**。

**Q2：v1→v2 召回涨了（0.50→0.65）但 precision 还是低（~0.37），你怎么解读？**
- **A**:hybrid RAG 给了模型更多相关规范，所以它**找到了更多真问题**（召回涨）；但它同时也更敢报，把不确定的也写出来，**误报没降下来**（precision 低、fp_rate 0.63）。根因是 W2/W3a 架构里 LLM 既负责发现、又负责去重/排序/定稿，自由度太大 → 噪音多。这个诊断直接定义了 W3b 的目标：**把「定稿」从 LLM 手里收回到确定性的 Summarizer**，让 LLM 只做有界的「发现 + 判断」。v3 结果验证了这个判断 —— precision 从 0.37 跳到 0.67，fp_rate 从 0.63 降到 0.33。

### Commit

```text
eval(w3a): v1 + v2 reports on 20 samples
```

---

# Phase 2 · W3b — pipeline 拆分（T8-T14）

> **架构转折**：删除 `@Tool` / `@SystemMessage` / `AiServices`，换成四个确定性 `@Component` 串成的 pipeline：
> `DiffAnalyzer → ToolFindingsProducer → LlmReviewer → Summarizer`。
> `CodeReviewAgent` 仍是接口，`PipelineCodeReviewer` 是唯一实现。核心思想：**确定性编排 + 一次有界 LLM 调用 + 确定性后处理**。

## T8 · ReviewContext + CodeSnippet — 不可变 pipeline 载体

### 技术细节

1. **两个 record 作为 pipeline 的数据契约**
   - `CodeSnippet(file, line, text)`：一条 grep 命中的跨文件上下文。
   - `ReviewContext(rawDiff, fileDiffs, contextByFile, sourceRoot)`：一次 review 的「世界状态」—— 原始 diff、解析后的 FileDiff、按文件分组的上下文、源码根。

2. **compact constructor 强制不可变**
   - `ReviewContext` 的 compact 构造器对 `fileDiffs`/`contextByFile` 做 `List.copyOf` / `Map.copyOf`，null 转空集合。
   - 结果：拿到 `ReviewContext` 的下游 stage 无法篡改它（`.put` 抛 `UnsupportedOperationException`）—— pipeline 各 stage 间传的是**只读快照**。

3. **为什么 pipeline 要显式的「上下文对象」**
   - W2 的工具各自 `gitClient.diff(...)` 重复取数；pipeline 里第一个 stage（DiffAnalyzer）把世界状态一次性构造好，后续 stage 共享同一个不可变 `ReviewContext`，**自然去重、零隐式重算**。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| record + compact constructor 防御性拷贝 | 不可变、线程安全、零样板；stage 间传只读快照 |
| 普通可变 POJO | stage 可能误改共享状态，pipeline 难推理 |
| 每个 stage 自己取数 | 回到 W2 的重复 git diff 问题 |

### 面试 Q&A

**Q1：pipeline 各 stage 之间为什么要传不可变对象？**
- **A**：pipeline 是「数据流过一串变换」，如果中间载体可变，任何一个 stage 都可能（有意或 bug）改掉共享状态，下游就要防御性地怀疑「我拿到的还是原始 diff 吗」，整条链路难以推理和测试。`ReviewContext` 用 record + compact constructor 里的 `List.copyOf`/`Map.copyOf` 做防御性拷贝，保证它一旦构造就是只读快照 —— DiffAnalyzer 产出后，ToolFindingsProducer / LlmReviewer 拿到的一定是同一份没被动过的世界状态。这让每个 stage 都能独立单测：给定 context in，断言 out，不用担心隐藏的可变副作用。

### Commit

```text
feat(pipeline): ReviewContext + CodeSnippet immutable records
```

---

## T9 · DiffAnalyzer — 确定性的 identifier-grep 上下文构建

### 技术细节

1. **pipeline 第一站：把「agent 可见的世界」显式化**
   - 输入 raw diff + sourceRoot，输出 `ReviewContext`。
   - 步骤：①`DiffParser.parse` 拿结构化 FileDiff（真实文件行号）；②从新增行抽 identifier；③对每个 identifier 在 `source-before/` grep，拿跨文件上下文。
   - sourceRoot 为 null 或非目录时：返回只有 fileDiffs、`contextByFile` 为空的 context，**不抛异常**（review 仍能只看 diff 跑）。

2. **identifier 抽取的两个正则 + stopword**
   - `METHOD_CALL = \b([a-z][A-Za-z0-9]+)\s*\(`：小写开头 + 后跟 `(` → 方法调用名。
   - `TYPE_NAME = \b([A-Z][A-Za-z0-9]+)\b`：大写开头 → 类型名。
   - `STOPWORDS`：`if/for/new/return/this/String/List/Map/Override...` 这类语言关键字和泛型容器名 —— 抽出来 grep 没意义还稀释上下文。
   - 上限：每文件最多 6 个 identifier、最多 20 条命中 —— **控制喂给 LLM 的上下文预算**。

3. **复用 `CodeSearchTool.grep`（W2 工具去 @Tool 化）**
   - `CodeSearchTool` 在 W3b 去掉 `@Tool` 注解，`searchCode` 改名 `grep(rootPath, needle)`，变成被 pipeline 直接调用的普通组件。
   - DiffAnalyzer 调 `search.grep(...)`，解析返回的 `path:line: snippet` 行成 `CodeSnippet`，跳过 `No matches`/`Not a directory`/`[truncated` 这些非命中行。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 确定性抽 identifier + grep | 可复现、零 LLM 成本地给模型补跨文件上下文 |
| 让 LLM 自己决定搜什么（W2 @Tool） | 不可控、不可复现、多花 LLM round-trip |
| 抽全部 identifier | 上下文爆炸；6 个/文件 + 20 命中是预算折中 |
| sourceRoot 缺失就报错 | review 应能降级到「只看 diff」，**graceful** |

### 面试 Q&A

**Q1：W2 让 LLM 自己调 CodeSearchTool，W3b 改成 DiffAnalyzer 确定性 grep，为什么这是进步？**
- **A**：W2 的 tool-self-decision 有三个问题：①**不可复现** —— 同一个 diff，LLM 这次搜 `foo` 下次搜 `bar`，eval 结果抖动；②**多 round-trip** —— 每次工具调用是一次 LLM 交互，慢且贵；③**不可控** —— LLM 可能搜无关的东西或不搜。DiffAnalyzer 把「搜什么」变成确定性规则（从新增行抽方法名/类型名，过滤 stopword，限量），**每次同样的 diff 产出同样的上下文**，零额外 LLM 调用就把跨文件上下文喂给后面那一次有界的 review 调用。这就是 W3 主线「把不确定性从 LLM 手里收回到确定性代码」在输入侧的体现 —— 对应 v3 延迟和 precision 的双重改善。

**Q2：identifier 抽取的 stopword 和数量上限解决什么问题？**
- **A**：解决「上下文信噪比」。不过滤的话，`String`/`List`/`if`/`return` 这些到处都是的词会 grep 出几百条无关命中，把真正有用的跨文件上下文淹没，还撑爆 prompt 预算。stopword 滤掉语言噪音，6 identifier/文件 + 20 命中/文件 的上限保证喂给 LLM 的是「最相关的少量上下文」而非「全仓库 dump」。这些数字是经验值，跟 W1 的 diff 截断阈值一样 —— 先定一个合理上限，用 eval 看召回有没有因为上下文不足而掉，再调。

### Commit

```text
feat(pipeline): DiffAnalyzer identifier-grep over source root
```

---

## T10 · ToolFindings + ToolFindingsProducer — 确定性的工具发现层

### 技术细节

1. **职责：跑静态分析，产出确定性 findings + tool 状态**
   - `ToolFindings(violations, statuses)` record：`violations` 是 Regex + SpotBugs 命中，`statuses` 是各工具运行状态。
   - `ToolFindingsProducer.produce(ctx)`：对 `ReviewContext` 跑 `RegexAnalyzer`（纯 diff）+ best-effort SpotBugs（需编译 `source-before/`），合并结果。

2. **tool_status 从「LLM 回填」变成「Java 产出的确定性数据」**
   - W2 的 `[tool_status]` 是文本 footer，靠 prompt 让 LLM 回填进 `ReviewResult.tool_status` —— 软约束，常漏。
   - W3b 里 tool status 由 producer **直接产出结构化数据**，包括 SpotBugs 在样本不可编译 / SpotBugs 不可用时的 graceful skip 记录。LLM 不再碰这个字段（prompt 明确写 `Leave 'tool_status' as []; the pipeline will fill it`）。

3. **承接 W2 的 SpotBugs 链路**
   - 复用 W2 的 `SourceCompiler`（best-effort javac）→ `SpotBugsAnalyzer`（XML 解析 + 过滤到新增行）。删掉了 W2 的 `RuleCheckerTool`（它的编排逻辑搬进了 producer）。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| tool_status 由 Java 产出 | 确定性、可信；不再赌 LLM 回填 |
| 保留 LLM 回填 tool_status | W2 已证明会漏，且是过程元数据不该靠模型 |
| 删 RuleCheckerTool | 它的逻辑就是「跑 analyzer 并汇总」，正好是 producer 的职责，无需保留 LLM 工具门面 |

### 面试 Q&A

**Q1：tool_status 从「LLM 回填」改成「Java 产出」，为什么重要？**
- **A**：tool_status 是**过程元数据**（哪些工具跑了/跳过/失败），不是代码问题。它的消费者是 CI 和评测系统，用来判断「这次 review 可信吗」—— 比如 SpotBugs 没跑成功，那「没发现 bug」就不能等同于「没有 bug」。W2 靠 prompt 让 LLM 把工具的文本状态回填进结果，是软约束，模型经常漏写，导致报告里 tool_status 缺失或不准。W3b 让 `ToolFindingsProducer` 直接产出结构化 status，**确定性的事实就该由确定性代码产出，不该绕一圈赌模型**。这也释放了 LLM 的注意力 —— prompt 里直接告诉它「tool_status 留空，pipeline 会填」。

### Commit

```text
feat(pipeline): ToolFindings + ToolFindingsProducer (Regex + SpotBugs)
```

---

## T11 · LlmReviewer — 唯一一次有界的 ChatModel 调用

### 技术细节

1. **pipeline 里**唯一**的 LLM 调用**
   - 不再有 `@Tool` / agent 自主决策。`LlmReviewer.review(ctx, tools)` 显式：①构造检索 query → ②`retriever.retrieve` 拿命中 → ③`tracker.toCitations` 转候选 → ④拼一个大 prompt → ⑤`chatModel.chat` 一次调用 → ⑥`jsonRepair.parseOrRepair` 解析。
   - 返回 `Draft(result, citationCandidates)` —— 把候选 citation 一并传给下游 Summarizer。

2. **prompt 把四类输入显式分块喂入**
   - `[DIFF]` + `[TOOL FINDINGS]`（渲染的 violations）+ `[CROSS-FILE CONTEXT]`（DiffAnalyzer 的 grep 上下文）+ `[CITATION CANDIDATES]`（编号候选列表）。
   - **citation 防幻觉**：SYSTEM prompt 明确「只能引用候选列表里出现的 id，不许编造，空数组允许」。
   - **行号约束**：明确「line 指新文件（post-change）」—— 延续 W1 DiffParser 的不变量。

3. **检索 query 的确定性构造**
   - `buildQuery` 把各 FileDiff 的 path + 新增行内容（截到 1000 字符）+ violations 的 rule/message（截到 2000 字符）拼成 query。
   - query 空时 fallback 到 rawDiff 或字面 `"code review"`。
   - 检索由 reviewer **显式调用**，不再需要 W3a 的 `RetrievalRecorder` ThreadLocal 侧信道。

4. **JSON 解析走 JsonRepair**
   - 不用 AiServices 的自动 parse，直接 `ChatModel.chat` 拿文本 → `JsonRepair.parseOrRepair` —— 把 T1 的护栏正式接进 pipeline 主路径。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 一次有界调用 | 可控、可复现、省 round-trip；prompt 显式包含所有上下文 |
| 保留多轮 tool-calling | 不可控、慢、eval 抖动 —— 正是 W3b 要消除的 |
| 候选 citation 编号注入 + 强约束 | 把「能引用什么」限定死，根治 LLM 编造 citation |
| 直接 ChatModel + JsonRepair vs AiServices 自动 parse | 前者把解析失败的处理权握在自己手里，可修复可控 |

### 面试 Q&A

**Q1：从「LLM 自主多轮调工具」到「一次有界调用」，丢了什么、得了什么？**
- **A**:**丢了** agent 的自主探索能力 —— 理论上 multi-turn agent 能根据中间结果决定再搜点什么、再看哪个文件。**得了** 可控性、可复现性、低延迟、低成本。对「代码评审」这个任务，输入是确定的（diff + 源码 + 规范），不需要开放式探索；我用 DiffAnalyzer 确定性地把跨文件上下文备齐、用 ToolFindingsProducer 把静态发现备齐、用检索把规范候选备齐，**一次性喂给模型让它做有界判断**就够了。代价是如果某个 case 真需要深挖额外上下文，单次调用看不到 —— 但 eval 数据说话：v3 的 precision/延迟双双大幅改善，说明对这个任务自主探索的边际收益小于它带来的噪音和成本。**自主性不是越多越好，要匹配任务的开放程度**。

**Q2：citation 编造问题你在 pipeline 里是怎么根治的？**
- **A**:三道防线。①**输入侧**：prompt 里给模型一个**编号的候选 citation 列表**（来自真实检索命中），并强约束「只能用列表里出现的 id，不许发明，空数组 OK」。②**解析侧**：JsonRepair 保证拿到结构化结果。③**后处理侧**：Summarizer 用 CitationKeywordInjector 对空 citation 做确定性回填。三层下来，citation 要么是模型从真实候选里选的、要么是规则从真实候选里补的，**没有任何环节能凭空产生不存在的 citation**。对比 W2 完全靠 prompt 求模型「请填 citation」，这是把约束从「祈求」变成「机制保证」。

### Commit

```text
feat(pipeline): LlmReviewer single-call ChatModel with bounded citation candidates
```

---

## T12 · Summarizer — 确定性的去重 / 补全 / 排序 / 定稿

### 技术细节

1. **职责：把「定稿」从 LLM 手里收回来**
   - `summarize(draft, tools, citationCandidates)`：以 LLM draft 的 findings 为基底，做四件确定性的事 → 补静态发现 → 去重 → 回填 citation → 排序，产出最终 `ReviewResult`。

2. **补静态发现（missing-finding fill）**
   - 遍历 `tools.violations()`：跳过 SUGGESTION 级、跳过已被 LLM finding 覆盖的（同文件且行号差 ≤2）。
   - 剩下的（CRITICAL/WARNING 且 LLM 漏报的）转成 finding 补进去 —— **静态分析的高危发现不会因为 LLM 没提就丢失**。
   - 补进来的 finding `source` 标 `spotbugs`/`regex`，category 记 OTHER。

3. **去重（dedup）**
   - bucketKey = `file | line/5 | title 前30字符小写` —— 同文件、相近行（5 行一桶）、相似标题视为重复。
   - 同 key 保留 severity 更高的（CRITICAL < WARNING < SUGGESTION 的 rank 更小者胜）。

4. **排序（sort）**
   - 三级排序：severity rank → file → line。CRITICAL 排最前，行号 null 排最后。
   - 让报告稳定有序，**可复现**（eval 对比时顺序一致）。

5. **citation 回填**
   - 复用 W3a 的 `CitationKeywordInjector`（注入进 Summarizer）—— pipeline 后处理阶段做关键词回填。

6. **`sameFile` 的路径容错**
   - 比较 file 时容忍 `a/b/Foo.java` vs `Foo.java`（互为后缀也算同文件）—— diff 路径和 violation 路径前缀可能不一致。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 确定性后处理（dedup/fill/sort） | 可复现、不依赖 LLM 自律；高危静态发现保底不丢 |
| 让 LLM 自己去重排序（W2） | 不稳定、不可复现，且会漏静态高危项 |
| 补全跳过 SUGGESTION 级 | 低优先级静态噪音不强塞，避免拉低 precision |
| 行号差 ≤2 视为覆盖 | LLM 和 analyzer 对同一问题报的行号可能差一两行，太严会重复报 |
| 5 行一桶去重 | 同一段代码的近似重复合并；桶太大会误合并不同问题 |

### 面试 Q&A

**Q1：为什么把去重/补全/排序交给 Summarizer，而不是让 LLM 在 prompt 里做？**
- **A**:因为这些是**确定性任务**，确定性任务交给确定性代码才可靠、可复现、可测试。W2 让 LLM 负责定稿的后果就是 v2 的高误报、低 precision —— 模型会重复报同一问题、漏掉静态分析已经抓到的高危项、排序随机。Summarizer 把这四件事变成代码：dedup 用 bucketKey 合并近似项并保留高 severity；missing-finding fill 保证 CRITICAL/WARNING 级静态发现即使 LLM 没提也会进最终报告（**安全保底**）；sort 给出稳定顺序让 eval 可比。LLM 只剩「发现问题 + 判断」这一件它真正擅长且无法被规则替代的事。**v3 precision 0.67 vs v2 0.37 就是这个职责重分配的直接结果**。

**Q2：missing-finding fill 为什么跳过 SUGGESTION 级、却保留 CRITICAL/WARNING？**
- **A**:这是 precision/recall 的精细权衡。静态分析器（尤其 regex）的 SUGGESTION 级命中噪音大（风格类、低置信度），如果 LLM 没在它的 review 里提及，多半是模型判断它不重要 —— 强塞进去会拉低 precision、制造误报。但 CRITICAL/WARNING 级是高危信号（潜在 NPE、SQL 注入模式等），这类**宁可多报也不能漏**，所以即使 LLM 漏了也补进去保底。换句话说：低优先级跟随 LLM 判断，高优先级由静态分析兜底。这个分级处理让 Summarizer 既不放大噪音、又守住召回下限。

### Commit

```text
feat(pipeline): Summarizer dedup + backfill + sort
```

---

## T13 · PipelineCodeReviewer — 替换 AiServices，成为唯一 agent

### 技术细节

1. **四 stage 串联**
   ```java
   String diff = extractDiff(request);
   ReviewContext ctx = diffAnalyzer.analyze(diff, sourceRoot);
   ToolFindings tools = toolFindingsProducer.produce(ctx);
   LlmReviewer.Draft draft = llmReviewer.review(ctx, tools);
   return summarizer.summarize(draft.result(), tools, draft.citationCandidates());
   ```
   - `PipelineCodeReviewer implements CodeReviewAgent`，是唯一的 `@Component` agent bean。

2. **彻底移除 LangChain4j agent 表面**
   - 删除 AiServices builder、`GuardedCodeReviewAgent`、`RetrievalRecorder`。
   - `CodeReviewAgent` 去掉 `@SystemMessage`（prompt 常量搬进 `LlmReviewer.SYSTEM`）。
   - `GitDiffTool`/`CodeSearchTool` 去掉 `@Tool` 注解变普通组件，`RuleCheckerTool` 删除。
   - 生产 review 路径**不再有** `@Tool`/`@SystemMessage`/`AiServices`。

3. **`extractDiff`**
   - 从 request 里找 `diff --git` marker 截取 —— 容忍 request 前面带额外说明文字。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| pipeline 四 stage 显式编排 | 每 stage 单一职责、可单测、可复现 |
| 保留 AiServices 作为备选路径 | 两套架构并存增加维护负担，且 spec 明确 pipeline 是方向 |
| 保留 @Tool 工具门面 | pipeline 直接调用普通方法即可，门面纯属冗余 |

### 面试 Q&A

**Q1：用 pipeline 完全替换 AiServices，是不是放弃了 LangChain4j 的核心价值？**
- **A**:没有放弃，是**用对了部分**。LangChain4j 我仍在用 —— `ChatModel` 调用、`ContentRetriever` / embedding store / RAG 这套检索基建、各种数据类型。我放弃的只是 `AiServices` + `@Tool` 那层「让 LLM 自主决定调用什么」的自动编排。对代码评审这种输入确定、不需要开放式探索的任务，自动编排带来的不可控、不可复现、多 round-trip 成本超过收益。pipeline 把编排权收回到 Java 代码：确定性地准备所有上下文，只在 `LlmReviewer` 那一处做一次有界 LLM 调用。**框架的检索/模型能力照用，框架的 agent 自主性按任务需要选择性放弃** —— 这是工程判断，不是否定框架。

**Q2:删掉 `RuleCheckerTool`、给 `GitDiffTool`/`CodeSearchTool` 去 @Tool，会不会破坏 CLI？**
- **A**:不会。这些工具的**核心逻辑没动**，只是去掉了 `@Tool` 注解（那是给 AiServices 自动暴露给 LLM 用的）和重命名。CLI 的 `review` 命令现在通过 pipeline 走 —— `DiffAnalyzer` 直接调 `CodeSearchTool.grep`，`ToolFindingsProducer` 直接跑 analyzer。`RuleCheckerTool` 删除是因为它的职责（跑 analyzer 并汇总成文本喂 LLM）已经被 `ToolFindingsProducer`（产出结构化 violations）取代，留着是死代码。这是「工具从 LLM 门面降级为被直接调用的普通组件」—— 逻辑复用，表面简化。CLI 行为不变，pipeline IT 测试覆盖端到端。

### Commit

```text
feat(pipeline): PipelineCodeReviewer replaces AiServices agent; remove @Tool surface
```

---

## T14 · v3-pipeline eval 报告

### 技术细节

1. **v3 dev eval**
   - `--version v3-pipeline --pipeline w3-pipeline --suite dev`，20 dev 样本。
   - smoke 先过：recall 1.00 / precision 1.00 / fp_rate 0.00（2 样本）。
   - 全量 dev：**recall 0.70 / precision 0.67 / fp_rate 0.33 / 平均延迟 4530ms**。

2. **三版本纵向对比**（详见总结表）
   - recall：0.50 → 0.65 → **0.70**
   - precision：0.31 → 0.37 → **0.67**
   - fp_rate：0.69 → 0.63 → **0.33**
   - 延迟：34.6s → 8.4s → **4.5s**
   - **pipeline 在召回小涨的同时，precision 近翻倍、误报率减半、延迟再降** —— 验证了「确定性编排 + 有界 LLM + 确定性后处理」的判断。

3. **诚实的 caveat**
   - 20 样本不足以形成稳定 release 基线，v3 数字要等 W4 的 40 样本、多次运行结果进一步验证。
   - v3 的 `severity_accuracy` 低于 v2 —— severity 校准是下一个明显的调优点。
   - `tool_success_rate` 仍受 SpotBugs 在不可编译样本上的 skip 影响 —— 要决定这个指标是否该区分「预期内 skip」和「analyzer 失败」。

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 20 样本就宣布 v3 胜出 | 趋势明确但样本不足；标注为「待 W4 验证」更诚实 |
| 跑完全程 release suite | W3 时间不够；20 dev 足够定方向，release 留 W4 |
| 隐藏 severity_accuracy 回退 | 不诚实；如实记为下一个调优目标 |

### 面试 Q&A

**Q1：v3 precision 从 0.37 跳到 0.67，你归因于什么？**
- **A**:主要是**职责重分配**，不是模型变强（同一个 Kimi）。三个具体机制：①`DiffAnalyzer` + `ToolFindingsProducer` 确定性地把上下文和静态发现备齐，LLM 拿到的输入信噪比更高；②`LlmReviewer` 一次有界调用 + 候选 citation 强约束，模型不再自由发散；③`Summarizer` 确定性去重排序，消掉了 W2 那种「同一问题重复报、随机噪音」。误报主要来自 W2 让 LLM 又发现又定稿的自由度，pipeline 把定稿收回确定性代码后，噪音自然降下来。**fp_rate 0.63→0.33 减半**和 precision 翻倍是同一件事的两个度量。当然 20 样本要谨慎下结论，但趋势和机制是自洽的。

**Q2:你为什么不把 v3 跑满 release suite 再下结论？**
- **A**：工程节奏。W3 的目标是**验证 pipeline 架构方向对不对**，20 dev 样本上三版本同条件对比已经能清楚回答这个问题（precision/fp_rate/延迟全面改善，趋势一致）。跑满 40 样本 × 多 run 的 release suite 是 W4 的事：它能扩大覆盖面、降低单次模型随机性的影响，但仍不是对真实 PR 分布的统计显著性证明。提前跑 release 既花时间又没必要，而且我如实在 notes 里把 v3 标为方向性结果，没有包装成最终结论。**先用小样本快速验证方向，再用更大样本和重复运行建立稳定 release 基线**。

### Commit

```text
eval(w3b): v3 pipeline report on 20 samples
docs(w3): pipeline architecture, v1/v2/v3 metrics, W3 learning notes
```

---

# W3 总结

## 已完成

**W3a — 单 Agent 稳定化（脚手架）**
- `JsonRepair`：parse-or-repair 护栏 + base64 payload 解码 + snake_case fallback（**永久保留**）
- `RetrievalRecorder`：ThreadLocal 抓 RAG 命中（W3b 删除）
- `CitationKeywordInjector`：空 citation 关键词回填（**W3b 复用**）
- `GuardedCodeReviewAgent`：装饰器包 AiServices（W3b 删除）
- `EvalCommand` 加固：清 DEBUG、`--samples` 过滤、`--suite smoke/dev/release`、超时重试
- timeout 60s → 90s
- v1 / v2 报告 + `eval/README.md`

**W3b — pipeline 拆分（架构）**
- `ReviewContext` / `CodeSnippet`：不可变 pipeline 载体
- `DiffAnalyzer`：确定性 identifier-grep 上下文
- `ToolFindings` / `ToolFindingsProducer`：确定性工具发现 + tool_status
- `LlmReviewer`：唯一一次有界 ChatModel 调用 + 候选 citation 强约束
- `Summarizer`：确定性 dedup / 补全 / citation 回填 / 排序
- `PipelineCodeReviewer`：替换 AiServices，移除全部 `@Tool`/`@SystemMessage`/`AiServices`
- v3 报告

## Eval 结果

| Version | Pipeline | Recall | Precision | FP Rate | Avg Latency |
| --- | --- | --- | --- | --- | --- |
| `v1-spotbugs-search` | `w2-spotbugs-codesearch` | 0.50 | 0.3125 | 0.6875 | 34618.9 ms |
| `v2-rag-hybrid` | `w2-hybrid-rerank` | 0.65 | 0.3714 | 0.6286 | 8353.9 ms |
| `v3-pipeline` | `w3-pipeline` | 0.70 | 0.6667 | 0.3333 | 4530.7 ms |

> 读法：hybrid RAG（v1→v2）主要拉召回但没治误报；pipeline 化（v2→v3）在召回小涨的同时**precision 近翻倍、误报率减半、延迟再降近半** —— 把「定稿」从 LLM 收回确定性代码是 precision 跃升的主因。

## 验证结果

```text
W3 前 baseline: mvn test                  PASS (59 tests)
W3b pipeline:   mvn -q test               PASS (79 tests)
                mvn -q clean package      PASS
v3 smoke eval                             recall 1.00 / precision 1.00 / fp_rate 0.00
rg "review error" 三个报告                无命中（接收时）
```

## W3 一句话主线

> **W3a 搭脚手架让 W2 单 Agent 跑出可信的 v1/v2 指标；W3b 拆掉「LLM 自主编排」换成「确定性编排 + 一次有界 LLM 调用 + 确定性后处理」，用 v3 指标证明这个方向。** 期间识别出「哪些脚手架是永久资产（JsonRepair / CitationKeywordInjector），哪些是用完即弃（RetrievalRecorder / GuardedCodeReviewAgent）」是有纪律演进的关键。

## W4 前最该补的三件事

1. **40 样本 release 评测**
   - v3 的 20 样本结论是方向性的，需要更大样本 + 多 run 降低偶然性，建立稳定 release 基线。

2. **severity 校准**
   - v3 `severity_accuracy` 回退到低于 v2，是最明显的调优目标。可在 Summarizer / prompt 层做 severity 归一化。

3. **tool_success_rate 语义澄清**
   - 该指标仍把「SpotBugs 在不可编译样本上的预期 skip」和「analyzer 真失败」混在一起，需要区分二者，否则指标会误导。
