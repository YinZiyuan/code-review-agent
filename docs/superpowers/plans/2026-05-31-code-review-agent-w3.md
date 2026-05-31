# Code Review Agent — W3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stabilize W2 single-agent on real eval (Phase 1, W3a → `v1-spotbugs-search.json` + `v2-rag-hybrid.json`), then split the agent into a deterministic pipeline (Phase 2, W3b → `v3-pipeline.json`).

**Architecture:** Phase 1 keeps the W2 `AiServices` + tool-self-decision shape and wraps the agent with a Java-side `JsonRepair` guard + minimal citation post-processing; Phase 2 removes `@Tool` and `AiServices` entirely and replaces them with four `@Component`s — `DiffAnalyzer` (deterministic identifier-grep over `source-before`), `ToolFindings` (Regex + SpotBugs), `LlmReviewer` (single `ChatModel.chat` call with RAG citations pre-injected), and `Summarizer` (deterministic dedup / fill / sort). `CodeReviewAgent` stays as an interface; `PipelineCodeReviewer` implements it.

**Tech Stack:** Java 17 · Spring Boot 3.5.6 · LangChain4j Spring Boot starter `1.15.0-beta25` · Kimi (`moonshot-v1-8k`) · JUnit 5 + Mockito + AssertJ

**Source spec:** [`docs/superpowers/specs/2026-05-31-code-review-agent-w3-design.md`](../specs/2026-05-31-code-review-agent-w3-design.md)

**Predecessor plan:** [`docs/superpowers/plans/2026-05-22-code-review-agent-w2.md`](2026-05-22-code-review-agent-w2.md)

---

## File Map

**Created in W3a (Phase 1):**

| File | Responsibility |
| --- | --- |
| `src/main/java/.../infra/JsonRepair.java` | Java-side capture-and-repair for LLM JSON drift; exposes `parseOrRepair(String, Class<T>) → T` and `repairThenParse(String, Class<T>) → T` |
| `src/main/java/.../agents/GuardedCodeReviewAgent.java` | `@Primary` `CodeReviewAgent` Bean wrapping AiServices proxy with JsonRepair + minimal citation injection |
| `src/main/java/.../rag/CitationKeywordInjector.java` | Keyword-match citation back-fill (W3a minimal version) |
| `src/main/java/.../rag/RetrievalRecorder.java` | Per-review `ThreadLocal` capture of which `Content`s were retrieved, so the guard knows which citations are candidates |
| `eval/README.md` | Reproducible commands for v0/v1/v2/v3 |

**Modified in W3a:**

| File | Change |
| --- | --- |
| `src/main/resources/application.yml` | `langchain4j.open-ai.chat-model.timeout` 60s → 90s |
| `src/main/java/.../config/AgentConfig.java` | `codeReviewAgent` Bean becomes the raw AiServices proxy (renamed to `aiServicesCodeReviewAgent`, not `@Primary`); add `GuardedCodeReviewAgent` `@Primary` Bean that wraps it |
| `src/main/java/.../config/RagConfig.java` | Wrap the final `ContentRetriever` in a `RecordingContentRetriever` that pushes hits to `RetrievalRecorder` before returning |
| `src/main/java/.../cli/EvalCommand.java` | Add `--suite`, `--samples` options; clear `DEBUG` system property on entry |
| `src/main/java/.../eval/EvaluationRunner.java` | Optional sample-id filter; per-sample retry on `ResourceAccessException` / `HttpTimeoutException` |
| `eval/reports/v1-spotbugs-search.json` | Run + commit |
| `eval/reports/v2-rag-hybrid.json` | Run + commit |

**Created in W3b (Phase 2):**

| File | Responsibility |
| --- | --- |
| `src/main/java/.../agents/pipeline/ReviewContext.java` | Immutable record carrying `rawDiff`, `fileDiffs`, `contextByFile`, `sourceRoot` |
| `src/main/java/.../agents/pipeline/CodeSnippet.java` | Record `(file, line, text)` used inside `ReviewContext.contextByFile` |
| `src/main/java/.../agents/pipeline/DiffAnalyzer.java` | Deterministic: parse diff, extract identifier candidates, grep `sourceRoot` via `CodeSearchTool` core method |
| `src/main/java/.../agents/pipeline/ToolFindings.java` | Record `(violations, statuses)` produced by `ToolFindingsProducer` |
| `src/main/java/.../agents/pipeline/ToolFindingsProducer.java` | Runs Regex + SpotBugs, fills `ToolFindings` |
| `src/main/java/.../agents/pipeline/LlmReviewer.java` | Single `ChatModel.chat` call; uses `HybridRetriever` + `CitationTracker` to build prompt with bounded citation candidate IDs; parses via `JsonRepair` |
| `src/main/java/.../agents/pipeline/Summarizer.java` | Deterministic dedup, fill missing-finding from violations, fill missing-citation, sort, emit final `ReviewResult` |
| `src/main/java/.../agents/pipeline/PipelineCodeReviewer.java` | `@Primary` `CodeReviewAgent` Bean wiring the four pipeline components |

**Modified in W3b:**

| File | Change |
| --- | --- |
| `src/main/java/.../agents/CodeReviewAgent.java` | Remove `@SystemMessage`; extract prompt string to a public constant for `LlmReviewer` |
| `src/main/java/.../tools/CodeSearchTool.java` | Remove `@Tool` annotation; rename `searchCode` to `grep(rootPath, needle)` for clarity (keep same logic) |
| `src/main/java/.../tools/RuleCheckerTool.java` | DELETE — its logic lives in `ToolFindingsProducer` |
| `src/main/java/.../tools/GitDiffTool.java` | Remove `@Tool` annotation (logic kept; CLI still uses it via direct method call) |
| `src/main/java/.../config/AgentConfig.java` | Delete the AiServices builder + `GuardedCodeReviewAgent`; `PipelineCodeReviewer` becomes the sole `CodeReviewAgent` Bean |
| `src/main/java/.../eval/EvaluationRunner.java` | No change to the loop; deprecated `[tool_status]` parsing path is now unused but kept |
| `eval/reports/v3-pipeline.json` | Run + commit |

**Test files (mirror main):**

| File | What it tests |
| --- | --- |
| `src/test/java/.../infra/JsonRepairTest.java` | Valid JSON pass-through; broken JSON repaired via mock ChatModel; repair-still-broken → re-throw |
| `src/test/java/.../agents/GuardedCodeReviewAgentTest.java` | Underlying agent throws `OutputParsingException` → guard invokes repair and succeeds; citation injection runs when finding has empty `citations[]` |
| `src/test/java/.../rag/CitationKeywordInjectorTest.java` | Section keyword in finding text → citation injected; unrelated finding stays empty |
| `src/test/java/.../rag/RetrievalRecorderTest.java` | Records pushed during retrieve, cleared on `clear()`, isolated per thread |
| `src/test/java/.../cli/EvalCommandTest.java` | `--samples reverse-001,reverse-002` filters runner input; `DEBUG` system property is cleared |
| `src/test/java/.../agents/pipeline/DiffAnalyzerTest.java` | Identifier extraction from `+`-lines; missing `sourceRoot` → empty `contextByFile` but no throw |
| `src/test/java/.../agents/pipeline/ToolFindingsProducerTest.java` | Regex + SpotBugs merged; SpotBugs unavailable → `statuses` contains `skipped` row |
| `src/test/java/.../agents/pipeline/LlmReviewerTest.java` | Mock ChatModel returns valid JSON → draft `ReviewResult`; broken JSON → JsonRepair path |
| `src/test/java/.../agents/pipeline/SummarizerTest.java` | Dedup near-duplicate findings; backfill CRITICAL violation as finding; backfill citation by keyword; severity-then-file-then-line sort |
| `src/test/java/.../agents/pipeline/PipelineCodeReviewerIT.java` | `@SpringBootTest`: mock ChatModel + real retrievers; full pipeline produces `ReviewResult` with tool_status, merged findings, citations filled |

**Test fixtures:**

| File | Used for |
| --- | --- |
| `src/test/resources/fixtures/json-repair/broken.json` | Hand-authored malformed `ReviewResult` (missing comma) |
| `src/test/resources/fixtures/json-repair/repaired.json` | Expected repaired form |
| `src/test/resources/fixtures/pipeline/sample-diff.patch` | Small reverse-001-style diff for IT |
| `src/test/resources/fixtures/pipeline/source-before/Foo.java` | Tiny source tree for IT's `DiffAnalyzer` to grep |

**Docs:**

| File | Change |
| --- | --- |
| `docs/learnings/w3-notes.md` | Per-task tech / design / Q&A notes (mirror W2 style) |
| `README.md` | Replace W2-current with W3-current; add v3 row to metrics table; replace architecture sketch with pipeline diagram |
| `CLAUDE.md` | Move "W2 (current)" → "W3 (current)"; promote pipeline narrative from future to present tense |

---

## Phase 1 · W3a — single-agent stabilization (Tasks 1-7)

### Task 1: `JsonRepair` — parse-or-repair LLM JSON

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/infra/JsonRepair.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/infra/JsonRepairTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package dev.langchain4j.example.codereview.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JsonRepairTest {

    record Box(String a, int b) { }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void valid_json_is_parsed_without_calling_llm() {
        ChatModel model = mock(ChatModel.class);
        JsonRepair repair = new JsonRepair(model, mapper);

        Box result = repair.parseOrRepair("{\"a\":\"x\",\"b\":1}", Box.class);

        assertThat(result).isEqualTo(new Box("x", 1));
    }

    @Test
    void broken_json_is_repaired_via_llm() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"a\":\"x\",\"b\":1}"))
                        .build());
        JsonRepair repair = new JsonRepair(model, mapper);

        Box result = repair.parseOrRepair("{\"a\":\"x\" \"b\":1}", Box.class);

        assertThat(result).isEqualTo(new Box("x", 1));
    }

    @Test
    void repair_still_broken_rethrows() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("still broken {"))
                        .build());
        JsonRepair repair = new JsonRepair(model, mapper);

        assertThatThrownBy(() -> repair.parseOrRepair("not json at all", Box.class))
                .isInstanceOf(JsonRepair.RepairFailedException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=JsonRepairTest test`
Expected: FAIL with "JsonRepair not defined" / cannot find symbol

- [ ] **Step 3: Implement `JsonRepair`**

```java
package dev.langchain4j.example.codereview.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonRepair {

    private static final Logger log = LoggerFactory.getLogger(JsonRepair.class);

    private final ChatModel model;
    private final ObjectMapper mapper;

    public JsonRepair(ChatModel model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
    }

    public <T> T parseOrRepair(String raw, Class<T> type) {
        try {
            return mapper.readValue(raw, type);
        } catch (JsonProcessingException first) {
            log.warn("JSON parse failed ({}); attempting repair", first.getOriginalMessage());
            String repaired = askForRepair(raw);
            try {
                return mapper.readValue(repaired, type);
            } catch (JsonProcessingException second) {
                throw new RepairFailedException(
                        "Repair did not produce valid JSON: " + second.getOriginalMessage(),
                        second);
            }
        }
    }

    private String askForRepair(String raw) {
        String prompt = """
                The following text was supposed to be a single JSON object but failed to parse.
                Return ONLY the corrected JSON — no prose, no markdown fences.
                Do NOT add, remove, or change semantic content; only fix syntax (quotes, commas, escapes).

                Broken JSON:
                """ + raw;
        var response = model.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build());
        String body = response.aiMessage().text();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end < start) {
            return body;
        }
        return body.substring(start, end + 1);
    }

    public static class RepairFailedException extends RuntimeException {
        public RepairFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -Dtest=JsonRepairTest test`
Expected: PASS — all 3 tests green

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/infra/JsonRepair.java \
        src/test/java/dev/langchain4j/example/codereview/infra/JsonRepairTest.java
git commit -m "feat(infra): JsonRepair parse-or-repair guard for LLM JSON drift"
```

---

### Task 2: `RetrievalRecorder` — per-review capture of RAG hits

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/rag/RetrievalRecorder.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/rag/RetrievalRecorderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalRecorderTest {

    @Test
    void records_and_clears_per_thread() {
        RetrievalRecorder recorder = new RetrievalRecorder();
        Content c = Content.from(TextSegment.from("hello",
                Metadata.from(Map.of("citation_id", "x#y"))));

        recorder.record(List.of(c));
        assertThat(recorder.snapshot()).containsExactly(c);

        recorder.clear();
        assertThat(recorder.snapshot()).isEmpty();
    }

    @Test
    void thread_isolated() throws Exception {
        RetrievalRecorder recorder = new RetrievalRecorder();
        Content c = Content.from(TextSegment.from("hello",
                Metadata.from(Map.of("citation_id", "x#y"))));

        Thread t = new Thread(() -> recorder.record(List.of(c)));
        t.start();
        t.join();

        assertThat(recorder.snapshot()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=RetrievalRecorderTest test`
Expected: FAIL — `RetrievalRecorder` undefined

- [ ] **Step 3: Implement `RetrievalRecorder`**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RetrievalRecorder {

    private final ThreadLocal<List<Content>> hits = ThreadLocal.withInitial(ArrayList::new);

    public void record(List<Content> contents) {
        if (contents != null) {
            hits.get().addAll(contents);
        }
    }

    public List<Content> snapshot() {
        return List.copyOf(hits.get());
    }

    public void clear() {
        hits.remove();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -Dtest=RetrievalRecorderTest test`
Expected: PASS — 2 tests green

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/rag/RetrievalRecorder.java \
        src/test/java/dev/langchain4j/example/codereview/rag/RetrievalRecorderTest.java
git commit -m "feat(rag): RetrievalRecorder ThreadLocal for per-review RAG hits"
```

---

### Task 3: `CitationKeywordInjector` — minimal back-fill of empty citations

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/rag/CitationKeywordInjector.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/rag/CitationKeywordInjectorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationKeywordInjectorTest {

    private final CitationKeywordInjector injector = new CitationKeywordInjector();

    @Test
    void section_keyword_in_finding_text_injects_citation() {
        Citation candidate = new Citation("sql-guidelines#parameterized-queries",
                "sql-guidelines.txt", "Parameterized Queries");
        ReviewFinding finding = finding("SQL injection via concatenation",
                "Use parameterized queries instead", List.of());

        List<ReviewFinding> out = injector.inject(List.of(finding), List.of(candidate));

        assertThat(out.get(0).citations()).containsExactly(candidate);
    }

    @Test
    void existing_citation_is_preserved_not_replaced() {
        Citation existing = new Citation("security-checklist#sql-001",
                "security-checklist.txt", "SQL Injection");
        Citation candidate = new Citation("sql-guidelines#parameterized-queries",
                "sql-guidelines.txt", "Parameterized Queries");
        ReviewFinding finding = finding("SQL injection",
                "Use parameterized queries", List.of(existing));

        List<ReviewFinding> out = injector.inject(List.of(finding), List.of(candidate));

        assertThat(out.get(0).citations()).containsExactly(existing);
    }

    @Test
    void unrelated_finding_stays_empty() {
        Citation candidate = new Citation("sql-guidelines#parameterized-queries",
                "sql-guidelines.txt", "Parameterized Queries");
        ReviewFinding finding = finding("Race condition on counter",
                "Use AtomicLong", List.of());

        List<ReviewFinding> out = injector.inject(List.of(finding), List.of(candidate));

        assertThat(out.get(0).citations()).isEmpty();
    }

    private ReviewFinding finding(String title, String description, List<Citation> citations) {
        return new ReviewFinding("F-001", "Foo.java", 10, new int[]{10, 12},
                Severity.WARNING, Category.SECURITY, title, description,
                "fix it", "evidence", citations, "llm_reviewer");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=CitationKeywordInjectorTest test`
Expected: FAIL — class undefined

- [ ] **Step 3: Implement `CitationKeywordInjector`**

```java
package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class CitationKeywordInjector {

    public List<ReviewFinding> inject(List<ReviewFinding> findings, List<Citation> candidates) {
        if (findings == null || findings.isEmpty() || candidates == null || candidates.isEmpty()) {
            return findings == null ? List.of() : findings;
        }
        List<ReviewFinding> out = new ArrayList<>(findings.size());
        for (ReviewFinding f : findings) {
            if (f.citations() != null && !f.citations().isEmpty()) {
                out.add(f);
                continue;
            }
            String haystack = (safe(f.title()) + " " + safe(f.description())).toLowerCase(Locale.ROOT);
            List<Citation> matched = new ArrayList<>();
            for (Citation c : candidates) {
                if (matches(haystack, c.section())) {
                    matched.add(c);
                }
            }
            out.add(new ReviewFinding(f.id(), f.file(), f.line(), f.lineRange(),
                    f.severity(), f.category(), f.title(), f.description(),
                    f.suggestion(), f.evidence(), matched, f.source()));
        }
        return out;
    }

    private boolean matches(String haystack, String section) {
        if (section == null || section.isBlank()) return false;
        String s = section.toLowerCase(Locale.ROOT);
        for (String word : s.split("[^a-z0-9]+")) {
            if (word.length() >= 4 && haystack.contains(word)) {
                return true;
            }
        }
        return Arrays.stream(haystack.split("[^a-z0-9]+"))
                .anyMatch(w -> w.length() >= 4 && s.contains(w));
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -Dtest=CitationKeywordInjectorTest test`
Expected: PASS — 3 tests green

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/rag/CitationKeywordInjector.java \
        src/test/java/dev/langchain4j/example/codereview/rag/CitationKeywordInjectorTest.java
git commit -m "feat(rag): CitationKeywordInjector minimal back-fill for empty citations"
```

---

### Task 4: `GuardedCodeReviewAgent` — wrap AiServices with JsonRepair + citation injection

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/agents/GuardedCodeReviewAgent.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/config/RagConfig.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/agents/GuardedCodeReviewAgentTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.rag.CitationKeywordInjector;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.example.codereview.rag.RetrievalRecorder;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.output.OutputParsingException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardedCodeReviewAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void output_parsing_exception_triggers_repair_with_raw_text() {
        CodeReviewAgent inner = mock(CodeReviewAgent.class);
        JsonRepair repair = mock(JsonRepair.class);
        RetrievalRecorder recorder = new RetrievalRecorder();
        CitationTracker tracker = new CitationTracker();
        CitationKeywordInjector injector = new CitationKeywordInjector();

        String rawText = "{\"summary\":\"x\",\"findings\":[],\"tool_status\":[]}";
        when(inner.review("req"))
                .thenThrow(new OutputParsingException(rawText, new RuntimeException("boom")));
        when(repair.parseOrRepair(rawText, ReviewResult.class))
                .thenReturn(new ReviewResult("x", List.of(), List.of()));

        GuardedCodeReviewAgent guard = new GuardedCodeReviewAgent(inner, repair, recorder, tracker, injector);

        ReviewResult out = guard.review("req");

        assertThat(out.summary()).isEqualTo("x");
    }

    @Test
    void empty_citations_are_backfilled_from_recorded_hits() {
        CodeReviewAgent inner = mock(CodeReviewAgent.class);
        JsonRepair repair = mock(JsonRepair.class);
        RetrievalRecorder recorder = new RetrievalRecorder();
        CitationTracker tracker = new CitationTracker();
        CitationKeywordInjector injector = new CitationKeywordInjector();

        ReviewFinding finding = new ReviewFinding("F-001", "Foo.java", 10, null,
                Severity.WARNING, Category.SECURITY,
                "SQL injection via concatenation", "Use parameterized queries",
                "fix", "evidence", List.of(), "llm_reviewer");
        when(inner.review("req"))
                .thenReturn(new ReviewResult("s", List.of(finding), List.of()));

        recorder.record(List.of(Content.from(TextSegment.from(
                "Parameterized queries content...",
                Metadata.from(Map.of(
                        "citation_id", "sql-guidelines#parameterized-queries",
                        "source_file", "sql-guidelines.txt",
                        "section", "Parameterized Queries"))))));

        GuardedCodeReviewAgent guard = new GuardedCodeReviewAgent(inner, repair, recorder, tracker, injector);

        ReviewResult out = guard.review("req");

        assertThat(out.findings().get(0).citations()).hasSize(1);
        assertThat(out.findings().get(0).citations().get(0).id())
                .isEqualTo("sql-guidelines#parameterized-queries");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=GuardedCodeReviewAgentTest test`
Expected: FAIL — `GuardedCodeReviewAgent` undefined

- [ ] **Step 3: Implement `GuardedCodeReviewAgent`**

```java
package dev.langchain4j.example.codereview.agents;

import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.rag.CitationKeywordInjector;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.example.codereview.rag.RetrievalRecorder;
import dev.langchain4j.service.output.OutputParsingException;

import java.util.List;

public class GuardedCodeReviewAgent implements CodeReviewAgent {

    private final CodeReviewAgent inner;
    private final JsonRepair jsonRepair;
    private final RetrievalRecorder recorder;
    private final CitationTracker tracker;
    private final CitationKeywordInjector injector;

    public GuardedCodeReviewAgent(CodeReviewAgent inner, JsonRepair jsonRepair,
                                  RetrievalRecorder recorder, CitationTracker tracker,
                                  CitationKeywordInjector injector) {
        this.inner = inner;
        this.jsonRepair = jsonRepair;
        this.recorder = recorder;
        this.tracker = tracker;
        this.injector = injector;
    }

    @Override
    public ReviewResult review(String request) {
        recorder.clear();
        ReviewResult result;
        try {
            result = inner.review(request);
        } catch (OutputParsingException e) {
            result = jsonRepair.parseOrRepair(e.text(), ReviewResult.class);
        }
        List<Citation> candidates = tracker.toCitations(recorder.snapshot());
        List<ReviewFinding> updated = injector.inject(result.findings(), candidates);
        recorder.clear();
        return new ReviewResult(result.summary(), updated, result.toolStatus());
    }
}
```

Note: `OutputParsingException.text()` returns the raw model output. If the LangChain4j version's accessor differs, adapt accordingly (use `e.getMessage()` only as a last resort — it includes framing).

- [ ] **Step 4: Wire JsonRepair, GuardedCodeReviewAgent in `AgentConfig`**

Modify `src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java`. Rename the existing bean method to `aiServicesCodeReviewAgent` and add the primary guarded bean:

```java
@Bean
public JsonRepair jsonRepair(ChatModel chatModel, ObjectMapper mapper) {
    return new JsonRepair(chatModel, mapper);
}

@Bean
public CodeReviewAgent aiServicesCodeReviewAgent(
        ChatModel chatModel,
        ContentRetriever retriever,
        GitDiffTool gitDiffTool,
        RuleCheckerTool ruleCheckerTool,
        CodeSearchTool codeSearchTool) {
    return AiServices.builder(CodeReviewAgent.class)
            .chatModel(chatModel)
            .tools(gitDiffTool, ruleCheckerTool, codeSearchTool)
            .contentRetriever(retriever)
            .build();
}

@Bean
@Primary
public CodeReviewAgent guardedCodeReviewAgent(
        @Qualifier("aiServicesCodeReviewAgent") CodeReviewAgent inner,
        JsonRepair jsonRepair,
        RetrievalRecorder recorder,
        CitationTracker tracker,
        CitationKeywordInjector injector) {
    return new GuardedCodeReviewAgent(inner, jsonRepair, recorder, tracker, injector);
}
```

Required imports (add): `org.springframework.context.annotation.Primary`, `org.springframework.beans.factory.annotation.Qualifier`, `dev.langchain4j.example.codereview.agents.GuardedCodeReviewAgent`, `dev.langchain4j.example.codereview.infra.JsonRepair`, `dev.langchain4j.example.codereview.rag.RetrievalRecorder`, `dev.langchain4j.example.codereview.rag.CitationTracker`, `dev.langchain4j.example.codereview.rag.CitationKeywordInjector`.

- [ ] **Step 5: Wire `RetrievalRecorder` into RAG path**

Modify `src/main/java/dev/langchain4j/example/codereview/config/RagConfig.java` — wrap the final returned retriever:

```java
@Bean
public ContentRetriever contentRetriever(
        KnowledgeBaseIndexer indexer,
        EmbeddingModel embeddingModel,
        ChatModel chatModel,
        CodeReviewProperties props,
        RetrievalRecorder recorder) {
    ContentRetriever vector = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(indexer.buildOrLoad())
            .embeddingModel(embeddingModel)
            .maxResults(props.rag().topK())
            .minScore(props.rag().minScore())
            .build();

    Bm25Retriever bm25 = indexer.getBm25Retriever();
    ContentRetriever bm25Wrapped = query -> bm25.retrieve(query, props.rag().bm25TopK());
    ContentRetriever hybrid = new HybridRetriever(vector, bm25Wrapped,
            props.rag().rrfK(), props.rag().rerankTopK());

    ContentRetriever effective = props.rag().rerankEnabled()
            ? new LlmReranker(hybrid, chatModel, props.rag().rerankTopK())
            : hybrid;

    return query -> {
        List<Content> hits = effective.retrieve(query);
        recorder.record(hits);
        return hits;
    };
}
```

Required imports (add): `dev.langchain4j.rag.content.Content`, `dev.langchain4j.example.codereview.rag.RetrievalRecorder`, `java.util.List`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn -Dtest=GuardedCodeReviewAgentTest test`
Then: `mvn -q test` to ensure no regression in W1/W2 tests
Expected: all green

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/agents/GuardedCodeReviewAgent.java \
        src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java \
        src/main/java/dev/langchain4j/example/codereview/config/RagConfig.java \
        src/test/java/dev/langchain4j/example/codereview/agents/GuardedCodeReviewAgentTest.java
git commit -m "feat(agents): GuardedCodeReviewAgent wraps AiServices with JsonRepair + citation backfill"
```

---

### Task 5: `EvalCommand` env hardening — clear DEBUG, sample filter, suite

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/cli/EvalCommand.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/cli/EvalCommandTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.config.CodeReviewProperties;
import dev.langchain4j.example.codereview.eval.EvalReport;
import dev.langchain4j.example.codereview.eval.EvaluationRunner;
import dev.langchain4j.example.codereview.eval.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalCommandTest {

    @TempDir Path tmp;

    @BeforeEach void setDebug() { System.setProperty("debug", "release"); }
    @AfterEach void clear() { System.clearProperty("debug"); }

    @Test
    void clears_debug_system_property_and_passes_filter() throws Exception {
        EvaluationRunner runner = mock(EvaluationRunner.class);
        ArgumentCaptor<Set<String>> filterCap = ArgumentCaptor.forClass(Set.class);
        when(runner.run(any(), any(), anyString(), any(), filterCap.capture()))
                .thenReturn(emptyReport());

        CodeReviewProperties props = new CodeReviewProperties(
                new CodeReviewProperties.Rag(tmp, 3, 0.4, false, 8, 4, 60),
                new CodeReviewProperties.Orchestration(java.time.Duration.ofSeconds(60), 3),
                new CodeReviewProperties.Eval("m", 1, tmp, tmp));
        EvalCommand cmd = new EvalCommand(runner, props);
        setField(cmd, "version", "v-test");
        setField(cmd, "pipeline", "p");
        setField(cmd, "samplesCsv", "reverse-001,reverse-002");

        cmd.call();

        assertThat(System.getProperty("debug")).isNull();
        assertThat(filterCap.getValue()).containsExactlyInAnyOrder("reverse-001", "reverse-002");
        verify(runner).run(eq(tmp), eq(tmp), eq("v-test"), any(), any());
    }

    private EvalReport emptyReport() {
        return new EvalReport("v-test", "abc", null, "t", Map.of(), List.of(), Map.of(), List.of());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=EvalCommandTest test`
Expected: FAIL — `runner.run(...)` 5-arg signature does not exist; `samplesCsv` field does not exist

- [ ] **Step 3: Update `EvaluationRunner.run` to accept a sample-id filter + per-sample timeout retry**

In `src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java`:

3a) Add overload and forward:

```java
public EvalReport run(Path samplesDir, Path reportsDir, String version,
                      Map<String, Object> config) throws IOException {
    return run(samplesDir, reportsDir, version, config, null);
}

public EvalReport run(Path samplesDir, Path reportsDir, String version,
                      Map<String, Object> config, Set<String> sampleIdFilter) throws IOException {
    List<Path> sampleDirs = listSampleDirs(samplesDir);
    if (sampleIdFilter != null && !sampleIdFilter.isEmpty()) {
        sampleDirs = sampleDirs.stream()
                .filter(p -> sampleIdFilter.contains(p.getFileName().toString()))
                .toList();
    }
    // ... rest unchanged, using the filtered sampleDirs
}
```

Add `import java.util.Set;`. Update the existing list-iteration body to use the filtered list, keeping all metrics/report logic identical.

3b) In `evaluateOne`, replace the single `agent.review(request)` call with a one-shot retry on timeout-like exceptions:

```java
private ReviewResult callAgentWithRetry(String request, String sampleId) {
    try {
        return agent.review(request);
    } catch (RuntimeException e) {
        if (!isRetryable(e)) {
            throw e;
        }
        log.warn("Sample {} review timed out; retrying once: {}", sampleId, e.toString());
        return agent.review(request);
    }
}

private static boolean isRetryable(Throwable t) {
    for (Throwable cur = t; cur != null; cur = cur.getCause()) {
        String name = cur.getClass().getName();
        if (name.equals("java.net.http.HttpTimeoutException")
                || name.equals("java.net.SocketTimeoutException")
                || name.equals("org.springframework.web.client.ResourceAccessException")) {
            return true;
        }
    }
    return false;
}
```

Then in `evaluateOne`, replace `result = agent.review(request);` with `result = callAgentWithRetry(request, sample.id());`.

- [ ] **Step 4: Update `EvalCommand`**

Replace the body of `src/main/java/dev/langchain4j/example/codereview/cli/EvalCommand.java`:

```java
package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.config.CodeReviewProperties;
import dev.langchain4j.example.codereview.eval.EvalReport;
import dev.langchain4j.example.codereview.eval.EvaluationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Component
@Command(name = "eval",
        description = "Run evaluation suite against PR samples",
        sortOptions = false)
public class EvalCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(EvalCommand.class);

    @Option(names = "--version", required = true, description = "Version label, e.g. v0-baseline")
    private String version;

    @Option(names = "--samples-dir", description = "Override samples directory")
    private Path samplesOverride;

    @Option(names = "--report-dir", description = "Override reports output directory")
    private Path reportDirOverride;

    @Option(names = "--pipeline",
            description = "Pipeline label recorded in report config",
            defaultValue = "w1-single-agent")
    private String pipeline;

    @Option(names = "--samples",
            description = "Comma-separated sample IDs to include (e.g. reverse-001,reverse-002). "
                    + "Default: all samples in --samples-dir.")
    private String samplesCsv;

    @Option(names = "--suite",
            description = "smoke | dev | release. smoke: first 2 samples; dev: all samples × 1; "
                    + "release: all samples × runs_per_sample. Default: dev.",
            defaultValue = "dev")
    private String suite;

    private final EvaluationRunner runner;
    private final CodeReviewProperties props;

    public EvalCommand(EvaluationRunner runner, CodeReviewProperties props) {
        this.runner = runner;
        this.props = props;
    }

    @Override
    public Integer call() throws Exception {
        if (System.getProperty("debug") != null) {
            log.warn("System property 'debug' detected (value={}); clearing to avoid Spring debug-mode log spam.",
                    System.getProperty("debug"));
            System.clearProperty("debug");
        }

        Path samples = samplesOverride != null ? samplesOverride : props.eval().samplesDir();
        Path reports = reportDirOverride != null ? reportDirOverride : props.eval().reportDir();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("judge_model", props.eval().judgeModel());
        config.put("runs_per_sample", props.eval().runsPerSample());
        config.put("pipeline", pipeline);
        config.put("suite", suite);

        Set<String> filter = parseFilter(samplesCsv, suite, samples);

        EvalReport report = runner.run(samples, reports, version, config, filter);
        System.out.printf("recall=%.2f precision=%.2f fp_rate=%.2f%n",
                report.metrics().get("recall"),
                report.metrics().get("precision"),
                report.metrics().get("fp_rate"));
        return 0;
    }

    private Set<String> parseFilter(String csv, String suite, Path samplesDir) {
        if (csv != null && !csv.isBlank()) {
            return Arrays.stream(csv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        }
        if ("smoke".equalsIgnoreCase(suite)) {
            try (var stream = java.nio.file.Files.list(samplesDir)) {
                return stream.filter(java.nio.file.Files::isDirectory)
                        .sorted()
                        .limit(2)
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            } catch (java.io.IOException e) {
                return Set.of();
            }
        }
        return Set.of();
    }
}
```

Notes:
- The previous `--samples` (path override) is renamed to `--samples-dir` to free the `--samples` name for the CSV filter. Update `eval/README.md` examples in Task 7 to use `--samples-dir`.
- `release` suite differs from `dev` only by `runs_per_sample` (which lives in properties), not by sample filter — so it returns `Set.of()` (no filter).

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -Dtest=EvalCommandTest test`
Then: `mvn -q test`
Expected: all green

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/cli/EvalCommand.java \
        src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java \
        src/test/java/dev/langchain4j/example/codereview/cli/EvalCommandTest.java
git commit -m "feat(eval): clear DEBUG sysprop, --samples filter, --suite flag"
```

---

### Task 6: bump LLM timeout to 90s

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Edit application.yml**

In `src/main/resources/application.yml`, change line 9:

```yaml
      timeout: 60s
```

to:

```yaml
      timeout: 90s
```

- [ ] **Step 2: Run full test suite to confirm no regression**

Run: `mvn -q test`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "chore(config): bump chat-model timeout 60s -> 90s for hybrid RAG eval"
```

---

### Task 7: W3a eval runs — produce v1 / v2 reports + eval/README.md

**Files:**
- Create: `eval/README.md`
- Create: `eval/reports/v1-spotbugs-search.json`
- Create: `eval/reports/v2-rag-hybrid.json`

**Prerequisite:** `MOONSHOT_API_KEY` is set in the environment.

- [ ] **Step 1: Write `eval/README.md`**

```markdown
# Evaluation Quickstart

All commands assume `MOONSHOT_API_KEY` is exported.

## v0 baseline (W1, 5 samples)

Already produced at `eval/reports/v0-baseline.json`. To reproduce on a single sample:

    java -jar target/code-review-agent-1.0.0.jar eval \
        --version v0-baseline \
        --pipeline w1-single-agent \
        --samples reverse-001

## v1 — SpotBugs + CodeSearch (W2 capability, evaluated in W3a, 20 samples)

    rm -f ~/.code-review-agent/cache/review-guidelines-v2.json
    java -jar target/code-review-agent-1.0.0.jar eval \
        --version v1-spotbugs-search \
        --pipeline w2-spotbugs-codesearch \
        --suite dev

Note: v1 turns RAG off-equivalent only conceptually; W3a does NOT add a rerank-off
flag. To run a strict v1 with no RAG hybrid + reranker, temporarily set
`code-review.rag.rerank-enabled: false` and `code-review.rag.bm25-top-k: 0` in
`application.yml`, or revert to commit `4f7469f` (pre-hybrid) before running.

## v2 — Hybrid RAG + LLM reranker (W2 capability, evaluated in W3a, 20 samples)

    rm -f ~/.code-review-agent/cache/review-guidelines-v2.json
    java -jar target/code-review-agent-1.0.0.jar eval \
        --version v2-rag-hybrid \
        --pipeline w2-hybrid-rerank \
        --suite dev

## v3 — Pipeline (W3b, 20 samples)

    java -jar target/code-review-agent-1.0.0.jar eval \
        --version v3-pipeline \
        --pipeline w3-pipeline \
        --suite dev

## Smoke check (any version)

    java -jar target/code-review-agent-1.0.0.jar eval \
        --version smoke \
        --pipeline w2-hybrid-rerank \
        --suite smoke
```

- [ ] **Step 2: Build the fat jar**

Run: `mvn -q clean package -DskipTests`
Expected: BUILD SUCCESS; `target/code-review-agent-1.0.0.jar` exists

- [ ] **Step 3: Run v2 first (current code path)**

Run:

    rm -f ~/.code-review-agent/cache/review-guidelines-v2.json
    java -jar target/code-review-agent-1.0.0.jar eval \
        --version v2-rag-hybrid \
        --pipeline w2-hybrid-rerank \
        --suite dev

Expected: prints recall/precision/fp_rate; `eval/reports/v2-rag-hybrid.json` written. Inspect per-sample list: no row should have `"review error: ..."` in its summary (means JsonRepair worked or wasn't needed).

If review errors remain, return to Task 1 and harden `JsonRepair` (e.g., strip markdown fences, retry once more, etc.); do not proceed.

- [ ] **Step 4: Run v1 (pre-hybrid commit)**

v1 represents the "SpotBugs + CodeSearchTool, no hybrid RAG" capability. Cleanly reproducing it means checking out the pre-hybrid commit, building a separate jar, and running it. Reverting via config is brittle (the W2 retriever pipeline still calls vector retrieval).

In a separate working tree (or worktree) to avoid touching the W3a tree:

```bash
git worktree add /tmp/code-review-v1 4f7469f
cd /tmp/code-review-v1
mvn -q clean package -DskipTests
rm -f ~/.code-review-agent/cache/review-guidelines.json ~/.code-review-agent/cache/review-guidelines-v2.json
env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval \
    --version v1-spotbugs-search \
    --pipeline w2-spotbugs-codesearch
```

(That commit predates W3a's `--suite` flag and the DEBUG-clearing code, so we use `env -u DEBUG` explicitly. Sample count at that commit was the W2-added 20.)

Copy the produced report into the W3 working tree:

```bash
cp /tmp/code-review-v1/eval/reports/v1-spotbugs-search.json \
   <path-to-W3-worktree>/eval/reports/v1-spotbugs-search.json
cd <path-to-W3-worktree>
git worktree remove /tmp/code-review-v1
```

If creating a worktree is not practical (e.g., dirty index), the fallback is config-disable v1: in W3a `application.yml`, temporarily set:

```yaml
code-review:
  rag:
    top-k: 0
    rerank-enabled: false
    bm25-top-k: 0
```

then rebuild and run. After the run, **revert `application.yml`** to `top-k: 3`, `rerank-enabled: true`, `bm25-top-k: 8`. Note that even with `top-k: 0`, the embedding retriever may execute; if so, accept v1 as "RAG present but disabled in config" and document the caveat in the report's `config` block.

- [ ] **Step 5: Commit reports + README**

```bash
git add eval/README.md eval/reports/v1-spotbugs-search.json eval/reports/v2-rag-hybrid.json
git commit -m "eval(w3a): v1 + v2 reports on 20 samples"
git tag w3a-eval-baseline
```

If the tag command fails because tag exists, choose `w3a-eval-baseline-2` or skip tagging.

---

## Phase 2 · W3b — pipeline split (Tasks 8-14)

### Task 8: `ReviewContext` + `CodeSnippet` records

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/CodeSnippet.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ReviewContext.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/agents/pipeline/ReviewContextTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewContextTest {

    @Test
    void context_by_file_is_immutable() {
        ReviewContext ctx = new ReviewContext(
                "diff", List.of(),
                Map.of("Foo.java", List.of(new CodeSnippet("Foo.java", 1, "x"))),
                Path.of("/tmp"));

        assertThatThrownBy(() -> ctx.contextByFile().put("Bar.java", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void code_snippet_holds_three_fields() {
        CodeSnippet snip = new CodeSnippet("Foo.java", 42, "return x;");
        assertThat(snip.file()).isEqualTo("Foo.java");
        assertThat(snip.line()).isEqualTo(42);
        assertThat(snip.text()).isEqualTo("return x;");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ReviewContextTest test`
Expected: FAIL — classes undefined

- [ ] **Step 3: Implement both records**

`CodeSnippet.java`:

```java
package dev.langchain4j.example.codereview.agents.pipeline;

public record CodeSnippet(String file, int line, String text) { }
```

`ReviewContext.java`:

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.infra.DiffParser;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record ReviewContext(
        String rawDiff,
        List<DiffParser.FileDiff> fileDiffs,
        Map<String, List<CodeSnippet>> contextByFile,
        Path sourceRoot
) {
    public ReviewContext {
        fileDiffs = fileDiffs == null ? List.of() : List.copyOf(fileDiffs);
        contextByFile = contextByFile == null ? Map.of() : Map.copyOf(contextByFile);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=ReviewContextTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/agents/pipeline/CodeSnippet.java \
        src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ReviewContext.java \
        src/test/java/dev/langchain4j/example/codereview/agents/pipeline/ReviewContextTest.java
git commit -m "feat(pipeline): ReviewContext + CodeSnippet immutable records"
```

---

### Task 9: refactor `CodeSearchTool` — expose `grep` as plain method

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/tools/CodeSearchTool.java`

- [ ] **Step 1: Modify `CodeSearchTool`**

Remove the `@Tool` annotation. Rename `searchCode` to `grep` (keeping the same body). The `@P` annotations may stay or be removed; they are harmless. The W2 system-prompt mention of `searchCode` will be removed when the AiServices agent is deleted in Task 14.

Replace the class body's signature line:

```java
public String searchCode(
        @P("Absolute path to a directory to search") String rootPath,
        @P("Substring (literal, case-sensitive) to look for") String needle) {
```

with:

```java
public String grep(String rootPath, String needle) {
```

Remove the `@Tool("""...""")` block entirely (including the `import dev.langchain4j.agent.tool.P;` and `import dev.langchain4j.agent.tool.Tool;` lines).

- [ ] **Step 2: Verify W2 callers compile**

Search for callers: `grep -r "searchCode" src/`. The only caller should be `AgentConfig.codeReviewAgent` (which references the bean, not the method directly). Compile to confirm:

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS — no missing-symbol errors

- [ ] **Step 3: Run existing tests**

Run: `mvn -q test`
Expected: PASS — no test directly references `searchCode`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/tools/CodeSearchTool.java
git commit -m "refactor(tools): CodeSearchTool exposes grep as plain method (remove @Tool)"
```

---

### Task 10: `DiffAnalyzer` — identifier extraction + grep into `contextByFile`

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/DiffAnalyzer.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/agents/pipeline/DiffAnalyzerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.tools.CodeSearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiffAnalyzerTest {

    @TempDir Path tmp;

    private final DiffParser parser = new DiffParser();
    private final CodeSearchTool search = new CodeSearchTool();

    @Test
    void grep_finds_identifier_in_source_root() throws Exception {
        Path src = tmp.resolve("source-before/com/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Helper.java"),
                "package com.example;\npublic class Helper {\n  void doThing() {}\n}\n");

        String diff = """
                diff --git a/com/example/Foo.java b/com/example/Foo.java
                --- a/com/example/Foo.java
                +++ b/com/example/Foo.java
                @@ -1,1 +1,3 @@
                 package com.example;
                +class Foo { void run() { new Helper().doThing(); } }
                """;

        DiffAnalyzer analyzer = new DiffAnalyzer(parser, search);
        ReviewContext ctx = analyzer.analyze(diff, tmp.resolve("source-before"));

        assertThat(ctx.contextByFile()).containsKey("com/example/Foo.java");
        assertThat(ctx.contextByFile().get("com/example/Foo.java"))
                .anyMatch(s -> s.file().equals("com/example/Helper.java")
                        && s.text().contains("doThing"));
    }

    @Test
    void missing_source_root_yields_empty_context_no_throw() {
        DiffAnalyzer analyzer = new DiffAnalyzer(parser, search);
        ReviewContext ctx = analyzer.analyze(
                "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n+y\n",
                tmp.resolve("nonexistent"));
        assertThat(ctx.contextByFile()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=DiffAnalyzerTest test`
Expected: FAIL — class undefined

- [ ] **Step 3: Implement `DiffAnalyzer`**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.tools.CodeSearchTool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DiffAnalyzer {

    private static final Pattern METHOD_CALL =
            Pattern.compile("\\b([a-z][A-Za-z0-9]+)\\s*\\(");
    private static final Pattern TYPE_NAME =
            Pattern.compile("\\b([A-Z][A-Za-z0-9]+)\\b");
    private static final Set<String> STOPWORDS =
            Set.of("if", "for", "new", "return", "this", "super", "true", "false",
                    "null", "String", "Integer", "Long", "Boolean", "Object", "List",
                    "Map", "Set", "Override");
    private static final int MAX_HITS_PER_FILE = 20;
    private static final int MAX_IDENTIFIERS_PER_FILE = 6;

    private final DiffParser parser;
    private final CodeSearchTool search;

    public DiffAnalyzer(DiffParser parser, CodeSearchTool search) {
        this.parser = parser;
        this.search = search;
    }

    public ReviewContext analyze(String rawDiff, Path sourceRoot) {
        List<DiffParser.FileDiff> files = parser.parse(rawDiff);
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return new ReviewContext(rawDiff, files, Map.of(), sourceRoot);
        }
        Map<String, List<CodeSnippet>> byFile = new LinkedHashMap<>();
        for (DiffParser.FileDiff file : files) {
            Set<String> idents = extractIdentifiers(file);
            List<CodeSnippet> snippets = new ArrayList<>();
            for (String id : idents) {
                String raw = search.grep(sourceRoot.toString(), id);
                if (raw == null || raw.startsWith("No matches") || raw.startsWith("Not a directory")) {
                    continue;
                }
                for (String line : raw.split("\n")) {
                    if (line.isBlank() || line.startsWith("[truncated")) continue;
                    CodeSnippet snip = parseGrepLine(line);
                    if (snip != null) snippets.add(snip);
                    if (snippets.size() >= MAX_HITS_PER_FILE) break;
                }
                if (snippets.size() >= MAX_HITS_PER_FILE) break;
            }
            if (!snippets.isEmpty()) {
                byFile.put(file.path(), List.copyOf(snippets));
            }
        }
        return new ReviewContext(rawDiff, files, Map.copyOf(byFile), sourceRoot);
    }

    private Set<String> extractIdentifiers(DiffParser.FileDiff file) {
        Set<String> out = new LinkedHashSet<>();
        for (DiffParser.AddedLine line : file.addedLines()) {
            Matcher m1 = METHOD_CALL.matcher(line.content());
            while (m1.find() && out.size() < MAX_IDENTIFIERS_PER_FILE) {
                String name = m1.group(1);
                if (!STOPWORDS.contains(name)) out.add(name);
            }
            Matcher m2 = TYPE_NAME.matcher(line.content());
            while (m2.find() && out.size() < MAX_IDENTIFIERS_PER_FILE) {
                String name = m2.group(1);
                if (!STOPWORDS.contains(name)) out.add(name);
            }
        }
        return out;
    }

    private CodeSnippet parseGrepLine(String line) {
        int firstColon = line.indexOf(':');
        if (firstColon < 0) return null;
        int secondColon = line.indexOf(':', firstColon + 1);
        if (secondColon < 0) return null;
        String file = line.substring(0, firstColon);
        int lineNo;
        try {
            lineNo = Integer.parseInt(line.substring(firstColon + 1, secondColon).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        String text = line.substring(secondColon + 1).trim();
        return new CodeSnippet(file, lineNo, text);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=DiffAnalyzerTest test`
Expected: PASS — both tests green

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/agents/pipeline/DiffAnalyzer.java \
        src/test/java/dev/langchain4j/example/codereview/agents/pipeline/DiffAnalyzerTest.java
git commit -m "feat(pipeline): DiffAnalyzer identifier-grep over source root"
```

---

### Task 11: `ToolFindings` record + `ToolFindingsProducer`

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindings.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducer.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.RegexAnalyzer;
import dev.langchain4j.example.codereview.analyzer.SourceCompiler;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import dev.langchain4j.example.codereview.infra.DiffParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolFindingsProducerTest {

    @Test
    void regex_violation_is_returned_with_ok_status() {
        String diff = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -1,1 +1,2 @@
                 class Foo {
                +  String password = "hunter2";
                """;
        DiffParser parser = new DiffParser();
        ReviewContext ctx = new ReviewContext(diff, parser.parse(diff), Map.of(), Path.of("/nonexistent"));

        SpotBugsAnalyzer spotbugs = new SpotBugsAnalyzer(
                (classesDir, output) -> false, new SourceCompiler());
        ToolFindingsProducer producer = new ToolFindingsProducer(new RegexAnalyzer(), spotbugs);

        ToolFindings out = producer.produce(ctx);

        assertThat(out.violations()).anyMatch(v -> v.rule().equals("hardcoded-credential"));
        assertThat(out.statuses()).anyMatch(s -> s.tool().equals("regex") && s.status().equals("ok"));
        assertThat(out.statuses()).anyMatch(s -> s.tool().equals("spotbugs") && s.status().equals("skipped"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=ToolFindingsProducerTest test`
Expected: FAIL — classes undefined

- [ ] **Step 3: Implement `ToolFindings`**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.model.ToolStatus;

import java.util.List;

public record ToolFindings(List<Violation> violations, List<ToolStatus> statuses) {
    public ToolFindings {
        violations = violations == null ? List.of() : List.copyOf(violations);
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }
}
```

- [ ] **Step 4: Implement `ToolFindingsProducer`**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.RegexAnalyzer;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.model.ToolStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ToolFindingsProducer {

    private final RegexAnalyzer regex;
    private final SpotBugsAnalyzer spotbugs;

    public ToolFindingsProducer(RegexAnalyzer regex, SpotBugsAnalyzer spotbugs) {
        this.regex = regex;
        this.spotbugs = spotbugs;
    }

    public ToolFindings produce(ReviewContext ctx) {
        List<Violation> all = new ArrayList<>(regex.analyze(ctx.fileDiffs()));
        List<ToolStatus> statuses = new ArrayList<>();
        statuses.add(new ToolStatus("regex", "ok", null));

        List<Violation> sb = spotbugs.analyzeWithSource(ctx.fileDiffs(), ctx.sourceRoot());
        if (sb.isEmpty()) {
            statuses.add(new ToolStatus("spotbugs", "skipped",
                    "not buildable or not installed"));
        } else {
            statuses.add(new ToolStatus("spotbugs", "ok", null));
            all.addAll(sb);
        }

        return new ToolFindings(dedupe(all), statuses);
    }

    private List<Violation> dedupe(List<Violation> in) {
        Set<String> seen = new LinkedHashSet<>();
        List<Violation> out = new ArrayList<>();
        for (Violation v : in) {
            String key = v.file() + ":" + v.line() + ":" + v.rule();
            if (seen.add(key)) out.add(v);
        }
        return out;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -Dtest=ToolFindingsProducerTest test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindings.java \
        src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducer.java \
        src/test/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducerTest.java
git commit -m "feat(pipeline): ToolFindings + ToolFindingsProducer (Regex + SpotBugs)"
```

---

### Task 12: `LlmReviewer` — single ChatModel call with bounded RAG citation IDs

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/LlmReviewer.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/agents/pipeline/LlmReviewerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.model.ToolStatus;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmReviewerTest {

    @Test
    void valid_llm_json_becomes_draft_review_result() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("""
                                {"summary":"ok","findings":[],"tool_status":[]}"""))
                        .build());

        ContentRetriever retriever = q -> List.of();
        CitationTracker tracker = new CitationTracker();
        JsonRepair repair = new JsonRepair(model, new ObjectMapper());

        LlmReviewer reviewer = new LlmReviewer(model, retriever, tracker, repair);

        ReviewContext ctx = new ReviewContext("diff", List.of(), Map.of(), Path.of("/tmp"));
        ToolFindings tools = new ToolFindings(List.of(), List.of(new ToolStatus("regex","ok",null)));

        var draft = reviewer.review(ctx, tools);

        assertThat(draft.result().summary()).isEqualTo("ok");
        assertThat(draft.citationCandidates()).isEmpty();
    }

    @Test
    void citation_candidates_are_passed_through() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("""
                                {"summary":"ok","findings":[],"tool_status":[]}"""))
                        .build());

        Content c = Content.from(TextSegment.from("body",
                Metadata.from(Map.of(
                        "citation_id", "sql#x",
                        "source_file", "sql.txt",
                        "section", "X"))));
        ContentRetriever retriever = q -> List.of(c);
        LlmReviewer reviewer = new LlmReviewer(model, retriever, new CitationTracker(),
                new JsonRepair(model, new ObjectMapper()));

        ReviewContext ctx = new ReviewContext("diff",
                List.of(new DiffParser.FileDiff("Foo.java", List.of())),
                Map.of(), Path.of("/tmp"));
        var draft = reviewer.review(ctx, new ToolFindings(List.of(
                new Violation(Severity.CRITICAL, "Foo.java", 1, "x", "msg")), List.of()));

        assertThat(draft.citationCandidates()).hasSize(1);
        assertThat(draft.citationCandidates().get(0).id()).isEqualTo("sql#x");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=LlmReviewerTest test`
Expected: FAIL — class undefined

- [ ] **Step 3: Implement `LlmReviewer`**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LlmReviewer {

    public record Draft(ReviewResult result, List<Citation> citationCandidates) { }

    private static final String SYSTEM = """
            You are a senior software engineer doing a code review.
            You are given: (a) a unified diff, (b) deterministic tool findings, (c) optional
            grep-style cross-file context, and (d) a numbered list of citation candidates from
            a vetted knowledge base.

            Return ONLY a single JSON object matching ReviewResult:
              {
                "summary": "1-2 sentences",
                "findings": [
                  {
                    "id": "F-001",
                    "file": "...",
                    "line": <int|null>,
                    "line_range": [<int>, <int>] or null,
                    "severity": "CRITICAL" | "WARNING" | "SUGGESTION",
                    "category": "SECURITY" | "PERFORMANCE" | "STABILITY" | "CONCURRENCY"
                                | "TEST" | "STYLE" | "OTHER",
                    "title": "<=80 chars",
                    "description": "...",
                    "suggestion": "...",
                    "evidence": "...",
                    "citations": [ { "id": "...", "source": "...", "section": "..." } ],
                    "source": "llm_reviewer"
                  }
                ],
                "tool_status": []
              }

            Rules:
            - Line numbers refer to the NEW file (post-change).
            - You MUST only put citations in 'citations[]' whose 'id' appears in the candidates
              list below. Do NOT invent citation IDs. Empty 'citations[]' is allowed.
            - Echo tool findings only when you agree with them; if you echo, set 'source' to
              the analyzer name (e.g. 'regex', 'spotbugs').
            - Leave 'tool_status' as []; the pipeline will fill it.
            - Output a single JSON object — no prose, no markdown fences.
            """;

    private final ChatModel chatModel;
    private final ContentRetriever retriever;
    private final CitationTracker tracker;
    private final JsonRepair jsonRepair;

    public LlmReviewer(ChatModel chatModel, ContentRetriever retriever,
                       CitationTracker tracker, JsonRepair jsonRepair) {
        this.chatModel = chatModel;
        this.retriever = retriever;
        this.tracker = tracker;
        this.jsonRepair = jsonRepair;
    }

    public Draft review(ReviewContext ctx, ToolFindings tools) {
        String query = buildQuery(ctx, tools);
        List<Content> hits = retriever.retrieve(Query.from(query));
        List<Citation> candidates = tracker.toCitations(hits);

        String prompt = SYSTEM
                + "\n\n[DIFF]\n" + ctx.rawDiff()
                + "\n\n[TOOL FINDINGS]\n" + renderViolations(tools.violations())
                + "\n\n[CROSS-FILE CONTEXT]\n" + renderContext(ctx)
                + "\n\n[CITATION CANDIDATES]\n" + renderCitations(candidates);

        var response = chatModel.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build());
        String raw = response.aiMessage().text();
        String body = extractJson(raw);

        ReviewResult result = jsonRepair.parseOrRepair(body, ReviewResult.class);
        return new Draft(result, candidates);
    }

    private String buildQuery(ReviewContext ctx, ToolFindings tools) {
        StringBuilder sb = new StringBuilder();
        for (DiffParser.FileDiff f : ctx.fileDiffs()) {
            sb.append(f.path()).append(' ');
            for (DiffParser.AddedLine l : f.addedLines()) {
                sb.append(l.content()).append(' ');
                if (sb.length() > 1000) break;
            }
            if (sb.length() > 1000) break;
        }
        for (Violation v : tools.violations()) {
            sb.append(v.rule()).append(' ').append(v.message()).append(' ');
            if (sb.length() > 2000) break;
        }
        return sb.toString().trim();
    }

    private String renderViolations(List<Violation> vs) {
        if (vs.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (Violation v : vs) {
            sb.append("- [").append(v.severity()).append("] ")
                    .append(v.file()).append(':').append(v.line())
                    .append(" (").append(v.rule()).append(") ")
                    .append(v.message()).append('\n');
        }
        return sb.toString();
    }

    private String renderContext(ReviewContext ctx) {
        if (ctx.contextByFile().isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        ctx.contextByFile().forEach((file, snippets) -> {
            sb.append("// for ").append(file).append('\n');
            for (CodeSnippet s : snippets) {
                sb.append(s.file()).append(':').append(s.line()).append(": ")
                        .append(s.text()).append('\n');
            }
        });
        return sb.toString();
    }

    private String renderCitations(List<Citation> citations) {
        if (citations.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < citations.size(); i++) {
            Citation c = citations.get(i);
            sb.append(i + 1).append(") id=").append(c.id())
                    .append(" source=").append(c.source())
                    .append(" section=").append(c.section()).append('\n');
        }
        return sb.toString();
    }

    private String extractJson(String body) {
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        return (start < 0 || end < start) ? body : body.substring(start, end + 1);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=LlmReviewerTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/agents/pipeline/LlmReviewer.java \
        src/test/java/dev/langchain4j/example/codereview/agents/pipeline/LlmReviewerTest.java
git commit -m "feat(pipeline): LlmReviewer single-call ChatModel with bounded citation candidates"
```

---

### Task 13: `Summarizer` — dedup, backfill, sort

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/Summarizer.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/agents/pipeline/SummarizerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.model.ToolStatus;
import dev.langchain4j.example.codereview.rag.CitationKeywordInjector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizerTest {

    private final Summarizer summarizer = new Summarizer(new CitationKeywordInjector());

    @Test
    void near_duplicate_findings_are_merged_keeping_highest_severity() {
        ReviewFinding a = mk("F-001", "Foo.java", 10, Severity.WARNING,
                "SQL injection in query", List.of());
        ReviewFinding b = mk("F-002", "Foo.java", 11, Severity.CRITICAL,
                "SQL injection in query", List.of());
        ReviewResult draft = new ReviewResult("s", List.of(a, b), List.of());

        ReviewResult out = summarizer.summarize(draft,
                new ToolFindings(List.of(), List.of()), List.of());

        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void unreported_critical_violation_is_backfilled_as_finding() {
        ReviewResult draft = new ReviewResult("s", List.of(), List.of());
        Violation v = new Violation(Severity.CRITICAL, "Foo.java", 42,
                "hardcoded-credential", "Possible hardcoded credential");

        ReviewResult out = summarizer.summarize(draft,
                new ToolFindings(List.of(v), List.of()), List.of());

        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).source()).isEqualTo("regex");
        assertThat(out.findings().get(0).line()).isEqualTo(42);
    }

    @Test
    void empty_citations_are_backfilled_from_candidates() {
        Citation candidate = new Citation("sql-guidelines#parameterized-queries",
                "sql-guidelines.txt", "Parameterized Queries");
        ReviewFinding f = mk("F-001", "Foo.java", 10, Severity.WARNING,
                "SQL injection via concatenation", List.of());

        ReviewResult out = summarizer.summarize(
                new ReviewResult("s", List.of(f), List.of()),
                new ToolFindings(List.of(), List.of()),
                List.of(candidate));

        assertThat(out.findings().get(0).citations()).containsExactly(candidate);
    }

    @Test
    void tool_statuses_are_passed_through() {
        ReviewResult out = summarizer.summarize(
                new ReviewResult("s", List.of(), List.of()),
                new ToolFindings(List.of(),
                        List.of(new ToolStatus("regex","ok",null),
                                new ToolStatus("spotbugs","skipped","x"))),
                List.of());

        assertThat(out.toolStatus()).hasSize(2);
    }

    @Test
    void findings_sorted_by_severity_then_file_then_line() {
        ReviewFinding low = mk("F-1", "Aaa.java", 1, Severity.SUGGESTION, "a", List.of());
        ReviewFinding hi = mk("F-2", "Zzz.java", 99, Severity.CRITICAL, "z", List.of());
        ReviewResult out = summarizer.summarize(
                new ReviewResult("s", List.of(low, hi), List.of()),
                new ToolFindings(List.of(), List.of()), List.of());

        assertThat(out.findings().get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(out.findings().get(1).severity()).isEqualTo(Severity.SUGGESTION);
    }

    private ReviewFinding mk(String id, String file, int line, Severity sev,
                             String title, List<Citation> citations) {
        return new ReviewFinding(id, file, line, null, sev, Category.SECURITY,
                title, title, "fix", "evidence", citations, "llm_reviewer");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=SummarizerTest test`
Expected: FAIL — class undefined

- [ ] **Step 3: Implement `Summarizer`**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.rag.CitationKeywordInjector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class Summarizer {

    private final CitationKeywordInjector citationInjector;

    public Summarizer(CitationKeywordInjector citationInjector) {
        this.citationInjector = citationInjector;
    }

    public ReviewResult summarize(ReviewResult draft, ToolFindings tools,
                                  List<Citation> citationCandidates) {
        List<ReviewFinding> base = draft.findings() == null
                ? new ArrayList<>() : new ArrayList<>(draft.findings());

        for (Violation v : tools.violations()) {
            if (v.severity() == Severity.SUGGESTION) continue;
            if (covered(base, v)) continue;
            base.add(toFinding(v, base.size() + 1));
        }

        List<ReviewFinding> dedup = dedup(base);
        List<ReviewFinding> withCitations = citationInjector.inject(dedup, citationCandidates);
        List<ReviewFinding> sorted = sort(withCitations);

        return new ReviewResult(
                draft.summary() == null ? "" : draft.summary(),
                sorted,
                tools.statuses());
    }

    private boolean covered(List<ReviewFinding> findings, Violation v) {
        for (ReviewFinding f : findings) {
            if (sameFile(f.file(), v.file())
                    && f.line() != null
                    && Math.abs(f.line() - v.line()) <= 2) {
                return true;
            }
        }
        return false;
    }

    private boolean sameFile(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return a.endsWith("/" + b) || b.endsWith("/" + a);
    }

    private ReviewFinding toFinding(Violation v, int index) {
        return new ReviewFinding(
                String.format("F-%03d", index),
                v.file(), v.line(), new int[]{v.line(), v.line()},
                v.severity(), Category.OTHER,
                v.rule(), v.message(),
                "Address the static-analyzer finding.", v.message(),
                List.of(), "regex".equals(v.rule()) ? "regex" : v.rule());
    }

    private List<ReviewFinding> dedup(List<ReviewFinding> in) {
        Map<String, ReviewFinding> byKey = new LinkedHashMap<>();
        for (ReviewFinding f : in) {
            String key = bucketKey(f);
            ReviewFinding existing = byKey.get(key);
            if (existing == null || sevRank(f.severity()) < sevRank(existing.severity())) {
                byKey.put(key, f);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private String bucketKey(ReviewFinding f) {
        String file = f.file() == null ? "" : f.file();
        int lineBucket = f.line() == null ? -1 : (f.line() / 5);
        String title = f.title() == null ? "" : f.title().toLowerCase(Locale.ROOT);
        String titleHead = title.length() > 30 ? title.substring(0, 30) : title;
        return file + "|" + lineBucket + "|" + titleHead;
    }

    private static int sevRank(Severity s) {
        return switch (s) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case SUGGESTION -> 2;
        };
    }

    private List<ReviewFinding> sort(List<ReviewFinding> in) {
        List<ReviewFinding> sorted = new ArrayList<>(in);
        sorted.sort(Comparator
                .comparingInt((ReviewFinding f) -> sevRank(f.severity()))
                .thenComparing(f -> f.file() == null ? "" : f.file())
                .thenComparingInt(f -> f.line() == null ? Integer.MAX_VALUE : f.line()));
        return sorted;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=SummarizerTest test`
Expected: PASS — all 5 tests green

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/agents/pipeline/Summarizer.java \
        src/test/java/dev/langchain4j/example/codereview/agents/pipeline/SummarizerTest.java
git commit -m "feat(pipeline): Summarizer dedup + backfill + sort"
```

---

### Task 14: `PipelineCodeReviewer` + AgentConfig rewrite

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/PipelineCodeReviewer.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/agents/CodeReviewAgent.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/config/RagConfig.java`
- Delete: `src/main/java/dev/langchain4j/example/codereview/tools/RuleCheckerTool.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/tools/GitDiffTool.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/agents/pipeline/PipelineCodeReviewerIT.java`

- [ ] **Step 1: Write the failing integration test**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class PipelineCodeReviewerIT {

    @Autowired CodeReviewAgent agent;

    @Test
    void end_to_end_review_returns_result_with_tool_status() {
        String request = """
                Review the following diff. The full diff is below; do not call git tools.

                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -1,1 +1,2 @@
                 class Foo {
                +  String password = "hunter2";
                """;
        ReviewResult result = agent.review(request);
        assertThat(result.toolStatus()).isNotEmpty();
    }

    @TestConfiguration
    static class MockModelConfig {
        @Bean @Primary
        ChatModel chatModel() {
            ChatModel model = mock(ChatModel.class);
            when(model.chat(any(ChatRequest.class)))
                    .thenReturn(ChatResponse.builder()
                            .aiMessage(AiMessage.from("""
                                    {"summary":"ok","findings":[],"tool_status":[]}"""))
                            .build());
            return model;
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=PipelineCodeReviewerIT test`
Expected: FAIL — `PipelineCodeReviewer` undefined, or Spring context has two `CodeReviewAgent` beans

- [ ] **Step 3: Extend `CodeReviewAgent` interface with `sourceRoot`**

The pipeline depends on `DiffAnalyzer` and `SpotBugs` knowing the source tree to grep / compile. Add a two-arg method while keeping the one-arg for callers that don't have a source tree handy. Replace `src/main/java/dev/langchain4j/example/codereview/agents/CodeReviewAgent.java` with:

```java
package dev.langchain4j.example.codereview.agents;

import dev.langchain4j.example.codereview.model.ReviewResult;

import java.nio.file.Path;
import java.nio.file.Paths;

public interface CodeReviewAgent {

    default ReviewResult review(String request) {
        return review(request, Paths.get("").toAbsolutePath());
    }

    ReviewResult review(String request, Path sourceRoot);
}
```

Update the two production callers:

- `src/main/java/dev/langchain4j/example/codereview/cli/ReviewCommand.java` — wherever it currently invokes `agent.review(prompt)`, change to `agent.review(prompt, Path.of(repoPath))` (`repoPath` is already a CLI argument). If the call site does not exist yet (W2 may have passed the request as a single string), pass the absolute repo path.
- `src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java` — in `evaluateOne`, change `result = callAgentWithRetry(request, sample.id());` to pass the sample source root. Refactor `callAgentWithRetry` accordingly:

```java
private ReviewResult callAgentWithRetry(String request, Path sourceRoot, String sampleId) {
    try {
        return agent.review(request, sourceRoot);
    } catch (RuntimeException e) {
        if (!isRetryable(e)) throw e;
        log.warn("Sample {} review timed out; retrying once: {}", sampleId, e.toString());
        return agent.review(request, sourceRoot);
    }
}
```

Invoke with `result = callAgentWithRetry(request, sample.sourceBeforeDir(), sample.id());`. Add `import java.nio.file.Path;` to both files if absent.

- [ ] **Step 4: Implement `PipelineCodeReviewer`**

```java
package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewResult;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class PipelineCodeReviewer implements CodeReviewAgent {

    private final DiffAnalyzer diffAnalyzer;
    private final ToolFindingsProducer toolFindingsProducer;
    private final LlmReviewer llmReviewer;
    private final Summarizer summarizer;

    public PipelineCodeReviewer(DiffAnalyzer diffAnalyzer,
                                ToolFindingsProducer toolFindingsProducer,
                                LlmReviewer llmReviewer,
                                Summarizer summarizer) {
        this.diffAnalyzer = diffAnalyzer;
        this.toolFindingsProducer = toolFindingsProducer;
        this.llmReviewer = llmReviewer;
        this.summarizer = summarizer;
    }

    @Override
    public ReviewResult review(String request, Path sourceRoot) {
        String diff = extractDiff(request);
        ReviewContext ctx = diffAnalyzer.analyze(diff, sourceRoot);
        ToolFindings tools = toolFindingsProducer.produce(ctx);
        LlmReviewer.Draft draft = llmReviewer.review(ctx, tools);
        return summarizer.summarize(draft.result(), tools, draft.citationCandidates());
    }

    private String extractDiff(String request) {
        int marker = request.indexOf("diff --git");
        return marker < 0 ? request : request.substring(marker);
    }
}
```

- [ ] **Step 5: Delete `RuleCheckerTool`**

```bash
rm src/main/java/dev/langchain4j/example/codereview/tools/RuleCheckerTool.java
rm -f src/test/java/dev/langchain4j/example/codereview/tools/RuleCheckerToolTest.java
```

(If no test file existed, ignore the second line.)

- [ ] **Step 6: Strip `@Tool` from `GitDiffTool`**

In `src/main/java/dev/langchain4j/example/codereview/tools/GitDiffTool.java`:

- Remove the `@Tool(...)` annotation above `getGitDiff`.
- Remove the `@P(...)` annotations on the two parameters.
- Remove imports of `dev.langchain4j.agent.tool.P` and `dev.langchain4j.agent.tool.Tool`.

The class otherwise stays unchanged — `ReviewCommand` already calls `getGitDiff` directly.

- [ ] **Step 7: Rewrite `AgentConfig`**

Replace `src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java` with:

```java
package dev.langchain4j.example.codereview.config;

import dev.langchain4j.example.codereview.analyzer.SourceCompiler;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import dev.langchain4j.example.codereview.eval.EvaluationRunner;
import dev.langchain4j.example.codereview.eval.LlmJudge;
import dev.langchain4j.example.codereview.eval.LlmJudgeImpl;
import dev.langchain4j.example.codereview.eval.Matcher;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.model.chat.ChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@Configuration
public class AgentConfig {

    @Bean
    public JsonRepair jsonRepair(ChatModel chatModel, ObjectMapper mapper) {
        return new JsonRepair(chatModel, mapper);
    }

    @Bean
    public LlmJudge llmJudge(ChatModel chatModel, ObjectMapper mapper) {
        return new LlmJudgeImpl(chatModel, mapper);
    }

    @Bean
    public Matcher matcher(LlmJudge judge) {
        return new Matcher(judge, 5);
    }

    @Bean
    public EvaluationRunner evaluationRunner(CodeReviewAgent agent, Matcher matcher, ObjectMapper mapper) {
        return new EvaluationRunner(agent, matcher, mapper);
    }

    @Bean
    public SourceCompiler sourceCompiler() {
        return new SourceCompiler();
    }

    @Bean
    public SpotBugsAnalyzer.Runner spotBugsRunner() {
        return (classesDir, output) -> {
            try {
                Process process = new ProcessBuilder(
                        "spotbugs", "-textui", "-quiet", "-xml", "-output", output.toString(),
                        classesDir.toString())
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0 && Files.size(output) > 0;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
    }
}
```

The W3a `GuardedCodeReviewAgent` bean and `aiServicesCodeReviewAgent` bean are gone — `PipelineCodeReviewer` is the only `CodeReviewAgent` Bean.

- [ ] **Step 8: Simplify `RagConfig`**

The `RetrievalRecorder` and recorder-wrapping retriever introduced in W3a Task 4 are no longer needed (the pipeline owns retrieval). Revert `RagConfig.contentRetriever` to its pre-W3a-task-4 form (drop the lambda wrapper and `RetrievalRecorder` parameter). The `RetrievalRecorder` Bean itself is removed.

Final body of `contentRetriever`:

```java
@Bean
public ContentRetriever contentRetriever(
        KnowledgeBaseIndexer indexer,
        EmbeddingModel embeddingModel,
        ChatModel chatModel,
        CodeReviewProperties props) {
    ContentRetriever vector = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(indexer.buildOrLoad())
            .embeddingModel(embeddingModel)
            .maxResults(props.rag().topK())
            .minScore(props.rag().minScore())
            .build();

    Bm25Retriever bm25 = indexer.getBm25Retriever();
    ContentRetriever bm25Wrapped = query -> bm25.retrieve(query, props.rag().bm25TopK());
    ContentRetriever hybrid = new HybridRetriever(vector, bm25Wrapped,
            props.rag().rrfK(), props.rag().rerankTopK());

    if (!props.rag().rerankEnabled()) {
        return hybrid;
    }
    return new LlmReranker(hybrid, chatModel, props.rag().rerankTopK());
}
```

Delete `RetrievalRecorder.java` and `RetrievalRecorderTest.java`; delete `GuardedCodeReviewAgent.java` and `GuardedCodeReviewAgentTest.java`. Their roles are subsumed by `LlmReviewer` + `Summarizer`.

```bash
rm src/main/java/dev/langchain4j/example/codereview/rag/RetrievalRecorder.java \
   src/test/java/dev/langchain4j/example/codereview/rag/RetrievalRecorderTest.java \
   src/main/java/dev/langchain4j/example/codereview/agents/GuardedCodeReviewAgent.java \
   src/test/java/dev/langchain4j/example/codereview/agents/GuardedCodeReviewAgentTest.java
```

`CitationKeywordInjector` is kept — `Summarizer` depends on it.

- [ ] **Step 9: Run all tests**

Run: `mvn -q test`
Expected: PASS — including the new `PipelineCodeReviewerIT`. If `PipelineCodeReviewerIT` fails because `MOONSHOT_API_KEY` is missing during context startup (the `ChatModel` Bean from the LangChain4j starter needs the key even with a mocked override), set `MOONSHOT_API_KEY=test` in the test env or annotate the test with `@TestPropertySource(properties = "langchain4j.open-ai.chat-model.api-key=test")`.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(pipeline): PipelineCodeReviewer replaces AiServices agent; remove @Tool surface"
```

---

### Task 15: W3b eval run — produce v3 report

**Files:**
- Create: `eval/reports/v3-pipeline.json`

**Prerequisite:** `MOONSHOT_API_KEY` is set.

- [ ] **Step 1: Rebuild**

Run: `mvn -q clean package -DskipTests`
Expected: BUILD SUCCESS

- [ ] **Step 2: Smoke-check first**

Run:

    java -jar target/code-review-agent-1.0.0.jar eval \
        --version v3-smoke \
        --pipeline w3-pipeline \
        --suite smoke

Expected: prints metrics for 2 samples; `eval/reports/v3-smoke.json` written. Inspect — confirm `tool_status` is populated and at least one finding has a non-empty `citations[]` somewhere across the 2 samples.

If smoke fails (e.g., JSON parse error rate non-zero), revisit Task 12 (LlmReviewer) and Task 1 (JsonRepair).

Delete the smoke report before the dev run so it doesn't get committed:

```bash
rm eval/reports/v3-smoke.json
```

- [ ] **Step 3: Full dev run**

Run:

    java -jar target/code-review-agent-1.0.0.jar eval \
        --version v3-pipeline \
        --pipeline w3-pipeline \
        --suite dev

Expected: `eval/reports/v3-pipeline.json` written with 20 per-sample rows.

- [ ] **Step 4: Sanity-check the report**

Open `eval/reports/v3-pipeline.json` and verify:
- `config.pipeline == "w3-pipeline"`
- `metrics.recall` and `metrics.precision` are numeric and within [0,1]
- No `per_sample` row has `"review error"` in its sample id list (`falseNegatives` accounting for review error is acceptable; outright crashes are not)

- [ ] **Step 5: Commit + tag**

```bash
git add eval/reports/v3-pipeline.json
git commit -m "eval(w3b): v3 pipeline report on 20 samples"
git tag w3-pipeline
```

---

### Task 16: docs sync — README, CLAUDE.md, w3-notes.md

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Create: `docs/learnings/w3-notes.md`

- [ ] **Step 1: Update README metrics table**

Open `README.md`, find the existing evaluation table (added in W1/W2 README). Add three rows (numbers come from the actual report files just produced — substitute placeholders below):

```markdown
| v1 + SpotBugs + CodeSearch (20 samples) | <recall> | <precision> | <fp_rate> | <latency>s | n/a |
| v2 + Hybrid RAG + LLM Rerank (20 samples) | <recall> | <precision> | <fp_rate> | <latency>s | n/a |
| v3 + Pipeline (20 samples) | <recall> | <precision> | <fp_rate> | <latency>s | n/a |
```

Read each value from the corresponding `eval/reports/*.json` `metrics` block.

- [ ] **Step 2: Replace architecture sketch in README**

In the README's architecture section, replace the single-agent diagram with the pipeline diagram from the W3 spec §3.3.

- [ ] **Step 3: Update CLAUDE.md Roadmap**

In `CLAUDE.md`, find the `## Project Roadmap Context` section. Update the W2/W3 lines:

```markdown
- **W2** (done): SpotBugs + CodeSearchTool + hybrid RAG + reranker + 20 samples → v1/v2 reports.
- **W3** (current, branch ...): pipeline split (`DiffAnalyzer` → `ToolFindings` → `LlmReviewer` → `Summarizer`); v3 report; JsonRepair guard.
- **W4**: 40-sample release evaluation, tuning, README/demo.
```

Also update the "Architecture" section §1: replace "The agent is a LangChain4j AI Service" paragraph with a paragraph describing the pipeline (each component, where the LLM call happens, why no `@Tool`).

- [ ] **Step 4: Create `docs/learnings/w3-notes.md`**

Use the W2 structure: one section per task with "技术细节 / 设计权衡 / 面试 Q&A / Commit". Fill in based on what was actually done. Minimum sections:

- T1 JsonRepair
- T2 RetrievalRecorder (deprecated by W3b — note this in the section)
- T3 CitationKeywordInjector
- T4 GuardedCodeReviewAgent (deprecated by W3b — note this)
- T5 EvalCommand env hardening
- T6 timeout bump
- T7 v1/v2 reports
- T8 ReviewContext + CodeSnippet
- T9 CodeSearchTool refactor
- T10 DiffAnalyzer
- T11 ToolFindings + Producer
- T12 LlmReviewer
- T13 Summarizer
- T14 PipelineCodeReviewer
- T15 v3 report

(Use this skeleton as starting structure; fill from actual experience and design decisions encountered during implementation.)

- [ ] **Step 5: Commit docs**

```bash
git add README.md CLAUDE.md docs/learnings/w3-notes.md
git commit -m "docs(w3): pipeline architecture, v1/v2/v3 metrics, W3 learning notes"
```

---

## Self-Review Checklist (for the implementer)

Before declaring W3 done, confirm:

- [ ] Three eval reports committed: `v1-spotbugs-search.json`, `v2-rag-hybrid.json`, `v3-pipeline.json`
- [ ] No `per_sample` row in the v2 or v3 report contains the literal string `"review error:"` in its sample identifier section (means JsonRepair worked)
- [ ] `grep -rn '@Tool' src/main/java/dev/langchain4j/example/codereview/tools/` returns nothing
- [ ] `grep -rn '@SystemMessage' src/main/java/dev/langchain4j/example/codereview/agents/` returns nothing
- [ ] `grep -rn 'AiServices' src/main/java/dev/langchain4j/example/codereview/` returns nothing in production code
- [ ] `mvn -q test` passes
- [ ] `README.md` has a v3 row in the metrics table and a pipeline architecture diagram
- [ ] `CLAUDE.md` Roadmap reflects W3 done

If any of these is not satisfied, the W3 deliverable is incomplete.
