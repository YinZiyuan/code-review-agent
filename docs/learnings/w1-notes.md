# W1 学习笔记

> 这份文档跟随 W1 实现 task-by-task 累积。每个任务沉淀三段：技术细节、设计权衡、面试 Q&A。
> 目标：W1 跑完后整理成博客 / 面试 cheat sheet。

**Spec：** [`../superpowers/specs/2026-05-17-code-review-agent-design.md`](../superpowers/specs/2026-05-17-code-review-agent-design.md)
**Plan：** [`../superpowers/plans/2026-05-17-code-review-agent-w1.md`](../superpowers/plans/2026-05-17-code-review-agent-w1.md)

---

## T1 · 切换到 Spring Boot 3.5 + LangChain4j Spring Boot Starter

### 技术细节

1. **`spring-boot-starter-parent` 的"父 POM"机制**
   - 通过 `<parent>` 继承一个由 Spring Boot 团队维护的"巨型 BOM"。它做了三件事：
     - 锁定了 200+ 常用库的版本（包括 Jackson、SLF4J、JUnit、Mockito、AssertJ），写依赖时**不用写 `<version>`**
     - 配置好 maven-compiler-plugin、surefire、failsafe 等插件
     - 提供 `spring-boot-maven-plugin` 用于打可执行 jar
   - 我们覆盖了 `java.version=17` 属性，告诉父 POM 编译目标。原来 pom 里手写的 `<maven.compiler.source>` / `<target>` 都不需要了
   - 这是为什么我们在 dependencies 里写 `spring-boot-starter-test` 时不需要版本号

2. **LangChain4j Spring Boot Starter vs 手动构造 ChatModel**
   - 原来的代码：`OpenAiChatModel.builder().baseUrl(...).apiKey(...).build()` — 手工构造
   - Spring Boot starter 模式：在 `application.yml` 里配 `langchain4j.open-ai.chat-model.*`，starter 自动注册一个 `ChatModel` bean 到容器
   - 任何 `@Component` 都能 `@Autowired ChatModel chatModel` 拿到。这是 Spring 的"约定优于配置"哲学，AI 应用版
   - 同样的，`langchain4j-spring-boot-starter` 会扫描 `@AiService` 注解或手工创建的 `AiServices`

3. **版本号差异：`1.15.0-beta25` (starter) vs `1.15.0` (core)**
   - `dependency:tree` 显示 `langchain4j-core` 解析到 `1.15.0`（无 beta），但我们写的 starter 是 `1.15.0-beta25`
   - 原因：starter 的 POM 里依赖的是不带 beta 的 core。这是 LangChain4j 的"Spring Boot 集成尚在 beta，但底层核心已稳定"的过渡状态
   - 风险：starter 和 core 的 API 不完全同步。需要在实现时遇到不一致再调

4. **`langchain4j-easy-rag` 和 `langchain4j-embeddings-bge-small-en-v15-q`**
   - `easy-rag`：开箱即用的 RAG 工具集（文档加载器、splitter、ingestor），W1 还在用，W2 升级为自定义 hybrid retrieval 后可能去掉
   - `bge-small-en-v15-q`：内嵌量化的 BGE-small 嵌入模型（ONNX runtime），约 100MB，本地跑不需要调用任何外部 API。对中文 PR 的语义检索效果不算最好，但**零成本 + 零延迟**，最适合作为 baseline

### 设计权衡

| 选项 | 为什么没选 |
| --- | --- |
| 不用 starter，自己写 `@Configuration` 注 `ChatModel` bean | 失去 `application.yml` 配置体验，且要重写一遍 LangChain4j starter 已经做好的事 |
| 用 Spring AI 而不是 LangChain4j | Spring AI 是 Spring 官方对标 LangChain 的库，但生态比 LangChain4j 浅，且我们已经在 LangChain4j 投入了。换框架不是 W1 应该做的事 |
| 用 LangChain4j 1.0.x 稳定版 | 1.0.x 没有 Spring Boot 3.x 兼容的 starter；spec 里 user 已确认走 1.15-beta |

### 面试 Q&A

**Q1**：你的项目为什么用 Spring Boot 而不是只用 LangChain4j 直接 main 函数跑？
- **A**：三个层面。**配置**：把 model/endpoint/timeout 从代码硬编码搬到 `application.yml`，profile 切换零成本（review/eval/sample 三个 profile）。**依赖管理**：spring-boot-starter-parent 锁定了上百个常用库版本，避免手动管理。**生态对接**：starter 的 `ContentRetriever` 自动注入到 `AiServices`，省掉了组装代码。本质上是把 LangChain4j 从"库"用法切换到"框架"用法，更接近生产。

**Q2**：LangChain4j 的 starter 和 langchain4j-core 是什么关系？为什么版本号能不同？
- **A**：core 是引擎（`ChatModel`、`AiServices`、`@Tool` 等 API），starter 是 Spring Boot 集成层（auto-configuration、property binding、bean 注册）。core 比较稳定（1.15.0），starter 还在 beta（1.15.0-beta25）。Maven 解析时父子继承关系决定哪个版本胜出 —— starter 显式声明了 `<version>1.15.0</version>` 的 core，所以 core 实际是 1.15.0。**生产风险**：starter 写在 beta 阶段的代码可能依赖 core 还没发布的 API，所以遇到怪异 NoSuchMethod 错误时先检查版本对齐。

**Q3**：`mvn dependency:tree` 你在迁移过程中怎么用？
- **A**：三个场景。**版本冲突排查**：搜 `langchain4j-core`，确认只有一个版本被解析（Maven 默认就近原则可能选错）。**bloat 排查**：看哪些 transitive 依赖被拉进来（比如发现意外多了一个 SLF4J 实现，会导致警告）。**漏依赖排查**：编译报错 `ClassNotFoundException` 时，看依赖树有没有需要的 jar，没有就显式加 dependency。我在这次迁移里就是用它确认了 starter 和 core 的版本错配。

### Commit

```
build: migrate to Spring Boot 3.5 + LangChain4j 1.15 starters
```

---
