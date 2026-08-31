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

## T3 · picocli 三子命令 + Spring Boot 整合

### 技术细节

1. **picocli-spring-boot-starter 干了三件事**
   - **`PicocliSpringFactory`** 自动注册为 `IFactory`，让 picocli 通过 Spring 容器解析 `@Command` 类（不再用 `new`）
   - 所有 `@Component + @Command` 类被收进 IoC，可以 `@Autowired` 任何 Spring bean
   - 没有自动 `main()` —— 我们仍要写 `CliRunner` 桥接 Spring 启动事件到 `CommandLine.execute(args)`

2. **`@EventListener(ApplicationReadyEvent.class)` —— Spring Boot 标准入口模式**
   - `ApplicationReadyEvent` 是 Spring Boot 应用**完全启动后**触发的事件，比 `@PostConstruct` 晚（IoC 完成后才能用 bean）、比 `CommandLineRunner` 灵活（CommandLineRunner 顺序不可控）
   - 另一种写法是实现 `CommandLineRunner` 或 `ApplicationRunner` —— 都可以，事件方式更显式
   - **API 注意**：Spring Boot 3.x 里 `event.getArgs()` 直接拿 `String[]`，**不是** `event.getApplicationArgs().getSourceArgs()`（plan 写错了，实际编译失败才发现）

3. **`ExitCodeGenerator` —— Spring Boot 退出码协议**
   - 实现这个接口，Spring Boot 在收到 `ContextClosedEvent` 时调用 `getExitCode()`，把返回值作为 JVM 退出码
   - 配合 `SpringApplication.exit(...)` 才能真正退出 JVM —— 否则 Spring Boot 不会主动结束进程（线程池仍在）
   - 这是为什么 `CodeReviewApplication.main` 写 `System.exit(SpringApplication.exit(SpringApplication.run(...)))`

4. **`ObjectProvider<T>` —— 延迟/可选依赖的 Spring 标准模式**
   - 直接 `private final CodeReviewAgent agent` 构造注入 → bean 不存在时启动失败
   - 改成 `ObjectProvider<CodeReviewAgent>` → 启动时只注入 provider（永远存在），运行时 `getIfAvailable()` 拿真 bean，没有返回 null
   - 这是 Spring Framework 4.3+ 的功能。等价于"延迟解析的依赖"，比 `@Autowired(required=false) + field` 优雅，比 `ApplicationContext.getBean()` 类型安全
   - **W1 用场景**：T3 写 `ReviewCommand` 时 `CodeReviewAgent` bean 还没造（T11 才造），用 ObjectProvider 让 T3 能独立 commit + 测试

5. **picocli `mixinStandardHelpOptions = true` 只在所在 Command 生效**
   - RootCommand 加了 → `--help` / `--version` 生效
   - 子 Command 没加 → `review --help` 报 "Unknown option"，但仍会 fallback 打印 usage 行（picocli 默认 error 行为）
   - 要给每个子 Command 也加，或者用全局策略。W1 不重要，暂不修

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| picocli vs Spring Shell | Spring Shell 偏交互式（REPL），picocli 偏一次性 CLI；我们要的是 `app review ...` 一次性运行，picocli 更合适 |
| `ObjectProvider` vs 临时 `@Bean` 占位 | 临时占位会污染代码（T11 还要再删一遍）；ObjectProvider 是惯用模式，留着也没问题 |
| 把 `main()` 放在 `CodeReviewApplication` 还是 `CliRunner` | `main()` 必须在 `@SpringBootApplication` 类，否则 spring-boot-maven-plugin 找不到入口；CLI 启动逻辑通过事件回调实现 |
| 子命令是否独立 jar | 单 jar 多命令更方便分发；如果要做 GraalVM native image，单 jar 也更友好 |

### 面试 Q&A

**Q1**：picocli + Spring Boot 整合，关键链路是什么？为什么需要 `IFactory`？
- **A**：picocli 默认通过反射 `clazz.getDeclaredConstructor().newInstance()` 创建 Command 对象 —— 这对依赖了 Spring bean 的 Command 来说不行（依赖永远是 null）。`IFactory` 是 picocli 给的扩展点：把"如何创建 Command 实例"委派给外部。`picocli-spring-boot-starter` 注册了 `PicocliSpringFactory`，它内部用 `ApplicationContext.getBean(clazz)` 拿 Spring 管理的实例 —— 这样 Command 上的构造器注入 / `@Autowired` 才生效。**没有这个桥接，Command 拿不到任何 Spring bean**。

**Q2**：你在 `ReviewCommand` 用了 `ObjectProvider<CodeReviewAgent>` 而不是直接注入。这有什么好处？什么时候应该这样写？
- **A**：三个理由。①**循环依赖破除**：A 注 B、B 注 A 时用 ObjectProvider 打断同步构造；②**可选依赖**：依赖可能不在容器里，`getIfAvailable()` 返回 null 即可；③**lazy resolution**：构造时不解析，运行时再要。我这里属于场景②——增量开发时下游 bean 还没造好，但 Command 必须能装配进 Spring 容器（picocli 需要它）。**生产场景**：跨模块插件式架构里，某个能力模块可能未启用，用 ObjectProvider 优雅降级。**反面**：如果依赖是核心必需的，直接构造注入更清晰，**fail-fast 优于 fail-soft**。

**Q3**：为什么不用 `CommandLineRunner` 直接跑 picocli，而是用 `@EventListener(ApplicationReadyEvent.class)`？
- **A**：行为基本等价 —— 都在 Spring 上下文完全初始化后调用。事件方式有两个细微优势：①**显式事件类型**，未来要响应不同生命周期阶段（比如 `ContextRefreshedEvent` vs `ApplicationReadyEvent`）粒度更细；②**多个监听器顺序可控**（通过 `@Order` 注解）。`CommandLineRunner` / `ApplicationRunner` 是 Spring Boot 内置的便利接口，但事件机制是 Spring Framework 原生的更基础设施。**选哪个不影响功能，但事件方式更"Spring 风格"**。

### Commit

```
feat(cli): picocli root + review/eval/sample subcommands
```

### 踩坑实录

**坑 2：plan 里 `ApplicationReadyEvent.getApplicationArgs().getSourceArgs()` 在 Spring Boot 3.5 不存在**
- 现象：编译报 "找不到符号 `getApplicationArgs()`"
- 原因：Spring Boot 3.x 把 args 直接挂在事件上 → `event.getArgs(): String[]`
- 修复：改成 `event.getArgs()`
- 教训：plan 里写的 API 是凭印象，**实际跑起来才能验证**。这就是为什么 T3 step 4 是"smoke test"，不是可选

**坑 3：`ReviewCommand` 构造注入 `CodeReviewAgent` 导致 ApplicationContext 启动失败**
- 现象：`--help` 还没执行，Spring 已经 `UnsatisfiedDependencyException` 退出
- 原因：T3 还没到 T11，`CodeReviewAgent` 没有任何 `@Bean` 生产者
- 修复：改用 `ObjectProvider<CodeReviewAgent>` 延迟解析
- 教训：**增量开发时，bean 依赖按"先有 placeholder，再补真实"组织**。ObjectProvider 是这种 staged 开发的关键工具

---

## T4 · DiffParser — 解析 unified diff，从 diff 行号到文件真实行号

### 技术细节

1. **Unified Diff 格式拆解（必背）**

   ```
   diff --git a/Foo.java b/Foo.java          ← 文件分隔标志
   index 1111111..2222222 100644             ← blob hash + mode（可选）
   --- a/Foo.java                            ← old 文件标识（a/ 前缀）
   +++ b/Foo.java                            ← new 文件标识（b/ 前缀）
   @@ -10,3 +10,5 @@ public class Foo {      ← hunk header
       int x = 1;                            ← 上下文行（' ' 开头）
       int y = 2;                            ← 上下文行
   +    int z = 3;                           ← 新增行（'+' 开头）
   +    System.out.println(z);               ← 新增行
       int w = 4;                            ← 上下文行
   ```

   - hunk header `@@ -OLD_START,OLD_COUNT +NEW_START,NEW_COUNT @@` —— `+10,5` 表示**新文件**从第 10 行起共 5 行（包含上下文 + 新增）
   - hunk header 后面的 `public class Foo {` 是"section header"（git diff 自动从最近的 `^[a-zA-Z]` 行抓的上下文），不参与解析

2. **行号映射的核心逻辑：`newLineNum` 计数器**

   ```
   for line in hunk:
     if line starts with '+' (not '+++'):
        record (newLineNum, content); newLineNum++
     elif line starts with '-' (not '---'):
        do nothing  ← 删除行不影响新文件行号
     else:                              ← 上下文行
        newLineNum++
   ```

   关键洞察：**新增行和上下文行都让 newLineNum 前进；删除行不前进**。这是把"diff 第 N 行"翻译成"new file 第 M 行"的全部秘密。
   - 原来 `RuleCheckerTool` 的 bug：直接用 `i + 1`（i 是 diff 数组的 index），导致行号偏离 — 比如说 hunk header `@@ -10,3 +10,5` 的 i 可能是 4，但实际文件行号是 10，差了 6

3. **正则的小细节**

   - `FILE_HEADER = ^\+\+\+ b/(.+)$` —— 只匹配新文件的 `+++ b/...`，不用 `--- a/...`（删除路径在 rename 场景下和新路径不同）
   - `HUNK_HEADER = ^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@.*$`
     - `(?:,\d+)?` 用 non-capturing group + `?` —— hunk 只有一行时 git 会省略 `,COUNT`（写成 `@@ -10 +10 @@`），所以是可选
     - `\+(\d+)` 捕获新文件起始行号（**只关心新行号**，老行号在这个 use case 里无用）

4. **为什么 `split("\n", -1)` 而不是 `split("\n")`**

   - `String.split(regex)` 默认丢掉**末尾空字符串**（`"a\n\n".split("\n")` 得到 `["a"]`）
   - `split(regex, -1)` 保留所有，包括末尾的空（得到 `["a", "", ""]`）
   - 这里我们循环逐行，**末尾空行没影响**，但养成习惯：解析文本类输入永远用 `-1`，避免 patch 文件最后一行被吞掉

5. **`record` 嵌套在外部类里**

   - `DiffParser.FileDiff` 和 `DiffParser.AddedLine` 是 static nested records（record 默认 static）
   - 这种"返回类型紧凑挂在 parser 类里"的写法，避免给每个简单 DTO 起独立文件名 + 包路径，是 Java 17+ 处理小数据结构的惯用法
   - 也是为什么 LangChain4j 的 `ChatResponse` 用类似嵌套结构

6. **`@Component` 注解为什么 W1 就加上**

   - DiffParser 是无状态、线程安全的 —— 完美的 singleton bean
   - T6 `GitDiffTool` 要构造注入 `DiffParser` → 必须是 Spring bean
   - 不加 `@Component` 也行（写 `new DiffParser()`），但每次都 new 浪费、且违反 IoC 原则

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 自己写 vs 用 `org.eclipse.jgit.diff.DiffParser` | jgit 是个 ~3MB 的依赖，且 API 偏 git 内部模型（`FileHeader`/`HunkHeader`/`Edit`），用起来比自己写正则还重。**自己写** |
| 解析所有行（含删除、上下文） vs 只关心新增 | W1 评测只关心**新增行的位置**（agent 报"哪行有问题"）。删除行不需要 review。**只关心新增** |
| 返回 `Map<String, List<AddedLine>>` vs `List<FileDiff>` | List 保留文件顺序（有时报告需要按文件顺序）；Map 查找快但丢顺序。**List** |
| 用 String 路径 vs `java.nio.file.Path` | git 路径都是 `/` 分隔的字符串，跨 OS 行为一致；`Path` 会做 OS 解析容易踩坑（Windows 反斜杠）。**用 String** |

### 面试 Q&A

**Q1**：你的 DiffParser 怎么把 diff 行号翻译成文件真实行号的？为什么之前的实现错了？
- **A**：核心是**只在 hunk 内、根据 hunk header 起始行号 + 行类型增减计数器**。hunk header `@@ -X,Y +M,N @@` 里 `+M` 是新文件的起始行号；遇到 `+` 或上下文行（` ` 开头）就 `newLineNum++`，遇到 `-` 行不变。原来的实现错在用 `i + 1`（diff 数组下标），不区分 hunk header 的偏移，所以每个 hunk 之后的行号都跑偏。**Bug 影响**：agent 报告"`Foo.java:5` 有问题"，但实际问题在 `Foo.java:42`，团队 review 找不到地方就不信任 agent。**修复后**所有 finding 的行号可以直接当 GitHub PR 评论的锚点用。

**Q2**：为什么你不用 jgit / 现成库来做 diff 解析？
- **A**：三个考虑。①**依赖体积**：jgit 是 ~3MB，对一个 CLI agent 不划算。②**API 错配**：jgit 的 `DiffParser` 返回 `FileHeader/HunkHeader/Edit` 这套 git 内部模型，我要的是"文件 → 新增行列表"这种 review 视角的数据结构，转换成本不低。③**unified diff 格式很稳定**：30+ 年没改过，自己写 100 行 + 5 个正则就能解决，可控性高。**反面**：如果未来要支持 rename 检测、merge conflict、binary diff，那时候考虑引入 jgit 是合理的。**原则**：选库前先问"我的需求是不是库的子集"。

**Q3**：你的 DiffParser 是 `@Component` 单例。线程安全吗？
- **A**：是的。两个层面。①**无可变状态**：类只有两个 `static final Pattern`，`parse()` 方法的所有变量（`currentPath` / `newLineNum` / `currentAdded`）都是**方法局部变量**，每次调用独立栈，多线程互不干扰。②**`Pattern` 自身线程安全**：JDK 文档明确说明 `java.util.regex.Pattern` 是 immutable + thread-safe，`Matcher` 不安全但每次调用 `pattern.matcher(line)` 都新建一个，留在栈上。**这就是"singleton + 无状态" 模式的标准应用**，Spring 默认的 singleton 作用域和它天然契合。**反面**：如果哪天加缓存 / 计数器作为字段，立刻就不安全了，那时候要么改成 prototype scope、要么用 `ConcurrentHashMap` / `AtomicInteger`。

### Commit

```
feat(infra): DiffParser with file-line-number mapping
```

---

## T5 · GitClient — 唯一的 git 子进程封装

### 技术细节

1. **JDK `ProcessBuilder` 的关键 4 个方法**

   ```java
   new ProcessBuilder("git", "diff", ref)
       .directory(repoPath.toFile())      // 设置 cwd（不设就是 JVM 启动目录）
       .redirectErrorStream(true)         // stderr 合并到 stdout，方便 readAllBytes 一把抓
       .start()                            // 异步启动子进程
       .waitFor(timeout, MILLISECONDS);   // 阻塞等待退出，超时返回 false
   ```

   - 不调 `redirectErrorStream(true)` 的话，stderr 默认到 `Process.getErrorStream()`，需要额外 thread 排空，否则**子进程 stderr 缓冲区满了会卡死**（经典坑）
   - `waitFor(timeout)` vs `waitFor()`：无 timeout 会无限等，**生产代码永远用带超时的版本**

2. **`readAllBytes() + UTF_8` 的现代写法**

   ```java
   String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
   ```

   - JDK 9+ 才有 `readAllBytes()`，之前要用 `BufferedReader` 循环 readLine 拼起来
   - 显式传 `StandardCharsets.UTF_8` —— 不传会用 JVM 默认 charset，**Windows 上是 GBK 会乱码**
   - 这种"小命令、小输出"场景 readAllBytes 最简洁；大输出（>10MB）要改流式处理避免 OOM

3. **超时 + 强制销毁的双保险**

   ```java
   if (!p.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
       p.destroyForcibly();
       throw new GitException("git command timed out after " + TIMEOUT);
   }
   ```

   - `destroyForcibly()` 发 SIGKILL，比 `destroy()` (SIGTERM) 更狠 —— 子进程拒绝退出时唯一的兜底
   - 没有这一步，git 卡住时 JVM 退出后仍然有僵尸进程占资源

4. **`InterruptedException` 的正确处理**

   ```java
   catch (IOException | InterruptedException e) {
       if (e instanceof InterruptedException) Thread.currentThread().interrupt();
       throw new GitException(...);
   }
   ```

   - `InterruptedException` 被 catch 时，**JVM 的中断标志会被自动清掉**
   - 上层（线程池、调用方）依靠中断标志判断是否要继续工作；不重置就会"中断信号丢失"
   - 这是 Java 并发的经典反例：教科书反复强调"never swallow InterruptedException without resetting"

5. **`@BeforeEach` 里搭真实 git 仓库的优势**

   - Mock `ProcessBuilder` 太脆 —— 要 mock `Process` / `InputStream` 一大串
   - `@TempDir` 给临时目录（每个测试一个），跑完自动清理
   - 在里面真跑 `git init` + 2 个 commit ≈ 200ms，**测试时间换正确性**
   - 副作用：测试机器必须装了 git CLI（CI 镜像基本都有）

6. **`commit.gpgsign=false` 配置的必要性**

   - 如果开发机的 `~/.gitconfig` 全局开了 `commit.gpgsign = true`，测试里的 `git commit` 会要求 GPG 密钥，CI 上没密钥就 fail
   - 测试里显式 `git config commit.gpgsign false`（local 覆盖 global），保证测试在任何机器上都过
   - **写测试时永远问一遍：测试结果依赖哪些环境配置？能不能用 local config 锁住？**

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| `ProcessBuilder` vs jgit `Git.open(repo)` API | jgit 是纯 Java 实现，不依赖系统 git，但 ~3MB 体积 + API 复杂。系统 git 几乎所有 dev/CI 环境都装了。**ProcessBuilder** |
| `RuntimeException` (GitException) vs checked exception | checked 强制调用方处理，但 git 错误通常是"不可恢复，报错给用户"性质。RuntimeException 让 tool 方法签名清爽。**RuntimeException** |
| 30s timeout vs 配置化 | 30s 对所有 git 操作够用（diff/show/log），加配置项 over-engineering。等出问题再加 |
| stderr 合并到 stdout vs 分开 | 分开能区分"正常输出" vs "warning/error"，但我们用 exit code 区分成功失败，stderr 内容只是失败时的诊断信息。合并简化代码 |

### 面试 Q&A

**Q1**：你封装 git 用 ProcessBuilder，相比 jgit 有什么取舍？
- **A**：ProcessBuilder 三个优势：①**零依赖**（系统 git 几乎处处都有），②**功能完整**（git 所有命令都能用，不像 jgit 只实现了子集），③**调试简单**（出问题 copy 命令到 shell 就能复现）。jgit 三个优势：①**跨平台一致**（不依赖系统 git 版本差异），②**性能更好**（同进程，没 fork 开销），③**API 类型安全**（不用解析文本输出）。**我选 ProcessBuilder** 因为这个 agent 跑在开发机和 CI 上，git 100% 存在；且 W1 只需要 `diff` / `show` 两三个命令，文本解析量小。**反面**：如果要做服务端常驻、并发跑几百次 git 操作，jgit 的同进程优势就明显。

**Q2**：你的 GitClient `run()` 方法里为什么要 `destroyForcibly()`，不是 `destroy()`？
- **A**：`destroy()` 在 Unix 发 SIGTERM —— 子进程可以注册 handler、做清理、甚至忽略。我们超时场景下进程**已经表现出有问题**（可能死循环、可能卡 I/O），SIGTERM 没法保证退出。`destroyForcibly()` 发 SIGKILL —— 内核强制 reap，子进程不可拒绝。**对应 JDK 文档保证**：destroyForcibly 返回的 Process 一定会终止。**反面**：destroyForcibly 不给子进程清理机会，可能留下临时文件 / 半写入的状态。对 git diff/show 这种**纯只读**操作没影响；对 git commit / push 就要小心（不过我们 GitClient 只暴露只读命令）。

**Q3**：你处理 InterruptedException 时为什么要 `Thread.currentThread().interrupt()`？
- **A**：Java 线程的"中断"是协作模型 —— 不是强制杀线程，而是设一个 boolean 标志。`Thread.sleep` / `wait` / `Object.wait` / `Process.waitFor` 等阻塞方法检测到中断标志会抛 `InterruptedException`，**抛出的同时把标志清掉**。如果我 catch 了不重置，外层（线程池 / Future / 调用方循环）就以为没人中断过我，继续派活。`Thread.currentThread().interrupt()` 重新设上标志，让中断信号沿调用栈传播。**经典 bug**：线程池里跑长任务，外面 `future.cancel(true)` 想停掉，被 catch 吞了中断信号 → 任务跑完才退出 → 线程池关不掉。**规则**：catch `InterruptedException` 之后必须做两件事之一 —— ①重新抛出（让上层处理）、②重新设中断标志（让上层检测）。我们这里因为要包装成 GitException，选②。

### Commit

```
feat(infra): GitClient subprocess wrapper with timeout
```

---

## T6 · GitDiffTool 重构 — 走 GitClient + 按文件拆分

### 技术细节

1. **重构动机：把"工具"从"基础设施"里拆出来**

   - 原 `GitDiffTool` 直接 `new ProcessBuilder("git", "diff", ref)` —— 既是 LLM 工具，又自己 fork 子进程，**职责糅在一起**
   - 新结构：`tools/GitDiffTool` 只负责**呈现**（per-file 切分 + 截断 + 错误转文本），子进程交给 `infra/GitClient`，diff 解析交给 `infra/DiffParser`
   - 包路径也反映分层：`tools/` = LLM agent 看见的工具门面，`infra/` = 不依赖 LLM 的基础设施。**未来加 `RuleCheckerTool`、`KnowledgeBaseTool` 都放 `tools/`**

2. **per-file 截断策略：`MAX_PER_FILE_CHARS` + `MAX_TOTAL_CHARS` 两道阀门**

   ```
   per file:   ≤ 4000 chars → 原样返回
              > 4000 chars → header（400 chars）+ "[... truncated, N added lines total ...]" + 前 20 个新增行
   total:     超过 12000 → 在文件边界截断，附 "[diff truncated; N files total]"
   ```

   - 为什么两道：单文件爆炸（生成的 .pb.go / lock 文件）和总量爆炸（大型 PR）是两种独立失控模式
   - 截断保留 `header（diff --git a/... + ---/+++）` 让 LLM 知道**这是什么文件**，附 20 个新增行让它知道**改了什么**，丢掉 hunk 上下文（context lines）—— 牺牲精度换 token 预算
   - 数字怎么定的：Moonshot k1.5 context 是 128k tokens ≈ 400k chars，留 80% 给系统消息 + 规范 + RAG → 单次 diff 上限大约 12k chars。**这是经验值，看 baseline metrics 再调**

3. **从 raw diff 里"切"文件段的小技巧**

   ```java
   String marker = "diff --git a/" + file.path() + " b/" + file.path();
   int start = rawDiff.indexOf(marker);
   int end = rawDiff.indexOf("\ndiff --git ", start + marker.length());
   String section = (end < 0) ? rawDiff.substring(start) : rawDiff.substring(start, end);
   ```

   - **关键点**：`end` 搜的是 `\ndiff --git `（前缀换行），避免在同一文件内的 `diff --git` 字符串里误命中（比如 patch 文件本身包含 diff 文本）
   - 为什么不让 DiffParser 直接返回 raw section：DiffParser 当前只输出**结构化的新增行**，加 raw section 字段会让它变成"半结构化 + 半 raw"，职责混淆。**保持 DiffParser 纯结构化，raw 切分留在 Tool 里**
   - 性能：每个文件一次 `indexOf` O(n)，总体 O(n·files)；几十个文件没问题，几百个文件 PR 才需要预切（极少见）

4. **Mockito + 真 `DiffParser` 的混合测试策略**

   ```java
   GitClient git = Mockito.mock(GitClient.class);
   when(git.diff(any(), any())).thenReturn(fakeDiff);
   GitDiffTool tool = new GitDiffTool(git, new DiffParser());
   ```

   - **mock 外部依赖**（GitClient 要 fork 进程 + 文件系统），**用真依赖**（DiffParser 是纯函数、无副作用）
   - 这种"边界处 mock，内部用真"的测试结构叫 **sociable test**（vs solitary/isolation test）—— 测**协作行为**比单测 mock 验证更接近真实
   - 反例：如果连 DiffParser 也 mock，测试只能验证"GitDiffTool 调用了 DiffParser"，**不验证拼装出的字符串结果**，等于自己跟自己核对脚本

5. **Mockito 在 Spring Boot 项目里的"免配置"**

   - `spring-boot-starter-test` 已经传递依赖了 Mockito 5、AssertJ、JUnit 5 —— **直接 import 用，不需要单独加依赖**
   - pom.xml 里搜 mockito 没结果是正常的；`mvn dependency:tree | grep mockito` 才能看到它是 transitive
   - 这是 starter 提供"测试一站式"体验的体现

6. **`git rm` vs `rm`**

   - 删 tracked 文件用 `git rm`：直接把删除登记到 index，下次 commit 就生效
   - 用 `rm`：文件系统层删了，但 git 还认为它存在，要再 `git add path` 才登记
   - 工作流上 `git rm` 一步到位、`rm + git add` 两步等价，**保持习惯用 `git rm` 减少 status 混乱**

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 改 `GitDiffTool` in-place vs 移到 `tools/` 子包 | 移到子包让分层清晰（tools = 工具门面），未来 `RuleCheckerTool` 也搬过去；in-place 会让根包变成 "什么都有的大杂烩"。**移子包** |
| 全 mock（含 DiffParser）vs sociable test（mock GitClient，真 DiffParser）| sociable test 验证更接近真实拼装；全 mock 太脆 + 信号弱。**sociable** |
| 截断时保留前 20 个新增行 vs 取头尾各 10 行 | 头尾各 10 行更全景，但实现复杂；前 20 行简单且通常 PR 改动集中在前部。**前 20 行，等评测发现召回掉了再改** |
| 把 raw section 抽取放进 DiffParser vs 留在 Tool | DiffParser 当前纯结构化（返回 records），加 raw 字段就混淆职责。**留 Tool**，未来若多个 Tool 都要 raw 再考虑抽到 DiffParser |
| `MAX_PER_FILE_CHARS / MAX_TOTAL_CHARS` 写常量 vs 进 `CodeReviewProperties` | 进 properties 早晚要做（评测要扫不同上限的影响），但 W1 baseline 还没建立、调参没依据。**先常量，T17 之后看评测数据再迁** |

### 面试 Q&A

**Q1**：你的 LLM "工具"（Tool）和你的"基础设施"（GitClient/DiffParser）为什么分两层？

- **A**：核心是**职责单一 + 复用边界**。Tool 是给 LLM 看的门面 —— `@Tool` 注解 + `@P` 参数描述 + 返回字符串。它的职责是"把基础事实转成 LLM 能消化的格式"（截断、per-file 分块、错误转文本）。基础设施（GitClient/DiffParser）是**不依赖 LLM 也能跑**的纯逻辑 —— GitClient 只管 fork 子进程，DiffParser 只管解析。这么分有三个好处：①**复用**：未来 `EvaluationRunner` 跑评测时直接用 GitClient/DiffParser，不需要走 LLM 工具链；②**测试性**：GitClient 用 `@TempDir + 真 git`，DiffParser 用 fixture，**都不需要 mock LLM**；③**演化解耦**：换 LLM 框架（LangChain4j → Spring AI）只动 Tool 层，基础设施零改动。**反例**：原来 GitDiffTool 直接 `new ProcessBuilder`，要复用 git 调用必须复制 ProcessBuilder 代码或者实例化整个 Tool —— 又得拉 LangChain4j 注解依赖。

**Q2**：LLM 工具返回字符串太长怎么办？你的截断策略是什么？为什么这么定？

- **A**：两道阀门。①**per-file 上限 4000 字符**：超了保留 header（让 LLM 知道是什么文件）+ 前 20 个新增行（让 LLM 知道改了什么）+ "truncated" 标记。②**总量上限 12000 字符**：在**文件边界**截断（不切到 hunk 中间），附 "N files total" 提示。**为什么按文件边界**：切到 hunk 中间，LLM 看到半截上下文可能瞎猜补全；按文件切，每个文件要么完整、要么被显式标"省略"，模型不会幻觉。**为什么这两个数字**：Moonshot k1.5 上下文 128k tokens ≈ 400k chars，留 80% 给系统消息 + RAG + 规范 + 输出预算 → diff 大约能用 12k。**实际**：W1 跑通后通过 evaluation 看"超长 diff 案例的召回率掉多少"，再决定要不要调成动态预算 / 大模型路由。**经验值不是真理 —— 用评测验证。**

**Q3**：你测 GitDiffTool 时 mock 了 GitClient 但用真的 DiffParser，这种混合策略有什么讲究？

- **A**：这叫 **sociable test**（合作式测试），相对的是 solitary/isolation test（所有协作者都 mock）。原则是 **mock 难以控制 / 副作用大 / 慢的依赖，真实使用纯函数 / 无 I/O 依赖**。GitClient 要 fork 子进程 + 依赖文件系统 + 跨平台 git 行为 —— mock 让测试稳定快 + 能模拟 timeout / 异常路径。DiffParser 是纯函数（无状态，无 I/O），用真实例可以**额外验证拼装逻辑**（GitDiffTool 喂给 DiffParser 的字符串能正确解析吗？拼出来的最终字符串包含 + 行吗？）。**反面**：全 isolation 测试只验证"我调用了 X.method(args)"，这种验证容易过拟合实现细节，重构时一改 method 签名所有测试爆红 —— 信号弱、维护贵。**混合策略的代价**：偶尔会 transitive 拉真依赖的复杂逻辑进来，让测试边界模糊；这时候考虑提取一个更小的子组件。**这次 DiffParser 100 行、纯函数，是 sociable 的完美场景。**

### Commit

```
refactor(tools): GitDiffTool uses GitClient + per-file splitting
```

---

## T7 · StaticAnalyzer 接口 + RegexAnalyzer

### 技术细节

1. **策略模式（Strategy Pattern）的现代 Java 写法**

   ```java
   public interface StaticAnalyzer {
       String name();
       List<Violation> analyze(List<DiffParser.FileDiff> files);
   }
   ```

   - 一个接口、多个实现：W1 写 `RegexAnalyzer`，W2 可以加 `CheckstyleAnalyzer` / `SpotbugsAnalyzer` / `SemgrepAnalyzer`，**不改调用方**
   - 调用方（T8 重构的 `RuleCheckerTool`）只依赖接口，Spring 用 `List<StaticAnalyzer>` 自动注入**所有实现**，多 analyzer 同时跑
   - 没有 GoF 教科书里那种 "Strategy / Context / ConcreteStrategy" 类层级的繁琐 —— 现代 Java 的接口 + Spring IoC 让这个模式变得几乎隐形

2. **规则内嵌为 `private record Rule` 的设计**

   ```java
   private record Rule(String id, Severity severity, Pattern pattern, String message) { }
   private static final List<Rule> RULES = List.of(...)
   ```

   - 9 条规则全部是**数据**（regex + 元数据），不是行为 —— 用 record 表达比抽象类 / lambda 都更准确
   - `static final List<Rule>` 启动时一次性编译所有 Pattern（Pattern 编译有成本，约 100μs/条），后续 `analyze()` 调用零编译开销
   - `private` 嵌套 record 让 Rule 不污染 package 命名空间 —— 它是 RegexAnalyzer 的内部表示，不该泄漏到外部

3. **`matches()` vs `find()` —— 经典正则坑**

   - `matcher.matches()`：整个字符串**全匹配**正则
   - `matcher.find()`：正则在字符串中能**找到**子串
   - 我们规则全部用 `.*PATTERN.*` 包前后缀 + `matches()`，等价于 `find()` 但更明确意图："这一行包含某种模式"
   - **反例**：如果写 `Pattern.compile("TODO")` + `matches()`，只有那一行字符串恰好就是 "TODO" 才命中，差点漏掉 99% 的 TODO 注释。**选错一个方法 = 100% 漏检**

4. **报告"文件行号"而不是"diff 行号"** （延续 T4 的成果）

   ```java
   for (DiffParser.AddedLine added : file.addedLines()) {
       // added.lineNumber() 已经是 DiffParser 算好的"新文件真实行号"
       out.add(new Violation(..., added.lineNumber(), ...));
   }
   ```

   - 因为 T4 的 DiffParser 已经做了 diff 行号 → 文件行号映射，Analyzer 直接消费**正确的语义行号**
   - 这是分层架构的红利：Analyzer 不需要知道 unified diff 格式，更不需要解析 hunk header
   - **测试 `reportsFileLineNotDiffLine` 守护这个不变量**：构造一个起始 line=100 的 FileDiff，违规行必须报 101（不是 list 索引 1 / 不是 diff 行 2）

5. **`@Component` 让 Spring 自动收集所有 analyzer**

   - 接口 `StaticAnalyzer` 没标注，实现类 `RegexAnalyzer` 标 `@Component`
   - T8 注入：`private final List<StaticAnalyzer> analyzers;` —— Spring 自动把所有 `@Component` 实现的 `StaticAnalyzer` 注进 list
   - 这是 Spring 的 **collection injection**：对 List/Set/Map 类型，Spring 把所有匹配的 bean 都装进来，**注册顺序 = 声明顺序 / `@Order` 注解**
   - 加新 analyzer 只需要 ①实现接口、②加 `@Component`，**无需改任何注册代码**。这就是"开闭原则"在 IoC 容器里的体现

6. **为什么先做 `Severity` 一行枚举（plan 说 T12 才做完整版）**

   - T12 才系统化做 `Category`/`Severity`/`Citation`/`ReviewFinding` —— 完整的结构化输出模型
   - 但 T7 的 `Violation` 已经需要 `Severity` —— **不可能跳过 T12 才做 T7**
   - 折中：T7 先创建一行版 `enum Severity { CRITICAL, WARNING, SUGGESTION }`，T12 时如果要加字段（比如 numeric weight、display label）再扩
   - 这是 **incremental construction**：复杂结构 W1 拆几个 milestone 渐进暴露，每个 milestone 内部都能跑

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| 自己写 regex vs 引入 Checkstyle/SpotBugs | 它们是真编译器级别的静态分析，但 ①需要项目能编译（agent 拿到的是 diff 不是完整项目），②依赖体积大（Checkstyle ~5MB），③学习曲线陡。**W1 用 regex 兜底，W2 加 Checkstyle 作为补充而非替代** |
| 9 条规则硬编码 vs YAML 配置 | 配置化让"加规则不改代码"，但 ①调试难（YAML 写错正则没编译期检查），②W1 规则少且稳定。**硬编码 + 规则改动走 commit；规则膨胀到 50+ 条再配置化** |
| Severity 三级 vs 五级 (BLOCKER/CRITICAL/MAJOR/MINOR/INFO) | 五级看似细，实际人很难稳定区分 MAJOR 和 MINOR。**三级（CRITICAL/WARNING/SUGGESTION）和团队心智模型对齐，符合 GitHub PR review 的常见三段态度** |
| `Violation` 包含 raw line 字符串 vs 只有元数据 | 加 raw line 让报告自包含（不需要回读源码就能 review），但 violation 列表会显著膨胀 token。**只元数据**，需要时让 Tool 层从 FileDiff 反查 |
| 把 analyzer 放 `analyzer/` 包还是 `tools/` 包 | analyzer 是基础设施（不依赖 LLM 注解，纯算法），属于 infra 类同辈；tools/ 是 LLM 工具门面。**`analyzer/` 独立包**，T8 的 `RuleCheckerTool`（tools/）依赖 `analyzer/` |

### 面试 Q&A

**Q1**：你为什么用策略模式做静态分析，而不是直接写一个大 if-else？

- **A**：三个核心理由。①**开闭原则**：未来加 Checkstyle / SpotBugs / Semgrep analyzer，只新增类不改调用方。一个大 if-else 加规则要改主流程，触发更多回归测试。②**独立测试**：每个 analyzer 一组单测，新规则的失败不污染其他 analyzer 的测试报告。③**可观测性**：每条 `Violation` 带 `rule` 字段（来自具体 analyzer），评测时能拆"哪类 analyzer 的召回率高 / 误报多"，是评测指标拆分的前提。**反面**：如果只有 1 种 analyzer 且永不扩展，策略模式确实是 over-engineering；但 W1 plan 明确要在 W2/W3 加 Checkstyle、Semgrep，前置接口划分是合理的。

**Q2**：你的规则是用 `matches()` + `.*PATTERN.*`，为什么不用 `find()`？两者什么区别？

- **A**：语义上等价（都表示"行内包含某模式"），但 **`matches()` 强制全字符串覆盖**让正则设计意图更明确。如果哪天 maintainer 顺手把 `.*` 去掉一个，`find()` 还能正常工作（继续找子串），`matches()` 会立刻退化成"必须等于"——bug 在测试里立刻暴露。这是**显式优于隐式**的体现。**另一层面**：`matches()` 配合 `.*A.*`，让 Pattern 的可读性变成"必须长这样"，比 `find()` 的"在某处出现"更接近 SonarQube / Semgrep 那种规则定义风格。**经典坑**：很多人写 `Pattern.compile("TODO").matcher(line).matches()` 期望"包含 TODO 就命中"，结果只有那一行 trim 后恰好是 "TODO" 才命中——99% 的 TODO 漏检。**规则**：写 regex 之前先想清楚 matches/find，并写一个反例的测试卡住意图。

**Q3**：Spring 的 collection injection (`List<StaticAnalyzer>`) 你是怎么用的？有什么注意点？

- **A**：用法是声明 `private final List<StaticAnalyzer> analyzers`，Spring 会扫所有 `@Component`/`@Service` 实现的 `StaticAnalyzer` bean，全部注入 list。**三个注意点**。①**顺序**：默认按 bean 注册顺序（同 package 大致是文件名字母序，跨 package 不可控），需要确定顺序时用 `@Order(1)` 或实现 `Ordered`。我这里 analyzer 并行汇总结果，顺序不影响正确性。②**空列表**：没有任何实现时 Spring 注入空 list，**不报错**——这是好事（可关闭功能），但也可能掩盖配置 bug（忘了加 `@Component`）；建议在 `@PostConstruct` 或启动日志里 assert `!analyzers.isEmpty()`。③**Map 注入**：`Map<String, StaticAnalyzer>` 会用 **bean name 作为 key**，便于按名字路由（"用哪个 analyzer 取决于配置项"），这是更高级的用法。**为什么不用 `ApplicationContext.getBeansOfType()`**：那是手动检索，丢失类型安全和构造时校验。**collection injection 是声明式 IoC 的优雅落点**。

### Commit

```
feat(analyzer): StaticAnalyzer interface + RegexAnalyzer
```

---

## T8 · RuleCheckerTool 重构 — 走 DiffParser + StaticAnalyzer

### 技术细节

1. **重构对照表：旧 vs 新**

   | 维度 | 旧实现 | 新实现 |
   | --- | --- | --- |
   | git 子进程 | `new ProcessBuilder("git", "diff", ref)` 直接 fork | 注入 `GitClient`（超时 + InterruptedException 正确处理） |
   | diff 解析 | `diff.split("\n")` + `for (i = ...) i+1` 当行号 | 注入 `DiffParser`，直接消费**新文件真实行号** |
   | 规则检查 | 9 条硬编码 `if (code.matches(...))` 散在主流程 | 委派给 `List<StaticAnalyzer>`，主流程只做汇总 |
   | 输出格式 | 手拼字符串，没有 rule id | 带 rule id 的结构化字符串（评测可以按 rule 聚合） |
   | 行号正确性 | **错的**（diff 数组下标 + 1） | **对的**（hunk header + 行类型增减） |

   每一列是独立的优化方向；这次重构一口气全做掉是因为它们互相耦合（修了行号 bug 但还用旧 ProcessBuilder 没意义）。

2. **`List<StaticAnalyzer>` —— Spring collection injection 的实战首秀**

   ```java
   public RuleCheckerTool(GitClient g, DiffParser d, List<StaticAnalyzer> analyzers) {
       this.analyzers = analyzers;
   }
   ```

   - Spring 启动时扫描所有 `@Component` 实现的 `StaticAnalyzer`（目前只有 `RegexAnalyzer`），注入成一个 list
   - 未来加 `CheckstyleAnalyzer` / `SemgrepAnalyzer` —— **零改动** RuleCheckerTool，新加的 analyzer 自动出现在 list 里
   - 主循环 `for (StaticAnalyzer a : analyzers) all.addAll(a.analyze(files))` —— Tool 不知道有几个 analyzer、是什么 analyzer，只负责汇总结果
   - 这就是依赖反转 + IoC 的实际收益：**主流程稳定，扩展通过新增 bean 实现**

3. **简单顺序合并 vs 并行执行**

   ```java
   for (StaticAnalyzer a : analyzers) {
       all.addAll(a.analyze(files));
   }
   ```

   - 现在是同步串行：W1 只有 1 个 analyzer 无所谓；W2 加 Checkstyle / SpotBugs 后串行会慢（Checkstyle 一次可能 1-2s）
   - 改并行最简洁的方式：`analyzers.parallelStream().flatMap(a -> a.analyze(files).stream()).toList()`
   - **W1 暂不做的理由**：①只有 1 个 analyzer，并发收益为 0；②并行后**结果顺序不稳定**，会让 LLM 看到的 prompt 抖动，评测复现性变差；③parallelStream 共享 ForkJoinPool common pool，多个 review 同时跑会互相干扰
   - 真要做并发，T4-evaluation Phase 加 `ExecutorService` 提供 deterministic ordering 控制更稳

4. **错误转字符串而不是抛异常**

   ```java
   try { diff = gitClient.diff(...); }
   catch (GitClient.GitException e) {
       return "Error running git diff: " + e.getMessage();
   }
   ```

   - LangChain4j 的 `@Tool` 方法**返回值就是 LLM 看到的工具输出**，抛异常会导致 agent 调用失败 → 整个 review 中断
   - 把错误转成自然语言字符串，LLM 可以根据返回内容"决定下一步"——比如 "Error: not a git repo" 它可能放弃 / 改用其他工具 / 报告给用户
   - 这是 LLM 工具设计的核心原则：**用文本反馈，让模型而不是异常处理器决策**
   - **反面**：底层异常被吞掉，调试困难。生产里要在 Tool 边界 log 异常（logger.warn(...,e)），返回值简化给 LLM

5. **每次 review 重新 git diff，没有缓存**

   - `RuleCheckerTool.checkRules` 和 `GitDiffTool.getGitDiff` 都会 `gitClient.diff(repo, ref)` —— 同一次 review 跑了两次相同的 git 命令
   - 30s 超时 + git diff 通常 <100ms，两次也就 200ms，**目前不优化**
   - W2 引入 ReviewContext（封装"一次 review 的所有缓存数据"）后，两个 tool 共享同一个 FileDiff 对象，自然去重
   - 这是典型的 "premature optimization" 警惕：先建链路，瓶颈出现了再加缓存

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| Tool 自己持 GitClient vs 让 GitDiffTool 暴露 FileDiff 给 RuleCheckerTool | 让两个 tool 共享中间结果会让 RuleCheckerTool 依赖 GitDiffTool —— 工具间耦合是危险的（agent 调用顺序由 LLM 决定，可能 RuleChecker 先于 GitDiff）。**Tool 自给自足，重复成本由 W2 ReviewContext 治理** |
| 串行 analyzer vs 并行 | 见上面"技术细节 3"。**串行更可控**，等多 analyzer 后再考虑 |
| 抛异常 vs 错误字符串 | LLM Tool 必须返回字符串，否则 agent 链路断。**返回错误字符串 + log warn** |
| 输出字符串包含 rule id vs 不包含 | rule id 让评测可以"哪条规则误报最多 / 召回最低"做聚合 —— 评测必需。**包含** |
| 移到 `tools/` 子包 vs in-place | 与 T6 GitDiffTool 同一逻辑，**tools/ 是 LLM 工具门面层** |

### 面试 Q&A

**Q1**：你这个 Tool 同时注入了 GitClient、DiffParser 和 `List<StaticAnalyzer>` 三个依赖，会不会职责太多？

- **A**：表面看是三个依赖，本质上**三个角色对应一个端到端工作流**：①取数据（GitClient）、②结构化数据（DiffParser）、③分析数据（StaticAnalyzer）。每一步都有独立可测试的协作者，Tool 自己只做"流程编排 + 输出格式化"。这恰恰是**单一职责**——Tool 的职责就是"流程编排"，不是"git 调用 + diff 解析 + 规则检查"三件事。**反例**：如果让 Tool 自己 `new ProcessBuilder`、自己 `split("\n")`、自己写 9 个 `if`——那才是职责爆炸。**判断标准**：依赖的数量不是职责复杂度的代理；看 Tool 的"代码本身做了什么"——它只在 try/catch 和 for 循环里组合调用，**没有任何业务逻辑代码**，这就是良好抽象。

**Q2**：你的 Tool 用 try-catch 把 GitException 转成字符串返回，而不是让异常向外冒泡。这是 anti-pattern 吗？

- **A**：在普通 Java 服务里**是 anti-pattern**（吞异常隐藏 bug）；但在 LLM Tool 边界**是 best practice**。原因：LangChain4j 的 `@Tool` 注解方法返回值就是 LLM 看到的"工具输出"，**LLM 通过文本理解失败**——它可以选择重试、换工具、或报告给用户。如果抛异常，LangChain4j 的 agent 链路会中断（除非 framework 帮你 catch，但行为不可控）。两个补救措施让它不是真正的 "swallow exception"：①每次 catch 时 logger.warn 带堆栈（debug 时有线索），②错误消息**包含具体原因**（"git command timed out" / "Not a directory"），LLM 和最终用户都能读懂。**原则**：**进程边界吞异常，但留下日志线索 + 让对端能根据返回值决策**。LLM tool / HTTP API / gRPC 服务边界都遵循这个原则。

**Q3**：你这个 Tool 和 GitDiffTool 都会 `gitClient.diff(...)` —— 一次 review 调两次 git diff。这不浪费吗？

- **A**：理论上浪费，实际上 W1 不优化。三个理由。①**成本量级**：git diff 通常 <100ms，两次 200ms 相对于 LLM 调用（数百毫秒到秒级）可以忽略。②**为什么不让两个 tool 共享**：tool 是 agent 自主调用的，**调用顺序由 LLM 决定**（可能先 checkRules 再 getGitDiff，或反过来）；如果让 RuleChecker 依赖 GitDiffTool 的输出，相当于强制 LLM 必须先调 GitDiff——这剥夺了 agent 的自主权，也违反"工具自洽"原则。③**正确的优化时机**：W2 引入 `ReviewContext`（一次 review 创建一次的上下文对象，包含 repo/ref/FileDiff/缓存），所有 tool 共享同一个 context，自然去重——而不是工具间互相依赖。**反面**：如果 git diff 真的成了瓶颈（大 repo / 远程 git），可以在 GitClient 层加 in-memory cache（key = repo+ref），TTL 1s 就够。**先建链路，瓶颈出现再优化。**

### Commit

```
refactor(tools): RuleCheckerTool uses DiffParser (real line numbers) + StaticAnalyzer
```

---

## T9 · EmbeddingCache — 把向量库序列化到本地 JSON

### 技术细节

1. **为什么要做这层缓存**

   - `BgeSmallEnV15QuantizedEmbeddingModel` 是 ONNX 量化模型，单条 embedding 计算大约 5-20ms（CPU-only）
   - 知识库（`review-guidelines/`）切 chunk 后可能有几十到几百个 segment，**整索引一次 1-5s**
   - 每次启动 agent 都重算 → CLI 工具的"启动延迟感知"非常差（用户期望 <500ms）
   - 缓存命中后启动从 **5s 降到 100ms 量级**，是 100% 用户体验改进
   - 缓存的语义：embedding 是**模型 + 文档**的函数；只要模型不变、文档不变，向量就不变，**可以无脑磁盘缓存**

2. **LangChain4j 内置的 `serializeToJson()` / `fromJson(String)`**

   ```java
   String json = store.serializeToJson();
   InMemoryEmbeddingStore<TextSegment> restored = InMemoryEmbeddingStore.fromJson(json);
   ```

   - 用的是 `JacksonInMemoryEmbeddingStoreJsonCodec`（jar 里能看到这个类）
   - 序列化时把每条 entry 的 `embedding（float[]）+ id（UUID）+ embedded（TextSegment）` 一并打包，**不只是向量**
   - 这是 LangChain4j 设计的好处：常用的中间态有内置序列化，不用自己撸 Jackson mixin

3. **API 漂移踩坑：`findRelevant` → `search`**

   - plan 里写 `loaded.get().findRelevant(embedding, 1)` —— **1.15 里这个方法已经移除**（早期 deprecated 后 1.15 直接干掉）
   - 现在的 API 是 `search(EmbeddingSearchRequest)` 返回 `EmbeddingSearchResult`：

   ```java
   EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
           .queryEmbedding(emb).maxResults(1).build();
   List<EmbeddingMatch<TextSegment>> matches = store.search(req).matches();
   ```

   - 怎么发现的：plan 在 T9 注释里就埋了"check method names and adjust"。我用 `javap -p` 直接看 `langchain4j-1.15.0.jar` 里的 `InMemoryEmbeddingStore.class`，对照 plan 的方法名，**几秒钟就锁定差异**
   - **教训**：beta-版本依赖的 API 出现 plan-vs-实现偏差时，**别问 LLM，直接 javap jar**——LLM 训练数据可能停留在更老版本，jar 里的字节码是真实事实源

4. **磁盘缓存的关键 4 步：mkdir → write → exists check → read**

   ```java
   Files.createDirectories(cacheDir);  // 幂等，目录不存在才创建
   Files.writeString(file, json, UTF_8);  // 全量写
   if (!Files.exists(file)) return Optional.empty();
   String json = Files.readString(file, UTF_8);
   ```

   - `Files.createDirectories` vs `Files.createDirectory`：后者要求父目录存在 + 当前目录不存在，前者递归创建且幂等 —— **永远用 createDirectories**
   - 全量 write 没有原子保证：写一半进程被杀会留下半截 JSON，下次 `fromJson` 解析失败抛异常。**生产应该写 tmpfile + Files.move(ATOMIC_MOVE)**；W1 不做（启动失败时手删 cache 即可）
   - 显式 `StandardCharsets.UTF_8`：JSON 里没非 ASCII 字符也加上，**养成习惯**（Windows JVM 默认 GBK）

5. **`sanitize(key)` —— 防止 key 注入文件系统**

   ```java
   key.replaceAll("[^a-zA-Z0-9._-]", "_")
   ```

   - 用户 / 调用方可能把 `"guidelines/v1"` 当 key —— 不 sanitize 就生成 `guidelines/v1.json` 触发**目录穿越**或文件系统 illegal char
   - 白名单（保留可打印安全字符）比黑名单（替换危险字符）**更安全**：永远只可能产生合法字符
   - 这是文件路径处理的**通用防御写法**：任何来自外部的 key 拼路径前都要 sanitize
   - **反例**：用 `Path.resolve(key)` 不 sanitize，攻击 key `"../../etc/passwd"` 就能跳出 cacheDir。Java 的 `Path.resolve` 不会拦截 `..`

6. **`@TempDir` 注入：JUnit 5 的临时目录便利**

   ```java
   @TempDir Path cacheDir;
   ```

   - JUnit 5 给每个 test 注入独立临时目录，跑完自动删
   - **没有副作用泄露**：A test 写的 cache 不会污染 B test
   - 之前 T5 GitClientTest 用过相同模式

7. **`EmbeddingCache` 暂时不是 `@Component` 的原因**

   - T9 单独看，加 `@Component` 也行
   - 但 T10 `RagConfig` 是 `@Configuration`，会显式 `@Bean` 注册 `EmbeddingCache(props.rag().embeddingCacheDir())`
   - 如果两边都注册（`@Component` + `@Bean`），Spring 会因"找到两个候选 bean"启动失败
   - **决策**：W1 走 `@Bean` 路径（路径来自 properties，需要构造参数），不加 `@Component`。等 T10 commit 后这个组合就闭合了

### 设计权衡

| 选项 | 评估 |
| --- | --- |
| JSON 序列化 vs Java native serialization | native serialization 紧凑、快，但 **跨版本不兼容**（LangChain4j 升级 entry 字段就破缓存）；JSON 慢一点但 schema 演化容忍度高 + 可读（debug 时能 cat 看）。**JSON** |
| 自己拿 Jackson 序列化 vs `serializeToJson` 内置 | 自己写要 mixin TextSegment / Embedding，重复造轮子。LangChain4j 既然有就用。**用内置** |
| 全量写 vs 增量 append | InMemoryEmbeddingStore 没暴露"导出 delta"的 API；增量需要 store 维护 dirty set。**全量** —— 写一次几 MB 不算开销 |
| 缓存失效条件做版本号 vs hash | 简单 versioning（CACHE_KEY = "review-guidelines"）是 W1 的事；W2 可以 hash 所有 guideline 文件内容当 key，**guideline 改了 cache 自动失效**。**W1 先简单，W2 加 hash** |
| cache miss 时静默重建 vs warn 用户 | T10 `KnowledgeBaseIndexer` log.info 说明 "Building from scratch" —— 让用户知道首次启动慢的原因 |

### 面试 Q&A

**Q1**：你为什么要给 embedding 加磁盘缓存？这个优化有什么常见误区？

- **A**：embedding 缓存是 RAG 系统**最便宜也最有效**的启动优化。BGE-small 量化模型一条 5-20ms，知识库几百条就是几秒；CLI 工具每次启动重算，用户体验直接崩。**缓存语义**：embedding = f(model, text)，model 不变 + text 不变 → 向量必然相同，可以无脑缓存。**常见误区三个**。①**忘了把模型版本进 key**：从 BGE-small 升级到 BGE-large，老 cache 还在用，向量维度对不上爆炸。我现在用 `CACHE_KEY = "review-guidelines"`（W1 只有一个模型），下次升级要改成 `guidelines-bge-small-v15`。②**忘了文档变更失效**：guideline 文档改了，应该重建 cache。W2 计划用文档内容 hash 当 key 自动失效，W1 是手工删 `~/.code-review-agent/cache/`。③**没考虑并发**：多进程同时 cache miss 会全量重建并 race-condition 覆盖文件。CLI 单进程不是问题，将来上服务端要加文件锁。

**Q2**：你的 cache `sanitize(key)` 用白名单替换非法字符 —— 这是不是 over-engineering？

- **A**：**不是**。这是文件路径处理的标准防御。具体威胁：①**目录穿越**：用户 key 包含 `../../etc/passwd`，不 sanitize 直接 `cacheDir.resolve(key)` 就跳出 cacheDir 写到任意路径——`Path.resolve` 不会拦截 `..`。②**文件系统非法字符**：Windows 不允许 `<>:|?*`，Mac/Linux 允许但下次读会失败。③**键映射歧义**：`"foo/bar"` 和 `"foo_bar"` 可能映射到同一文件，cache 混乱。白名单（只保留 alphanumeric + `._-`）覆盖所有威胁，比黑名单**只能想到我已知的攻击向量**更安全。**性能成本**：一次 regex replace，O(n) on key length，几乎为零。**反例**：Snyk 早年统计过，文件路径相关的 CVE 里 60%+ 是 path traversal —— 这是真威胁，不是 over-engineering。

**Q3**：你写的 `Files.writeString` 不是原子的，如果 JVM 中途崩了 cache 会损坏。生产里你会怎么改？

- **A**：标准 fix 是 **temp file + atomic rename**：

  ```java
  Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
  Files.writeString(tmp, json, UTF_8);
  Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  ```

  原理：POSIX `rename(2)` 是 inode 层的原子操作 —— 要么旧文件，要么新文件，**不存在"半新半旧"中间态**。`ATOMIC_MOVE` 在大多数文件系统（ext4 / APFS / NTFS）能保证；跨文件系统的 move 会降级成 copy+delete 失去原子性，会抛 `AtomicMoveNotSupportedException`，这种情况要选 cache 路径和工作目录同一卷。**W1 没做的原因**：①CLI 短期任务，写一次 cache 后立刻 close（被中断概率极低），②即使损坏了用户手删即可（无副作用），③加这个会让代码从 5 行变 15 行 + 一个异常分支测试。**生产服务（长跑 + 高并发）必须做**；CLI 工具的工程权衡可以省。**这就是 YAGNI 的合理应用 —— 知道什么时候该加，但有充分理由不加。**

### Commit

```
feat(infra): EmbeddingCache JSON round-trip
```

### 踩坑实录

**坑 4：plan 写的 `findRelevant(Embedding, int)` 在 langchain4j 1.15 已删**
- 现象：测试编译报"找不到符号 findRelevant"
- 原因：`findRelevant` 早期 deprecated 后 1.15 直接删，替换为 `search(EmbeddingSearchRequest)` 返回 `EmbeddingSearchResult`
- 修复：测试改用 `EmbeddingSearchRequest.builder().queryEmbedding(...).maxResults(1).build()` + `.search(req).matches()`
- 怎么找到的：plan 注释里早就提示 "If the method names differ, check javadoc and adjust"。用 `javap -p` 直接看 jar 里的 `InMemoryEmbeddingStore.class`，几秒钟锁定真实 API
- 教训：**beta 依赖的 API 信息源排序：jar 里的字节码 > 官方 release notes > LLM > Stack Overflow**。jar 是真实事实源，前三个都可能滞后或错

---
