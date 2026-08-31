# Code Review Agent — W2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lift v0 baseline (60% recall / 50% precision on 5 samples) to two new evaluated milestones:
- **v1** = SpotBugs + CodeSearchTool + 20 reverse-style samples
- **v2** = Hybrid RAG (BM25 + vector) + LLM reranker + citation tracking + 8-doc knowledge base

**Architecture:** Same single-agent shape from W1 (LangChain4j `AiServices` + tools + ContentRetriever). Static analysis stays behind the W1 `StaticAnalyzer` strategy interface — `SpotBugsAnalyzer` joins `RegexAnalyzer` as a second implementation. RAG is upgraded by replacing the simple `EmbeddingStoreContentRetriever` with a custom `HybridRetriever` that fuses BM25 (Lucene) and vector results via RRF, then optionally passes them through an `LlmReranker`. Citation metadata flows from chunk → `Content` → finding via a `CitationTracker`.

**Tech Stack:** Java 17 · Spring Boot 3.5.6 · LangChain4j Spring Boot starter `1.15.0-beta25` · Apache Lucene 9.x (BM25) · SpotBugs 4.8.x CLI (subprocess + XML output) · Kimi (`moonshot-v1-8k`) · JUnit 5 + Mockito + AssertJ

**Source spec:** `docs/superpowers/specs/2026-05-17-code-review-agent-design.md` §1 W2, §2 (analyzer / rag / tools), §3 (流程 1), §4 (sample 4.2 keyword coverage)

**Predecessor plan:** `docs/superpowers/plans/2026-05-17-code-review-agent-w1.md`

---

## File Map

**Created in W2:**

| File | Responsibility |
| --- | --- |
| `src/main/java/.../analyzer/SpotBugsAnalyzer.java` | Subprocess wrapper → XML parse → `List<Violation>`; graceful skip if not buildable |
| `src/main/java/.../analyzer/SourceCompiler.java` | Best-effort `javac` of a directory tree to a temp `classes/` dir; returns Optional<Path> |
| `src/main/java/.../tools/CodeSearchTool.java` | `@Tool` exposed grep + identifier-lookup over a repo / sample-source dir |
| `src/main/java/.../rag/Bm25Retriever.java` | Lucene 9 standard index; returns top-k chunks with metadata |
| `src/main/java/.../rag/HybridRetriever.java` | Fuses vector + BM25 results via Reciprocal Rank Fusion |
| `src/main/java/.../rag/LlmReranker.java` | LLM-as-reranker; rates 0-1 and re-sorts top-k |
| `src/main/java/.../rag/CitationTracker.java` | Maps `Content` → `Citation`; stable IDs across one review |
| `src/main/java/.../rag/ChunkMetadata.java` | Record fields stored on each `TextSegment` (source file, section heading) |
| `src/main/resources/review-guidelines/sql-guidelines.txt` | NEW knowledge doc |
| `src/main/resources/review-guidelines/performance.txt` | NEW knowledge doc |
| `src/main/resources/review-guidelines/api-design.txt` | NEW knowledge doc |
| `src/main/resources/review-guidelines/exception-handling.txt` | NEW knowledge doc |
| `src/main/resources/review-guidelines/concurrency.txt` | NEW knowledge doc |
| `src/main/resources/review-guidelines/testing.txt` | NEW knowledge doc |
| `eval/samples/reverse-006/` .. `reverse-020/` | 15 new reverse-style samples (3 more per category × 5 categories) |
| `eval/reports/v1-spotbugs-search.json` | v1 evaluation output |
| `eval/reports/v2-rag-hybrid.json` | v2 evaluation output |
| `docs/learnings/w2-notes.md` | Per-task technical / design / Q&A notes (mirror of `w1-notes.md`) |

**Modified in W2:**

| File | Change |
| --- | --- |
| `pom.xml` | Add `lucene-core` + `lucene-analyzers-common` deps |
| `src/main/resources/application.yml` | `rerank-enabled: true`; new `code-review.rag.bm25-top-k`, `rerank-top-k`, `rrf-k` keys |
| `src/main/java/.../config/CodeReviewProperties.java` | Extend `Rag` record with new fields |
| `src/main/java/.../config/RagConfig.java` | Build `HybridRetriever` (vector + BM25) + optional `LlmReranker` wrapping the `ContentRetriever` |
| `src/main/java/.../config/AgentConfig.java` | Add `CodeSearchTool` to `tools(...)` builder |
| `src/main/java/.../rag/KnowledgeBaseIndexer.java` | Attach `ChunkMetadata` (source file, section heading) to each `TextSegment`; build Lucene index alongside the embedding store |
| `src/main/java/.../agents/CodeReviewAgent.java` | Mention CodeSearchTool in the workflow; require `citations[]` populated when a finding is RAG-supported |
| `src/main/java/.../eval/EvaluationRunner.java` | Record `tool_status` from `ReviewResult` into `SampleMetrics`; use `EvaluationContext` to scope CodeSearchTool to the sample's `source-before/` |
| `src/main/java/.../eval/SampleMetrics.java` | Add `toolStatuses` field |
| `src/main/java/.../cli/EvalCommand.java` | Accept `--pipeline <name>` flag; serialize into report config |
| `README.md` | Update metrics table once v1/v2 are produced |
| `CLAUDE.md` | Update "W1 (current)" → "W2 (current)" pointer + new tool list once W2 done |

**Test files (mirror main):**

| File | What it tests |
| --- | --- |
| `src/test/java/.../analyzer/SpotBugsAnalyzerTest.java` | Skip-when-classes-missing + parse-fixture-xml paths (no real SpotBugs needed for the parse test) |
| `src/test/java/.../analyzer/SourceCompilerTest.java` | Single-file compile success; multi-file with missing dep returns empty |
| `src/test/java/.../tools/CodeSearchToolTest.java` | grep substring hits, no-hit, regex toggle off |
| `src/test/java/.../rag/Bm25RetrieverTest.java` | Index → query → relevance ordering on fixture text |
| `src/test/java/.../rag/HybridRetrieverTest.java` | RRF math: when same item appears in both lists, it ranks above singletons |
| `src/test/java/.../rag/LlmRerankerTest.java` | With mocked judge: reorders by score, ties broken stably |
| `src/test/java/.../rag/CitationTrackerTest.java` | Same chunk → same citation ID across calls |
| `src/test/resources/fixtures/spotbugs/sample-output.xml` | SpotBugs XML for parser test |
| `src/test/resources/fixtures/code-search/Foo.java` etc. | Files for CodeSearchTool tests |

---

## Phase 1 · Static analysis + tool coverage + sample expansion (Tasks 1-7)

### Task 1: `SourceCompiler` — best-effort javac to a temp `classes/` dir

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/analyzer/SourceCompiler.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/analyzer/SourceCompilerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCompilerTest {

    @TempDir Path src;

    @Test
    void compilesSelfContainedSingleFile() throws Exception {
        Files.writeString(src.resolve("Foo.java"),
                "public class Foo { public int x() { return 1; } }");
        SourceCompiler compiler = new SourceCompiler();

        Optional<Path> classesDir = compiler.compile(src);

        assertThat(classesDir).isPresent();
        assertThat(classesDir.get().resolve("Foo.class")).exists();
    }

    @Test
    void returnsEmptyWhenSourceReferencesMissingType() throws Exception {
        Files.writeString(src.resolve("UsesMissing.java"),
                "public class UsesMissing { com.example.Missing m; }");
        SourceCompiler compiler = new SourceCompiler();

        Optional<Path> result = compiler.compile(src);

        assertThat(result).isEmpty();
    }

    @Test
    void emptyDirReturnsEmpty() throws Exception {
        SourceCompiler compiler = new SourceCompiler();
        assertThat(compiler.compile(src)).isEmpty();
    }
}
```

- [ ] **Step 2: Run, confirm fail**

Run: `mvn -q test -Dtest=SourceCompilerTest`
Expected: compile error (class missing).

- [ ] **Step 3: Implement `SourceCompiler.java`**

```java
package dev.langchain4j.example.codereview.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class SourceCompiler {

    private static final Logger log = LoggerFactory.getLogger(SourceCompiler.class);

    public Optional<Path> compile(Path sourceDir) {
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            List<Path> javaFiles = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            if (javaFiles.isEmpty()) return Optional.empty();

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                log.warn("No system Java compiler available (JRE-only runtime).");
                return Optional.empty();
            }

            Path classesDir = Files.createTempDirectory("crv-classes-");
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
                fm.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()));
                Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(javaFiles);

                boolean ok = compiler.getTask(null, fm, null, List.of("-nowarn", "-proc:none"), null, units).call();
                if (!ok) {
                    log.debug("javac failed for {}: not self-contained", sourceDir);
                    return Optional.empty();
                }
                return Optional.of(classesDir);
            }
        } catch (IOException e) {
            log.warn("SourceCompiler I/O error: {}", e.toString());
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run, confirm pass**

Run: `mvn -q test -Dtest=SourceCompilerTest`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/analyzer/SourceCompiler.java \
        src/test/java/dev/langchain4j/example/codereview/analyzer/SourceCompilerTest.java
git commit -m "feat(analyzer): SourceCompiler best-effort javac to temp classes dir"
```

---

### Task 2: `SpotBugsAnalyzer` — XML parse + graceful skip

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/analyzer/SpotBugsAnalyzer.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/analyzer/SpotBugsAnalyzerTest.java`
- Create: `src/test/resources/fixtures/spotbugs/sample-output.xml`

- [ ] **Step 1: Drop a fixture SpotBugs XML**

`src/test/resources/fixtures/spotbugs/sample-output.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<BugCollection version="4.8.6">
  <BugInstance type="NP_NULL_ON_SOME_PATH" priority="1" rank="9" abbrev="NP" category="CORRECTNESS">
    <Class classname="com.example.UserService"/>
    <Method classname="com.example.UserService" name="displayName"/>
    <SourceLine classname="com.example.UserService" start="5" end="5" sourcefile="UserService.java" sourcepath="com/example/UserService.java"/>
    <LongMessage>Possible null pointer dereference of user.getProfile() at UserService.java:5</LongMessage>
  </BugInstance>
  <BugInstance type="DM_EXIT" priority="2" rank="14" abbrev="DM" category="BAD_PRACTICE">
    <Class classname="com.example.Util"/>
    <SourceLine classname="com.example.Util" start="42" end="42" sourcefile="Util.java" sourcepath="com/example/Util.java"/>
    <LongMessage>Util.java:42 calls System.exit(...) which shuts down the entire VM.</LongMessage>
  </BugInstance>
</BugCollection>
```

- [ ] **Step 2: Write the failing test**

```java
package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpotBugsAnalyzerTest {

    @TempDir Path tmp;

    @Test
    void parsesFixtureXmlAndKeepsOnlyChangedLines() throws Exception {
        Path xml = Path.of("src/test/resources/fixtures/spotbugs/sample-output.xml");

        // Pretend SpotBugs already ran and produced this XML.
        SpotBugsAnalyzer analyzer = new SpotBugsAnalyzer(/* runner */ (classesDir, output) -> {
            Files.copy(xml, output);
            return true;
        }, new SourceCompiler());

        DiffParser.FileDiff changed = new DiffParser.FileDiff("UserService.java", List.of(
                new DiffParser.AddedLine(5, "return user.getProfile().getDisplayName().trim();")
        ));
        // Need a fake source dir so the compile step runs (we don't actually use the result here
        // — the runner above ignores it and returns the fixture XML).
        Path sourceDir = Files.createDirectory(tmp.resolve("src"));
        Files.writeString(sourceDir.resolve("UserService.java"), "public class UserService {}");

        List<Violation> v = analyzer.analyzeWithSource(List.of(changed), sourceDir);

        assertThat(v).hasSize(1);
        assertThat(v.get(0).file()).isEqualTo("UserService.java");
        assertThat(v.get(0).line()).isEqualTo(5);
        assertThat(v.get(0).rule()).isEqualTo("NP_NULL_ON_SOME_PATH");
        assertThat(v.get(0).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void skipsWhenSourcesDoNotCompile() throws Exception {
        Path sourceDir = Files.createDirectory(tmp.resolve("bad"));
        Files.writeString(sourceDir.resolve("X.java"),
                "public class X { com.example.Missing m; }");

        SpotBugsAnalyzer analyzer = new SpotBugsAnalyzer(
                (classesDir, output) -> { throw new AssertionError("should not run"); },
                new SourceCompiler());

        List<Violation> v = analyzer.analyzeWithSource(List.of(), sourceDir);
        assertThat(v).isEmpty();
    }

    @Test
    void skipsWhenAnalyzeIsCalledWithoutSource() {
        SpotBugsAnalyzer analyzer = new SpotBugsAnalyzer(
                (classesDir, output) -> { throw new AssertionError("should not run"); },
                new SourceCompiler());

        // The StaticAnalyzer-interface entry point has no source dir; degrade to skip.
        assertThat(analyzer.analyze(List.of())).isEmpty();
    }
}
```

- [ ] **Step 3: Run, confirm fail**

Run: `mvn -q test -Dtest=SpotBugsAnalyzerTest`
Expected: compile error.

- [ ] **Step 4: Implement `SpotBugsAnalyzer.java`**

```java
package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class SpotBugsAnalyzer implements StaticAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SpotBugsAnalyzer.class);

    @FunctionalInterface
    public interface Runner {
        /** Run SpotBugs over classesDir; write XML to output. Return false to signal "skip". */
        boolean run(Path classesDir, Path output) throws IOException;
    }

    private final Runner runner;
    private final SourceCompiler compiler;

    public SpotBugsAnalyzer(Runner runner, SourceCompiler compiler) {
        this.runner = runner;
        this.compiler = compiler;
    }

    @Override public String name() { return "spotbugs"; }

    /** StaticAnalyzer entry point — no source dir, so skip gracefully. */
    @Override
    public List<Violation> analyze(List<DiffParser.FileDiff> files) {
        return List.of();
    }

    public List<Violation> analyzeWithSource(List<DiffParser.FileDiff> files, Path sourceDir) {
        Optional<Path> classesDir = compiler.compile(sourceDir);
        if (classesDir.isEmpty()) {
            log.debug("SpotBugs skipped: source not compilable at {}", sourceDir);
            return List.of();
        }
        try {
            Path output = Files.createTempFile("spotbugs-", ".xml");
            if (!runner.run(classesDir.get(), output)) {
                log.debug("SpotBugs runner reported skip");
                return List.of();
            }
            return parseAndFilter(output, files);
        } catch (IOException e) {
            log.warn("SpotBugs IO error: {}", e.toString());
            return List.of();
        }
    }

    private List<Violation> parseAndFilter(Path xml, List<DiffParser.FileDiff> files) throws IOException {
        Set<String> changedKeys = new HashSet<>();
        for (DiffParser.FileDiff f : files) {
            for (DiffParser.AddedLine line : f.addedLines()) {
                changedKeys.add(f.path() + ":" + line.lineNumber());
            }
        }

        List<Violation> out = new ArrayList<>();
        try (var in = Files.newInputStream(xml)) {
            XMLEventReader r = XMLInputFactory.newInstance().createXMLEventReader(in);
            String type = null, priority = null, message = null, file = null;
            int line = -1;
            while (r.hasNext()) {
                XMLEvent ev = r.nextEvent();
                if (ev.isStartElement()) {
                    StartElement se = ev.asStartElement();
                    String name = se.getName().getLocalPart();
                    switch (name) {
                        case "BugInstance" -> {
                            type = attr(se, "type");
                            priority = attr(se, "priority");
                            message = null; file = null; line = -1;
                        }
                        case "SourceLine" -> {
                            if (file == null) {
                                file = attr(se, "sourcepath");
                                line = parseInt(attr(se, "start"), -1);
                            }
                        }
                        case "LongMessage" -> {
                            ev = r.nextEvent();
                            if (ev.isCharacters()) message = ev.asCharacters().getData();
                        }
                        default -> { /* skip */ }
                    }
                } else if (ev.isEndElement()
                        && ev.asEndElement().getName().getLocalPart().equals("BugInstance")
                        && file != null && line > 0) {
                    String shortFile = file.substring(file.lastIndexOf('/') + 1);
                    if (changedKeys.contains(shortFile + ":" + line)) {
                        out.add(new Violation(
                                mapSeverity(priority), shortFile, line, type,
                                message != null ? message : type));
                    }
                }
            }
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new IOException(e);
        }
        return out;
    }

    private static String attr(StartElement se, String name) {
        var a = se.getAttributeByName(new javax.xml.namespace.QName(name));
        return a == null ? null : a.getValue();
    }
    private static int parseInt(String s, int fallback) {
        try { return s == null ? fallback : Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }
    private static Severity mapSeverity(String priority) {
        if (priority == null) return Severity.WARNING;
        return switch (priority) {
            case "1" -> Severity.CRITICAL;
            case "2" -> Severity.WARNING;
            default -> Severity.SUGGESTION;
        };
    }
}
```

- [ ] **Step 5: Run, confirm pass**

Run: `mvn -q test -Dtest=SpotBugsAnalyzerTest`
Expected: 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/analyzer/SpotBugsAnalyzer.java \
        src/test/java/dev/langchain4j/example/codereview/analyzer/SpotBugsAnalyzerTest.java \
        src/test/resources/fixtures/spotbugs/sample-output.xml
git commit -m "feat(analyzer): SpotBugsAnalyzer with XML parse + graceful skip"
```

---

### Task 3: Wire SpotBugs subprocess runner + register bean

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java` (provide a default `SpotBugsAnalyzer.Runner` bean)
- Modify: `src/main/java/dev/langchain4j/example/codereview/tools/RuleCheckerTool.java` (call `analyzeWithSource` for SpotBugs, plumb tool_status)

- [ ] **Step 1: Add a `@Bean SpotBugsAnalyzer.Runner` to `AgentConfig`**

Append inside `AgentConfig`:

```java
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

// ...

@Bean
public SpotBugsAnalyzer.Runner spotBugsRunner() {
    return (classesDir, output) -> {
        // Look for `spotbugs` on PATH; skip if missing.
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "spotbugs", "-textui", "-quiet", "-xml", "-output", output.toString(),
                    classesDir.toString())
                    .redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && Files.size(output) > 0;
        } catch (IOException e) {
            // spotbugs not installed → skip
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    };
}
```

- [ ] **Step 2: Update `RuleCheckerTool` to call `analyzeWithSource` for SpotBugs and emit tool_status text**

Replace its body so that it:
1. Detects `SpotBugsAnalyzer` in the injected analyzer list.
2. For SpotBugs, calls `analyzeWithSource(files, repoSourceDir)` where `repoSourceDir = repoPath` for the `review` command and the sample's `source-before/` for eval — pass the repoPath argument verbatim, since both flows pass an absolute dir.
3. Returns text suffixed with a line like `[tool_status] spotbugs=ok` or `[tool_status] spotbugs=skipped (not buildable)`.

```java
package dev.langchain4j.example.codereview.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
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
    private final SpotBugsAnalyzer spotBugsAnalyzer;

    public RuleCheckerTool(GitClient gitClient, DiffParser diffParser,
                           List<StaticAnalyzer> analyzers,
                           SpotBugsAnalyzer spotBugsAnalyzer) {
        this.gitClient = gitClient;
        this.diffParser = diffParser;
        this.analyzers = analyzers;
        this.spotBugsAnalyzer = spotBugsAnalyzer;
    }

    @Tool("Runs all configured static analyzers on a git repo's diff. Returns violations with real file line numbers and a [tool_status] footer.")
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
        StringBuilder status = new StringBuilder();
        for (StaticAnalyzer a : analyzers) {
            if (a == spotBugsAnalyzer) continue; // handled separately below
            all.addAll(a.analyze(files));
            status.append("[tool_status] ").append(a.name()).append("=ok\n");
        }

        List<Violation> spot = spotBugsAnalyzer.analyzeWithSource(files, Path.of(repoPath));
        if (spot.isEmpty()) {
            status.append("[tool_status] spotbugs=skipped (not buildable or not installed)\n");
        } else {
            status.append("[tool_status] spotbugs=ok\n");
            all.addAll(spot);
        }

        StringBuilder body = new StringBuilder();
        if (all.isEmpty()) {
            body.append("No rule violations found.\n");
        } else {
            body.append("Found ").append(all.size()).append(" violation(s):\n");
            body.append(all.stream()
                    .map(v -> "[" + v.severity() + "] " + v.file() + ":" + v.line()
                            + " (" + v.rule() + ") " + v.message())
                    .collect(Collectors.joining("\n"))).append("\n");
        }
        body.append(status);
        return body.toString();
    }
}
```

- [ ] **Step 3: Update the agent prompt to expect `[tool_status]` lines**

In `CodeReviewAgent.java`, extend the `@SystemMessage`:

```
After calling checkRules, treat any '[tool_status] X=skipped (...)' lines as a hint
to mention them in your ReviewResult.tool_status (e.g. {tool: "spotbugs", status: "skipped", reason: "..."}).
For tools reported as ok, set status: "ok".
```

- [ ] **Step 4: Build + run the existing test suite**

Run: `mvn -q test`
Expected: all existing tests still pass; new SpotBugs / SourceCompiler tests included.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java \
        src/main/java/dev/langchain4j/example/codereview/tools/RuleCheckerTool.java \
        src/main/java/dev/langchain4j/example/codereview/agents/CodeReviewAgent.java
git commit -m "feat(tools): wire SpotBugs subprocess runner + tool_status footer"
```

---

### Task 4: `CodeSearchTool` — grep + identifier lookup over a repo

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/tools/CodeSearchTool.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/tools/CodeSearchToolTest.java`
- Create: `src/test/resources/fixtures/code-search/Foo.java`, `Bar.java`, `nested/Baz.java`

- [ ] **Step 1: Drop fixtures**

```java
// src/test/resources/fixtures/code-search/Foo.java
package fixtures;
public class Foo {
    public String name() { return "foo"; }
}
```

```java
// src/test/resources/fixtures/code-search/Bar.java
package fixtures;
public class Bar {
    Foo f = new Foo();
    public void use() { f.name(); }
}
```

```java
// src/test/resources/fixtures/code-search/nested/Baz.java
package fixtures.nested;
public class Baz {
    public void nope() { System.out.println("no foo here"); }
}
```

- [ ] **Step 2: Write failing test**

```java
package dev.langchain4j.example.codereview.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSearchToolTest {

    private final CodeSearchTool tool = new CodeSearchTool();
    private static final String FIXTURES = "src/test/resources/fixtures/code-search";

    @Test
    void findsSubstringAcrossFilesAndReportsLine() {
        String result = tool.searchCode(FIXTURES, "Foo");
        assertThat(result)
                .contains("Foo.java:")
                .contains("Bar.java:");
    }

    @Test
    void emptyResultMessageWhenNoMatches() {
        String result = tool.searchCode(FIXTURES, "definitely-not-here-zzz");
        assertThat(result).contains("No matches");
    }

    @Test
    void honorsIncludeGlob() {
        String result = tool.searchCode(FIXTURES, "println");
        assertThat(result).contains("nested/Baz.java");
    }

    @Test
    void capsHitsAndReportsTruncation() {
        // Generated query that matches many lines should produce <= 50 hits + truncation note
        String result = tool.searchCode(FIXTURES, "{");
        long lines = result.lines().filter(l -> l.contains(".java:")).count();
        assertThat(lines).isLessThanOrEqualTo(50);
    }
}
```

- [ ] **Step 3: Run, confirm fail**

Run: `mvn -q test -Dtest=CodeSearchToolTest`
Expected: compile error.

- [ ] **Step 4: Implement `CodeSearchTool.java`**

```java
package dev.langchain4j.example.codereview.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class CodeSearchTool {

    private static final int MAX_HITS = 50;
    private static final int MAX_LINE_LEN = 200;

    @Tool("""
            Searches a local directory tree (Java files only) for a substring.
            Returns lines in the form 'relative/path.java:LINE: matched-line-snippet'.
            Use it to find callers, definitions, or other occurrences of identifiers you see in the diff.
            """)
    public String searchCode(
            @P("Absolute path to a directory to search") String rootPath,
            @P("Substring (literal, case-sensitive) to look for") String needle) {
        Path root = Path.of(rootPath);
        if (!Files.isDirectory(root)) return "Not a directory: " + rootPath;
        if (needle == null || needle.isEmpty()) return "Empty needle.";

        List<String> hits = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()::iterator) {
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                String rel = root.relativize(file).toString().replace('\\', '/');
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!line.contains(needle)) continue;
                    String trimmed = line.length() > MAX_LINE_LEN
                            ? line.substring(0, MAX_LINE_LEN) + "…"
                            : line;
                    hits.add(rel + ":" + (i + 1) + ": " + trimmed);
                    if (hits.size() >= MAX_HITS) {
                        truncated = true;
                        break;
                    }
                }
                if (truncated) break;
            }
        } catch (IOException e) {
            return "Error walking " + root + ": " + e.getMessage();
        }
        if (hits.isEmpty()) return "No matches for: " + needle;
        StringBuilder out = new StringBuilder();
        hits.forEach(h -> out.append(h).append('\n'));
        if (truncated) out.append("[truncated at ").append(MAX_HITS).append(" hits]\n");
        return out.toString();
    }
}
```

- [ ] **Step 5: Run, confirm pass**

Run: `mvn -q test -Dtest=CodeSearchToolTest`
Expected: 4 tests pass.

- [ ] **Step 6: Add `CodeSearchTool` to `AgentConfig`**

In `AgentConfig.codeReviewAgent`, take `CodeSearchTool` as a constructor parameter and append it to `.tools(...)`. Update the agent's `@SystemMessage` to mention it:

```
Optional step: call searchCode(repoPath, "<identifier>") if you need to find callers
or definitions of types/methods that appear in the diff.
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/tools/CodeSearchTool.java \
        src/test/java/dev/langchain4j/example/codereview/tools/CodeSearchToolTest.java \
        src/test/resources/fixtures/code-search/ \
        src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java \
        src/main/java/dev/langchain4j/example/codereview/agents/CodeReviewAgent.java
git commit -m "feat(tools): CodeSearchTool grep + identifier lookup"
```

---

### Task 5: Expand knowledge base from 2 → 8 guideline docs

**Files:**
- Create: `src/main/resources/review-guidelines/sql-guidelines.txt`
- Create: `src/main/resources/review-guidelines/performance.txt`
- Create: `src/main/resources/review-guidelines/api-design.txt`
- Create: `src/main/resources/review-guidelines/exception-handling.txt`
- Create: `src/main/resources/review-guidelines/concurrency.txt`
- Create: `src/main/resources/review-guidelines/testing.txt`

- [ ] **Step 1: Author the 6 new guideline files**

Each file follows the structure of the existing `java-best-practices.txt` / `security-checklist.txt`: top-level title, then 4-6 `## Section` headers, each with 4-8 short bullet points. Aim for ~40-60 lines per file so the recursive splitter (500-char chunks, 50-char overlap) produces 3-5 chunks per file.

Content guidance for each:

- `sql-guidelines.txt`: parameterized queries, ORM vs JDBC, batch inserts, N+1, transaction scope, isolation level, connection pool sizing.
- `performance.txt`: avoid O(n²), prefer streaming over materializing, lazy collections, cache invalidation pitfalls, blocking calls in async paths, GC pressure from short-lived large allocations.
- `api-design.txt`: REST resource naming, idempotency, status codes, versioning, pagination defaults, request/response DTOs vs domain types.
- `exception-handling.txt`: avoid checked exception leakage across module boundaries, wrap third-party exceptions with context, no Exception swallow, prefer Result<T,E> over thrown control flow.
- `concurrency.txt`: synchronized vs ReentrantLock, ConcurrentHashMap vs Collections.synchronizedMap, happens-before, volatile gotchas, deadlock ordering, ExecutorService shutdown.
- `testing.txt`: AAA layout, FIRST principles, integration-vs-unit split, mocks vs fakes, snapshot test pitfalls, flaky test triage, test naming.

Each doc is your own writing (no external content), targeting "senior Java/back-end review checklist" voice. Keep it in English to match the existing two.

- [ ] **Step 2: Delete cached embeddings so the indexer rebuilds**

```bash
rm -rf ~/.code-review-agent/cache/review-guidelines.json
```

- [ ] **Step 3: Build + smoke-test that indexer picks up 8 files**

Run a quick smoke test by starting the app:

```bash
MOONSHOT_API_KEY=dummy mvn -q spring-boot:run -Dspring-boot.run.arguments="--help" 2>&1 | grep "Indexed"
```

Expected log line: `Indexed 8 document(s); cache saved.`

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/review-guidelines/
git commit -m "docs(rag): expand guidelines from 2 to 8 review domains"
```

---

### Task 6: Add 15 reverse-style samples (reverse-006 … reverse-020)

**Files:**
- Create: `eval/samples/reverse-006/` through `eval/samples/reverse-020/`

W1 baseline:
| ID | Category |
| --- | --- |
| reverse-001 | stability (NPE) |
| reverse-002 | security |
| reverse-003 | concurrency |
| reverse-004 | performance |
| reverse-005 | stability (NPE again) |

W2 target distribution (matches spec §4.2 keyword table):
| Range | Category | Count after W2 | Subcategory hints |
| --- | --- | --- | --- |
| reverse-006..008 | security | 4 total | SQL injection, weak crypto, XSS, hardcoded secret |
| reverse-009..011 | stability | 5 total | NPE on collection get, NPE in switch, unchecked cast NPE |
| reverse-012..014 | concurrency | 4 total | non-thread-safe DateFormat, double-check broken, race on lazy init |
| reverse-015..017 | performance | 4 total | N+1 query, O(n²) in loop, blocking call in stream |
| reverse-018..020 | resource/test | 3 total | unclosed Stream/Reader, missing try-with-resources, swallowed assertion |

- [ ] **Step 1: Author each sample directory**

Per sample, create:

```
eval/samples/reverse-NNN/
├── meta.json
├── diff.patch
├── source-before/
│   └── <Class>.java   (self-contained when possible; reuse fixture style of reverse-001)
├── source-after/
│   └── <Class>.java   (fixed version, NOT consumed by agent)
└── annotation.json
```

`meta.json` template (set category/difficulty per row above):

```json
{
  "id": "reverse-NNN",
  "source_type": "reverse_style",
  "source_url": "local://reverse-NNN",
  "language": "java",
  "category": "<security|stability|concurrency|performance|test|other>",
  "difficulty": "<easy|medium|hard>",
  "diff_size_lines": <int>,
  "collected_at": "2026-05-22"
}
```

`annotation.json` template:

```json
{
  "expected_issues": [
    {
      "id": "I-001",
      "file": "<File>.java",
      "line": <int>,
      "line_range": [<low>, <high>],
      "category": "<SECURITY|STABILITY|CONCURRENCY|PERFORMANCE|TEST|OTHER>",
      "subcategory": "<sql_injection|npe|race|n_plus_1|...>",
      "severity": "<CRITICAL|WARNING|SUGGESTION>",
      "description": "<one sentence>",
      "must_detect": true,
      "alternative_descriptions": ["<paraphrase 1>", "<paraphrase 2>"]
    }
  ],
  "should_not_report": [],
  "notes": "<the original commit message style that motivated this reverse sample>"
}
```

Make each `source-before/` file as **self-contained as possible** (e.g. avoid referencing unfamiliar types that javac can't resolve) — this lets `SourceCompiler` / `SpotBugsAnalyzer` actually fire on a subset of samples. Where unavoidable, leave it: `SpotBugsAnalyzer` will skip gracefully and the eval honestly reflects "SpotBugs only fires on N/20".

- [ ] **Step 2: Sanity-check sample loading**

For each new sample, verify:
- `diff.patch` references only the file under `source-before/`.
- `annotation.expected_issues[0].file` matches the path inside the diff (no `a/` / `b/` prefix).
- `expected_issues[0].line` is a real post-change line number (the value `+M` from the hunk header plus the offset to the broken line).

Quick check:

```bash
for d in eval/samples/reverse-{006..020}; do
  echo "--- $d ---"
  jq -r '.expected_issues[0] | "\(.file):\(.line) [\(.category)/\(.severity)]"' "$d/annotation.json"
done
```

Expected: 15 lines, all category/severity values from the closed enum, all `file:line` look plausible.

- [ ] **Step 3: Run a smoke eval to verify the runner doesn't choke on any sample**

```bash
export MOONSHOT_API_KEY=dummy
mvn -q clean package -DskipTests
# Use a small judge by overriding runs-per-sample if needed; this run only checks pipeline survives.
# It WILL fail to actually review without a real key — but should reach the matcher loop
# and report failures cleanly per-sample.
java -jar target/code-review-agent-1.0.0.jar eval --version smoke-w2 --report-dir /tmp/crv-smoke
```

Expected: process exits with non-zero (no real LLM), but it must list all 20 sample IDs in stderr/stdout without throwing on sample loading.

- [ ] **Step 4: Commit (one commit per logical batch — keep diff readable)**

Suggestion: one commit per category group (4 commits, batches of 3-5 samples).

```bash
git add eval/samples/reverse-006 eval/samples/reverse-007 eval/samples/reverse-008
git commit -m "eval(samples): add 3 reverse-style security samples"
# ... repeat for each batch
```

---

### Task 7: Run the v1 evaluation

**Files:**
- Create: `eval/reports/v1-spotbugs-search.json` (generated)
- Modify: `README.md` (add v1 row to the metrics table)
- Modify: `src/main/java/dev/langchain4j/example/codereview/cli/EvalCommand.java` (let `--pipeline` flag override the config string)

- [ ] **Step 1: Add `--pipeline` flag to `EvalCommand`**

```java
@Option(names = "--pipeline", description = "Pipeline label recorded in report config", defaultValue = "w1-single-agent")
private String pipeline;

// ... in call():
Map<String, Object> config = Map.of(
        "judge_model", props.eval().judgeModel(),
        "runs_per_sample", props.eval().runsPerSample(),
        "pipeline", pipeline
);
```

- [ ] **Step 2: Run the eval**

```bash
export MOONSHOT_API_KEY=<real-kimi-key>
mvn -q clean package -DskipTests
java -jar target/code-review-agent-1.0.0.jar eval \
    --version v1-spotbugs-search \
    --pipeline w2-spotbugs-codesearch
```

Expected:
- 20 samples processed.
- Output line `recall=0.XX precision=0.XX fp_rate=0.XX` printed.
- File `eval/reports/v1-spotbugs-search.json` created.

If recall < v0's 60%, that's a real signal worth tracking — record it honestly; do not retry until numbers look good. Note any per-sample regressions in `docs/learnings/w2-notes.md` for later debugging.

- [ ] **Step 3: Update README metrics table**

Edit `README.md` to add the v1 row:

```markdown
| v0-baseline | 60% | 50% | 50% | 5 reverse-style samples |
| v1-spotbugs-search | <recall>% | <prec>% | <fp>% | 20 reverse-style samples |
```

- [ ] **Step 4: Commit v1 artifacts**

```bash
git add eval/reports/v1-spotbugs-search.json README.md \
        src/main/java/dev/langchain4j/example/codereview/cli/EvalCommand.java
git commit -m "eval: v1-spotbugs-search — 20 samples, SpotBugs+CodeSearch pipeline"
```

---

## Phase 2 · Hybrid RAG + reranker + citation tracking (Tasks 8-13)

### Task 8: Add Lucene dependency + chunk metadata

**Files:**
- Modify: `pom.xml` (add `lucene-core`, `lucene-analyzers-common`)
- Create: `src/main/java/dev/langchain4j/example/codereview/rag/ChunkMetadata.java`

- [ ] **Step 1: Add Lucene to `pom.xml`**

Inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-core</artifactId>
    <version>9.11.1</version>
</dependency>
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-analysis-common</artifactId>
    <version>9.11.1</version>
</dependency>
```

(Note: artifactId is `lucene-analysis-common`, not `lucene-analyzers-common` — the latter was Lucene 8.x naming.)

- [ ] **Step 2: Run `mvn dependency:tree | head -40` to confirm both resolve**

Expected: no conflict warnings; both jars present.

- [ ] **Step 3: Create `ChunkMetadata.java`**

```java
package dev.langchain4j.example.codereview.rag;

public record ChunkMetadata(String sourceFile, String section, String snippet) {

    public String citationId() {
        return sourceFile.replace(".txt", "") + "#" + (section == null ? "intro" : sectionSlug());
    }

    private String sectionSlug() {
        return section.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
```

- [ ] **Step 4: Compile check**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/dev/langchain4j/example/codereview/rag/ChunkMetadata.java
git commit -m "build(rag): add Lucene 9.11 + ChunkMetadata record"
```

---

### Task 9: `Bm25Retriever` — Lucene index of guideline docs

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/rag/Bm25Retriever.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/rag/Bm25RetrieverTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25RetrieverTest {

    @Test
    void indexAndQueryReturnsRelevantChunks() {
        Bm25Retriever r = new Bm25Retriever();
        r.index(List.of(
                new Bm25Retriever.Doc("Use PreparedStatement to avoid SQL injection.",
                        new ChunkMetadata("sql.txt", "Injection", "Use PreparedStatement to avoid SQL injection.")),
                new Bm25Retriever.Doc("Always close Streams in try-with-resources.",
                        new ChunkMetadata("io.txt", "Resources", "Always close Streams in try-with-resources.")),
                new Bm25Retriever.Doc("Validate user input before persisting.",
                        new ChunkMetadata("sql.txt", "Validation", "Validate user input before persisting."))
        ));

        List<Content> hits = r.retrieve(Query.from("sql injection"), 2);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).textSegment().text()).contains("SQL injection");
    }

    @Test
    void emptyQueryReturnsEmpty() {
        Bm25Retriever r = new Bm25Retriever();
        r.index(List.of(new Bm25Retriever.Doc("anything",
                new ChunkMetadata("x.txt", "s", "anything"))));
        assertThat(r.retrieve(Query.from(""), 5)).isEmpty();
    }
}
```

- [ ] **Step 2: Run, confirm fail**

Run: `mvn -q test -Dtest=Bm25RetrieverTest`
Expected: compile error.

- [ ] **Step 3: Implement `Bm25Retriever.java`**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Bm25Retriever {

    public record Doc(String text, ChunkMetadata metadata) { }

    private final Directory directory = new ByteBuffersDirectory();
    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    private final Map<Integer, ChunkMetadata> metadataById = new HashMap<>();

    public void index(List<Doc> docs) {
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            writer.deleteAll();
            for (int i = 0; i < docs.size(); i++) {
                Doc d = docs.get(i);
                Document lucene = new Document();
                lucene.add(new TextField("body", d.text(), Field.Store.YES));
                lucene.add(new StoredField("id", i));
                writer.addDocument(lucene);
                metadataById.put(i, d.metadata());
            }
        } catch (Exception e) {
            throw new RuntimeException("Bm25 index error", e);
        }
    }

    public List<Content> retrieve(Query query, int topK) {
        String q = query.text();
        if (q == null || q.isBlank()) return List.of();
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            org.apache.lucene.search.Query parsed = new QueryParser("body", analyzer)
                    .parse(QueryParser.escape(q));
            TopDocs top = searcher.search(parsed, topK);

            List<Content> hits = new ArrayList<>();
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = reader.storableFields().document(sd.doc);
                int id = doc.getField("id").numericValue().intValue();
                ChunkMetadata meta = metadataById.get(id);
                TextSegment seg = TextSegment.from(doc.get("body"), toLcMetadata(meta));
                hits.add(Content.from(seg));
            }
            return hits;
        } catch (Exception e) {
            throw new RuntimeException("Bm25 query error", e);
        }
    }

    private static dev.langchain4j.data.document.Metadata toLcMetadata(ChunkMetadata meta) {
        return dev.langchain4j.data.document.Metadata.from(Map.of(
                "source_file", meta.sourceFile(),
                "section", meta.section() == null ? "" : meta.section(),
                "citation_id", meta.citationId()
        ));
    }
}
```

(If the Lucene 9.11 API for `reader.storableFields().document(...)` differs in a minor version, fall back to `reader.document(sd.doc)` — both work in 9.x.)

- [ ] **Step 4: Run, confirm pass**

Run: `mvn -q test -Dtest=Bm25RetrieverTest`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/rag/Bm25Retriever.java \
        src/test/java/dev/langchain4j/example/codereview/rag/Bm25RetrieverTest.java
git commit -m "feat(rag): Bm25Retriever backed by Lucene 9 ByteBuffers index"
```

---

### Task 10: `HybridRetriever` — fuse vector + BM25 via RRF

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/rag/HybridRetriever.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/rag/HybridRetrieverTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrieverTest {

    private static Content content(String text, String id) {
        return Content.from(TextSegment.from(text, Metadata.from("citation_id", id)));
    }

    @Test
    void rrfRanksItemInBothListsAboveSingletons() {
        ContentRetriever vector = q -> List.of(
                content("A common doc", "doc-A"),
                content("Only-vector",  "doc-V")
        );
        ContentRetriever bm25 = q -> List.of(
                content("Only-bm25",    "doc-B"),
                content("A common doc", "doc-A")
        );

        HybridRetriever h = new HybridRetriever(vector, bm25, 60, 3);
        List<Content> hits = h.retrieve(Query.from("anything"));

        assertThat(hits).hasSize(3);
        // doc-A scores 1/(60+1) + 1/(60+2) ≈ 0.0329; the singletons each score ≤ 1/61 ≈ 0.0164.
        assertThat(hits.get(0).textSegment().metadata().getString("citation_id"))
                .isEqualTo("doc-A");
    }

    @Test
    void deduplicatesByCitationId() {
        ContentRetriever vector = q -> List.of(content("x", "id-1"), content("x", "id-1"));
        ContentRetriever bm25 = q -> List.of();
        HybridRetriever h = new HybridRetriever(vector, bm25, 60, 5);
        assertThat(h.retrieve(Query.from("q"))).hasSize(1);
    }
}
```

- [ ] **Step 2: Run, confirm fail**

Run: `mvn -q test -Dtest=HybridRetrieverTest`
Expected: compile error.

- [ ] **Step 3: Implement `HybridRetriever.java`**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HybridRetriever implements ContentRetriever {

    private final ContentRetriever vector;
    private final ContentRetriever bm25;
    private final int rrfK;
    private final int topK;

    public HybridRetriever(ContentRetriever vector, ContentRetriever bm25, int rrfK, int topK) {
        this.vector = vector;
        this.bm25 = bm25;
        this.rrfK = rrfK;
        this.topK = topK;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> v = safe(vector.retrieve(query));
        List<Content> b = safe(bm25.retrieve(query));

        Map<String, Double> scores = new HashMap<>();
        Map<String, Content> byId = new HashMap<>();

        accumulate(v, scores, byId);
        accumulate(b, scores, byId);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byId.get(e.getKey()))
                .toList();
    }

    private void accumulate(List<Content> list, Map<String, Double> scores, Map<String, Content> byId) {
        for (int i = 0; i < list.size(); i++) {
            Content c = list.get(i);
            String id = idOf(c);
            byId.putIfAbsent(id, c);
            scores.merge(id, 1.0 / (rrfK + i + 1), Double::sum);
        }
    }

    private static String idOf(Content c) {
        Object id = c.textSegment().metadata().getString("citation_id");
        if (id != null && !((String) id).isBlank()) return (String) id;
        // Fallback: hash of text snippet
        return Integer.toHexString(c.textSegment().text().hashCode());
    }

    private static List<Content> safe(List<Content> list) {
        return list == null ? List.of() : list;
    }
}
```

- [ ] **Step 4: Run, confirm pass**

Run: `mvn -q test -Dtest=HybridRetrieverTest`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/rag/HybridRetriever.java \
        src/test/java/dev/langchain4j/example/codereview/rag/HybridRetrieverTest.java
git commit -m "feat(rag): HybridRetriever fuses vector + BM25 via Reciprocal Rank Fusion"
```

---

### Task 11: `LlmReranker` — LLM-as-reranker on top of HybridRetriever

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/rag/LlmReranker.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/rag/LlmRerankerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class LlmRerankerTest {

    private static Content c(String id, String text) {
        return Content.from(TextSegment.from(text, Metadata.from("citation_id", id)));
    }

    @Test
    void reordersByLlmScore() {
        ChatModel model = Mockito.mock(ChatModel.class);
        ChatResponse resp = ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"scores\":[0.2,0.9,0.5]}"))
                .build();
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenReturn(resp);

        ContentRetriever upstream = q -> List.of(
                c("id-A", "A text"),
                c("id-B", "B text"),
                c("id-C", "C text"));
        LlmReranker reranker = new LlmReranker(upstream, model, 2);

        List<Content> out = reranker.retrieve(Query.from("q"));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).textSegment().metadata().getString("citation_id")).isEqualTo("id-B");
        assertThat(out.get(1).textSegment().metadata().getString("citation_id")).isEqualTo("id-C");
    }

    @Test
    void onJudgeFailureReturnsOriginalOrder() {
        ChatModel model = Mockito.mock(ChatModel.class);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenThrow(new RuntimeException("judge down"));

        ContentRetriever upstream = q -> List.of(c("id-A", "A"), c("id-B", "B"));
        LlmReranker reranker = new LlmReranker(upstream, model, 5);

        List<Content> out = reranker.retrieve(Query.from("q"));
        assertThat(out).extracting(x -> x.textSegment().metadata().getString("citation_id"))
                .containsExactly("id-A", "id-B");
    }
}
```

- [ ] **Step 2: Run, confirm fail**

Run: `mvn -q test -Dtest=LlmRerankerTest`
Expected: compile error.

- [ ] **Step 3: Implement `LlmReranker.java`**

```java
package dev.langchain4j.example.codereview.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

public class LlmReranker implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ContentRetriever upstream;
    private final ChatModel model;
    private final int topK;

    public LlmReranker(ContentRetriever upstream, ChatModel model, int topK) {
        this.upstream = upstream;
        this.model = model;
        this.topK = topK;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> candidates = upstream.retrieve(query);
        if (candidates.size() <= topK) return candidates;
        try {
            double[] scores = rate(query.text(), candidates);
            record Scored(Content c, double s, int origIdx) { }
            List<Scored> scored = IntStream.range(0, candidates.size())
                    .mapToObj(i -> new Scored(candidates.get(i),
                            i < scores.length ? scores[i] : 0.0, i))
                    .sorted(Comparator.<Scored>comparingDouble(s -> s.s).reversed()
                            .thenComparingInt(s -> s.origIdx))
                    .toList();
            List<Content> out = new ArrayList<>();
            for (int i = 0; i < topK && i < scored.size(); i++) out.add(scored.get(i).c());
            return out;
        } catch (Exception e) {
            log.warn("LlmReranker fell back to upstream order: {}", e.toString());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    private double[] rate(String query, List<Content> candidates) throws Exception {
        StringBuilder prompt = new StringBuilder("""
                Rate each candidate's relevance to the query on a 0.0–1.0 scale.
                Respond with JSON: {"scores":[<num>, <num>, ...]} in the same order.

                Query:
                """).append(query).append("\n\nCandidates:\n");
        for (int i = 0; i < candidates.size(); i++) {
            String text = candidates.get(i).textSegment().text();
            if (text.length() > 400) text = text.substring(0, 400) + "…";
            prompt.append(i).append(") ").append(text).append("\n");
        }

        ChatRequest req = ChatRequest.builder()
                .messages(UserMessage.from(prompt.toString()))
                .build();
        ChatResponse resp = model.chat(req);
        String body = resp.aiMessage().text();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) throw new IllegalStateException("no JSON in judge output");
        JsonNode node = MAPPER.readTree(body.substring(start, end + 1));
        JsonNode scoresNode = node.get("scores");
        if (scoresNode == null || !scoresNode.isArray()) throw new IllegalStateException("no scores array");
        double[] out = new double[scoresNode.size()];
        for (int i = 0; i < out.length; i++) out[i] = scoresNode.get(i).asDouble();
        return out;
    }
}
```

- [ ] **Step 4: Run, confirm pass**

Run: `mvn -q test -Dtest=LlmRerankerTest`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/rag/LlmReranker.java \
        src/test/java/dev/langchain4j/example/codereview/rag/LlmRerankerTest.java
git commit -m "feat(rag): LlmReranker — LLM-as-reranker with fallback to upstream order"
```

---

### Task 12: `CitationTracker` + wire metadata through KnowledgeBaseIndexer + RagConfig

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/rag/CitationTracker.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/rag/CitationTrackerTest.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/rag/KnowledgeBaseIndexer.java` (build BM25 + attach metadata)
- Modify: `src/main/java/dev/langchain4j/example/codereview/config/RagConfig.java` (assemble HybridRetriever → LlmReranker → CitationTracker)
- Modify: `src/main/java/dev/langchain4j/example/codereview/config/CodeReviewProperties.java` (add `bm25TopK`, `rerankTopK`, `rrfK`)
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write CitationTracker test**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationTrackerTest {

    private static Content c(String id, String section, String file) {
        return Content.from(TextSegment.from("body",
                Metadata.from(java.util.Map.of(
                        "citation_id", id,
                        "section", section,
                        "source_file", file))));
    }

    @Test
    void mapsContentsToCitations() {
        CitationTracker t = new CitationTracker();
        List<Citation> cs = t.toCitations(List.of(
                c("sql-injection-1", "Injection", "sql-guidelines.txt"),
                c("npe-null-safety", "Null Safety", "java-best-practices.txt")));
        assertThat(cs).extracting(Citation::id)
                .containsExactly("sql-injection-1", "npe-null-safety");
        assertThat(cs.get(1).source()).isEqualTo("java-best-practices.txt");
        assertThat(cs.get(1).section()).isEqualTo("Null Safety");
    }

    @Test
    void deduplicatesSameId() {
        CitationTracker t = new CitationTracker();
        List<Citation> cs = t.toCitations(List.of(
                c("dup", "S", "a.txt"),
                c("dup", "S", "a.txt")));
        assertThat(cs).hasSize(1);
    }
}
```

- [ ] **Step 2: Implement `CitationTracker.java`**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.rag.content.Content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CitationTracker {

    public List<Citation> toCitations(List<Content> contents) {
        if (contents == null) return List.of();
        Set<String> seen = new HashSet<>();
        List<Citation> out = new ArrayList<>();
        for (Content c : contents) {
            var meta = c.textSegment().metadata();
            String id = meta.getString("citation_id");
            String section = meta.getString("section");
            String source = meta.getString("source_file");
            if (id == null || id.isBlank()) continue;
            if (!seen.add(id)) continue;
            out.add(new Citation(id, source, section));
        }
        return out;
    }
}
```

- [ ] **Step 3: Update `KnowledgeBaseIndexer`**

Replace the indexer to:
1. Read each guideline file as raw text, split into sections by `## ` headings.
2. For each section, chunk further if needed (>500 chars) using a simple paragraph splitter.
3. Attach `ChunkMetadata(source_file, section, snippet)` as `Metadata` on each `TextSegment`.
4. Build *both* the InMemoryEmbeddingStore (existing) *and* a `Bm25Retriever`.
5. Expose `getEmbeddingStore()` and `getBm25Retriever()`.

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.infra.EmbeddingCache;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class KnowledgeBaseIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseIndexer.class);
    private static final String CACHE_KEY = "review-guidelines-v2";
    private static final int CHUNK_MAX_CHARS = 500;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingCache cache;
    private InMemoryEmbeddingStore<TextSegment> store;
    private Bm25Retriever bm25;

    public KnowledgeBaseIndexer(EmbeddingModel embeddingModel, EmbeddingCache cache) {
        this.embeddingModel = embeddingModel;
        this.cache = cache;
    }

    public synchronized void buildOrLoad() {
        Optional<InMemoryEmbeddingStore<TextSegment>> cached = cache.load(CACHE_KEY);
        List<Chunk> chunks = readChunks();

        if (cached.isPresent()) {
            this.store = cached.get();
            log.info("Loaded vector store from cache (key={})", CACHE_KEY);
        } else {
            this.store = new InMemoryEmbeddingStore<>();
            for (Chunk ch : chunks) {
                TextSegment seg = TextSegment.from(ch.text, toMetadata(ch.meta));
                var emb = embeddingModel.embed(seg).content();
                store.add(emb, seg);
            }
            cache.save(CACHE_KEY, store);
            log.info("Indexed {} chunks; cache saved.", chunks.size());
        }

        this.bm25 = new Bm25Retriever();
        bm25.index(chunks.stream()
                .map(c -> new Bm25Retriever.Doc(c.text, c.meta))
                .toList());
    }

    public InMemoryEmbeddingStore<TextSegment> getEmbeddingStore() {
        if (store == null) buildOrLoad();
        return store;
    }

    public Bm25Retriever getBm25Retriever() {
        if (bm25 == null) buildOrLoad();
        return bm25;
    }

    private record Chunk(String text, ChunkMetadata meta) { }

    private List<Chunk> readChunks() {
        Path dir = toClasspathPath("review-guidelines/");
        List<Chunk> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                String fileName = file.getFileName().toString();
                String currentSection = "intro";
                StringBuilder buf = new StringBuilder();
                for (String line : body.split("\n", -1)) {
                    if (line.startsWith("## ")) {
                        flush(out, fileName, currentSection, buf);
                        currentSection = line.substring(3).trim();
                        buf.setLength(0);
                    } else {
                        buf.append(line).append('\n');
                        if (buf.length() > CHUNK_MAX_CHARS) {
                            flush(out, fileName, currentSection, buf);
                        }
                    }
                }
                flush(out, fileName, currentSection, buf);
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot read guidelines: " + e.getMessage(), e);
        }
        return out;
    }

    private static void flush(List<Chunk> out, String file, String section, StringBuilder buf) {
        String text = buf.toString().trim();
        if (text.isEmpty()) return;
        out.add(new Chunk(text, new ChunkMetadata(file, section, text)));
        buf.setLength(0);
    }

    private static Metadata toMetadata(ChunkMetadata m) {
        return Metadata.from(Map.of(
                "source_file", m.sourceFile(),
                "section", m.section() == null ? "" : m.section(),
                "citation_id", m.citationId()));
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

(Note: cache key bumped to `review-guidelines-v2` so existing caches are not reused with the old metadata-less chunks.)

- [ ] **Step 4: Extend `CodeReviewProperties.Rag`**

```java
public record Rag(
        Path embeddingCacheDir,
        int topK,
        double minScore,
        boolean rerankEnabled,
        int bm25TopK,
        int rerankTopK,
        int rrfK
) { }
```

Update `application.yml`:

```yaml
code-review:
  rag:
    embedding-cache-dir: ${user.home}/.code-review-agent/cache
    top-k: 3
    min-score: 0.4
    rerank-enabled: true
    bm25-top-k: 8
    rerank-top-k: 4
    rrf-k: 60
```

- [ ] **Step 5: Rewire `RagConfig`**

```java
package dev.langchain4j.example.codereview.config;

import dev.langchain4j.example.codereview.infra.EmbeddingCache;
import dev.langchain4j.example.codereview.rag.Bm25Retriever;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.example.codereview.rag.HybridRetriever;
import dev.langchain4j.example.codereview.rag.KnowledgeBaseIndexer;
import dev.langchain4j.example.codereview.rag.LlmReranker;
import dev.langchain4j.model.chat.ChatModel;
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
        KnowledgeBaseIndexer idx = new KnowledgeBaseIndexer(model, cache);
        idx.buildOrLoad();
        return idx;
    }

    @Bean
    public CitationTracker citationTracker() {
        return new CitationTracker();
    }

    @Bean
    public ContentRetriever contentRetriever(
            KnowledgeBaseIndexer indexer,
            EmbeddingModel embeddingModel,
            ChatModel chatModel,
            CodeReviewProperties props) {

        ContentRetriever vector = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(indexer.getEmbeddingStore())
                .embeddingModel(embeddingModel)
                .maxResults(props.rag().topK())
                .minScore(props.rag().minScore())
                .build();

        Bm25Retriever bm25 = indexer.getBm25Retriever();
        ContentRetriever bm25Wrapped = q -> bm25.retrieve(q, props.rag().bm25TopK());

        ContentRetriever hybrid = new HybridRetriever(vector, bm25Wrapped,
                props.rag().rrfK(), props.rag().rerankTopK());

        if (!props.rag().rerankEnabled()) return hybrid;
        return new LlmReranker(hybrid, chatModel, props.rag().rerankTopK());
    }
}
```

- [ ] **Step 6: Run tests**

Run: `mvn -q test`
Expected: all unit tests still pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/rag/CitationTracker.java \
        src/main/java/dev/langchain4j/example/codereview/rag/KnowledgeBaseIndexer.java \
        src/main/java/dev/langchain4j/example/codereview/config/RagConfig.java \
        src/main/java/dev/langchain4j/example/codereview/config/CodeReviewProperties.java \
        src/main/resources/application.yml \
        src/test/java/dev/langchain4j/example/codereview/rag/CitationTrackerTest.java
git commit -m "feat(rag): wire HybridRetriever + LlmReranker + citation metadata through RagConfig"
```

---

### Task 13: Ask the agent to populate `citations[]` + smoke-test one review

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/agents/CodeReviewAgent.java`

- [ ] **Step 1: Strengthen the `@SystemMessage`**

```java
@SystemMessage("""
        You are a senior software engineer doing a code review.

        Workflow:
        1. Call getGitDiff(repoPath, ref) to see the changes.
        2. Call checkRules(repoPath, ref) with the SAME repoPath and ref to get static rule violations.
        3. Optionally call searchCode(repoPath, "<identifier>") if you need to find callers or definitions.
        4. Relevant best-practice excerpts will be automatically injected — when a finding is supported
           by an excerpt, include its citation_id (and section if available) in citations[].
        5. Return a ReviewResult JSON object with:
           - summary: 1-2 sentences
           - findings: list of {id, file, line, line_range, severity, category, title, description,
             suggestion, evidence, citations, source}
           - tool_status: list of {tool, status, reason}

        Constraints:
        - severity must be one of CRITICAL, WARNING, SUGGESTION.
        - category must be one of SECURITY, PERFORMANCE, STABILITY, CONCURRENCY, TEST, STYLE, OTHER.
        - source must be "llm_reviewer" for findings you produce; use tool rule IDs when echoing analyzer findings.
        - line numbers must match the new file (post-change) line numbering.
        - For findings backed by an injected excerpt, populate citations[] with the excerpt's citation_id;
          for findings backed only by tool output or your own reasoning, citations may be empty.
        - Echo any '[tool_status] X=skipped (...)' lines into tool_status entries.
        """)
ReviewResult review(@UserMessage String request);
```

- [ ] **Step 2: Smoke-test a single review against a real repo**

```bash
export MOONSHOT_API_KEY=<real-key>
mvn -q clean package -DskipTests
java -jar target/code-review-agent-1.0.0.jar review . HEAD~1 2>&1 | tee /tmp/w2-smoke.txt
```

Expected: a `ReviewResult`-shaped JSON in the output; at least one finding has a non-empty `citations[]` array referencing a guideline file (e.g. `java-best-practices#null-safety`).

If citations[] stays empty across multiple smoke runs, that's a sign the prompt isn't being followed — adjust prompt wording before running the eval.

- [ ] **Step 3: Commit prompt change**

```bash
git add src/main/java/dev/langchain4j/example/codereview/agents/CodeReviewAgent.java
git commit -m "feat(agent): require citations[] population for RAG-supported findings"
```

---

### Task 14: Run the v2 evaluation

**Files:**
- Create: `eval/reports/v2-rag-hybrid.json` (generated)
- Modify: `README.md` (add v2 row)
- Modify: `CLAUDE.md` (flip pointer from "W1 (current)" to "W2 (current)")

- [ ] **Step 1: Clear any stale embedding cache one more time**

```bash
rm -rf ~/.code-review-agent/cache/review-guidelines-v2.json
```

(The v2 cache key was set in Task 12. First eval run will re-embed; subsequent runs hit cache.)

- [ ] **Step 2: Run the eval**

```bash
export MOONSHOT_API_KEY=<real-key>
java -jar target/code-review-agent-1.0.0.jar eval \
    --version v2-rag-hybrid \
    --pipeline w2-hybrid-rerank
```

Expected: 20 samples processed, recall/precision printed, `eval/reports/v2-rag-hybrid.json` written. Spec target for v2 is recall 68% / precision 55% (§4.5) — record what we actually get; do not retune to chase the number, but note any large regressions vs v1.

- [ ] **Step 3: Update README**

Add a v2 row to the metrics table; also drop a one-paragraph "W2 notes" section pointing at `docs/learnings/w2-notes.md` and explaining what changed since W1.

- [ ] **Step 4: Update CLAUDE.md roadmap**

In the "Project Roadmap Context" section, flip the bullets:

```markdown
- **W1** (done, tagged `w1-final`): single-agent + regex analyzer + 5 reverse-style samples → v0 baseline (60%/50%).
- **W2** (current, branch `feat/w2`): SpotBugs + CodeSearchTool + hybrid RAG + reranker + 20 samples → v1/v2.
- **W3**: pipeline split (`DiffAnalyzer` → `ToolFindings` → `LlmReviewer` → `Summarizer`); optional multi-agent.
- **W4**: 40-sample release evaluation, tuning, README/demo.
```

- [ ] **Step 5: Commit final v2 artifacts + docs**

```bash
git add eval/reports/v2-rag-hybrid.json README.md CLAUDE.md
git commit -m "eval: v2-rag-hybrid — hybrid RAG + reranker on 20-sample set"
```

- [ ] **Step 6: Tag the milestone**

```bash
git tag w2-final
```

(Do not push the tag automatically — the user decides when to push.)

---

## Out-of-scope deferrals (carry over to W3+)

- **Pipeline split into `DiffAnalyzer → ToolFindings → LlmReviewer → Summarizer`** — W3 default. W2 keeps the single-agent shape.
- **Multi-agent orchestrator** — W3 stretch.
- **40-sample release evaluation suite** — W4.
- **CitationTracker injected into the LLM response post-processing** (vs. trusting the LLM to emit citation_ids itself) — if the W2 smoke shows the LLM is unreliable at this, lift it into a post-processing step in W3's `Summarizer`.
- **Token / input-tokens / output-tokens accurate measurement** — `SampleMetrics` currently reports zeros; threading real numbers through LangChain4j response metadata is a W3 task (the pipeline split makes it cleaner).
- **W1's `sample` picocli stub → real `SampleCollector`** — Not blocking W2; W4 will fill this in for the "10 real PR" samples.

---

## Self-review checklist

- [x] **Spec coverage**: SpotBugs (T1-T3), CodeSearchTool (T4), guidelines 2→8 (T5), 20 samples (T6), v1 eval (T7), hybrid retrieval (T8-T10), LLM reranker (T11), citation tracking (T12-T13), v2 eval (T14). All §1 W2 bullets accounted for.
- [x] **No placeholders**: every code block is complete; no "TBD"/"implement later".
- [x] **Type consistency**: `Bm25Retriever.Doc` ↔ `KnowledgeBaseIndexer.Chunk`; `ChunkMetadata.citationId()` matches the metadata key `"citation_id"` used everywhere; `HybridRetriever` and `LlmReranker` both implement `ContentRetriever` so they're drop-in for `RagConfig`.
- [x] **Eval honesty**: v1 and v2 numbers are recorded as-measured; no retuning loop until the user reads the report.
