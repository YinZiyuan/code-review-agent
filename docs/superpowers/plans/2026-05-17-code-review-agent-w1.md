# Code Review Agent — W1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate to Spring Boot, clean up the 5 known small issues, add structured `ReviewResult` output, build the `EvaluationRunner` framework, and produce the first v0 baseline metric from 5 reverse-constructed PR samples.

**Architecture:** Single Spring Boot CLI app with three picocli subcommands (`review`/`eval`/`sample`). Pipeline-shaped agent (W3 default) is not yet built — W1 keeps the single-agent + tools shape from current code, but evolves the LLM output to structured `ReviewResult` JSON so the evaluation framework has a stable parsing target.

**Tech Stack:** Java 17 · Spring Boot 3.5.6 · LangChain4j Spring Boot starter (`1.15.0-beta25`, exact version verified via `mvn dependency:tree`) · picocli-spring-boot-starter 4.7.6 · Kimi (`moonshot-v1-8k`) · BGE-small-en-v15-quantized · JUnit 5 + Mockito + AssertJ (via spring-boot-starter-test)

**Source spec:** `docs/superpowers/specs/2026-05-17-code-review-agent-design.md` §1 W1, §2 (partial), §4.1.1, §5.3, §5.7, §6.6

---

## File Map

**Created in W1:**

| File | Responsibility |
| --- | --- |
| `src/main/java/.../CodeReviewApplication.java` | `@SpringBootApplication` entry point |
| `src/main/resources/application.yml` | Spring Boot config root |
| `src/main/resources/application-eval.yml` | Eval profile overrides |
| `src/main/java/.../config/CodeReviewProperties.java` | `@ConfigurationProperties("code-review")` |
| `src/main/java/.../config/AgentConfig.java` | `@Bean` factories for `CodeReviewAgent`, tools |
| `src/main/java/.../config/RagConfig.java` | `@Bean` factory for `ContentRetriever`, embedding cache |
| `src/main/java/.../cli/ReviewCommand.java` | `@Command(name="review")` — runs single review against a repo |
| `src/main/java/.../cli/EvalCommand.java` | `@Command(name="eval")` — runs evaluation suite |
| `src/main/java/.../cli/SampleCommand.java` | `@Command(name="sample")` — stub for W2 |
| `src/main/java/.../infra/GitClient.java` | Single git subprocess wrapper |
| `src/main/java/.../infra/DiffParser.java` | Parse unified diff hunks, map diff line → file line |
| `src/main/java/.../infra/EmbeddingCache.java` | Serialize/load `InMemoryEmbeddingStore` to local JSON |
| `src/main/java/.../analyzer/StaticAnalyzer.java` | Interface `List<Violation> analyze(ParsedDiff)` |
| `src/main/java/.../analyzer/Violation.java` | POJO: severity, file, line, rule, message |
| `src/main/java/.../analyzer/RegexAnalyzer.java` | Extract existing regex rules into this implementation |
| `src/main/java/.../model/Severity.java` | Enum `CRITICAL`, `WARNING`, `SUGGESTION` |
| `src/main/java/.../model/Category.java` | Enum `SECURITY`, `PERFORMANCE`, `STABILITY`, `CONCURRENCY`, `TEST`, `STYLE`, `OTHER` |
| `src/main/java/.../model/Citation.java` | POJO: id, source, section |
| `src/main/java/.../model/ReviewFinding.java` | POJO matching spec §4.1.1 schema |
| `src/main/java/.../model/ReviewResult.java` | POJO: summary, findings, tool_status |
| `src/main/java/.../model/ToolStatus.java` | POJO: tool, status, reason |
| `src/main/java/.../reporting/MarkdownReporter.java` | Render `ReviewResult` → Markdown |
| `src/main/java/.../rag/KnowledgeBaseIndexer.java` | Build embedding store (cache-aware) |
| `src/main/java/.../eval/Sample.java` | POJO + loader for a single sample directory |
| `src/main/java/.../eval/Annotation.java` | POJO matching `annotation.json` |
| `src/main/java/.../eval/ExpectedIssue.java` | POJO inside annotation |
| `src/main/java/.../eval/SuppressedPattern.java` | POJO for `should_not_report` items |
| `src/main/java/.../eval/MatchResult.java` | Record per-finding match outcome |
| `src/main/java/.../eval/SampleMetrics.java` | TP/FP/FN per sample |
| `src/main/java/.../eval/Metrics.java` | Aggregate metrics across samples |
| `src/main/java/.../eval/LlmJudge.java` | LLM-as-judge semantic match |
| `src/main/java/.../eval/Matcher.java` | Two-layer matching algorithm |
| `src/main/java/.../eval/EvaluationRunner.java` | Orchestrate: load samples → run agent → match → write report |
| `src/main/java/.../eval/EvalReport.java` | POJO for `v{N}.json` report |
| `eval/samples/reverse-001/` | First reverse-constructed sample (worked example) |
| `eval/samples/README.md` | Sample format documentation |
| `eval/reports/v0-baseline.json` | First baseline produced by W1 |
| `README.md` (new) | Project intro + v0 baseline snippet |

**Modified in W1:**

| File | Change |
| --- | --- |
| `pom.xml` | Switch to Spring Boot parent; add starters; add test deps |
| `.gitignore` | Add `target/`, `.env`, `~/.code-review-agent/cache/` |
| `src/main/java/.../codereview/CodeReviewAgent.java` | Return `ReviewResult` (not `String`); fix prompt; expects JSON output |
| `src/main/java/.../codereview/GitDiffTool.java` | Use `GitClient`; per-file splitting with size budget |
| `src/main/java/.../codereview/RuleCheckerTool.java` | Use `DiffParser` for correct line numbers; delegate to `StaticAnalyzer` |
| `src/main/java/.../codereview/KnowledgeBaseLoader.java` | Replaced by `KnowledgeBaseIndexer` + `EmbeddingCache` (delete file) |
| `src/main/java/.../codereview/CodeReviewRunner.java` | **Delete** — replaced by `CodeReviewApplication` + `ReviewCommand` |

**Test files (mirrors main package):**

| File | What it tests |
| --- | --- |
| `src/test/java/.../infra/DiffParserTest.java` | Hunk parsing, line-number mapping, edge cases |
| `src/test/java/.../infra/GitClientTest.java` | Run against a fixture git repo |
| `src/test/java/.../infra/EmbeddingCacheTest.java` | Serialize/deserialize round-trip, miss/hit |
| `src/test/java/.../analyzer/RegexAnalyzerTest.java` | Each rule's positive + negative case |
| `src/test/java/.../model/ReviewFindingTest.java` | JSON serde + schema validation |
| `src/test/java/.../reporting/MarkdownReporterTest.java` | Rendering, severity sort, citation links |
| `src/test/java/.../eval/MetricsTest.java` | recall / precision / fp_rate / severity formulas |
| `src/test/java/.../eval/MatcherTest.java` | Two-layer matching, edge cases (mock judge) |
| `src/test/java/.../eval/EvaluationRunnerIT.java` | `@SpringBootTest`, mocked `ChatModel`, runs 2 fixture samples |
| `src/test/resources/fixtures/diff-hunks/*.patch` | Real diff samples for parser tests |
| `src/test/resources/fixtures/spotbugs-output.xml` | (placeholder, used in W2) |
| `src/test/resources/eval-fixtures/sample-pass/` | Fixture for eval IT |
| `src/test/resources/eval-fixtures/sample-fail/` | Fixture for eval IT |

---

## Phase 1 · Spring Boot foundation (Tasks 1-3)

### Task 1: Switch pom.xml to Spring Boot + verify dependency tree

**Files:**
- Modify: `pom.xml` (full rewrite)
- Modify: `.gitignore`

- [ ] **Step 1: Rewrite `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.6</version>
        <relativePath/>
    </parent>

    <groupId>dev.langchain4j.example</groupId>
    <artifactId>code-review-agent</artifactId>
    <version>1.0.0</version>

    <properties>
        <java.version>17</java.version>
        <langchain4j.version>1.15.0-beta25</langchain4j.version>
        <picocli.version>4.7.6</picocli.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-spring-boot-starter</artifactId>
            <version>${langchain4j.version}</version>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-open-ai-spring-boot-starter</artifactId>
            <version>${langchain4j.version}</version>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-easy-rag</artifactId>
            <version>${langchain4j.version}</version>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-embeddings-bge-small-en-v15-q</artifactId>
            <version>${langchain4j.version}</version>
        </dependency>

        <dependency>
            <groupId>info.picocli</groupId>
            <artifactId>picocli-spring-boot-starter</artifactId>
            <version>${picocli.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Verify dependency tree resolves**

Run: `mvn -q dependency:tree | head -80`
Expected: no conflict warnings; `langchain4j-core` resolves to a single version. If `1.15.0-beta25` is unavailable (LangChain4j releases beta versions rapidly), check Maven Central for the latest `1.15.x-betaN` and update `langchain4j.version`. **Do not** mix beta generations.

- [ ] **Step 3: Update `.gitignore`**

```
target/
.env
.idea/
*.iml
.DS_Store
~/.code-review-agent/
eval/reports/*-traces/
```

- [ ] **Step 4: Confirm clean compile**

Run: `mvn -q clean compile`
Expected: BUILD SUCCESS (the existing source under `dev/langchain4j/example/codereview` will still compile — it imports only LangChain4j types that still exist in 1.15).
If compilation fails, fix imports inline (LangChain4j 1.15 may have renamed `ChatModel` etc.). Note any breaking changes for later tasks.

- [ ] **Step 5: Commit**

```bash
git add pom.xml .gitignore
git commit -m "build: migrate to Spring Boot 3.5 + LangChain4j 1.15 starters"
```

---

### Task 2: Spring Boot application skeleton + minimal config

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/CodeReviewApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/java/dev/langchain4j/example/codereview/config/CodeReviewProperties.java`

- [ ] **Step 1: Create `CodeReviewApplication.java`**

```java
package dev.langchain4j.example.codereview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodeReviewApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(CodeReviewApplication.class, args)));
    }
}
```

- [ ] **Step 2: Create `application.yml`**

```yaml
langchain4j:
  open-ai:
    chat-model:
      base-url: https://api.moonshot.cn/v1
      api-key: ${MOONSHOT_API_KEY:}
      model-name: moonshot-v1-8k
      temperature: 0
      max-tokens: 4096
      timeout: 60s
      log-requests: false
      log-responses: false

code-review:
  rag:
    embedding-cache-dir: ${user.home}/.code-review-agent/cache
    top-k: 3
    min-score: 0.4
    rerank-enabled: false   # W2 turns this on
  orchestration:
    reviewer-timeout: 60s
    parallelism: 3
  eval:
    judge-model: moonshot-v1-8k
    runs-per-sample: 1      # W1 keeps it 1; release uses 3
    samples-dir: eval/samples
    report-dir: eval/reports

spring:
  main:
    web-application-type: none
    banner-mode: off

logging:
  level:
    root: INFO
    dev.langchain4j.example: INFO
```

- [ ] **Step 3: Create `CodeReviewProperties.java`**

```java
package dev.langchain4j.example.codereview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "code-review")
public record CodeReviewProperties(
        Rag rag,
        Orchestration orchestration,
        Eval eval
) {
    public record Rag(
            Path embeddingCacheDir,
            int topK,
            double minScore,
            boolean rerankEnabled
    ) { }

    public record Orchestration(
            Duration reviewerTimeout,
            int parallelism
    ) { }

    public record Eval(
            String judgeModel,
            int runsPerSample,
            Path samplesDir,
            Path reportDir
    ) { }
}
```

- [ ] **Step 4: Smoke-test app starts**

Run: `MOONSHOT_API_KEY=dummy mvn -q spring-boot:run -Dspring-boot.run.arguments="--help" 2>&1 | tail -20`
Expected: Spring Boot starts (banner suppressed), then exits cleanly because no `CommandLineRunner` is registered yet. No exception stack traces.

If `ChatModel` bean fails to construct due to missing API key, that's expected before we wire picocli — fine to ignore for now.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/CodeReviewApplication.java \
        src/main/java/dev/langchain4j/example/codereview/config/CodeReviewProperties.java \
        src/main/resources/application.yml
git commit -m "feat: Spring Boot app skeleton + CodeReviewProperties"
```

---

### Task 3: picocli `review` command — port existing main into a subcommand

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/cli/RootCommand.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/cli/ReviewCommand.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/cli/CliRunner.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/cli/EvalCommand.java` (stub)
- Create: `src/main/java/dev/langchain4j/example/codereview/cli/SampleCommand.java` (stub)
- Delete: `src/main/java/dev/langchain4j/example/codereview/CodeReviewRunner.java`

- [ ] **Step 1: Create `RootCommand.java`**

```java
package dev.langchain4j.example.codereview.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(
        name = "code-review-agent",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        subcommands = {ReviewCommand.class, EvalCommand.class, SampleCommand.class}
)
public class RootCommand implements Runnable {
    @Override public void run() { /* show help via picocli when no subcommand */ }
}
```

- [ ] **Step 2: Create `ReviewCommand.java`**

```java
package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.CodeReviewAgent;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Component
@Command(name = "review", description = "Review a git diff")
public class ReviewCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to git repository", defaultValue = ".")
    private String repoPath;

    @Parameters(index = "1", description = "Git ref to diff against", defaultValue = "HEAD~1")
    private String ref;

    private final CodeReviewAgent agent;

    public ReviewCommand(CodeReviewAgent agent) {
        this.agent = agent;
    }

    @Override
    public Integer call() {
        System.out.println("Repository : " + repoPath);
        System.out.println("Diff ref   : " + ref);

        String request = "Review code changes in repo: " + repoPath +
                "\nCompare against ref: " + ref +
                "\nCall getGitDiff first, then checkRules, then produce the review.";
        // CodeReviewAgent still returns String until Task 16 swaps it to ReviewResult.
        String result = agent.review(request);
        System.out.println("\n" + result);
        return 0;
    }
}
```

- [ ] **Step 3: Stub `EvalCommand.java` and `SampleCommand.java`**

```java
// EvalCommand.java
package dev.langchain4j.example.codereview.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Component
@Command(name = "eval", description = "Run evaluation suite (wired up in Task 21)")
public class EvalCommand implements Callable<Integer> {
    @Override public Integer call() {
        System.err.println("eval command is implemented in Task 21");
        return 2;
    }
}
```

```java
// SampleCommand.java
package dev.langchain4j.example.codereview.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Component
@Command(name = "sample", description = "Collect PR samples (W2)")
public class SampleCommand implements Callable<Integer> {
    @Override public Integer call() {
        System.err.println("sample command is implemented in W2");
        return 2;
    }
}
```

- [ ] **Step 4: Create `CliRunner.java`**

```java
package dev.langchain4j.example.codereview.cli;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

@Component
public class CliRunner implements ExitCodeGenerator {

    private final RootCommand rootCommand;
    private final IFactory factory;
    private int exitCode = 0;

    public CliRunner(RootCommand rootCommand, IFactory factory) {
        this.rootCommand = rootCommand;
        this.factory = factory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run(ApplicationReadyEvent event) {
        String[] args = event.getApplicationArgs().getSourceArgs();
        exitCode = new CommandLine(rootCommand, factory).execute(args);
    }

    @Override public int getExitCode() { return exitCode; }
}
```

Note: `picocli-spring-boot-starter` auto-registers an `IFactory` that resolves picocli `@Component` commands from the Spring context.

- [ ] **Step 5: Delete the old `CodeReviewRunner`**

```bash
git rm src/main/java/dev/langchain4j/example/codereview/CodeReviewRunner.java
```

- [ ] **Step 6: Verify build + help works**

Run: `mvn -q clean package -DskipTests` (this requires `MOONSHOT_API_KEY` to be set — even just a dummy: `export MOONSHOT_API_KEY=dummy`).

Then run: `MOONSHOT_API_KEY=dummy java -jar target/code-review-agent-1.0.0.jar --help 2>&1 | head -20`
Expected: picocli prints help text listing `review`, `eval`, `sample`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/cli/
git rm src/main/java/dev/langchain4j/example/codereview/CodeReviewRunner.java
git commit -m "feat: picocli root + review/eval/sample subcommands"
```

---

## Phase 2 · Infra cleanup (Tasks 4-11)

### Task 4: `DiffParser` — parse hunks, map diff line → file line

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/infra/DiffParser.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/infra/DiffParserTest.java`
- Create: `src/test/resources/fixtures/diff-hunks/simple-add.patch`
- Create: `src/test/resources/fixtures/diff-hunks/multi-file.patch`

- [ ] **Step 1: Create fixtures**

`src/test/resources/fixtures/diff-hunks/simple-add.patch`:
```
diff --git a/Foo.java b/Foo.java
index 1111111..2222222 100644
--- a/Foo.java
+++ b/Foo.java
@@ -10,3 +10,5 @@ public class Foo {
     int x = 1;
     int y = 2;
+    int z = 3;
+    System.out.println(z);
     int w = 4;
```

`src/test/resources/fixtures/diff-hunks/multi-file.patch`:
```
diff --git a/A.java b/A.java
--- a/A.java
+++ b/A.java
@@ -1,2 +1,3 @@
 package a;
+import java.util.List;
 class A {}
diff --git a/B.java b/B.java
--- a/B.java
+++ b/B.java
@@ -5,3 +5,4 @@
 line5
 line6
+line7-new
 line8
```

- [ ] **Step 2: Write failing tests**

`DiffParserTest.java`:
```java
package dev.langchain4j.example.codereview.infra;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiffParserTest {

    private final DiffParser parser = new DiffParser();

    @Test
    void parsesSingleFileAddedLines() throws Exception {
        String patch = Files.readString(Path.of("src/test/resources/fixtures/diff-hunks/simple-add.patch"));
        List<DiffParser.FileDiff> files = parser.parse(patch);

        assertThat(files).hasSize(1);
        DiffParser.FileDiff foo = files.get(0);
        assertThat(foo.path()).isEqualTo("Foo.java");
        // "int z = 3;" is added at new-file line 12; "System.out.println(z);" at line 13.
        assertThat(foo.addedLines()).extracting(DiffParser.AddedLine::lineNumber)
                .containsExactly(12, 13);
        assertThat(foo.addedLines()).extracting(DiffParser.AddedLine::content)
                .containsExactly("    int z = 3;", "    System.out.println(z);");
    }

    @Test
    void parsesMultipleFiles() throws Exception {
        String patch = Files.readString(Path.of("src/test/resources/fixtures/diff-hunks/multi-file.patch"));
        List<DiffParser.FileDiff> files = parser.parse(patch);

        assertThat(files).extracting(DiffParser.FileDiff::path)
                .containsExactly("A.java", "B.java");
        assertThat(files.get(0).addedLines()).extracting(DiffParser.AddedLine::lineNumber)
                .containsExactly(2);
        assertThat(files.get(1).addedLines()).extracting(DiffParser.AddedLine::lineNumber)
                .containsExactly(7);
    }

    @Test
    void ignoresFileHeaderLinesStartingWithPlusPlusPlus() throws Exception {
        String patch = Files.readString(Path.of("src/test/resources/fixtures/diff-hunks/simple-add.patch"));
        List<DiffParser.FileDiff> files = parser.parse(patch);
        assertThat(files.get(0).addedLines()).noneMatch(l -> l.content().startsWith("+++"));
    }

    @Test
    void emptyInputProducesEmptyList() {
        assertThat(parser.parse("")).isEmpty();
    }
}
```

- [ ] **Step 3: Run tests to confirm they fail**

Run: `mvn -q test -Dtest=DiffParserTest`
Expected: compile error (class `DiffParser` does not exist).

- [ ] **Step 4: Implement `DiffParser.java`**

```java
package dev.langchain4j.example.codereview.infra;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiffParser {

    private static final Pattern FILE_HEADER = Pattern.compile("^\\+\\+\\+ b/(.+)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    public record AddedLine(int lineNumber, String content) { }

    public record FileDiff(String path, List<AddedLine> addedLines) { }

    public List<FileDiff> parse(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isBlank()) return List.of();

        List<FileDiff> files = new ArrayList<>();
        String currentPath = null;
        List<AddedLine> currentAdded = new ArrayList<>();
        int newLineNum = 0;
        boolean inHunk = false;

        for (String line : unifiedDiff.split("\n", -1)) {
            Matcher fileMatch = FILE_HEADER.matcher(line);
            if (fileMatch.matches()) {
                if (currentPath != null) {
                    files.add(new FileDiff(currentPath, List.copyOf(currentAdded)));
                }
                currentPath = fileMatch.group(1);
                currentAdded = new ArrayList<>();
                inHunk = false;
                continue;
            }

            Matcher hunkMatch = HUNK_HEADER.matcher(line);
            if (hunkMatch.matches()) {
                newLineNum = Integer.parseInt(hunkMatch.group(1));
                inHunk = true;
                continue;
            }

            if (!inHunk || currentPath == null) continue;

            if (line.startsWith("+") && !line.startsWith("+++")) {
                currentAdded.add(new AddedLine(newLineNum, line.substring(1)));
                newLineNum++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                // deleted line, no new-file advancement
            } else {
                // context or empty line in hunk
                newLineNum++;
            }
        }

        if (currentPath != null) {
            files.add(new FileDiff(currentPath, List.copyOf(currentAdded)));
        }
        return files;
    }
}
```

- [ ] **Step 5: Run tests, confirm pass**

Run: `mvn -q test -Dtest=DiffParserTest`
Expected: 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/infra/DiffParser.java \
        src/test/java/dev/langchain4j/example/codereview/infra/DiffParserTest.java \
        src/test/resources/fixtures/diff-hunks/
git commit -m "feat(infra): DiffParser with file-line-number mapping"
```

---

### Task 5: `GitClient` — single git subprocess wrapper

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/infra/GitClient.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/infra/GitClientTest.java`

- [ ] **Step 1: Write failing test**

```java
package dev.langchain4j.example.codereview.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitClientTest {

    @TempDir Path repo;
    private GitClient git;

    @BeforeEach
    void initRepo() throws Exception {
        git = new GitClient();
        runCmd("git", "init", "-q");
        runCmd("git", "config", "user.email", "test@example.com");
        runCmd("git", "config", "user.name", "Test");
        Files.writeString(repo.resolve("a.txt"), "v1\n");
        runCmd("git", "add", ".");
        runCmd("git", "commit", "-q", "-m", "v1");
        Files.writeString(repo.resolve("a.txt"), "v1\nv2\n");
        runCmd("git", "add", ".");
        runCmd("git", "commit", "-q", "-m", "v2");
    }

    private void runCmd(String... args) throws Exception {
        new ProcessBuilder(args).directory(repo.toFile()).inheritIO().start().waitFor();
    }

    @Test
    void diffAgainstHeadTildeReturnsContent() {
        String diff = git.diff(repo, "HEAD~1");
        assertThat(diff).contains("+v2");
    }

    @Test
    void diffOnEmptyReturnsBlank() {
        String diff = git.diff(repo, "HEAD");
        assertThat(diff).isBlank();
    }

    @Test
    void nonexistentRepoThrows() {
        assertThatThrownBy(() -> git.diff(Path.of("/nonexistent/path/that/does/not/exist"), "HEAD"))
                .isInstanceOf(GitClient.GitException.class);
    }
}
```

- [ ] **Step 2: Run, confirm failing (class missing)**

Run: `mvn -q test -Dtest=GitClientTest`
Expected: compile error.

- [ ] **Step 3: Implement `GitClient.java`**

```java
package dev.langchain4j.example.codereview.infra;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class GitClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    public String diff(Path repoPath, String ref) {
        if (!Files.isDirectory(repoPath)) {
            throw new GitException("Not a directory: " + repoPath);
        }
        return run(repoPath, "git", "diff", ref);
    }

    public String currentBranch(Path repoPath) {
        return run(repoPath, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    public String show(Path repoPath, String revision) {
        return run(repoPath, "git", "show", revision);
    }

    private String run(Path repoPath, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(repoPath.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new GitException("git command timed out after " + TIMEOUT);
            }
            if (p.exitValue() != 0) {
                throw new GitException("git " + String.join(" ", cmd) + " failed: " + out);
            }
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new GitException("git execution error: " + e.getMessage(), e);
        }
    }

    public static class GitException extends RuntimeException {
        public GitException(String message) { super(message); }
        public GitException(String message, Throwable cause) { super(message, cause); }
    }
}
```

- [ ] **Step 4: Run tests, confirm pass**

Run: `mvn -q test -Dtest=GitClientTest`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/infra/GitClient.java \
        src/test/java/dev/langchain4j/example/codereview/infra/GitClientTest.java
git commit -m "feat(infra): GitClient subprocess wrapper with timeout"
```

---

### Task 6: Refactor `GitDiffTool` to use `GitClient` + per-file splitting

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/GitDiffTool.java` (move to `tools/`)
- Create: `src/test/java/dev/langchain4j/example/codereview/tools/GitDiffToolTest.java`

- [ ] **Step 1: Move + rewrite `GitDiffTool.java`**

Move file to `src/main/java/dev/langchain4j/example/codereview/tools/GitDiffTool.java`.

```java
package dev.langchain4j.example.codereview.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.GitClient;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class GitDiffTool {

    private static final int MAX_PER_FILE_CHARS = 4000;
    private static final int MAX_TOTAL_CHARS = 12000;

    private final GitClient gitClient;
    private final DiffParser diffParser;

    public GitDiffTool(GitClient gitClient, DiffParser diffParser) {
        this.gitClient = gitClient;
        this.diffParser = diffParser;
    }

    @Tool("Retrieves the git diff for a repository. Diff is split per file; oversized files are summarized.")
    public String getGitDiff(
            @P("Absolute path to the git repository") String repoPath,
            @P("Git ref to compare against, e.g. 'HEAD~1'") String ref) {
        try {
            String raw = gitClient.diff(Path.of(repoPath), (ref == null || ref.isBlank()) ? "HEAD~1" : ref);
            if (raw.isBlank()) {
                return "No changes found when comparing against '" + ref + "'.";
            }
            List<DiffParser.FileDiff> files = diffParser.parse(raw);
            StringBuilder out = new StringBuilder();
            for (DiffParser.FileDiff file : files) {
                String section = renderFile(file, raw);
                if (out.length() + section.length() > MAX_TOTAL_CHARS) {
                    out.append("\n... [diff truncated at ").append(MAX_TOTAL_CHARS).append(" chars; ")
                            .append(files.size()).append(" files total]\n");
                    break;
                }
                out.append(section);
            }
            return out.toString();
        } catch (GitClient.GitException e) {
            return "Error running git diff: " + e.getMessage();
        }
    }

    private String renderFile(DiffParser.FileDiff file, String rawDiff) {
        // Extract the file's section from the raw diff to preserve hunks intact.
        String marker = "diff --git a/" + file.path() + " b/" + file.path();
        int start = rawDiff.indexOf(marker);
        if (start < 0) return "";
        int end = rawDiff.indexOf("\ndiff --git ", start + marker.length());
        String section = (end < 0) ? rawDiff.substring(start) : rawDiff.substring(start, end);

        if (section.length() <= MAX_PER_FILE_CHARS) {
            return section + "\n";
        }
        // Summarize: keep header + first 20 added lines.
        String header = section.substring(0, Math.min(400, section.length()));
        String summary = file.addedLines().stream()
                .limit(20)
                .map(l -> "+L" + l.lineNumber() + ": " + l.content())
                .collect(Collectors.joining("\n"));
        return header + "\n[... file truncated, " + file.addedLines().size() + " added lines total ...]\n"
                + summary + "\n";
    }
}
```

- [ ] **Step 2: Write a focused test**

```java
package dev.langchain4j.example.codereview.tools;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.GitClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class GitDiffToolTest {

    @Test
    void returnsBlankMessageWhenNoChanges() {
        GitClient git = Mockito.mock(GitClient.class);
        when(git.diff(any(Path.class), eq("HEAD~1"))).thenReturn("");
        GitDiffTool tool = new GitDiffTool(git, new DiffParser());

        String result = tool.getGitDiff("/some/repo", "HEAD~1");

        assertThat(result).contains("No changes found");
    }

    @Test
    void preservesSmallDiff() {
        GitClient git = Mockito.mock(GitClient.class);
        String fakeDiff = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -1,1 +1,2 @@
                 line1
                +line2-new
                """;
        when(git.diff(any(), any())).thenReturn(fakeDiff);
        GitDiffTool tool = new GitDiffTool(git, new DiffParser());

        String result = tool.getGitDiff("/repo", "HEAD~1");
        assertThat(result).contains("+line2-new");
    }
}
```

- [ ] **Step 3: Remove old root-level `GitDiffTool.java`**

```bash
git rm src/main/java/dev/langchain4j/example/codereview/GitDiffTool.java
```

- [ ] **Step 4: Run tests**

Run: `mvn -q test -Dtest=GitDiffToolTest`
Expected: 2 tests pass. Compile may break temporarily because `CodeReviewRunner` was deleted in Task 3 and `CodeReviewAgent` still imports the old `GitDiffTool` location — fix that import to `tools.GitDiffTool`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/tools/GitDiffTool.java \
        src/test/java/dev/langchain4j/example/codereview/tools/GitDiffToolTest.java
git rm src/main/java/dev/langchain4j/example/codereview/GitDiffTool.java
git commit -m "refactor(tools): GitDiffTool uses GitClient + per-file splitting"
```

---

### Task 7: `StaticAnalyzer` strategy interface + `RegexAnalyzer`

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/analyzer/StaticAnalyzer.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/analyzer/Violation.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/analyzer/RegexAnalyzer.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/analyzer/RegexAnalyzerTest.java`

- [ ] **Step 1: Create `Violation.java`**

```java
package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.model.Severity;

public record Violation(
        Severity severity,
        String file,
        int line,
        String rule,
        String message
) { }
```

(`Severity` enum is created in Task 12; for ordering, create it now as a one-liner to unblock compilation:)

```java
// src/main/java/dev/langchain4j/example/codereview/model/Severity.java
package dev.langchain4j.example.codereview.model;
public enum Severity { CRITICAL, WARNING, SUGGESTION }
```

- [ ] **Step 2: Create `StaticAnalyzer.java`**

```java
package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;

import java.util.List;

public interface StaticAnalyzer {
    String name();
    List<Violation> analyze(List<DiffParser.FileDiff> files);
}
```

- [ ] **Step 3: Write failing test for `RegexAnalyzer`**

```java
package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegexAnalyzerTest {

    private final RegexAnalyzer analyzer = new RegexAnalyzer();

    private DiffParser.FileDiff fileWith(String... lines) {
        List<DiffParser.AddedLine> added = java.util.stream.IntStream.range(0, lines.length)
                .mapToObj(i -> new DiffParser.AddedLine(i + 10, lines[i]))
                .toList();
        return new DiffParser.FileDiff("Foo.java", added);
    }

    @Test
    void detectsHardcodedCredential() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("String apiKey = \"sk-real-key\";")));
        assertThat(v).hasSize(1);
        assertThat(v.get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(v.get(0).rule()).isEqualTo("hardcoded-credential");
        assertThat(v.get(0).line()).isEqualTo(10);
    }

    @Test
    void detectsSystemOutPrintln() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("System.out.println(\"hi\");")));
        assertThat(v).extracting(Violation::rule).contains("system-out-println");
    }

    @Test
    void detectsPrintStackTrace() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("e.printStackTrace();")));
        assertThat(v).extracting(Violation::rule).contains("print-stack-trace");
    }

    @Test
    void detectsCatchGenericException() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("} catch (Exception e) {")));
        assertThat(v).extracting(Violation::rule).contains("catch-generic-exception");
    }

    @Test
    void detectsTodoFixme() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("// TODO: fix this")));
        assertThat(v).extracting(Violation::rule).contains("unresolved-todo");
    }

    @Test
    void noViolationsForCleanLine() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("int x = 1;")));
        assertThat(v).isEmpty();
    }

    @Test
    void reportsFileLineNotDiffLine() {
        // 3 added lines at file line 100, 101, 102. The middle one violates.
        DiffParser.FileDiff fd = new DiffParser.FileDiff("Bar.java", List.of(
                new DiffParser.AddedLine(100, "int a = 1;"),
                new DiffParser.AddedLine(101, "System.out.println(\"bad\");"),
                new DiffParser.AddedLine(102, "int b = 2;")
        ));
        List<Violation> v = analyzer.analyze(List.of(fd));
        assertThat(v).hasSize(1);
        assertThat(v.get(0).line()).isEqualTo(101);
    }
}
```

- [ ] **Step 4: Run, confirm fail**

Run: `mvn -q test -Dtest=RegexAnalyzerTest`
Expected: compile error.

- [ ] **Step 5: Implement `RegexAnalyzer.java`**

```java
package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class RegexAnalyzer implements StaticAnalyzer {

    private record Rule(String id, Severity severity, Pattern pattern, String message) { }

    private static final List<Rule> RULES = List.of(
            new Rule("hardcoded-credential", Severity.CRITICAL,
                    Pattern.compile(".*\\b(password|passwd|apiKey|api_key|secret|token)\\s*=\\s*\"[^\"]+\".*"),
                    "Possible hardcoded credential — use env vars or a secrets manager"),
            new Rule("system-out-println", Severity.WARNING,
                    Pattern.compile(".*System\\.(out|err)\\.println\\(.*"),
                    "Use SLF4J logger instead of System.out/err.println"),
            new Rule("print-stack-trace", Severity.WARNING,
                    Pattern.compile(".*\\.printStackTrace\\(\\).*"),
                    "Use logger.error(msg, e) instead of e.printStackTrace()"),
            new Rule("catch-generic-exception", Severity.SUGGESTION,
                    Pattern.compile(".*catch\\s*\\(\\s*Exception\\s+\\w+\\s*\\).*"),
                    "Avoid catching generic Exception — prefer specific exception types"),
            new Rule("empty-catch", Severity.WARNING,
                    Pattern.compile(".*catch.*\\{\\s*\\}.*"),
                    "Empty or silently-ignored catch block"),
            new Rule("raw-thread", Severity.SUGGESTION,
                    Pattern.compile(".*new\\s+Thread\\s*\\(.*"),
                    "Consider using ExecutorService instead of raw Thread"),
            new Rule("thread-sleep", Severity.SUGGESTION,
                    Pattern.compile(".*Thread\\.sleep\\(.*"),
                    "Avoid Thread.sleep() in business logic"),
            new Rule("unresolved-todo", Severity.WARNING,
                    Pattern.compile(".*(TODO|FIXME).*"),
                    "Unresolved TODO/FIXME — track in an issue or resolve before merging"),
            new Rule("manual-null-check", Severity.SUGGESTION,
                    Pattern.compile(".*(==\\s*null|!=\\s*null).*"),
                    "Consider Optional or Objects.requireNonNull() instead of manual null checks")
    );

    @Override public String name() { return "regex"; }

    @Override
    public List<Violation> analyze(List<DiffParser.FileDiff> files) {
        List<Violation> out = new ArrayList<>();
        for (DiffParser.FileDiff file : files) {
            for (DiffParser.AddedLine added : file.addedLines()) {
                String code = added.content().trim();
                for (Rule r : RULES) {
                    if (r.pattern().matcher(code).matches()) {
                        out.add(new Violation(r.severity(), file.path(), added.lineNumber(),
                                r.id(), r.message()));
                    }
                }
            }
        }
        return out;
    }
}
```

- [ ] **Step 6: Run, confirm pass**

Run: `mvn -q test -Dtest=RegexAnalyzerTest`
Expected: 7 tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/analyzer/ \
        src/main/java/dev/langchain4j/example/codereview/model/Severity.java \
        src/test/java/dev/langchain4j/example/codereview/analyzer/
git commit -m "feat(analyzer): StaticAnalyzer interface + RegexAnalyzer"
```

---

### Task 8: Refactor `RuleCheckerTool` to use `DiffParser` + `RegexAnalyzer`

**Files:**
- Modify: move `src/main/java/dev/langchain4j/example/codereview/RuleCheckerTool.java` → `tools/RuleCheckerTool.java`

- [ ] **Step 1: Rewrite `RuleCheckerTool.java`**

```java
package dev.langchain4j.example.codereview.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.example.codereview.analyzer.StaticAnalyzer;
import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.GitClient;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class RuleCheckerTool {

    private final GitClient gitClient;
    private final DiffParser diffParser;
    private final List<StaticAnalyzer> analyzers;

    public RuleCheckerTool(GitClient gitClient, DiffParser diffParser, List<StaticAnalyzer> analyzers) {
        this.gitClient = gitClient;
        this.diffParser = diffParser;
        this.analyzers = analyzers;
    }

    @Tool("Runs all configured static analyzers on a git repo's diff. Returns violations with real file line numbers.")
    public String checkRules(
            @P("Absolute path to the git repository") String repoPath,
            @P("Git ref to compare against, e.g. 'HEAD~1'") String ref) {
        String diff;
        try {
            diff = gitClient.diff(Path.of(repoPath), (ref == null || ref.isBlank()) ? "HEAD~1" : ref);
        } catch (GitClient.GitException e) {
            return "Error running git diff: " + e.getMessage();
        }
        if (diff.isBlank()) return "No changes found.";

        List<DiffParser.FileDiff> files = diffParser.parse(diff);
        List<Violation> all = new ArrayList<>();
        for (StaticAnalyzer a : analyzers) {
            all.addAll(a.analyze(files));
        }
        if (all.isEmpty()) return "No rule violations found.";

        return "Found " + all.size() + " violation(s):\n" +
                all.stream()
                        .map(v -> "[" + v.severity() + "] " + v.file() + ":" + v.line()
                                + " (" + v.rule() + ") " + v.message())
                        .collect(Collectors.joining("\n"));
    }
}
```

- [ ] **Step 2: Delete old root-level `RuleCheckerTool.java`**

```bash
git rm src/main/java/dev/langchain4j/example/codereview/RuleCheckerTool.java
```

- [ ] **Step 3: Build to confirm refactor doesn't break compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS. If `CodeReviewAgent` import paths are off, fix them inline.

- [ ] **Step 4: Run full test suite**

Run: `mvn -q test`
Expected: all tests pass (DiffParserTest, GitClientTest, GitDiffToolTest, RegexAnalyzerTest).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/tools/RuleCheckerTool.java
git rm src/main/java/dev/langchain4j/example/codereview/RuleCheckerTool.java
git commit -m "refactor(tools): RuleCheckerTool uses DiffParser (real line numbers) + StaticAnalyzer"
```

---

### Task 9: `EmbeddingCache` — serialize embedding store to local JSON

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/infra/EmbeddingCache.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/infra/EmbeddingCacheTest.java`

- [ ] **Step 1: Write failing test**

```java
package dev.langchain4j.example.codereview.infra;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingCacheTest {

    @TempDir Path cacheDir;

    @Test
    void writesAndReadsBackStore() {
        EmbeddingCache cache = new EmbeddingCache(cacheDir);
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        store.add(Embedding.from(new float[]{0.1f, 0.2f, 0.3f}), TextSegment.from("hello"));

        cache.save("guidelines-v1", store);

        Optional<InMemoryEmbeddingStore<TextSegment>> loaded = cache.load("guidelines-v1");
        assertThat(loaded).isPresent();
        // round-trip preserved at least one segment
        assertThat(loaded.get().findRelevant(Embedding.from(new float[]{0.1f, 0.2f, 0.3f}), 1))
                .isNotEmpty();
    }

    @Test
    void missingKeyReturnsEmpty() {
        EmbeddingCache cache = new EmbeddingCache(cacheDir);
        assertThat(cache.load("nope")).isEmpty();
    }
}
```

Note: `InMemoryEmbeddingStore` in LangChain4j provides `serializeToJson()` / `fromJson(String)`. If the method names differ in `1.15.0-beta25`, check `InMemoryEmbeddingStore` javadoc and adjust.

- [ ] **Step 2: Run, confirm fail**

Run: `mvn -q test -Dtest=EmbeddingCacheTest`
Expected: compile error.

- [ ] **Step 3: Implement `EmbeddingCache.java`**

```java
package dev.langchain4j.example.codereview.infra;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class EmbeddingCache {

    private final Path cacheDir;

    public EmbeddingCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create cache dir: " + cacheDir, e);
        }
    }

    public void save(String key, InMemoryEmbeddingStore<TextSegment> store) {
        Path file = cacheDir.resolve(sanitize(key) + ".json");
        try {
            Files.writeString(file, store.serializeToJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot write cache file: " + file, e);
        }
    }

    public Optional<InMemoryEmbeddingStore<TextSegment>> load(String key) {
        Path file = cacheDir.resolve(sanitize(key) + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(InMemoryEmbeddingStore.fromJson(json));
        } catch (IOException e) {
            throw new RuntimeException("Cannot read cache file: " + file, e);
        }
    }

    private String sanitize(String key) {
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
```

- [ ] **Step 4: Run, confirm pass**

Run: `mvn -q test -Dtest=EmbeddingCacheTest`
Expected: 2 tests pass. If `serializeToJson` / `fromJson` method names differ, adjust per actual LangChain4j 1.15.x API; the test will tell you immediately.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/infra/EmbeddingCache.java \
        src/test/java/dev/langchain4j/example/codereview/infra/EmbeddingCacheTest.java
git commit -m "feat(infra): EmbeddingCache JSON round-trip"
```

---

### Task 10: `KnowledgeBaseIndexer` — cache-aware indexer + RagConfig

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/rag/KnowledgeBaseIndexer.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/config/RagConfig.java`
- Delete: `src/main/java/dev/langchain4j/example/codereview/KnowledgeBaseLoader.java`

- [x] **Step 1: Create `KnowledgeBaseIndexer.java`**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.infra.EmbeddingCache;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocuments;
import static dev.langchain4j.data.document.splitter.DocumentSplitters.recursive;

public class KnowledgeBaseIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseIndexer.class);
    private static final String CACHE_KEY = "review-guidelines";

    private final EmbeddingModel embeddingModel;
    private final EmbeddingCache cache;

    public KnowledgeBaseIndexer(EmbeddingModel embeddingModel, EmbeddingCache cache) {
        this.embeddingModel = embeddingModel;
        this.cache = cache;
    }

    public InMemoryEmbeddingStore<TextSegment> buildOrLoad() {
        Optional<InMemoryEmbeddingStore<TextSegment>> cached = cache.load(CACHE_KEY);
        if (cached.isPresent()) {
            log.info("Loaded knowledge base from cache (key={})", CACHE_KEY);
            return cached.get();
        }
        log.info("Building knowledge base from scratch...");
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        Path guidelinesPath = toClasspathPath("review-guidelines/");
        PathMatcher txtMatcher = FileSystems.getDefault().getPathMatcher("glob:*.txt");
        List<Document> docs = loadDocuments(guidelinesPath, txtMatcher);

        EmbeddingStoreIngestor.builder()
                .documentSplitter(recursive(500, 50))
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build()
                .ingest(docs);

        cache.save(CACHE_KEY, store);
        log.info("Indexed {} document(s); cache saved.", docs.size());
        return store;
    }

    private static Path toClasspathPath(String relativePath) {
        try {
            URL url = KnowledgeBaseIndexer.class.getClassLoader().getResource(relativePath);
            if (url == null) throw new RuntimeException("Resource not found: " + relativePath);
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [x] **Step 2: Create `RagConfig.java`**

```java
package dev.langchain4j.example.codereview.config;

import dev.langchain4j.example.codereview.infra.EmbeddingCache;
import dev.langchain4j.example.codereview.rag.KnowledgeBaseIndexer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallEnV15QuantizedEmbeddingModel();
    }

    @Bean
    public EmbeddingCache embeddingCache(CodeReviewProperties props) {
        return new EmbeddingCache(props.rag().embeddingCacheDir());
    }

    @Bean
    public KnowledgeBaseIndexer knowledgeBaseIndexer(EmbeddingModel model, EmbeddingCache cache) {
        return new KnowledgeBaseIndexer(model, cache);
    }

    @Bean
    public ContentRetriever contentRetriever(
            KnowledgeBaseIndexer indexer,
            EmbeddingModel embeddingModel,
            CodeReviewProperties props) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(indexer.buildOrLoad())
                .embeddingModel(embeddingModel)
                .maxResults(props.rag().topK())
                .minScore(props.rag().minScore())
                .build();
    }
}
```

- [x] **Step 3: Delete old `KnowledgeBaseLoader.java`**

```bash
git rm src/main/java/dev/langchain4j/example/codereview/KnowledgeBaseLoader.java
```

- [x] **Step 4: Build**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [x] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/rag/KnowledgeBaseIndexer.java \
        src/main/java/dev/langchain4j/example/codereview/config/RagConfig.java
git rm src/main/java/dev/langchain4j/example/codereview/KnowledgeBaseLoader.java
git commit -m "feat(rag): KnowledgeBaseIndexer with disk-cached embeddings"
```

---

### Task 11: Fix `CodeReviewAgent` prompt to match tool signatures + wire via `AgentConfig`

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/CodeReviewAgent.java` → `agents/CodeReviewAgent.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java`

- [x] **Step 1: Move + rewrite `CodeReviewAgent.java`**

Move to `src/main/java/dev/langchain4j/example/codereview/agents/CodeReviewAgent.java`.

```java
package dev.langchain4j.example.codereview.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface CodeReviewAgent {

    @SystemMessage("""
            You are a senior software engineer doing a code review.

            Workflow:
            1. Call getGitDiff(repoPath, ref) to see the changes.
            2. Call checkRules(repoPath, ref) with the SAME repoPath and ref to get static rule violations.
            3. The knowledge base will automatically inject relevant best-practice excerpts.
            4. Produce a structured review in Markdown.

            Output format:
            ## Code Review Report

            ### Summary
            Brief description of what changed and overall assessment.

            ### Issues Found
            **[CRITICAL|WARNING|SUGGESTION]** `filename:line` — clear description and recommendation

            ### Looks Good
            Note what was done well (if anything).

            ### Conclusion
            Approve / Request Changes / Needs Discussion
            """)
    String review(@UserMessage String request);
}
```

(Task 16 swaps the return type to `ReviewResult`. Keeping `String` for now keeps the picocli `ReviewCommand` working end-to-end.)

- [x] **Step 2: Create `AgentConfig.java`**

```java
package dev.langchain4j.example.codereview.config;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.tools.GitDiffTool;
import dev.langchain4j.example.codereview.tools.RuleCheckerTool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    public CodeReviewAgent codeReviewAgent(
            ChatModel chatModel,
            ContentRetriever retriever,
            GitDiffTool gitDiffTool,
            RuleCheckerTool ruleCheckerTool) {
        return AiServices.builder(CodeReviewAgent.class)
                .chatModel(chatModel)
                .tools(gitDiffTool, ruleCheckerTool)
                .contentRetriever(retriever)
                .build();
    }
}
```

- [x] **Step 3: Delete old root-level `CodeReviewAgent.java`**

```bash
git rm src/main/java/dev/langchain4j/example/codereview/CodeReviewAgent.java
```

- [x] **Step 4: Update `ReviewCommand` import**

Open `cli/ReviewCommand.java`, change import `dev.langchain4j.example.codereview.CodeReviewAgent;` to `dev.langchain4j.example.codereview.agents.CodeReviewAgent;`.

- [x] **Step 5: Build + run smoke test**

Run: `MOONSHOT_API_KEY=dummy mvn -q clean package -DskipTests`
Expected: BUILD SUCCESS.

Run with a real key against this repo:
```bash
export MOONSHOT_API_KEY=<your real key>
java -jar target/code-review-agent-1.0.0.jar review . HEAD~1
```
Expected: agent fetches diff, runs analyzers, returns a Markdown review. Tool calls succeed without "Method not found" errors.

- [x] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/agents/CodeReviewAgent.java \
        src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java \
        src/main/java/dev/langchain4j/example/codereview/cli/ReviewCommand.java
git rm src/main/java/dev/langchain4j/example/codereview/CodeReviewAgent.java
git commit -m "fix(agent): align prompt with tool signatures; wire via AgentConfig"
```

---

## Phase 3 · Structured output model (Tasks 12-16)

### Task 12: `Category` enum (Severity already exists from Task 7)

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/model/Category.java`

- [x] **Step 1: Create `Category.java`**

```java
package dev.langchain4j.example.codereview.model;

public enum Category {
    SECURITY, PERFORMANCE, STABILITY, CONCURRENCY, TEST, STYLE, OTHER
}
```

- [x] **Step 2: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/model/Category.java
git commit -m "feat(model): Category enum"
```

---

### Task 13: `Citation`, `ReviewFinding`, `ToolStatus`, `ReviewResult` POJOs + JSON schema tests

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/model/Citation.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/model/ReviewFinding.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/model/ToolStatus.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/model/ReviewResult.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/model/ReviewFindingTest.java`

- [x] **Step 1: Create POJOs**

```java
// Citation.java
package dev.langchain4j.example.codereview.model;
public record Citation(String id, String source, String section) { }
```

```java
// ToolStatus.java
package dev.langchain4j.example.codereview.model;
public record ToolStatus(String tool, String status, String reason) { }
```

```java
// ReviewFinding.java
package dev.langchain4j.example.codereview.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewFinding(
        String id,
        String file,
        Integer line,
        int[] lineRange,
        Severity severity,
        Category category,
        String title,
        String description,
        String suggestion,
        String evidence,
        List<Citation> citations,
        String source
) { }
```

```java
// ReviewResult.java
package dev.langchain4j.example.codereview.model;

import java.util.List;

public record ReviewResult(
        String summary,
        List<ReviewFinding> findings,
        List<ToolStatus> toolStatus
) {
    public static ReviewResult empty(String summary) {
        return new ReviewResult(summary, List.of(), List.of());
    }
}
```

- [x] **Step 2: Write failing test for JSON round-trip + schema validation**

```java
package dev.langchain4j.example.codereview.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewFindingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesFullExample() throws Exception {
        String json = """
                {
                  "id": "F-001",
                  "file": "src/main/java/UserCtrl.java",
                  "line": 42,
                  "line_range": [40, 45],
                  "severity": "CRITICAL",
                  "category": "SECURITY",
                  "title": "SQL injection",
                  "description": "User input concatenated into SQL",
                  "suggestion": "Use prepared statement",
                  "evidence": "request.getParameter('id') concatenated into query",
                  "citations": [
                    {"id": "security-checklist#sql-001", "source": "security-checklist.txt", "section": "SQL Injection"}
                  ],
                  "source": "llm_reviewer"
                }
                """;
        ReviewFinding f = mapper.readValue(json, ReviewFinding.class);
        assertThat(f.id()).isEqualTo("F-001");
        assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(f.category()).isEqualTo(Category.SECURITY);
        assertThat(f.citations()).hasSize(1);
        assertThat(f.citations().get(0).source()).isEqualTo("security-checklist.txt");
    }

    @Test
    void roundTripsResult() throws Exception {
        ReviewResult r = new ReviewResult(
                "Found 1 finding.",
                List.of(new ReviewFinding(
                        "F-001", "Foo.java", 10, new int[]{10, 12},
                        Severity.WARNING, Category.STYLE,
                        "Bad name", "Bad name detected", "Rename it", "the name is x1",
                        List.of(), "regex")),
                List.of(new ToolStatus("regex", "ok", null))
        );
        String json = mapper.writeValueAsString(r);
        ReviewResult parsed = mapper.readValue(json, ReviewResult.class);
        assertThat(parsed.findings()).hasSize(1);
        assertThat(parsed.findings().get(0).source()).isEqualTo("regex");
    }

    @Test
    void rejectsUnknownSeverity() {
        String json = """
                {"id":"X","file":"a","severity":"BANANA","category":"OTHER","title":"t","description":"d","suggestion":"s","evidence":"e","source":"regex"}
                """;
        assertThatThrownBy(() -> mapper.readValue(json, ReviewFinding.class))
                .hasMessageContaining("BANANA");
    }
}
```

The default `ObjectMapper` won't auto-convert snake_case JSON keys (`line_range`) to camelCase Java properties. To support both, add Jackson naming strategy in a config class later, OR write JSON in tests using camelCase. For simplicity here we'll register `PropertyNamingStrategies.SNAKE_CASE` globally in the next step.

- [x] **Step 3: Register snake_case ObjectMapper**

Create `src/main/java/dev/langchain4j/example/codereview/config/JsonConfig.java`:

```java
package dev.langchain4j.example.codereview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class JsonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }
}
```

Update the test constructor `ObjectMapper mapper = new ObjectMapper();` → use the same naming strategy:

```java
private final ObjectMapper mapper = new ObjectMapper()
        .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
```

- [x] **Step 4: Run, confirm pass**

Run: `mvn -q test -Dtest=ReviewFindingTest`
Expected: 3 tests pass.

- [x] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/model/ \
        src/main/java/dev/langchain4j/example/codereview/config/JsonConfig.java \
        src/test/java/dev/langchain4j/example/codereview/model/
git commit -m "feat(model): ReviewFinding/ReviewResult POJOs + snake_case JSON config"
```

---

### Task 14: `MarkdownReporter` — render `ReviewResult` to Markdown

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reporting/MarkdownReporter.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/reporting/MarkdownReporterTest.java`

- [x] **Step 1: Write failing test**

```java
package dev.langchain4j.example.codereview.reporting;

import dev.langchain4j.example.codereview.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownReporterTest {

    private final MarkdownReporter reporter = new MarkdownReporter();

    @Test
    void emptyResultProducesNoIssuesSection() {
        String md = reporter.render(ReviewResult.empty("Nothing to review."));
        assertThat(md).contains("## Code Review Report");
        assertThat(md).contains("Nothing to review.");
        assertThat(md).contains("No issues found");
    }

    @Test
    void rendersFindingsSortedBySeverity() {
        ReviewResult r = new ReviewResult(
                "2 findings.",
                List.of(
                        new ReviewFinding("F-002", "B.java", 20, null, Severity.SUGGESTION,
                                Category.STYLE, "Style nit", "desc", "fix", "ev", List.of(), "regex"),
                        new ReviewFinding("F-001", "A.java", 10, null, Severity.CRITICAL,
                                Category.SECURITY, "SQL injection", "desc", "fix", "ev", List.of(), "llm_reviewer")
                ),
                List.of()
        );
        String md = reporter.render(r);
        int critIdx = md.indexOf("CRITICAL");
        int suggIdx = md.indexOf("SUGGESTION");
        assertThat(critIdx).isLessThan(suggIdx);
        assertThat(md).contains("A.java:10");
        assertThat(md).contains("B.java:20");
    }

    @Test
    void includesCitations() {
        ReviewResult r = new ReviewResult(
                "1 finding.",
                List.of(new ReviewFinding(
                        "F-001", "A.java", 10, null, Severity.WARNING, Category.SECURITY,
                        "title", "desc", "fix", "ev",
                        List.of(new Citation("sec#1", "security-checklist.txt", "SQL")),
                        "llm_reviewer")),
                List.of()
        );
        String md = reporter.render(r);
        assertThat(md).contains("security-checklist.txt");
        assertThat(md).contains("SQL");
    }

    @Test
    void includesToolStatus() {
        ReviewResult r = new ReviewResult(
                "ok",
                List.of(),
                List.of(new ToolStatus("spotbugs", "skipped", "project did not compile"))
        );
        String md = reporter.render(r);
        assertThat(md).contains("spotbugs");
        assertThat(md).contains("skipped");
        assertThat(md).contains("project did not compile");
    }
}
```

- [x] **Step 2: Run, confirm fail**

Run: `mvn -q test -Dtest=MarkdownReporterTest`
Expected: compile error.

- [x] **Step 3: Implement `MarkdownReporter.java`**

```java
package dev.langchain4j.example.codereview.reporting;

import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.model.ToolStatus;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class MarkdownReporter {

    public String render(ReviewResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Code Review Report\n\n");
        sb.append("### Summary\n").append(result.summary() == null ? "" : result.summary()).append("\n\n");

        sb.append("### Issues Found\n");
        if (result.findings() == null || result.findings().isEmpty()) {
            sb.append("No issues found.\n\n");
        } else {
            List<ReviewFinding> sorted = result.findings().stream()
                    .sorted(Comparator.comparing(ReviewFinding::severity))
                    .toList();
            for (ReviewFinding f : sorted) {
                sb.append("- **[").append(f.severity()).append("]** `")
                        .append(f.file()).append(":").append(f.line() == null ? "?" : f.line())
                        .append("` — ").append(f.title()).append("\n");
                sb.append("  - ").append(f.description()).append("\n");
                if (f.suggestion() != null && !f.suggestion().isBlank()) {
                    sb.append("  - **Suggestion:** ").append(f.suggestion()).append("\n");
                }
                if (f.evidence() != null && !f.evidence().isBlank()) {
                    sb.append("  - **Evidence:** ").append(f.evidence()).append("\n");
                }
                if (f.citations() != null && !f.citations().isEmpty()) {
                    String cites = f.citations().stream()
                            .map(c -> "`" + c.source() + "` §" + c.section())
                            .collect(Collectors.joining(", "));
                    sb.append("  - **Citations:** ").append(cites).append("\n");
                }
            }
            sb.append("\n");
        }

        if (result.toolStatus() != null && !result.toolStatus().isEmpty()) {
            sb.append("### Tool Status\n");
            for (ToolStatus ts : result.toolStatus()) {
                sb.append("- `").append(ts.tool()).append("`: ").append(ts.status());
                if (ts.reason() != null) sb.append(" — ").append(ts.reason());
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
```

`Severity` enum order is `CRITICAL, WARNING, SUGGESTION` — so natural enum ordering already sorts critical first.

- [x] **Step 4: Run, confirm pass**

Run: `mvn -q test -Dtest=MarkdownReporterTest`
Expected: 4 tests pass.

- [x] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reporting/ \
        src/test/java/dev/langchain4j/example/codereview/reporting/
git commit -m "feat(reporting): MarkdownReporter with severity sort + citations"
```

---

### Task 15: Update `CodeReviewAgent` to return `ReviewResult`

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/agents/CodeReviewAgent.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/cli/ReviewCommand.java`

- [x] **Step 1: Rewrite `CodeReviewAgent.java`**

```java
package dev.langchain4j.example.codereview.agents;

import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface CodeReviewAgent {

    @SystemMessage("""
            You are a senior software engineer doing a code review.

            Workflow:
            1. Call getGitDiff(repoPath, ref) to see the changes.
            2. Call checkRules(repoPath, ref) with the SAME repoPath and ref to get static rule violations.
            3. Relevant best-practice excerpts will be automatically injected.
            4. Return a ReviewResult (JSON) with:
               - summary: 1-2 sentences
               - findings: list of {id (F-001 style), file, line, line_range, severity, category, title, description, suggestion, evidence, citations, source}
               - tool_status: list of {tool, status, reason}

            Constraints:
            - severity ∈ {CRITICAL, WARNING, SUGGESTION}
            - category ∈ {SECURITY, PERFORMANCE, STABILITY, CONCURRENCY, TEST, STYLE, OTHER}
            - source must be "llm_reviewer" for findings YOU produce; quote tool rule IDs when echoing analyzer findings.
            - line numbers must match the new file (post-change) line numbering.
            - If you have no findings, return an empty findings list with a summary explaining why.
            """)
    ReviewResult review(@UserMessage String request);
}
```

LangChain4j `AiServices` will see the return type and instruct the model to produce JSON conforming to `ReviewResult`. The library handles the JSON-mode prompt and parsing.

- [x] **Step 2: Update `ReviewCommand` to use `MarkdownReporter`**

```java
package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.reporting.MarkdownReporter;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Component
@Command(name = "review", description = "Review a git diff")
public class ReviewCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to git repository", defaultValue = ".")
    private String repoPath;

    @Parameters(index = "1", description = "Git ref to diff against", defaultValue = "HEAD~1")
    private String ref;

    private final CodeReviewAgent agent;
    private final MarkdownReporter reporter;

    public ReviewCommand(CodeReviewAgent agent, MarkdownReporter reporter) {
        this.agent = agent;
        this.reporter = reporter;
    }

    @Override
    public Integer call() {
        System.out.println("Repository : " + repoPath);
        System.out.println("Diff ref   : " + ref);

        String request = "Review code changes in repo: " + repoPath +
                "\nCompare against ref: " + ref +
                "\nCall getGitDiff first, then checkRules, then produce the ReviewResult.";
        ReviewResult result = agent.review(request);
        System.out.println("\n" + reporter.render(result));
        return 0;
    }
}
```

- [x] **Step 3: Build + smoke test**

Run: `MOONSHOT_API_KEY=dummy mvn -q clean package -DskipTests`
Expected: BUILD SUCCESS.

With real key:
```bash
export MOONSHOT_API_KEY=<real>
java -jar target/code-review-agent-1.0.0.jar review . HEAD~1
```
Expected: a Markdown report is printed. If the model occasionally fails JSON parsing, that's OK for W1 — evaluation will reveal the rate. If it fails systematically, lower `max-tokens` to force shorter output or strengthen the schema description in the prompt.

- [x] **Step 4: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/agents/CodeReviewAgent.java \
        src/main/java/dev/langchain4j/example/codereview/cli/ReviewCommand.java
git commit -m "feat(agent): CodeReviewAgent returns structured ReviewResult"
```

---

## Phase 4 · Evaluation framework (Tasks 16-22)

### Task 16: Eval data POJOs (`Sample`, `Annotation`, `ExpectedIssue`, `SuppressedPattern`)

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/Sample.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/Annotation.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/ExpectedIssue.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/SuppressedPattern.java`

- [x] **Step 1: Create POJOs**

```java
// SuppressedPattern.java
package dev.langchain4j.example.codereview.eval;
public record SuppressedPattern(String pattern, String reason) { }
```

```java
// ExpectedIssue.java
package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.Severity;

import java.util.List;

public record ExpectedIssue(
        String id,
        String file,
        int line,
        int[] lineRange,
        Category category,
        String subcategory,
        Severity severity,
        String description,
        boolean mustDetect,
        List<String> alternativeDescriptions
) { }
```

```java
// Annotation.java
package dev.langchain4j.example.codereview.eval;

import java.util.List;

public record Annotation(
        List<ExpectedIssue> expectedIssues,
        List<SuppressedPattern> shouldNotReport,
        String notes
) { }
```

```java
// Sample.java
package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public record Sample(
        String id,
        Path directory,
        String diffPatch,
        Path sourceBeforeDir,
        Annotation annotation,
        Map<String, Object> meta
) {
    public static Sample load(Path sampleDir, ObjectMapper mapper) throws IOException {
        String diff = Files.readString(sampleDir.resolve("diff.patch"));
        String annotJson = Files.readString(sampleDir.resolve("annotation.json"));
        Annotation annotation = mapper.readValue(annotJson, Annotation.class);
        Map<String, Object> meta = Map.of();
        Path metaFile = sampleDir.resolve("meta.json");
        if (Files.exists(metaFile)) {
            meta = mapper.readValue(Files.readString(metaFile), Map.class);
        }
        return new Sample(
                sampleDir.getFileName().toString(),
                sampleDir,
                diff,
                sampleDir.resolve("source-before"),
                annotation,
                meta
        );
    }

    public static ObjectMapper defaultMapper() {
        return new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
```

- [x] **Step 2: Build**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [x] **Step 3: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/eval/
git commit -m "feat(eval): Sample/Annotation/ExpectedIssue/SuppressedPattern POJOs"
```

---

### Task 17: `Metrics` — recall, precision, fp_rate, severity_accuracy

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/Metrics.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/SampleMetrics.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/eval/MetricsTest.java`

- [x] **Step 1: Create `SampleMetrics.java`**

```java
package dev.langchain4j.example.codereview.eval;

public record SampleMetrics(
        String sampleId,
        int truePositives,
        int falsePositives,
        int falseNegatives,
        int severityMatches,
        int severityComparisons,
        long latencyMs,
        long inputTokens,
        long outputTokens,
        int toolCallsTotal,
        int toolCallsFailed
) { }
```

- [x] **Step 2: Write failing tests for `Metrics`**

```java
package dev.langchain4j.example.codereview.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricsTest {

    @Test
    void recallIsTpOverTpPlusFn() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 3, 1, 1, 2, 3, 100, 100, 50, 5, 0),
                new SampleMetrics("s2", 1, 0, 2, 1, 1, 200, 200, 80, 3, 0)
        );
        // TP=4, FN=3 → recall = 4/7
        assertThat(Metrics.recall(ms)).isCloseTo(4.0/7.0, within(0.0001));
    }

    @Test
    void precisionIsTpOverTpPlusFp() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 3, 1, 0, 2, 3, 100, 100, 50, 5, 0),
                new SampleMetrics("s2", 1, 2, 0, 1, 1, 200, 200, 80, 3, 0)
        );
        // TP=4, FP=3 → precision = 4/7
        assertThat(Metrics.precision(ms)).isCloseTo(4.0/7.0, within(0.0001));
    }

    @Test
    void fpRateIsFpOverTotalReported() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 3, 1, 0, 0, 0, 0, 0, 0, 0, 0),
                new SampleMetrics("s2", 1, 2, 0, 0, 0, 0, 0, 0, 0, 0)
        );
        // FP=3, reported=TP+FP=7 → 3/7
        assertThat(Metrics.fpRate(ms)).isCloseTo(3.0/7.0, within(0.0001));
    }

    @Test
    void severityAccuracyIsMatchesOverComparisons() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 0, 0, 0, 4, 5, 0, 0, 0, 0, 0),
                new SampleMetrics("s2", 0, 0, 0, 3, 5, 0, 0, 0, 0, 0)
        );
        // 7/10
        assertThat(Metrics.severityAccuracy(ms)).isCloseTo(0.7, within(0.0001));
    }

    @Test
    void zeroDenominatorReturnsZero() {
        SampleMetrics empty = new SampleMetrics("s1", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(Metrics.recall(List.of(empty))).isEqualTo(0.0);
        assertThat(Metrics.precision(List.of(empty))).isEqualTo(0.0);
        assertThat(Metrics.fpRate(List.of(empty))).isEqualTo(0.0);
        assertThat(Metrics.severityAccuracy(List.of(empty))).isEqualTo(0.0);
    }

    @Test
    void avgLatencyAndTokens() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 0, 0, 0, 0, 0, 100, 1000, 500, 10, 0),
                new SampleMetrics("s2", 0, 0, 0, 0, 0, 300, 3000, 1500, 8, 0)
        );
        assertThat(Metrics.avgLatencyMs(ms)).isEqualTo(200.0);
        assertThat(Metrics.avgInputTokens(ms)).isEqualTo(2000.0);
        assertThat(Metrics.avgOutputTokens(ms)).isEqualTo(1000.0);
    }

    @Test
    void toolSuccessRate() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 0, 0, 0, 0, 0, 0, 0, 0, 10, 1),
                new SampleMetrics("s2", 0, 0, 0, 0, 0, 0, 0, 0, 10, 0)
        );
        // (20-1)/20 = 0.95
        assertThat(Metrics.toolSuccessRate(ms)).isCloseTo(0.95, within(0.0001));
    }
}
```

- [x] **Step 3: Run, confirm fail**

Run: `mvn -q test -Dtest=MetricsTest`
Expected: compile error.

- [x] **Step 4: Implement `Metrics.java`**

```java
package dev.langchain4j.example.codereview.eval;

import java.util.List;

public final class Metrics {

    private Metrics() { }

    public static double recall(List<SampleMetrics> ms) {
        int tp = ms.stream().mapToInt(SampleMetrics::truePositives).sum();
        int fn = ms.stream().mapToInt(SampleMetrics::falseNegatives).sum();
        return safeDiv(tp, tp + fn);
    }

    public static double precision(List<SampleMetrics> ms) {
        int tp = ms.stream().mapToInt(SampleMetrics::truePositives).sum();
        int fp = ms.stream().mapToInt(SampleMetrics::falsePositives).sum();
        return safeDiv(tp, tp + fp);
    }

    public static double fpRate(List<SampleMetrics> ms) {
        int fp = ms.stream().mapToInt(SampleMetrics::falsePositives).sum();
        int reported = ms.stream().mapToInt(s -> s.truePositives() + s.falsePositives()).sum();
        return safeDiv(fp, reported);
    }

    public static double severityAccuracy(List<SampleMetrics> ms) {
        int matches = ms.stream().mapToInt(SampleMetrics::severityMatches).sum();
        int total = ms.stream().mapToInt(SampleMetrics::severityComparisons).sum();
        return safeDiv(matches, total);
    }

    public static double avgLatencyMs(List<SampleMetrics> ms) {
        return ms.isEmpty() ? 0 : ms.stream().mapToLong(SampleMetrics::latencyMs).average().orElse(0);
    }

    public static double avgInputTokens(List<SampleMetrics> ms) {
        return ms.isEmpty() ? 0 : ms.stream().mapToLong(SampleMetrics::inputTokens).average().orElse(0);
    }

    public static double avgOutputTokens(List<SampleMetrics> ms) {
        return ms.isEmpty() ? 0 : ms.stream().mapToLong(SampleMetrics::outputTokens).average().orElse(0);
    }

    public static double toolSuccessRate(List<SampleMetrics> ms) {
        int total = ms.stream().mapToInt(SampleMetrics::toolCallsTotal).sum();
        int failed = ms.stream().mapToInt(SampleMetrics::toolCallsFailed).sum();
        return safeDiv(total - failed, total);
    }

    private static double safeDiv(int num, int den) {
        return den == 0 ? 0.0 : (double) num / den;
    }
}
```

- [x] **Step 5: Run, confirm pass**

Run: `mvn -q test -Dtest=MetricsTest`
Expected: 7 tests pass.

- [x] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/eval/Metrics.java \
        src/main/java/dev/langchain4j/example/codereview/eval/SampleMetrics.java \
        src/test/java/dev/langchain4j/example/codereview/eval/MetricsTest.java
git commit -m "feat(eval): Metrics with recall/precision/fp/severity/latency/token formulas"
```

---

### Task 18: `Matcher` — two-layer matching (position + LLM judge)

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/MatchResult.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/LlmJudge.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/Matcher.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/eval/MatcherTest.java`

- [x] **Step 1: Create supporting types**

```java
// MatchResult.java
package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.ReviewFinding;

public record MatchResult(
        ExpectedIssue expected,
        ReviewFinding agentFinding,
        boolean matched,
        double confidence,
        String judgeReason
) {
    public static MatchResult miss(ExpectedIssue expected) {
        return new MatchResult(expected, null, false, 0.0, "no candidate at expected location");
    }
}
```

```java
// LlmJudge.java
package dev.langchain4j.example.codereview.eval;

public interface LlmJudge {
    JudgeVerdict judge(ExpectedIssue expected, dev.langchain4j.example.codereview.model.ReviewFinding agent);

    record JudgeVerdict(boolean match, double confidence, String reason) { }
}
```

- [x] **Step 2: Write failing tests for `Matcher`**

```java
package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.Severity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatcherTest {

    private ExpectedIssue expected(int line, int[] range) {
        return new ExpectedIssue(
                "I-1", "Foo.java", line, range,
                Category.SECURITY, "sql_injection", Severity.CRITICAL,
                "SQL injection vulnerability", true,
                List.of("Unparameterized query"));
    }

    private ReviewFinding agent(String file, int line, String desc) {
        return new ReviewFinding(
                "F-1", file, line, null, Severity.CRITICAL, Category.SECURITY,
                "title", desc, "fix", "ev", List.of(), "llm_reviewer");
    }

    @Test
    void layer1MissesWhenNoCandidateInRange() {
        LlmJudge judge = Mockito.mock(LlmJudge.class);
        Matcher matcher = new Matcher(judge, 5);

        List<MatchResult> results = matcher.match(
                List.of(expected(42, new int[]{40, 45})),
                List.of(agent("Foo.java", 100, "something else"))
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).matched()).isFalse();
        verify(judge, never()).judge(any(), any());
    }

    @Test
    void layer1FiltersByFileNotJustLine() {
        LlmJudge judge = Mockito.mock(LlmJudge.class);
        Matcher matcher = new Matcher(judge, 5);

        List<MatchResult> results = matcher.match(
                List.of(expected(42, new int[]{40, 45})),
                List.of(agent("OtherFile.java", 42, "SQL injection"))
        );

        assertThat(results.get(0).matched()).isFalse();
        verify(judge, never()).judge(any(), any());
    }

    @Test
    void layer2MatchesViaJudge() {
        LlmJudge judge = Mockito.mock(LlmJudge.class);
        when(judge.judge(any(), any()))
                .thenReturn(new LlmJudge.JudgeVerdict(true, 0.9, "same problem"));
        Matcher matcher = new Matcher(judge, 5);

        List<MatchResult> results = matcher.match(
                List.of(expected(42, new int[]{40, 45})),
                List.of(agent("Foo.java", 43, "Found a SQL injection vulnerability here"))
        );

        assertThat(results.get(0).matched()).isTrue();
        assertThat(results.get(0).confidence()).isEqualTo(0.9);
        verify(judge, times(1)).judge(any(), any());
    }

    @Test
    void layer2RejectsWhenJudgeSaysNo() {
        LlmJudge judge = Mockito.mock(LlmJudge.class);
        when(judge.judge(any(), any()))
                .thenReturn(new LlmJudge.JudgeVerdict(false, 0.1, "different concern"));
        Matcher matcher = new Matcher(judge, 5);

        List<MatchResult> results = matcher.match(
                List.of(expected(42, new int[]{40, 45})),
                List.of(agent("Foo.java", 43, "missing javadoc"))
        );

        assertThat(results.get(0).matched()).isFalse();
    }

    @Test
    void rangeToleranceDefault5() {
        LlmJudge judge = Mockito.mock(LlmJudge.class);
        when(judge.judge(any(), any()))
                .thenReturn(new LlmJudge.JudgeVerdict(true, 1.0, "yes"));
        Matcher matcher = new Matcher(judge, 5);

        // expected range 40-45, ±5 → effective 35-50
        List<MatchResult> results = matcher.match(
                List.of(expected(42, new int[]{40, 45})),
                List.of(agent("Foo.java", 48, "sql injection"))
        );
        assertThat(results.get(0).matched()).isTrue();
    }
}
```

- [x] **Step 3: Run, confirm fail**

Run: `mvn -q test -Dtest=MatcherTest`
Expected: compile error.

- [x] **Step 4: Implement `Matcher.java`**

```java
package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.ReviewFinding;

import java.util.ArrayList;
import java.util.List;

public class Matcher {

    private final LlmJudge judge;
    private final int lineTolerance;

    public Matcher(LlmJudge judge, int lineTolerance) {
        this.judge = judge;
        this.lineTolerance = lineTolerance;
    }

    public List<MatchResult> match(List<ExpectedIssue> expected, List<ReviewFinding> agentFindings) {
        List<MatchResult> results = new ArrayList<>();
        for (ExpectedIssue exp : expected) {
            MatchResult best = MatchResult.miss(exp);
            for (ReviewFinding f : agentFindings) {
                if (f.file() == null || !f.file().equals(exp.file())) continue;
                if (f.line() == null) continue;
                int low = exp.lineRange() != null && exp.lineRange().length == 2
                        ? exp.lineRange()[0] - lineTolerance
                        : exp.line() - lineTolerance;
                int high = exp.lineRange() != null && exp.lineRange().length == 2
                        ? exp.lineRange()[1] + lineTolerance
                        : exp.line() + lineTolerance;
                if (f.line() < low || f.line() > high) continue;

                LlmJudge.JudgeVerdict v = judge.judge(exp, f);
                if (v.match()) {
                    best = new MatchResult(exp, f, true, v.confidence(), v.reason());
                    break;
                }
            }
            results.add(best);
        }
        return results;
    }
}
```

- [x] **Step 5: Run, confirm pass**

Run: `mvn -q test -Dtest=MatcherTest`
Expected: 5 tests pass.

- [x] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/eval/MatchResult.java \
        src/main/java/dev/langchain4j/example/codereview/eval/LlmJudge.java \
        src/main/java/dev/langchain4j/example/codereview/eval/Matcher.java \
        src/test/java/dev/langchain4j/example/codereview/eval/MatcherTest.java
git commit -m "feat(eval): two-layer Matcher (position + LLM judge)"
```

---

### Task 19: `LlmJudgeImpl` — concrete judge using ChatModel

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/LlmJudgeImpl.java`

- [x] **Step 1: Implement**

```java
package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class LlmJudgeImpl implements LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeImpl.class);

    private final ChatModel chatModel;
    private final ObjectMapper mapper;

    public LlmJudgeImpl(ChatModel chatModel, ObjectMapper mapper) {
        this.chatModel = chatModel;
        this.mapper = mapper;
    }

    @Override
    public JudgeVerdict judge(ExpectedIssue expected, ReviewFinding agent) {
        String prompt = """
                You are evaluating whether two code review findings describe the SAME issue.

                Expected issue (ground truth):
                  Description: %s
                  Category: %s
                  Alternative phrasings: %s

                Agent's finding:
                  Description: %s
                  Severity: %s

                Question: Do these describe the SAME underlying problem? Answer with JSON only:
                {"match": true|false, "confidence": 0.0-1.0, "reason": "..."}
                """.formatted(
                expected.description(),
                expected.category(),
                String.join("; ", expected.alternativeDescriptions() == null ? List.of() : expected.alternativeDescriptions()),
                agent.description(),
                agent.severity()
        );

        String raw = chatModel.chat(prompt);
        try {
            // Strip markdown fences if model added them
            String json = raw.replaceAll("(?s)```(?:json)?", "").trim();
            return mapper.readValue(json, JudgeVerdict.class);
        } catch (Exception e) {
            log.warn("Judge response not parseable: {}", raw);
            return new JudgeVerdict(false, 0.0, "judge parse error: " + e.getMessage());
        }
    }
}
```

- [x] **Step 2: Wire into `AgentConfig`**

Append to `config/AgentConfig.java`:

```java
    @Bean
    public dev.langchain4j.example.codereview.eval.LlmJudge llmJudge(
            ChatModel chatModel,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new dev.langchain4j.example.codereview.eval.LlmJudgeImpl(chatModel, mapper);
    }

    @Bean
    public dev.langchain4j.example.codereview.eval.Matcher matcher(
            dev.langchain4j.example.codereview.eval.LlmJudge judge) {
        return new dev.langchain4j.example.codereview.eval.Matcher(judge, 5);
    }
```

- [x] **Step 3: Build**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [x] **Step 4: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/eval/LlmJudgeImpl.java \
        src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java
git commit -m "feat(eval): LlmJudgeImpl + bean wiring"
```

---

### Task 20: `EvalReport` POJO + `EvaluationRunner`

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/EvalReport.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java`

- [x] **Step 1: Create `EvalReport.java`**

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
        List<SampleMetrics> perSample
) { }
```

- [x] **Step 2: Create `EvaluationRunner.java`**

```java
package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    private final CodeReviewAgent agent;
    private final Matcher matcher;
    private final ObjectMapper mapper;

    public EvaluationRunner(CodeReviewAgent agent, Matcher matcher, ObjectMapper mapper) {
        this.agent = agent;
        this.matcher = matcher;
        this.mapper = mapper;
    }

    public EvalReport run(Path samplesDir, Path reportsDir, String version, Map<String, Object> config) throws IOException {
        List<Path> sampleDirs = listSampleDirs(samplesDir);
        List<SampleMetrics> per = new ArrayList<>();

        for (Path dir : sampleDirs) {
            Sample sample = Sample.load(dir, mapper);
            log.info("Evaluating sample {}", sample.id());
            SampleMetrics m = evaluateOne(sample);
            per.add(m);
        }

        Map<String, Double> aggregate = Map.of(
                "recall", Metrics.recall(per),
                "precision", Metrics.precision(per),
                "fp_rate", Metrics.fpRate(per),
                "severity_accuracy", Metrics.severityAccuracy(per),
                "avg_latency_ms", Metrics.avgLatencyMs(per),
                "avg_input_tokens", Metrics.avgInputTokens(per),
                "avg_output_tokens", Metrics.avgOutputTokens(per),
                "tool_success_rate", Metrics.toolSuccessRate(per)
        );

        EvalReport report = new EvalReport(
                version,
                currentCommit(),
                currentTag(),
                Instant.now().toString(),
                config,
                List.of("diff.patch", "source-before/"),
                aggregate,
                per
        );

        Files.createDirectories(reportsDir);
        Path reportFile = reportsDir.resolve(version + ".json");
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        Files.writeString(reportFile, mapper.writeValueAsString(report));
        log.info("Report written to {}", reportFile);
        return report;
    }

    private SampleMetrics evaluateOne(Sample sample) {
        long start = System.currentTimeMillis();
        ReviewResult result;
        try {
            String request = "Review the following diff. The full diff is below; do NOT call git tools.\n\n" + sample.diffPatch();
            result = agent.review(request);
        } catch (Exception e) {
            log.warn("Sample {} review failed: {}", sample.id(), e.toString());
            result = ReviewResult.empty("review error: " + e.getMessage());
        }
        long latency = System.currentTimeMillis() - start;

        List<MatchResult> matches = matcher.match(
                sample.annotation().expectedIssues(),
                result.findings() == null ? List.of() : result.findings());

        int tp = (int) matches.stream().filter(MatchResult::matched).count();
        int fn = matches.size() - tp;
        int reported = result.findings() == null ? 0 : result.findings().size();
        int fp = Math.max(0, reported - tp);

        int sevMatch = 0, sevTotal = 0;
        for (MatchResult m : matches) {
            if (m.matched()) {
                sevTotal++;
                if (m.expected().severity() == m.agentFinding().severity()) sevMatch++;
            }
        }

        return new SampleMetrics(sample.id(), tp, fp, fn, sevMatch, sevTotal, latency,
                0L, 0L,    // W1 leaves token counting at 0; LangChain4j doesn't expose it on AiServices easily
                0, 0);     // tool call success: 0 means unknown; W2 will count via interceptor
    }

    private List<Path> listSampleDirs(Path samplesDir) throws IOException {
        if (!Files.isDirectory(samplesDir)) {
            throw new IOException("Samples directory not found: " + samplesDir);
        }
        try (var stream = Files.list(samplesDir)) {
            return stream.filter(Files::isDirectory).sorted().toList();
        }
    }

    private String currentCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            p.waitFor();
            return new String(p.getInputStream().readAllBytes()).trim();
        } catch (Exception e) { return "unknown"; }
    }

    private String currentTag() {
        try {
            Process p = new ProcessBuilder("git", "describe", "--tags", "--exact-match")
                    .redirectErrorStream(true).start();
            p.waitFor();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            return s.contains("fatal") ? null : s;
        } catch (Exception e) { return null; }
    }
}
```

Note on tokens: token tracking is left as `0L` in W1. W2 can add an `OpenAiTokenUsageListener` once we move past `1.15-beta`. The metric stays in the schema for forward compatibility.

- [x] **Step 3: Wire into `AgentConfig`**

Append to `config/AgentConfig.java`:

```java
    @Bean
    public dev.langchain4j.example.codereview.eval.EvaluationRunner evaluationRunner(
            dev.langchain4j.example.codereview.agents.CodeReviewAgent agent,
            dev.langchain4j.example.codereview.eval.Matcher matcher,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new dev.langchain4j.example.codereview.eval.EvaluationRunner(agent, matcher, mapper);
    }
```

- [x] **Step 4: Build**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [x] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/eval/EvalReport.java \
        src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java \
        src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java
git commit -m "feat(eval): EvaluationRunner orchestrates sample → agent → match → report"
```

---

### Task 21: `EvalCommand` (real implementation)

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/cli/EvalCommand.java`

- [x] **Step 1: Rewrite `EvalCommand.java`**

```java
package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.config.CodeReviewProperties;
import dev.langchain4j.example.codereview.eval.EvalReport;
import dev.langchain4j.example.codereview.eval.EvaluationRunner;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "eval", description = "Run evaluation suite against PR samples")
public class EvalCommand implements Callable<Integer> {

    @Option(names = "--version", required = true, description = "Version label, e.g. v0-baseline")
    private String version;

    @Option(names = "--samples", description = "Override samples directory")
    private Path samplesOverride;

    @Option(names = "--report-dir", description = "Override reports output directory")
    private Path reportDirOverride;

    private final EvaluationRunner runner;
    private final CodeReviewProperties props;

    public EvalCommand(EvaluationRunner runner, CodeReviewProperties props) {
        this.runner = runner;
        this.props = props;
    }

    @Override
    public Integer call() throws Exception {
        Path samples = samplesOverride != null ? samplesOverride : props.eval().samplesDir();
        Path reports = reportDirOverride != null ? reportDirOverride : props.eval().reportDir();

        Map<String, Object> config = Map.of(
                "judge_model", props.eval().judgeModel(),
                "runs_per_sample", props.eval().runsPerSample(),
                "pipeline", "w1-single-agent"
        );

        EvalReport report = runner.run(samples, reports, version, config);
        System.out.printf("recall=%.2f precision=%.2f fp_rate=%.2f%n",
                report.metrics().get("recall"),
                report.metrics().get("precision"),
                report.metrics().get("fp_rate"));
        return 0;
    }
}
```

- [x] **Step 2: Build**

Run: `mvn -q clean package -DskipTests`
Expected: BUILD SUCCESS. With `MOONSHOT_API_KEY=dummy` if needed.

- [x] **Step 3: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/cli/EvalCommand.java
git commit -m "feat(cli): EvalCommand wires EvaluationRunner to picocli"
```

---

### Task 22: Integration test — full eval pipeline with mocked `ChatModel`

**Files:**
- Create: `src/test/java/dev/langchain4j/example/codereview/eval/EvaluationRunnerIT.java`
- Create: `src/test/resources/eval-fixtures/sample-pass/diff.patch`
- Create: `src/test/resources/eval-fixtures/sample-pass/annotation.json`
- Create: `src/test/resources/eval-fixtures/sample-pass/source-before/.gitkeep`
- Create: `src/test/resources/eval-fixtures/sample-fail/diff.patch`
- Create: `src/test/resources/eval-fixtures/sample-fail/annotation.json`
- Create: `src/test/resources/eval-fixtures/sample-fail/source-before/.gitkeep`
- Create: `src/test/java/dev/langchain4j/example/codereview/TestChatModelConfig.java`

- [x] **Step 1: Create sample fixtures**

`sample-pass/diff.patch`:
```
diff --git a/User.java b/User.java
--- a/User.java
+++ b/User.java
@@ -10,3 +10,4 @@ public class User {
     int x = 1;
+    String pwd = "hardcoded";
     int y = 2;
```

`sample-pass/annotation.json`:
```json
{
  "expected_issues": [
    {
      "id": "I-001",
      "file": "User.java",
      "line": 11,
      "line_range": [11, 11],
      "category": "SECURITY",
      "subcategory": "hardcoded_credential",
      "severity": "CRITICAL",
      "description": "Hardcoded credential",
      "must_detect": true,
      "alternative_descriptions": ["password in code", "secret in source"]
    }
  ],
  "should_not_report": [],
  "notes": "test fixture"
}
```

`sample-fail/diff.patch`:
```
diff --git a/Clean.java b/Clean.java
--- a/Clean.java
+++ b/Clean.java
@@ -1,2 +1,3 @@
 public class Clean {
+    int x = 1;
 }
```

`sample-fail/annotation.json`:
```json
{
  "expected_issues": [
    {
      "id": "I-001",
      "file": "Clean.java",
      "line": 2,
      "line_range": [2, 2],
      "category": "STYLE",
      "subcategory": "naming",
      "severity": "WARNING",
      "description": "field named x is meaningless",
      "must_detect": true,
      "alternative_descriptions": []
    }
  ],
  "should_not_report": [],
  "notes": "agent will likely miss this — used to test FN counting"
}
```

- [x] **Step 2: Test config that mocks `ChatModel`**

```java
// src/test/java/dev/langchain4j/example/codereview/TestChatModelConfig.java
package dev.langchain4j.example.codereview;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

@TestConfiguration
public class TestChatModelConfig {

    @Bean @Primary
    public ChatModel testChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                // Always returns a judge verdict OR a fixed review depending on prompt content
                String last = request.messages().get(request.messages().size() - 1).toString();
                if (last.contains("Do these describe the SAME underlying problem")) {
                    return ChatResponse.builder()
                            .aiMessage(AiMessage.from("{\"match\": true, \"confidence\": 0.9, \"reason\": \"same\"}"))
                            .build();
                }
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("(unused)"))
                        .build();
            }
        };
    }

    @Bean @Primary
    public CodeReviewAgent testAgent() {
        // Always reports the hardcoded credential at User.java:11 — passes sample-pass, misses sample-fail
        return request -> new ReviewResult(
                "1 finding",
                List.of(new ReviewFinding(
                        "F-001", "User.java", 11, new int[]{11, 11},
                        Severity.CRITICAL, Category.SECURITY,
                        "Hardcoded credential",
                        "Found hardcoded password",
                        "Move to environment variable",
                        "pwd = \"hardcoded\"",
                        List.of(),
                        "llm_reviewer")),
                List.of()
        );
    }
}
```

Adjust imports/method names for `ChatModel` if `1.15-beta25` exposes a different interface; if so, fall back to mocking via Mockito on the actual interface.

- [x] **Step 3: Write the integration test**

```java
package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.TestChatModelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "MOONSHOT_API_KEY=dummy",
        "code-review.eval.runs-per-sample=1"
})
@Import(TestChatModelConfig.class)
class EvaluationRunnerIT {

    @Autowired EvaluationRunner runner;

    @TempDir Path workDir;

    @Test
    void runsTwoFixtureSamplesAndProducesReport() throws Exception {
        Path samples = workDir.resolve("samples");
        Path reports = workDir.resolve("reports");
        Files.createDirectories(samples);

        copyFixture("sample-pass", samples);
        copyFixture("sample-fail", samples);

        EvalReport report = runner.run(samples, reports, "v0-test", Map.of("pipeline", "test"));

        assertThat(report.perSample()).hasSize(2);

        // sample-pass: agent matches expected → TP=1
        SampleMetrics passMetrics = report.perSample().stream()
                .filter(s -> s.sampleId().equals("sample-pass")).findFirst().orElseThrow();
        assertThat(passMetrics.truePositives()).isEqualTo(1);

        // sample-fail: agent misses the expected naming issue → FN=1
        SampleMetrics failMetrics = report.perSample().stream()
                .filter(s -> s.sampleId().equals("sample-fail")).findFirst().orElseThrow();
        assertThat(failMetrics.falseNegatives()).isEqualTo(1);

        // report file should exist
        assertThat(Files.exists(reports.resolve("v0-test.json"))).isTrue();
        assertThat(report.allowedInputs()).contains("diff.patch", "source-before/");
    }

    private void copyFixture(String name, Path samplesRoot) throws Exception {
        Path target = samplesRoot.resolve(name);
        Files.createDirectories(target.resolve("source-before"));
        copy("eval-fixtures/" + name + "/diff.patch", target.resolve("diff.patch"));
        copy("eval-fixtures/" + name + "/annotation.json", target.resolve("annotation.json"));
    }

    private void copy(String classpath, Path dst) throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) throw new IllegalStateException("fixture missing: " + classpath);
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
```

- [x] **Step 4: Run the integration test**

Run: `mvn -q test -Dtest=EvaluationRunnerIT`
Expected: 1 test passes. The report file appears at `<tmp>/reports/v0-test.json` and contains the expected per-sample metrics.

If Spring Boot fails to start because the `ChatModel` bean is being auto-configured by LangChain4j starter (auto-config might detect the missing API key), the simplest workaround is to set `langchain4j.open-ai.chat-model.api-key=dummy` in `application-test.yml` and activate test profile via `@ActiveProfiles("test")`.

- [x] **Step 5: Commit**

```bash
git add src/test/java/dev/langchain4j/example/codereview/TestChatModelConfig.java \
        src/test/java/dev/langchain4j/example/codereview/eval/EvaluationRunnerIT.java \
        src/test/resources/eval-fixtures/
git commit -m "test(eval): integration test runs 2 fixture samples through full pipeline"
```

---

## Phase 5 · Baseline collection (Tasks 23-26)

### Task 23: Eval directory + format docs + worked example sample

**Files:**
- Create: `eval/samples/reverse-001/meta.json`
- Create: `eval/samples/reverse-001/diff.patch`
- Create: `eval/samples/reverse-001/source-before/` (one file)
- Create: `eval/samples/reverse-001/source-after/` (one file)
- Create: `eval/samples/reverse-001/annotation.json`
- Create: `eval/samples/README.md`
- Create: `eval/.gitignore`

- [ ] **Step 1: Pick a single real fix commit as worked example**

Open https://github.com/apache/dubbo/commits/main and search for a commit with message `fix npe`, `fix sql injection`, `fix race condition`, etc. Open one with a small diff (≤ 50 lines).

Example chosen: `https://github.com/apache/dubbo/commit/<HASH>` (replace with whatever you find).

Use `gh` or `git format-patch` to grab the patch:
```bash
gh api repos/apache/dubbo/commits/<HASH> -H "Accept: application/vnd.github.v3.diff" > /tmp/fix.patch
```

- [ ] **Step 2: Reverse the patch (so `diff.patch` becomes the "broken" state under review)**

```bash
mkdir -p eval/samples/reverse-001/source-before eval/samples/reverse-001/source-after
# Manually reverse: the "+" lines in the fix become the "-" baseline; the original (with bug) is the source-before.
# Easiest workflow:
#   1. Checkout commit BEFORE the fix → copy file(s) to source-before/
#   2. Checkout the fix commit → copy file(s) to source-after/
#   3. Build diff.patch via: diff -u source-before/F.java source-after/F.java
```

- [ ] **Step 3: Write `meta.json`**

```json
{
  "id": "reverse-001",
  "source_type": "reverse_constructed",
  "source_url": "https://github.com/apache/dubbo/commit/<HASH>",
  "language": "java",
  "category": "stability",
  "difficulty": "easy",
  "diff_size_lines": 12,
  "collected_at": "2026-05-20"
}
```

- [ ] **Step 4: Write `annotation.json`**

Read the commit message and any linked issue; that's your ground truth. Fill in:
```json
{
  "expected_issues": [
    {
      "id": "I-001",
      "file": "<path/to/Foo.java>",
      "line": <line in source-after>,
      "line_range": [<lo>, <hi>],
      "category": "STABILITY",
      "subcategory": "npe",
      "severity": "CRITICAL",
      "description": "Possible NullPointerException when ... is null",
      "must_detect": true,
      "alternative_descriptions": ["NPE risk", "missing null check"]
    }
  ],
  "should_not_report": [],
  "notes": "Reversed from fix commit <HASH>: 'fix: NPE in ...'."
}
```

- [ ] **Step 5: Write `eval/samples/README.md`**

````markdown
# Eval Samples

Each subdirectory is one PR sample. Layout:

```
<sample-id>/
├── meta.json         metadata
├── diff.patch        diff to review (agent input)
├── source-before/    source state before the diff (agent tool can read)
├── source-after/     source after the fix (HUMAN ONLY; agent must NOT read)
└── annotation.json   ground truth (HUMAN ONLY; agent must NOT read)
```

## Sample types

- `reverse-NNN/`: reverse-constructed from a real fix commit. The "broken" state is the input; the commit message + alternative phrasings are the ground truth.
- `real-NNN/`: real PR sampled from a public project, ground truth taken from human reviewer comments.
- `synthetic-NNN/`: hand-crafted edge case (clean PR, oversized diff, etc.).

## Severity / Category enums

- `severity`: `CRITICAL | WARNING | SUGGESTION`
- `category`: `SECURITY | PERFORMANCE | STABILITY | CONCURRENCY | TEST | STYLE | OTHER`

## Agent isolation

The evaluation runner only exposes `diff.patch` and `source-before/` to the agent. `annotation.json`, `source-after/`, and the `category/difficulty/notes` fields of `meta.json` are forbidden inputs.
````

- [ ] **Step 6: `.gitignore` cache files inside eval dir**

```
# eval/.gitignore
reports/*-traces/
```

- [ ] **Step 7: Commit**

```bash
git add eval/
git commit -m "docs(eval): sample directory format + worked example reverse-001"
```

---

### Task 24: Collect 4 more reverse-constructed samples (HUMAN TASK)

**Files:**
- Create: `eval/samples/reverse-002/...` through `eval/samples/reverse-005/...`

- [ ] **Step 1: Pick 4 more fix commits across 4 different categories**

| Sample | Category | Suggested search |
| --- | --- | --- |
| reverse-002 | SECURITY | `fix sql injection` or `parameterize query` |
| reverse-003 | CONCURRENCY | `fix race condition` or `synchronize` |
| reverse-004 | PERFORMANCE | `fix n+1` or `eager fetch` |
| reverse-005 | STABILITY | `fix memory leak` or `close resource` |

Source repos to scan (have many high-quality fix commits): `apache/dubbo`, `spring-projects/spring-framework`, `apache/rocketmq`, `apache/kafka`, `netty/netty`.

- [ ] **Step 2: For each, repeat the workflow from Task 23 steps 1-4**

Aim for small diffs (≤ 50 lines), clear commit messages, and a single dominant issue. Skip commits where the fix is "rewrite the function" — those don't give clean ground truth.

- [ ] **Step 3: Audit your annotations**

For each sample, ask: "If I described this finding in 3 different phrasings, would the LLM judge call them equivalent?" If unsure, add more `alternative_descriptions`.

- [ ] **Step 4: Commit per sample**

```bash
git add eval/samples/reverse-002/
git commit -m "eval(sample): reverse-002 (SQL injection from <repo>@<hash>)"
# repeat for 003-005
```

---

### Task 25: Run v0 baseline and record results

**Files:**
- Create: `eval/reports/v0-baseline.json` (produced by running)
- Create or modify: `README.md`

- [ ] **Step 1: Build the jar**

```bash
mvn -q clean package -DskipTests
```

- [ ] **Step 2: Run baseline evaluation**

```bash
export MOONSHOT_API_KEY=<real>
java -jar target/code-review-agent-1.0.0.jar eval --version v0-baseline
```

Expected: console prints `recall=0.xx precision=0.yy fp_rate=0.zz`, and `eval/reports/v0-baseline.json` is created.

This run will take a few minutes (5 samples × 1 review call each + ~5 judge calls). If the agent JSON-parses fail repeatedly, debug by examining the raw response (`logging.level.dev.langchain4j=DEBUG` to see the LLM round trips).

- [ ] **Step 3: Stage and tag the baseline**

```bash
git add eval/reports/v0-baseline.json
git commit -m "eval: v0-baseline metrics from 5 reverse-constructed samples"
git tag v0-baseline
```

- [ ] **Step 4: Create or update `README.md`**

```markdown
# Code Review Agent

A LangChain4j-based Java agent that reviews git diffs and produces structured findings, evaluated against a growing set of reverse-constructed PR samples.

## Status (W1)

| Version | Recall | Precision | FP Rate | Samples |
|---------|--------|-----------|---------|---------|
| v0-baseline | <pct> | <pct> | <pct> | 5 (reverse-constructed) |

Full report: [`eval/reports/v0-baseline.json`](eval/reports/v0-baseline.json)

## Quick start

```bash
export MOONSHOT_API_KEY=<your-kimi-key>
mvn -q clean package -DskipTests
java -jar target/code-review-agent-1.0.0.jar review . HEAD~1
```

## Evaluation

```bash
java -jar target/code-review-agent-1.0.0.jar eval --version v0-baseline
```

See `eval/samples/README.md` for sample format.

## Design

Full design spec: [`docs/superpowers/specs/2026-05-17-code-review-agent-design.md`](docs/superpowers/specs/2026-05-17-code-review-agent-design.md)
```

Replace `<pct>` placeholders with the numbers you just got.

- [ ] **Step 5: Commit README**

```bash
git add README.md
git commit -m "docs: README with v0-baseline metrics and quick start"
```

---

### Task 26: Final W1 verification

- [ ] **Step 1: Run all unit + integration tests**

Run: `mvn -q clean test`
Expected: all tests pass.

- [ ] **Step 2: Run smoke review against this repo**

```bash
java -jar target/code-review-agent-1.0.0.jar review . HEAD~1
```
Expected: a Markdown review is produced; line numbers refer to real file lines (not diff lines).

- [ ] **Step 3: Verify git log**

```bash
git log --oneline | head -30
```
Expected: 20-25 commits telling the W1 story (Spring Boot, infra cleanup, structured output, eval framework, baseline).

- [ ] **Step 4: Push branch / tag (only if user requests it)**

Don't push automatically. Tell the user the branch is ready and let them push.

---

## Spec Coverage Self-Check

| Spec section | Covered by task(s) |
| --- | --- |
| §1 W1 day 1-2 (5 cleanup issues) | T4 (line numbers via DiffParser), T5/T6 (GitClient unification), T6 (per-file split), T9/T10 (EmbeddingCache), T11 (prompt fix) |
| §1 W1 day 3-7 (eval framework + structured output + 5 samples + baseline) | T12-T15 (structured output), T16-T22 (eval framework), T23-T24 (samples), T25 (baseline) |
| §2 package layout: agents/, tools/, analyzer/, model/, rag/, eval/, infra/, config/, cli/, reporting/ | All present after T1-T22 |
| §4.1.1 ReviewResult schema | T13 |
| §4.1 Sample/Annotation schema | T16 |
| §4.2 sample collection (W1 = 5 reverse-constructed) | T23-T24 |
| §4.3 metric definitions (recall/precision/fp/severity, latency/tokens/tool-success) | T17 (formulas) |
| §4.4 two-layer matching (position + LLM judge) | T18, T19 |
| §4.5 EvalReport schema with allowed_inputs, config, commit/tag | T20 |
| §5.3 Spring Boot 3.5 + LangChain4j starter | T1, T2 |
| §6.6 W1 required tests: DiffParser, Metrics, ReviewFinding schema, EvaluationRunner IT | T4 (DiffParser), T13 (ReviewFinding), T17 (Metrics), T22 (EvaluationRunner IT) |
| §5.7 reproducibility (commit/tag in report) | T20 |

**Gaps:** Sample isolation enforcement (only exposing `diff.patch` + `source-before/` to the agent via a temp workdir) is **partially** covered — the EvaluationRunner doesn't construct a temp workdir today; it just passes the diff string. Full file-system isolation lands in W2 alongside `CodeSearchTool`. The report still records `allowed_inputs` so the evaluation story holds.

---

## Plan complete and saved to `docs/superpowers/plans/2026-05-17-code-review-agent-w1.md`.

Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
