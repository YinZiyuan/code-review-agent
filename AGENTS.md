# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Commands

Java 17 + Maven (Spring Boot parent 3.5.6). All commands are run from the repo root.

```bash
# Build the fat jar (skip tests for a fast iteration loop)
mvn -q clean package -DskipTests

# Run full test suite
mvn test

# Run a single test class / method
mvn -Dtest=DiffParserTest test
mvn -Dtest=MatcherTest#matchesByFileAndLine test

# Review the local repo's last commit (requires MOONSHOT_API_KEY in env)
export MOONSHOT_API_KEY=<kimi-key>
java -jar target/code-review-agent-1.0.0.jar review . HEAD~1

# Run the evaluation suite against eval/samples/, writing eval/reports/<version>.json
java -jar target/code-review-agent-1.0.0.jar eval --version v0-baseline
env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval \
  --version v3-pipeline \
  --pipeline w3-pipeline \
  --suite dev
```

The CLI is a picocli app with three subcommands wired in `RootCommand`: `review`, `eval`, `sample`. The Spring Boot app is configured `web-application-type: none` — it's a CLI, not a server.

## Architecture

This is a LangChain4j-backed code review pipeline that reviews git diffs and emits a structured `ReviewResult`. Three things are worth understanding before touching code:

**1. The agent is deterministic orchestration with one bounded LLM call.**
[CodeReviewAgent](src/main/java/dev/langchain4j/example/codereview/agents/CodeReviewAgent.java) is a plain interface. [PipelineCodeReviewer](src/main/java/dev/langchain4j/example/codereview/agents/pipeline/PipelineCodeReviewer.java) implements it by running `DiffAnalyzer -> ToolFindingsProducer -> LlmReviewer -> Summarizer`. Production code no longer exposes LangChain4j `@Tool` methods or builds the agent through `AiServices`. The LLM (Moonshot/Kimi over the OpenAI-compatible endpoint, configured in [application.yml](src/main/resources/application.yml)) is called directly by [LlmReviewer](src/main/java/dev/langchain4j/example/codereview/agents/pipeline/LlmReviewer.java), which receives bounded diff context, tool findings, and Hybrid RAG citation candidates. [Summarizer](src/main/java/dev/langchain4j/example/codereview/agents/pipeline/Summarizer.java) performs deterministic deduplication, missing-finding fill, citation back-fill, and severity sorting.

**2. Diff line numbers come from `DiffParser`, not from raw diff offsets.**
A historical bug treated hunk-local diff line numbers as file line numbers. [DiffParser](src/main/java/dev/langchain4j/example/codereview/infra/DiffParser.java) parses hunk headers and computes real post-change file line numbers; every analyzer and every reported finding MUST use those. The same applies to `ReviewFinding.line` — it refers to the new file. Static analyzers implement [StaticAnalyzer](src/main/java/dev/langchain4j/example/codereview/analyzer/StaticAnalyzer.java); they receive parsed `FileDiff` objects with the correct line numbers attached. The pipeline path feeds those analyzers through [ToolFindingsProducer](src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducer.java).

**3. Evaluation is the contract, not the tests.**
The project follows eval-driven development: every capability change is expected to ship with metrics in `eval/reports/`. [EvaluationRunner](src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java) iterates samples, calls the agent with the bare `diff.patch` plus the sample's `source-before/` as `sourceRoot`, matches agent findings against `ExpectedIssue`s via [Matcher](src/main/java/dev/langchain4j/example/codereview/eval/Matcher.java) (line-window prefilter + `LlmJudge` semantic match), aggregates with [Metrics](src/main/java/dev/langchain4j/example/codereview/eval/Metrics.java), and writes a JSON report named `<version>.json`. Severity/category enums are fixed: `CRITICAL|WARNING|SUGGESTION` and `SECURITY|PERFORMANCE|STABILITY|CONCURRENCY|TEST|STYLE|OTHER`.

### Sample isolation (do not violate)

Per [eval/samples/README.md](eval/samples/README.md): the agent is only allowed to see `diff.patch` and `source-before/`. `annotation.json`, `source-after/`, and the `category/difficulty/notes` fields of `meta.json` are forbidden inputs — that's how the eval stays honest. Code that loads a sample for the agent should go through `Sample.load` and only read the agent-visible fields.

### Configuration

Strongly-typed config lives in [CodeReviewProperties](src/main/java/dev/langchain4j/example/codereview/config/CodeReviewProperties.java) (a `@ConfigurationProperties` record) bound to the `code-review.*` block of `application.yml`. RAG params (`top-k`, `min-score`, `rerank-enabled`, `bm25-top-k`, `rerank-top-k`, `rrf-k`), eval params (`judge-model`, `runs-per-sample`, `samples-dir`, `report-dir`), and orchestration params all flow from there. The LLM endpoint is configured under `langchain4j.open-ai.chat-model.*`. Embedding cache is persisted to `~/.code-review-agent/cache` to avoid re-indexing on every startup.

## Project Roadmap Context

The repo is structured around a 4-week evolution (see [docs/superpowers/specs/2026-05-17-code-review-agent-design.md](docs/superpowers/specs/2026-05-17-code-review-agent-design.md)):

- **W1** (done): single-agent + regex analyzer + 5 reverse-style samples → v0 baseline (60% recall / 50% precision).
- **W2** (done): SpotBugs + CodeSearchTool + hybrid RAG + reranker + 20 samples → v1/v2 eval reports.
- **W3** (current, branch `feat/w3-pipeline`): pipeline split (`DiffAnalyzer` → `ToolFindingsProducer` → `LlmReviewer` → `Summarizer`) → v3 eval report.
- **W4**: 40-sample release evaluation, tuning, README/demo.

The W3 pipeline split is deliberate. When adding a new reviewer stage, slot it into the pipeline rather than reintroducing a single autonomous `AiServices` agent.
