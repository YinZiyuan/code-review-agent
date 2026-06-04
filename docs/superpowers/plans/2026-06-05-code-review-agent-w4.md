# W4 Implementation Plan — release 评测补足 + 调优 + 交付

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `w3-pipeline`（v3）打磨成 40 样本 release suite 上可信、可复现、可交付的稳定发布版（v3.1-tuned），并补齐 README / 架构图 / 评测报告 / demo 脚本。

**Architecture:** 不动 pipeline 四 stage 核心。先给 `EvaluationRunner` 加「重复跑 + 多 run 聚合」能力；再把样本扩到 40；用 worktree honest 复现重跑所有版本；做 severity 校准 + `ToolStatus` 枚举化两项调优产出 v3.1-tuned；最后写交付物。

**Tech Stack:** Java 17 + Spring Boot 3.5.6 + Maven，picocli CLI，JUnit 5 + AssertJ，LangChain4j（仅 ChatModel/RAG），Python 3（仅交付阶段画图脚本，零 Maven 依赖）。

**Spec:** [docs/superpowers/specs/2026-06-05-code-review-agent-w4-design.md](../specs/2026-06-05-code-review-agent-w4-design.md)

**Branch:** 实现前从当前 `feat/w2` 切 `feat/w4`：`git switch -c feat/w4`

---

# 阶段 0 · 分支准备

### Task 0: 切分支

**Files:** 无（git 操作）

- [ ] **Step 1: 确认 working tree 状态**

Run: `git status --short`
Expected: 仅 `M docs/learnings/w3-notes.md`（未提交的 W3 笔记）与已提交的 spec/plan。不要把它卷进 W4 commit。

- [ ] **Step 2: 从 feat/w2 切 feat/w4**

Run: `git switch -c feat/w4`
Expected: `Switched to a new branch 'feat/w4'`

---

# 阶段 1 · runner 重复跑 + 多 run 聚合（Finding 1 / Phase 2 前置）

> 目标：`EvaluationRunner` 支持「每样本跑 N 次」，report 体现 N runs 的均值 + 标准差 + 每 run 指标。`runs=1` 必须与旧行为逐字节等价（headline 指标=该次，stddev=0，perSample 不加后缀）。

## 文件结构

- Modify: `src/main/java/dev/langchain4j/example/codereview/eval/EvalReport.java`（加两字段）
- Modify: `src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java`（runs 循环 + 聚合）
- Modify: `src/main/java/dev/langchain4j/example/codereview/cli/EvalCommand.java`（`--runs` 选项）
- Test: `src/test/java/dev/langchain4j/example/codereview/eval/EvaluationRunnerIT.java`（加重复跑用例）

### Task 1: EvalReport 增加 perRunMetrics + metricsStdDev 字段

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/eval/EvalReport.java`

- [ ] **Step 1: 在 record 末尾追加两个字段**

把 EvalReport 改成（**新字段加在最后，保持既有字段顺序不变**）：

```java
package dev.langchain4j.example.codereview.eval;

import java.util.List;
import java.util.Map;

public record EvalReport(
        String version,
        String commit,
        String tag,
        String timestamp,
        Map<String, Object> config,
        List<String> allowedInputs,
        Map<String, Double> metrics,
        List<SampleMetrics> perSample,
        List<Map<String, Double>> perRunMetrics,
        Map<String, Double> metricsStdDev
) { }
```

- [ ] **Step 2: 编译，确认唯一构造点（EvaluationRunner）报错**

Run: `mvn -q -o compile 2>&1 | grep -A2 EvalReport | head -20`
Expected: 编译失败，指向 `EvaluationRunner.java` 构造 EvalReport 处参数不足（下个 Task 修）。若离线 `-o` 失败就去掉 `-o`。

### Task 2: EvaluationRunner 重复跑 + 聚合（先写测试）

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/eval/EvaluationRunnerIT.java`

- [ ] **Step 1: 写失败测试 —— runs=3 跑出 3 个 perRunMetrics 且 perSample 翻 3 倍**

在 `EvaluationRunnerIT` 末尾（最后一个 `}` 前）加：

```java
    @Test
    void repeatedRunsAggregatesAcrossRuns() throws Exception {
        Path samples = workDir.resolve("samples-rep");
        Path reports = workDir.resolve("reports-rep");
        Files.createDirectories(samples);
        copyFixture("sample-pass", samples);

        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        CodeReviewAgent agent = (request, sourceRoot) -> new ReviewResult(
                "1 finding",
                List.of(new ReviewFinding(
                        "F-001", "User.java", 11, new int[]{11, 11},
                        Severity.CRITICAL, Category.SECURITY,
                        "Hardcoded credential", "Found hardcoded password",
                        "Move to environment variable", "pwd = \"hardcoded\"",
                        List.of(), "llm_reviewer")),
                List.of());
        Matcher matcher = new Matcher((expected, finding) ->
                new LlmJudge.JudgeVerdict(
                        expected.file().equals(finding.file()) && expected.category() == finding.category(),
                        0.9, "same"), 5);
        EvaluationRunner runner = new EvaluationRunner(agent, matcher, mapper);

        EvalReport report = runner.run(samples, reports, "v-rep", Map.of("pipeline", "test"), null, 3);

        assertThat(report.perRunMetrics()).hasSize(3);
        assertThat(report.perSample()).hasSize(3); // 1 sample × 3 runs, flattened
        assertThat(report.perSample()).allSatisfy(s ->
                assertThat(s.sampleId()).startsWith("sample-pass#run"));
        // deterministic agent → zero variance
        assertThat(report.metricsStdDev().get("recall")).isCloseTo(0.0, within(0.0001));
        assertThat(report.metrics().get("recall")).isCloseTo(1.0, within(0.0001));
    }

    @Test
    void singleRunKeepsLegacyBehaviour() throws Exception {
        Path samples = workDir.resolve("samples-one");
        Path reports = workDir.resolve("reports-one");
        Files.createDirectories(samples);
        copyFixture("sample-pass", samples);

        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        CodeReviewAgent agent = (request, sourceRoot) -> new ReviewResult("none", List.of(), List.of());
        Matcher matcher = new Matcher((expected, finding) ->
                new LlmJudge.JudgeVerdict(false, 0.0, "no"), 5);
        EvaluationRunner runner = new EvaluationRunner(agent, matcher, mapper);

        EvalReport report = runner.run(samples, reports, "v-one", Map.of("pipeline", "test"));

        assertThat(report.perRunMetrics()).hasSize(1);
        assertThat(report.perSample()).hasSize(1);
        assertThat(report.perSample().get(0).sampleId()).isEqualTo("sample-pass"); // no #run suffix
        assertThat(report.metricsStdDev().values()).allSatisfy(v -> assertThat(v).isEqualTo(0.0));
    }
```

加 import（若文件尚未 import）：`import static org.assertj.core.api.Assertions.within;`

- [ ] **Step 2: 跑测试确认失败（方法签名不存在 / 字段不存在）**

Run: `mvn -q -Dtest=EvaluationRunnerIT test`
Expected: 编译失败 —— `run(...,null,3)` 6 参重载不存在，`perRunMetrics()`/`metricsStdDev()` 未定义。

- [ ] **Step 3: 重写 EvaluationRunner 的 run + 聚合逻辑**

替换 `EvaluationRunner.java` 中从 `public EvalReport run(Path samplesDir...` 到该方法结束 `}`（含旧的 `run` 5 参重载）的整段，为：

```java
    public EvalReport run(Path samplesDir, Path reportsDir, String version, Map<String, Object> config) throws IOException {
        return run(samplesDir, reportsDir, version, config, null, 1);
    }

    public EvalReport run(Path samplesDir, Path reportsDir, String version,
                          Map<String, Object> config, Set<String> sampleIdFilter) throws IOException {
        return run(samplesDir, reportsDir, version, config, sampleIdFilter, 1);
    }

    public EvalReport run(Path samplesDir, Path reportsDir, String version,
                          Map<String, Object> config, Set<String> sampleIdFilter, int runs) throws IOException {
        int runCount = Math.max(1, runs);
        List<Path> sampleDirs = listSampleDirs(samplesDir);
        if (sampleIdFilter != null && !sampleIdFilter.isEmpty()) {
            sampleDirs = sampleDirs.stream()
                    .filter(path -> sampleIdFilter.contains(path.getFileName().toString()))
                    .toList();
        }

        List<SampleMetrics> flattened = new ArrayList<>();
        List<Map<String, Double>> perRunMetrics = new ArrayList<>();

        for (int r = 0; r < runCount; r++) {
            List<SampleMetrics> thisRun = new ArrayList<>();
            for (Path dir : sampleDirs) {
                Sample sample = Sample.load(dir, mapper);
                log.info("Evaluating sample {} (run {}/{})", sample.id(), r + 1, runCount);
                SampleMetrics m = evaluateOne(sample);
                thisRun.add(m);
                flattened.add(runCount > 1 ? withRunSuffix(m, r + 1) : m);
            }
            perRunMetrics.add(aggregate(thisRun));
        }

        Map<String, Double> meanMetrics = meanAcrossRuns(perRunMetrics);
        Map<String, Double> stdDevMetrics = stdDevAcrossRuns(perRunMetrics, meanMetrics);

        EvalReport report = new EvalReport(
                version,
                currentCommit(),
                currentTag(),
                Instant.now().toString(),
                config,
                List.of("diff.patch", "source-before/"),
                meanMetrics,
                flattened,
                perRunMetrics,
                stdDevMetrics
        );

        Files.createDirectories(reportsDir);
        ObjectMapper reportMapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(reportsDir.resolve(version + ".json"),
                reportMapper.writeValueAsString(report),
                StandardCharsets.UTF_8);
        return report;
    }

    private static Map<String, Double> aggregate(List<SampleMetrics> perSample) {
        Map<String, Double> agg = new LinkedHashMap<>();
        agg.put("recall", Metrics.recall(perSample));
        agg.put("precision", Metrics.precision(perSample));
        agg.put("fp_rate", Metrics.fpRate(perSample));
        agg.put("severity_accuracy", Metrics.severityAccuracy(perSample));
        agg.put("avg_latency_ms", Metrics.avgLatencyMs(perSample));
        agg.put("avg_input_tokens", Metrics.avgInputTokens(perSample));
        agg.put("avg_output_tokens", Metrics.avgOutputTokens(perSample));
        agg.put("tool_success_rate", Metrics.toolSuccessRate(perSample));
        return agg;
    }

    private static Map<String, Double> meanAcrossRuns(List<Map<String, Double>> runs) {
        Map<String, Double> mean = new LinkedHashMap<>();
        if (runs.isEmpty()) {
            return mean;
        }
        for (String key : runs.get(0).keySet()) {
            double sum = runs.stream().mapToDouble(m -> m.getOrDefault(key, 0.0)).sum();
            mean.put(key, sum / runs.size());
        }
        return mean;
    }

    private static Map<String, Double> stdDevAcrossRuns(List<Map<String, Double>> runs, Map<String, Double> mean) {
        Map<String, Double> sd = new LinkedHashMap<>();
        for (String key : mean.keySet()) {
            double mu = mean.get(key);
            double var = runs.stream()
                    .mapToDouble(m -> {
                        double d = m.getOrDefault(key, 0.0) - mu;
                        return d * d;
                    })
                    .average().orElse(0.0);
            sd.put(key, Math.sqrt(var));
        }
        return sd;
    }

    private static SampleMetrics withRunSuffix(SampleMetrics m, int run) {
        return new SampleMetrics(m.sampleId() + "#run" + run, m.truePositives(), m.falsePositives(),
                m.falseNegatives(), m.severityMatches(), m.severityComparisons(), m.latencyMs(),
                m.inputTokens(), m.outputTokens(), m.toolCallsTotal(), m.toolCallsFailed(), m.toolStatuses());
    }
```

在文件顶部 import 区补：`import java.util.LinkedHashMap;`

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=EvaluationRunnerIT test`
Expected: PASS（含两个新用例 + 原有用例）。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/dev/langchain4j/example/codereview/eval/EvalReport.java \
        src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java \
        src/test/java/dev/langchain4j/example/codereview/eval/EvaluationRunnerIT.java
git commit -m "feat(eval): repeated runs with mean+stddev aggregation"
```

### Task 3: EvalCommand 暴露 --runs

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/cli/EvalCommand.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/cli/EvalCommandTest.java`

- [ ] **Step 1: 看现有 EvalCommandTest 风格**

Run: `sed -n '1,40p' src/test/java/dev/langchain4j/example/codereview/cli/EvalCommandTest.java`
Expected: 了解它如何 mock runner / 解析选项，照此加用例。

- [ ] **Step 2: 写失败测试 —— `--runs 3` 透传给 runner**

在 `EvalCommandTest` 加一个用例，断言 `--runs 3` 时 `runner.run(..., 3)`（6 参重载）被调用、且 `config` 里 `runs_per_sample==3`。具体 mock 方式对齐文件里既有用例（若用 Mockito，则 `verify(runner).run(any(), any(), eq("vX"), argThat(c -> c.get("runs_per_sample").equals(3)), any(), eq(3))`）。

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -q -Dtest=EvalCommandTest test`
Expected: FAIL（`--runs` 选项不存在 / 6 参重载未被调用）。

- [ ] **Step 4: 加 --runs 选项并透传**

在 `EvalCommand` 选项区（`--suite` 之后）加：

```java
    @Option(names = "--runs",
            description = "Repeat each sample N times for variance. Default: 1.",
            defaultValue = "1")
    private int runs;
```

把 `call()` 里 `config.put("runs_per_sample", props.eval().runsPerSample());` 改为反映实际：

```java
        config.put("runs_per_sample", runs);
```

把 `runner.run(samples, reports, version, config, filter);` 改为：

```java
        EvalReport report = runner.run(samples, reports, version, config, filter, runs);
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q -Dtest=EvalCommandTest test`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/dev/langchain4j/example/codereview/cli/EvalCommand.java \
        src/test/java/dev/langchain4j/example/codereview/cli/EvalCommandTest.java
git commit -m "feat(eval): --runs CLI option wired to repeated runs"
```

---

# 阶段 2 · ToolStatus 枚举化 + tool_success_rate 语义（Finding 5 / Phase 3 调优项 2）

> 目标：`ToolStatus` 用 `ToolRunState{RAN, SKIPPED_EXPECTED, FAILED}` 区分「跑了」「预期跳过」「真失败」；`tool_success_rate` 把 SKIPPED_EXPECTED 排除出分母。先做这项调优（与样本无关，纯代码，可独立验证）。

## 文件结构

- Create: `src/main/java/dev/langchain4j/example/codereview/model/ToolRunState.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/model/ToolStatus.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/analyzer/SpotBugsResult.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/analyzer/SpotBugsAnalyzer.java`（analyzeWithSource 返回 SpotBugsResult）
- Modify: `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducer.java`（emit 枚举 + try/catch→FAILED）
- Modify: `src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java:115-121`（按枚举计数）
- Modify: `src/main/java/dev/langchain4j/example/codereview/reporting/MarkdownReporter.java:51`
- Test: `ToolFindingsProducerTest`, `MetricsTest`, `SpotBugsAnalyzerTest`

### Task 4: 引入 ToolRunState 枚举 + 改造 ToolStatus

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/model/ToolRunState.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/model/ToolStatus.java`

- [ ] **Step 1: 创建枚举**

`ToolRunState.java`:

```java
package dev.langchain4j.example.codereview.model;

/** 工具运行状态：跑了 / 预期内跳过（如样本不可编译、工具未安装）/ 真失败（analyzer 抛异常）。 */
public enum ToolRunState {
    RAN,
    SKIPPED_EXPECTED,
    FAILED
}
```

- [ ] **Step 2: 改 ToolStatus 用枚举**

`ToolStatus.java`:

```java
package dev.langchain4j.example.codereview.model;

public record ToolStatus(String tool, ToolRunState state, String reason) { }
```

- [ ] **Step 3: 编译，确认所有旧构造点 + 消费点报错**

Run: `mvn -q -o compile 2>&1 | grep -E "ToolStatus|status\(\)" | head`
Expected: 报错点为 `ToolFindingsProducer.java:28/32/34`、`EvaluationRunner.java:119`、`MarkdownReporter.java:51`（接下来逐个修）。

### Task 5: SpotBugs 区分「跑了」vs「预期跳过」

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/analyzer/SpotBugsResult.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/analyzer/SpotBugsAnalyzer.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/analyzer/SpotBugsAnalyzerTest.java`

- [ ] **Step 1: 创建结果类型**

`SpotBugsResult.java`:

```java
package dev.langchain4j.example.codereview.analyzer;

import java.util.List;

/** ran=true 表示 SpotBugs 实际编译并执行了（无论是否有命中）；ran=false 表示预期内跳过（不可编译 / 未安装）。 */
public record SpotBugsResult(boolean ran, List<Violation> violations) {
    public static SpotBugsResult skipped() {
        return new SpotBugsResult(false, List.of());
    }
}
```

- [ ] **Step 2: 看现有 SpotBugsAnalyzerTest 断言什么**

Run: `sed -n '1,80p' src/test/java/dev/langchain4j/example/codereview/analyzer/SpotBugsAnalyzerTest.java`
Expected: 了解它如何注入 `Runner`/`SourceCompiler` mock、断言返回 `List<Violation>`。

- [ ] **Step 3: 写失败测试 —— 不可编译时 ran=false，可编译时 ran=true**

把 `SpotBugsAnalyzerTest` 中调用 `analyzeWithSource(...)` 的断言改为基于 `SpotBugsResult`。至少两个用例：
- compiler 返回 `Optional.empty()` → `result.ran()==false` 且 `violations().isEmpty()`。
- compiler 返回 classesDir 且 runner 写出含命中的 XML → `result.ran()==true` 且 `violations()` 非空。

（沿用文件里既有 mock 写法；只把返回类型从 `List<Violation>` 换成 `SpotBugsResult`，断言相应调整。）

- [ ] **Step 4: 跑测试确认失败**

Run: `mvn -q -Dtest=SpotBugsAnalyzerTest test`
Expected: FAIL（返回类型不匹配 / 方法签名变了）。

- [ ] **Step 5: 改 analyzeWithSource 返回 SpotBugsResult**

把 `SpotBugsAnalyzer.analyzeWithSource` 改为：

```java
    public SpotBugsResult analyzeWithSource(List<DiffParser.FileDiff> files, Path sourceDir) {
        Optional<Path> classesDir = compiler.compile(sourceDir);
        if (classesDir.isEmpty()) {
            log.debug("SpotBugs skipped: source not compilable at {}", sourceDir);
            return SpotBugsResult.skipped();
        }
        try {
            Path output = Files.createTempFile("spotbugs-", ".xml");
            if (!runner.run(classesDir.get(), output)) {
                log.debug("SpotBugs runner reported skip");
                return SpotBugsResult.skipped();
            }
            return new SpotBugsResult(true, parseAndFilter(output, files));
        } catch (IOException e) {
            log.warn("SpotBugs I/O error: {}", e.toString());
            return SpotBugsResult.skipped();
        }
    }
```

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -q -Dtest=SpotBugsAnalyzerTest test`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/dev/langchain4j/example/codereview/model/ToolRunState.java \
        src/main/java/dev/langchain4j/example/codereview/model/ToolStatus.java \
        src/main/java/dev/langchain4j/example/codereview/analyzer/SpotBugsResult.java \
        src/main/java/dev/langchain4j/example/codereview/analyzer/SpotBugsAnalyzer.java \
        src/test/java/dev/langchain4j/example/codereview/analyzer/SpotBugsAnalyzerTest.java
git commit -m "feat(analyzer): ToolRunState enum + SpotBugsResult ran/skipped distinction"
```

### Task 6: ToolFindingsProducer emit 枚举状态 + FAILED 兜底

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducer.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducerTest.java`

- [ ] **Step 1: 写失败测试 —— 三态归类**

在 `ToolFindingsProducerTest` 加用例（对齐文件里既有 mock 注入 RegexAnalyzer/SpotBugsAnalyzer 的方式）：
- spotbugs `SpotBugsResult.skipped()` → statuses 含 `("spotbugs", SKIPPED_EXPECTED, ...)`。
- spotbugs `new SpotBugsResult(true, List.of())`（跑了没命中）→ `("spotbugs", RAN, null)`。
- regex 正常 → `("regex", RAN, null)`。
- spotbugs analyze 抛 `RuntimeException` → `("spotbugs", FAILED, <msg>)`，且不冒泡（produce 不抛）。

断言示例：

```java
assertThat(findings.statuses())
        .anySatisfy(s -> {
            assertThat(s.tool()).isEqualTo("spotbugs");
            assertThat(s.state()).isEqualTo(ToolRunState.SKIPPED_EXPECTED);
        });
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=ToolFindingsProducerTest test`
Expected: FAIL（编译错误：旧 `new ToolStatus(...,"ok",...)` 与枚举不符）。

- [ ] **Step 3: 改 produce()**

把 `ToolFindingsProducer.produce` 改为：

```java
    public ToolFindings produce(ReviewContext ctx) {
        List<Violation> all = new ArrayList<>();
        List<ToolStatus> statuses = new ArrayList<>();

        try {
            all.addAll(regex.analyze(ctx.fileDiffs()));
            statuses.add(new ToolStatus("regex", ToolRunState.RAN, null));
        } catch (RuntimeException e) {
            statuses.add(new ToolStatus("regex", ToolRunState.FAILED, e.toString()));
        }

        try {
            SpotBugsResult sb = spotbugs.analyzeWithSource(ctx.fileDiffs(), ctx.sourceRoot());
            if (!sb.ran()) {
                statuses.add(new ToolStatus("spotbugs", ToolRunState.SKIPPED_EXPECTED,
                        "not buildable or not installed"));
            } else {
                statuses.add(new ToolStatus("spotbugs", ToolRunState.RAN, null));
                all.addAll(sb.violations());
            }
        } catch (RuntimeException e) {
            statuses.add(new ToolStatus("spotbugs", ToolRunState.FAILED, e.toString()));
        }

        return new ToolFindings(dedupe(all), statuses);
    }
```

补 import：`import dev.langchain4j.example.codereview.model.ToolRunState;`

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=ToolFindingsProducerTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducer.java \
        src/test/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducerTest.java
git commit -m "feat(pipeline): ToolFindingsProducer emits RAN/SKIPPED_EXPECTED/FAILED"
```

### Task 7: Metrics/Runner 按枚举计数 + MarkdownReporter 适配

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java`（line ~115-121 计数）
- Modify: `src/main/java/dev/langchain4j/example/codereview/reporting/MarkdownReporter.java:51`
- Test: `src/test/java/dev/langchain4j/example/codereview/eval/MetricsTest.java`

- [ ] **Step 1: 写失败测试 —— runner 从枚举状态计数（SKIPPED_EXPECTED 不进分母，FAILED 才算失败）**

行为变更点是 **runner 怎么把 `toolStatus` 枚举翻成 `toolCallsTotal`/`toolCallsFailed`**，所以测在 `EvaluationRunnerIT`。stub agent 返回带三态 toolStatus 的 ReviewResult，断言结果 SampleMetrics 的计数：

```java
    @Test
    void toolCountsExcludeExpectedSkips() throws Exception {
        Path samples = workDir.resolve("samples-tool");
        Path reports = workDir.resolve("reports-tool");
        Files.createDirectories(samples);
        copyFixture("sample-pass", samples);

        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        CodeReviewAgent agent = (request, sourceRoot) -> new ReviewResult(
                "n", List.of(),
                List.of(new ToolStatus("regex", ToolRunState.RAN, null),
                        new ToolStatus("spotbugs", ToolRunState.SKIPPED_EXPECTED, "not buildable"),
                        new ToolStatus("other", ToolRunState.FAILED, "boom")));
        Matcher matcher = new Matcher((e, f) -> new LlmJudge.JudgeVerdict(false, 0.0, "no"), 5);
        EvaluationRunner runner = new EvaluationRunner(agent, matcher, mapper);

        EvalReport report = runner.run(samples, reports, "v-tool", Map.of("pipeline", "test"));
        SampleMetrics m = report.perSample().get(0);
        assertThat(m.toolCallsTotal()).isEqualTo(2);  // RAN + FAILED, SKIPPED_EXPECTED excluded
        assertThat(m.toolCallsFailed()).isEqualTo(1); // only FAILED
    }
```

加 import：`import dev.langchain4j.example.codereview.model.ToolStatus;`、`import dev.langchain4j.example.codereview.model.ToolRunState;`（若尚未存在）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=EvaluationRunnerIT test`
Expected: FAIL —— 旧 runner 用 `!"ok".equalsIgnoreCase(status.status())` 计数，会把 SKIPPED_EXPECTED 也算成失败，且 `status()` 已不存在（编译错误）。

- [ ] **Step 3: 改 runner 计数逻辑**

把 `EvaluationRunner.evaluateOne` 末尾的 `return new SampleMetrics(...)` 段（当前 line ~115-121）替换为：

```java
        List<ToolStatus> statuses = result.toolStatus() == null ? List.of() : result.toolStatus();
        int toolTotal = (int) statuses.stream()
                .filter(s -> s.state() != ToolRunState.SKIPPED_EXPECTED)
                .count();
        int toolFailed = (int) statuses.stream()
                .filter(s -> s.state() == ToolRunState.FAILED)
                .count();

        return new SampleMetrics(sample.id(), tp, fp, fn, severityMatches, severityComparisons,
                latency, 0L, 0L, toolTotal, toolFailed, statuses);
```

补 import：`import dev.langchain4j.example.codereview.model.ToolStatus;` 和 `import dev.langchain4j.example.codereview.model.ToolRunState;`

- [ ] **Step 4: 改 MarkdownReporter:51 用 state()**

把 `MarkdownReporter.java:51` 的 `.append(ts.status())` 改为 `.append(ts.state())`。

- [ ] **Step 5: 全量编译 + 测试**

Run: `mvn -q test`
Expected: 全绿。若有遗漏的 `status()` 调用点，按编译错误修。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java \
        src/main/java/dev/langchain4j/example/codereview/reporting/MarkdownReporter.java \
        src/test/java/dev/langchain4j/example/codereview/eval/EvaluationRunnerIT.java
git commit -m "feat(eval): tool_success_rate excludes expected skips; reporter uses state"
```

---

# 阶段 3 · 隔离边界测试（Finding 4）

> 目标：钉住「`EvaluationRunner` 传给 `agent.review` 的只有 diff + source-before」这条不变量，守住样本隔离，防未来回归。

### Task 8: 隔离边界测试

**Files:**
- Test: `src/test/java/dev/langchain4j/example/codereview/eval/IsolationBoundaryIT.java`（新建）

- [ ] **Step 1: 写测试 —— agent 收到的 request 只含 diff，sourceRoot 指向 source-before**

新建 `IsolationBoundaryIT.java`：构造一个 capturing agent（lambda 记录收到的 `request` 与 `sourceRoot`），跑一个 fixture 样本，断言：
- `request` 包含该样本 `diff.patch` 的内容片段；
- `request` **不包含** annotation 的 ground-truth 描述文本、`source-after` 内容、`meta.json` 的 `category`/`difficulty`/`notes` 值；
- `sourceRoot` 路径以 `source-before` 结尾。

```java
package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IsolationBoundaryIT {

    @TempDir Path workDir;

    @Test
    void agentReceivesOnlyDiffAndSourceBefore() throws Exception {
        Path samples = workDir.resolve("samples");
        Path sample = samples.resolve("iso-001");
        Files.createDirectories(sample.resolve("source-before"));
        Files.createDirectories(sample.resolve("source-after"));
        Files.writeString(sample.resolve("diff.patch"),
                "diff --git a/A.java b/A.java\n+int x = secretDiffToken;\n");
        Files.writeString(sample.resolve("source-before").resolve("A.java"), "class A {}\n");
        Files.writeString(sample.resolve("source-after").resolve("A.java"), "class A { int sourceAfterToken; }\n");
        Files.writeString(sample.resolve("meta.json"),
                "{\"id\":\"iso-001\",\"category\":\"SECURITY_metaToken\",\"difficulty\":\"hard_metaToken\",\"notes\":\"notesToken\"}");
        Files.writeString(sample.resolve("annotation.json"),
                "{\"expected_issues\":[{\"id\":\"I-1\",\"file\":\"A.java\",\"line\":1,\"line_range\":[1,1],"
                + "\"category\":\"SECURITY\",\"severity\":\"CRITICAL\",\"description\":\"annotationGroundTruthToken\","
                + "\"must_detect\":true,\"alternative_descriptions\":[]}],\"should_not_report\":[],\"notes\":\"x\"}");

        AtomicReference<String> seenRequest = new AtomicReference<>();
        AtomicReference<Path> seenRoot = new AtomicReference<>();
        CodeReviewAgent agent = (request, sourceRoot) -> {
            seenRequest.set(request);
            seenRoot.set(sourceRoot);
            return ReviewResult.empty("none");
        };
        Matcher matcher = new Matcher((expected, finding) -> new LlmJudge.JudgeVerdict(false, 0.0, "no"), 5);
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        new EvaluationRunner(agent, matcher, mapper)
                .run(samples, workDir.resolve("reports"), "iso", Map.of("pipeline", "test"));

        assertThat(seenRequest.get()).contains("secretDiffToken");
        assertThat(seenRequest.get())
                .doesNotContain("annotationGroundTruthToken")
                .doesNotContain("sourceAfterToken")
                .doesNotContain("metaToken")
                .doesNotContain("notesToken");
        assertThat(seenRoot.get().getFileName().toString()).isEqualTo("source-before");
    }
}
```

- [ ] **Step 2: 跑测试确认通过**

Run: `mvn -q -Dtest=IsolationBoundaryIT test`
Expected: PASS（验证当前 runner 行为已满足隔离；这是回归守卫）。

- [ ] **Step 3: 提交**

```bash
git add src/test/java/dev/langchain4j/example/codereview/eval/IsolationBoundaryIT.java
git commit -m "test(eval): isolation boundary — agent sees only diff + source-before"
```

---

# 阶段 4 · 样本扩充 20 → 40（Phase 1）

> 目标：补 10 reverse + 10 synthetic 到 40 样本。先建「样本校验测试」做守卫，再按配方造样本。

### Task 9: 样本分布统计 + 校验测试（先建守卫）

**Files:**
- Create: `src/test/java/dev/langchain4j/example/codereview/eval/SampleSetValidationTest.java`
- Create: `docs/learnings/w4-sample-distribution.md`（分布表）

- [ ] **Step 1: 统计现有 20 样本的 category/difficulty 分布**

Run: `for d in eval/samples/reverse-*; do python3 -c "import json,sys; m=json.load(open('$d/meta.json')); print(m.get('category'), m.get('difficulty'))"; done | sort | uniq -c`
Expected: 看到各 category/difficulty 计数；据此确定缺口（spec 预判 CONCURRENCY/PERFORMANCE/TEST 偏少）。把结果记进 `docs/learnings/w4-sample-distribution.md`，列出「现状 → 目标 40 的补法」。

- [ ] **Step 2: 写样本校验测试**

新建 `SampleSetValidationTest.java`：遍历 `eval/samples/` 每个子目录，断言：
- 必含 `diff.patch`、`meta.json`、`annotation.json`、`source-before/`；
- `Sample.load(dir, mapper)` 不抛异常；
- `annotation.expectedIssues()` 每条的 `category`/`severity` 是合法枚举（Jackson 反序列化成功即合法），`line >= 0`；
- `DiffParser.parse(diff)` 不抛异常且至少解析出一个 FileDiff。

```java
package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.langchain4j.example.codereview.infra.DiffParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SampleSetValidationTest {

    @Test
    void everySampleIsWellFormedAndAgentVisibleFieldsParse() throws Exception {
        Path samplesDir = Path.of("eval/samples");
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        DiffParser parser = new DiffParser();

        List<Path> dirs;
        try (var s = Files.list(samplesDir)) {
            dirs = s.filter(Files::isDirectory).sorted().toList();
        }
        assertThat(dirs).isNotEmpty();

        for (Path dir : dirs) {
            assertThat(Files.exists(dir.resolve("diff.patch"))).as("%s diff.patch", dir).isTrue();
            assertThat(Files.exists(dir.resolve("meta.json"))).as("%s meta.json", dir).isTrue();
            assertThat(Files.exists(dir.resolve("annotation.json"))).as("%s annotation.json", dir).isTrue();
            assertThat(Files.isDirectory(dir.resolve("source-before"))).as("%s source-before", dir).isTrue();

            Sample sample = Sample.load(dir, mapper); // throws if annotation/enums malformed
            sample.annotation().expectedIssues().forEach(issue -> {
                assertThat(issue.category()).as("%s category", dir).isNotNull();
                assertThat(issue.severity()).as("%s severity", dir).isNotNull();
                assertThat(issue.line()).as("%s line", dir).isGreaterThanOrEqualTo(0);
            });

            assertThat(parser.parse(sample.diffPatch())).as("%s parses to >=1 FileDiff", dir).isNotEmpty();
        }
    }
}
```

> 若 `DiffParser` 构造或 `parse` 签名不同，先 `Run: sed -n '1,40p' src/main/java/dev/langchain4j/example/codereview/infra/DiffParser.java` 核对后调整。

- [ ] **Step 3: 跑测试确认现有 20 样本通过**

Run: `mvn -q -Dtest=SampleSetValidationTest test`
Expected: PASS（现有 20 样本应全部合法；若某个不合法，先修样本或修测试断言到反映真实约定）。

- [ ] **Step 4: 提交守卫**

```bash
git add src/test/java/dev/langchain4j/example/codereview/eval/SampleSetValidationTest.java \
        docs/learnings/w4-sample-distribution.md
git commit -m "test(eval): sample-set validation guard + distribution baseline"
```

### Task 10: 造 10 个 reverse-021..030（补稀缺类目）

**Files:**
- Create: `eval/samples/reverse-021/` … `eval/samples/reverse-030/`（每个含 meta.json/diff.patch/annotation.json/source-before/source-after）

**配方（每个样本重复）：**
1. 选一个目标类目/难度缺口（优先 CONCURRENCY / PERFORMANCE / TEST，且补若干 hard）。
2. 在 `source-before/` 写一段**正确**的 Java（数行、可独立理解）。
3. 写 `diff.patch`：以 `source-before` 为基准，**引入一个该类目的真实缺陷**（reverse-style：从「对的代码」反向构造出「错的改动」）。diff 用标准 `diff --git` + hunk header，确保行号真实。
4. `annotation.json`：按 `eval/samples/reverse-001/annotation.json` 的 schema 写 ground truth（`file`/`line`/`line_range`/`category`/`severity`/`description`/`must_detect`/`alternative_descriptions`）。`line` 用**新文件 post-change 行号**。
5. `meta.json`：按 `reverse-001/meta.json` schema，`category`/`difficulty` 填对应缺口值。
6. `source-after/` 放修好的版本（仅人工核对，禁止 agent 输入）。

- [ ] **Step 1: 造 reverse-021..030（逐个按配方）**

逐个创建 10 个目录。每造 2-3 个就跑一次校验：

Run: `mvn -q -Dtest=SampleSetValidationTest test`
Expected: PASS（新样本合法）。

- [ ] **Step 2: 子集 smoke 确认新样本能被 review（不崩）**

Run: `mvn -q clean package -DskipTests && env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval --version w4-smoke-rev --samples reverse-021,reverse-022 --suite dev`
Expected: 命令成功退出，per-sample 无 `review error`（需 `MOONSHOT_API_KEY`）。

- [ ] **Step 3: 提交**

```bash
git add eval/samples/reverse-02*  eval/samples/reverse-030
git commit -m "eval(samples): add reverse-021..030 covering scarce categories"
```

### Task 11: 造 10 个 synthetic-001..010（合成边界）

**Files:**
- Create: `eval/samples/synthetic-001/` … `eval/samples/synthetic-010/`

**三类配方（各约 3-4 个）：**
- **真阴性（clean diff）**：`source-before` 正确，`diff.patch` 是一个**正确无害**的改动；`annotation.json` 的 `expected_issues` 为 `[]`，可在 `should_not_report` 写「容易被误报的点」。测 v3 是否过度报 → 直接影响 fp_rate。
- **近邻干扰**：`diff.patch` 同时含一个**真缺陷** + 一处**相似但无害**的改动；`annotation.json` 只标真缺陷那条。
- **行号/跨文件刁钻**：构造 hunk header 行号偏移较大、或缺陷依赖另一文件上下文（`source-before/` 放多文件）的 case，测 DiffParser 行号与 DiffAnalyzer 跨文件 grep。

- [ ] **Step 1: 造 synthetic-001..010（逐个按配方）**

每造 2-3 个跑校验：

Run: `mvn -q -Dtest=SampleSetValidationTest test`
Expected: PASS。

- [ ] **Step 2: 确认总数 40**

Run: `ls -d eval/samples/*/ | grep -E 'reverse-|synthetic-' | wc -l`
Expected: `40`。

- [ ] **Step 3: 更新分布表**

把最终 40 样本的 category/difficulty 分布写进 `docs/learnings/w4-sample-distribution.md`（Run Task 9 Step 1 的统计命令，改 glob 为 `reverse-* synthetic-*`）。

- [ ] **Step 4: 提交**

```bash
git add eval/samples/synthetic-00* eval/samples/synthetic-010 docs/learnings/w4-sample-distribution.md
git commit -m "eval(samples): add synthetic-001..010 edge cases (true-neg / near-miss / line-tricky)"
```

---

# 阶段 5 · 40 样本 release 评测（Phase 2）

> 目标：打 tag 锁版本，worktree honest 复现，产出 strict-40 报告（复现不了的降级为 `*-20sample-historical.json`）。v3/v3.1-tuned ×3，基线 ×1。

### Task 12: 打里程碑 tag

**Files:** 无（git tag）

- [ ] **Step 1: 确认各版本 commit**

Run: `git log --oneline --all | grep -iE "spotbugs|hybrid|pipeline|baseline" | head -30`
Expected: 定位 v0（regex baseline 的 W1 commit）、v1=`4f7469f`、v2（hybrid+rerank、pipeline 重构前的 commit）、v3（pipeline 完成，当前线 `82039a1`/`6f373b4` 一带）。把确认的 4 个 commit hash 记下。

- [ ] **Step 2: 打 tag**

```bash
git tag eval/v0 <v0-commit>
git tag eval/v1 4f7469f
git tag eval/v2 <v2-commit>
git tag eval/v3 <v3-commit>
git tag --list 'eval/*'
```

Expected: 列出 `eval/v0..v3`。**注意**：tag 指向旧 commit，那些 commit 的 `eval/samples/` 只有 20 样本——复现时要用 worktree 跑 **当前 40 样本目录**（见 Task 13）。

### Task 13: worktree 复现 v0/v1/v2（×1，40 样本）

**Files:** 产出 `eval/reports/v0.json` / `v1.json` / `v2.json`（或对应 `*-20sample-historical.json`）

> 关键：worktree checkout 旧代码，但 `--samples-dir` 指向**主树的 40 样本**，让旧代码评新样本。旧代码若无 `--samples-dir`/`--suite`，用其当时的 CLI 形式 + 显式样本目录。

- [ ] **Step 1: 对每个旧版本，在 /tmp worktree 里复现**

对 vN ∈ {v0, v1, v2}：

```bash
git worktree add /tmp/crev-vN eval/vN
cd /tmp/crev-vN
mvn -q clean package -DskipTests
# 旧代码评主树 40 样本（路径按实际主树位置）
env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval \
  --version vN --samples-dir /Users/yzy/Project/code-review-agent/eval/samples \
  --report-dir /tmp/crev-vN-reports
cd /Users/yzy/Project/code-review-agent
git worktree remove /tmp/crev-vN
```

> 若旧版本 CLI 不支持 `--samples-dir`/`--report-dir`，改用其当时支持的参数（`Run: java -jar target/...jar eval --help` 查）；仍跑不通 → 进 Step 3 降级。

- [ ] **Step 2: 校验无 review error，copy 回主树**

> 注意：review error 进的是 `ReviewResult.empty("review error…")` 的 summary，**不落进 report JSON**。唯一可靠信号是 runner 的日志行 `"Sample {} review failed"`。所以 Step 1 的 eval 命令要 `2>&1 | tee /tmp/crev-vN.log`，再扫日志：

Run: `grep -c "review failed" /tmp/crev-vN.log || echo 0`
Expected: `0`。为 `0` 才 `cp /tmp/crev-vN-reports/vN.json eval/reports/vN.json`；非 0 说明带病，按 W3 红线不出报告，排查或进 Step 3 降级。

- [ ] **Step 3: 复现不了的版本 → 降级为历史文件**

若某 vN 在 40 样本上 build/跑不通：保留其现有 20 样本报告为 `eval/reports/vN-20sample-historical.json`，并在文件旁注（或 Task 17 表格脚注）标「20 样本历史值，不可与 40 样本比较」。**不伪造 40 样本数。**

- [ ] **Step 4: 提交**

```bash
git add eval/reports/
git commit -m "eval(w4): reproduce v0/v1/v2 on 40-sample suite (or historical fallback)"
```

### Task 14: 跑 v3（×3，40 样本 release）

**Files:** 产出 `eval/reports/v3.json`

- [ ] **Step 1: 主树 build + 跑 v3 ×3**

```bash
mvn -q clean package -DskipTests
env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval \
  --version v3 --pipeline w3-pipeline --suite release --runs 3 2>&1 | tee /tmp/v3-run.log
```

Expected: 命令成功；stdout 打印 recall/precision/fp_rate。

- [ ] **Step 2: 红线 + 方差检查**

Run: `grep -c "review failed" /tmp/v3-run.log || echo 0`
Expected: `0`（review error 不落 JSON，只在日志；非 0 则按红线不出报告）。再 `python3 -c "import json;r=json.load(open('eval/reports/v3.json'));print('runs',len(r['per_run_metrics']));print('stddev recall',r['metrics_std_dev']['recall'])"`
Expected: `runs 3` + 一个 stddev 数值（看稳定性）。

- [ ] **Step 3: 提交**

```bash
git add eval/reports/v3.json
git commit -m "eval(w4): v3 release report on 40 samples x3 runs"
```

---

# 阶段 6 · severity 校准 → v3.1-tuned（Phase 3 调优项 1）

> 目标：诊断 v3 severity 偏移，做最小校准，产出 v3.1-tuned；硬约束：precision/recall 相对 v3-40 不回退。

### Task 15: 诊断 severity 偏移 + 校准

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/LlmReviewer.java`（SYSTEM prompt severity 判据）和/或 `Summarizer.java`（确定性钳制）
- Test: 相应 `LlmReviewerTest` / `SummarizerTest`

- [ ] **Step 1: 从 v3 报告拉 severity 混淆**

Run: `python3 -c "import json;r=json.load(open('eval/reports/v3.json'));print('severity_accuracy',r['metrics']['severity_accuracy'])"`
并人工抽查 per-sample：对比 agent finding severity 与 annotation severity，定位系统性偏移（高估？低估？哪个 category）。把诊断写进 `docs/learnings/w4-notes.md` 草稿。

- [ ] **Step 2: 校准（按诊断择一/二）**

- 优先改 `LlmReviewer.SYSTEM`：补 severity 判据（CRITICAL = 安全漏洞/数据损坏/崩溃；WARNING = 正确性风险但有条件触发；SUGGESTION = 风格/可维护性）。
- 若某 source（如 SpotBugs priority-1）系统性被 LLM 降级，在 `Summarizer` 对该 source 做确定性 severity 钳制。

为改动加/改单测（如 `SummarizerTest` 断言「SpotBugs CRITICAL violation 补进来时 severity 不被降级」）。

- [ ] **Step 3: 跑单测**

Run: `mvn -q -Dtest=LlmReviewerTest,SummarizerTest test`
Expected: PASS。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ src/test/java/dev/langchain4j/example/codereview/agents/pipeline/
git commit -m "feat(pipeline): severity calibration (prompt criteria + summarizer clamp)"
```

### Task 16: 跑 v3.1-tuned（×3）+ 达标判定

**Files:** 产出 `eval/reports/v3.1-tuned.json`

- [ ] **Step 1: build + 跑 v3.1-tuned**

```bash
mvn -q clean package -DskipTests
env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval \
  --version v3.1-tuned --pipeline w4-tuned --suite release --runs 3 2>&1 | tee /tmp/v31-run.log
```

- [ ] **Step 2: 达标判定（spec 验收）**

Run:
```bash
python3 -c "
import json
v3=json.load(open('eval/reports/v3.json'))['metrics']
t=json.load(open('eval/reports/v3.1-tuned.json'))['metrics']
print('severity_accuracy', v3['severity_accuracy'], '->', t['severity_accuracy'])
print('recall', v3['recall'], '->', t['recall'])
print('precision', v3['precision'], '->', t['precision'])
"
```
Expected: `severity_accuracy` 上升；`recall`/`precision` **不低于 v3**（基准：v2-40 若存在则与之比，否则与 v3-40 比，见 spec）。若 precision/recall 回退 → 回退该校准改动（spec 硬约束），把 severity 记为已知 caveat。

- [ ] **Step 3: 红线检查 + 提交**

Run: `grep -c "review failed" /tmp/v31-run.log || echo 0` → `0`

```bash
git add eval/reports/v3.1-tuned.json
git commit -m "eval(w4): v3.1-tuned release report (severity calibrated)"
```

---

# 阶段 7 · 交付（Phase 4）

### Task 17: 指标曲线生成脚本（Finding 5 归一化）

**Files:**
- Create: `scripts/plot_metrics.py`
- Create: `docs/eval-metrics.md`（生成产物：表 + Mermaid 图）

- [ ] **Step 1: 写脚本**

`scripts/plot_metrics.py`：读 `eval/reports/*.json`（**只取 strict-40 档**，跳过 `*-20sample-historical.json`，后者单列脚注），输出 Markdown 表 + Mermaid `xychart-beta`（recall/precision/fp_rate/latency 随版本变化）到 `docs/eval-metrics.md`。

要点（钉死，避免 Finding 5 假设同词表）：
- 只读 `metrics`（已算好的 aggregate 数）画曲线，**不解析 per-sample tool status 词表**。
- 若要展示 tool 状态，归一化 `ok→RAN`、`skipped→SKIPPED_EXPECTED`。
- 每版本附 `config`（pipeline / suite / runs_per_sample）做脚注。
- `*-20sample-historical.json` 仅在表格脚注列出，标「不可与 40 样本比较」，不进图。

```python
#!/usr/bin/env python3
import json, glob, os

ORDER = ["v0", "v1", "v2", "v3", "v3.1-tuned"]
STATUS_NORM = {"ok": "RAN", "skipped": "SKIPPED_EXPECTED"}

def load_strict():
    out = {}
    for p in glob.glob("eval/reports/*.json"):
        name = os.path.basename(p)[:-5]
        if name.endswith("-20sample-historical"):
            continue
        out[name] = json.load(open(p))
    return out

def row(name, r):
    m = r["metrics"]; c = r.get("config", {})
    return (name, m["recall"], m["precision"], m["fp_rate"], m["avg_latency_ms"],
            m["severity_accuracy"], c.get("runs_per_sample"), c.get("pipeline"))

def main():
    reports = load_strict()
    rows = [row(n, reports[n]) for n in ORDER if n in reports]
    lines = ["# Eval Metrics (strict 40-sample)\n",
             "| Version | Recall | Precision | FP Rate | Latency(ms) | Sev Acc | Runs | Pipeline |",
             "| --- | --- | --- | --- | --- | --- | --- | --- |"]
    for n, rec, pre, fp, lat, sev, runs, pl in rows:
        lines.append(f"| {n} | {rec:.2f} | {pre:.2f} | {fp:.2f} | {lat:.0f} | {sev:.2f} | {runs} | {pl} |")
    # Mermaid recall/precision curve
    names = [r[0] for r in rows]
    lines += ["\n```mermaid", "xychart-beta",
              f'  x-axis [{", ".join(names)}]',
              "  y-axis \"score\" 0 1",
              f'  line [{", ".join(f"{r[1]:.2f}" for r in rows)}]',
              f'  line [{", ".join(f"{r[2]:.2f}" for r in rows)}]',
              "```",
              "\n_Line 1 = recall, Line 2 = precision._"]
    hist = sorted(glob.glob("eval/reports/*-20sample-historical.json"))
    if hist:
        lines.append("\n> Historical (20-sample, NOT comparable to 40-sample): "
                     + ", ".join(os.path.basename(h) for h in hist))
    open("docs/eval-metrics.md", "w").write("\n".join(lines) + "\n")
    print("wrote docs/eval-metrics.md")

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 跑脚本**

Run: `python3 scripts/plot_metrics.py && sed -n '1,40p' docs/eval-metrics.md`
Expected: 生成表 + Mermaid 块，数字与 `eval/reports/` 一致。

- [ ] **Step 3: 提交**

```bash
git add scripts/plot_metrics.py docs/eval-metrics.md
git commit -m "docs(w4): metrics curve generator + eval-metrics report"
```

### Task 18: 架构图

**Files:**
- Create: `docs/architecture.md`（两张 Mermaid 图）

- [ ] **Step 1: 写两张 Mermaid 图**

`docs/architecture.md`：
- 图①运行时 review pipeline：`extractDiff → DiffAnalyzer → ToolFindingsProducer → LlmReviewer(+RAG retrieve) → Summarizer → ReviewResult`。
- 图②eval 闭环：`samples(diff+source-before) → agent.review → Matcher(line-window + LlmJudge) → Metrics → EvalReport(json)`。

用 `flowchart LR`。节点名与代码类名一致（便于对照）。

- [ ] **Step 2: 校验 Mermaid 语法**

Run: `grep -c "flowchart" docs/architecture.md`
Expected: `2`。（渲染由 GitHub/README 完成；此处只确保块存在、节点名正确。）

- [ ] **Step 3: 提交**

```bash
git add docs/architecture.md
git commit -m "docs(w4): runtime pipeline + eval-loop architecture diagrams"
```

### Task 19: README 重写

**Files:**
- Modify: `README.md`（若不存在则 Create）

- [ ] **Step 1: 看现有 README**

Run: `test -f README.md && sed -n '1,60p' README.md || echo "no README"`
Expected: 了解现状，决定改写范围。

- [ ] **Step 2: 重写 README**

结构（spec 交付物 1）：是什么 / 架构（嵌 `docs/architecture.md` 图或链接）/ 怎么跑（引用 `eval/README.md` 命令，不复制第二份真相）/ eval 成绩（嵌 `docs/eval-metrics.md` 表 + **诚实声明：全合成样本局限**）/ 设计取舍（pipeline vs AiServices，引用 W3 notes）/ 后续路线（真实 PR 样本、多 Agent v4-stretch）。

- [ ] **Step 3: 验证 README 里的命令真能跑**

逐条执行 README 的 build/review/eval 命令（至少 build + 一个 smoke eval），确认无误、指标数字与 `eval/reports/` 一致。

Run: `mvn -q clean package -DskipTests && env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval --version readme-smoke --suite smoke`
Expected: 成功，数字与 README 声明一致。

- [ ] **Step 4: 提交**

```bash
git add README.md
git commit -m "docs(w4): rewrite README — architecture, run commands, eval results, honesty note"
```

### Task 20: demo 脚本 + W4 学习笔记 + 收尾

**Files:**
- Create: `docs/demo-script.md`
- Create: `docs/learnings/w4-notes.md`

- [ ] **Step 1: 写 demo 脚本**

`docs/demo-script.md`：可复现命令清单（build → review 一个 sample → 展示输出 → 跑 smoke eval → 跑 release ×3 → 跑 `plot_metrics.py`），供 user 据此真录屏。每条命令附「预期看到什么」。agent 不做真录屏。

- [ ] **Step 2: 写 W4 学习笔记**

`docs/learnings/w4-notes.md`：沿用 W3 格式（技术细节 / 设计权衡 / 面试 Q&A），覆盖：重复跑聚合、ToolStatus 三态、隔离边界测试、样本扩充策略与诚实声明、severity 校准、strict-40 vs 历史值分档。

- [ ] **Step 3: 全量验收**

Run: `mvn -q clean package` （不跳测试）
Expected: BUILD SUCCESS + 全部测试绿。

- [ ] **Step 4: 提交**

```bash
git add docs/demo-script.md docs/learnings/w4-notes.md
git commit -m "docs(w4): demo script + W4 learning notes"
```

---

# 整体验收门槛（spec 对齐）

```text
mvn test                              全绿（重复跑聚合 / 隔离边界 / ToolStatus 三态 / 样本校验）
mvn -q clean package                  成功
eval/samples 共 40 个 + SampleSetValidationTest 绿
v3 / v3.1-tuned：40 样本 ×3，无 review error，report 含 per_run_metrics + metrics_std_dev
v0/v1/v2：40 样本 ×1 report 或明确降级 *-20sample-historical.json（二者必居其一，不伪造）
severity_accuracy(v3.1-40) >= 基准(v2-40 若有，否则 v3-40)，precision/recall 不回退
docs/eval-metrics.md 曲线只用 strict 40 档；历史值仅脚注
README 命令全部验证可跑，数字与 eval/reports 一致
```
