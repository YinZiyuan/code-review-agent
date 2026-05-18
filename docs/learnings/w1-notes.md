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
