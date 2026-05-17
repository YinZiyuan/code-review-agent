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

## T2 · Spring Boot 应用骨架 + ConfigurationProperties

### 技术细节

1. **`@SpringBootApplication` 是三个注解的合成糖**
   - `@SpringBootConfiguration`（一种 `@Configuration`，告诉 Spring 这是配置类）
   - `@EnableAutoConfiguration`（触发 starter 的 auto-config 机制，扫描 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`）
   - `@ComponentScan`（扫描同包及子包的 `@Component`/`@Service`/`@Configuration`）
   - 主类放在根包（`dev.langchain4j.example.codereview`），所以子包 `config/`、`cli/`、`tools/`、`agents/` 都被自动扫到

2. **`@ConfigurationPropertiesScan` vs `@EnableConfigurationProperties`**
   - 我们用的是 `@ConfigurationPropertiesScan`：自动扫描带 `@ConfigurationProperties` 的类（包括 `record`），不需要逐个注册
   - 替代方案是 `@EnableConfigurationProperties(CodeReviewProperties.class)`：显式列出每个 properties 类
   - scan 方式适合多个 properties 类的项目，eager 注册；explicit 方式更明确但啰嗦
   - **注意点**：`@ConfigurationProperties` 类如果用 `record`，必须 **没有** `@Component`，否则双重注册会冲突

3. **`record` 作为 ConfigurationProperties 的优势**
   - 不可变（构造后字段不能改）、自动有 equals/hashCode/toString、零样板
   - Spring Boot 3 支持 record 绑定（2.6+ 引入）
   - 嵌套 record（`Rag`/`Orchestration`/`Eval`）会被 Spring 自动递归绑定 `code-review.rag.top-k → CodeReviewProperties.rag().topK()`
   - **Kebab-case 自动转 camelCase**：YAML 写 `top-k`，Java 字段 `topK` —— 这是 Spring Boot 的 relaxed binding

4. **`Path` 和 `Duration` 类型的自动转换**
   - `${user.home}/.code-review-agent/cache` 自动转成 `java.nio.file.Path`，省掉手动 `Path.of(...)`
   - `60s` 自动转成 `java.time.Duration`，省掉 `Duration.ofSeconds(60)`
   - Spring Boot 内置了 `org.springframework.boot.convert.DurationStyle`，支持 `60s` / `1m` / `1h` / `PT1M30S`(ISO-8601)

5. **`web-application-type: none` 的意义**
   - 默认情况下 Spring Boot 检测到 classpath 上有 `spring-webmvc` 就会启动 Tomcat。我们是 CLI 应用，**显式禁用 web** 避免端口占用 + 启动延迟
   - 实测启动时间 0.591s（如果加载 web stack 通常要 2-3s）

6. **`api-key: ${MOONSHOT_API_KEY:}` 的两层 fallback**
   - `${VAR:default}` 是 Spring 占位符语法，没设环境变量就用 default（这里 default 是空串）
   - 这让 dev 环境用 dummy 也能起来 —— 但真调 LLM 时会报 401。我们之后会在 IT 测试里再覆盖

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| `record` vs 传统 POJO + Lombok `@Data` | record 是 JDK 内置，无第三方依赖；POJO 字段不可变性需要 `final` 显式声明。**选 record** |
| 整个 properties 写一个大 record vs 嵌套子 record | 大 record 字段会爆炸（10+ 个），嵌套结构和 YAML 层级对齐更可读。**选嵌套** |
| `@Value("${code-review.rag.top-k}")` vs `@ConfigurationProperties` | `@Value` 散落各处，难维护，无类型校验，IDE 重命名不安全。**选 properties** |
| 启动延迟可以接受到几秒吗 | CLI 应用每次启动都要付这个成本，越短越好。0.6s 可以接受；如果加 web stack 涨到 3s 就明显了 |

### 面试 Q&A

**Q1**：Spring Boot 的 auto-configuration 是怎么工作的？为什么我加个 `langchain4j-spring-boot-starter` 就有 `ChatModel` bean？
- **A**：每个 starter 在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 列出自己的 `@AutoConfiguration` 类。`@SpringBootApplication` 内含 `@EnableAutoConfiguration`，它在启动时读这个文件，加载所有 import 进 IoC 容器。每个 auto-config 类用 `@ConditionalOnClass` / `@ConditionalOnProperty` / `@ConditionalOnMissingBean` 决定是否真注册 bean —— 比如 LangChain4j 的 OpenAI auto-config 会检查 `langchain4j.open-ai.chat-model.api-key` 存在才注册 `OpenAiChatModel`。这套机制让 starter 实现"加依赖就生效，但用户能用 properties 关掉或覆盖"。

**Q2**：你的 `CodeReviewProperties` 为什么用 record？什么时候不能用？
- **A**：用 record 三个好处：①不可变（线程安全，配置不该被运行时改）、②自动 equals/hashCode/toString、③零样板。**不能用 record 的场景**：①需要 setter（非典型场景，配置不应该改）、②需要 JSR-303 字段级注解放在 setter 上（record 注解放构造参数即可，问题不大）、③要继承（record 是 final）。我们这里都是叶子配置，完美适配。**坑**：record 用 `@ConfigurationProperties` 时**不要**加 `@Component`，否则 Spring 会双重注册抱怨。

**Q3**：你把 web 关了（`web-application-type: none`），为什么不用 `spring-boot-starter` 而非 `spring-boot-starter-web`？这俩什么区别？
- **A**：starter（无后缀）就是核心 starter，只包含 Spring 容器 + logging + auto-config 基础设施。starter-web 在 starter 基础上加 spring-webmvc + 嵌入式 Tomcat。我们项目是 CLI，不需要 HTTP 端口，所以选 starter。但 LangChain4j 的 OpenAI starter 会传递依赖 `httpclient5`（用来调 LLM HTTP API），所以 jar 里还是会有 HTTP 客户端 —— 那是出站调用，不是入站监听。`web-application-type: none` 是双保险：即使将来有人意外加了 web 依赖，运行时也不会启动 Tomcat。

### Commit

```
feat: Spring Boot app skeleton + CodeReviewProperties
```

### 踩坑实录

**坑 1：`git add` 时携带了 4 个不相关的 doc/ 文件**
- 现象：`git add A B C` 然后 `git commit`，结果 commit 里多出了 4 个用户的个人 docx 文件
- 原因：未深究，可能是 IDE 自动 stage 或某个 hook。这些文件本来 untracked
- 修复：`git reset --soft HEAD~1` 撤销 commit（保留 staged 状态），`git reset HEAD doc/` 把 doc/ 路径 unstage，再 `git commit` 一次。现在它们回到 untracked 状态
- 教训：**重要节点 commit 前先 `git status` 确认 staged 范围**，特别是用 `Write` 工具创建过新文件之后

---
