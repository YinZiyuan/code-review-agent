---
last_updated: 2026-08-29
updated_by: superpowers-memory:ingest
triggered_by_plan: null
---

# Runtime Review Pipeline

## Responsibility

Owns conversion of a raw Git diff plus permitted local source context into an auditable `ReviewResult`. It does not own benchmark ground truth, metric calculation, or report-version comparison.

## Main Components

- `ReviewCommand` and `GitDiffTool` obtain the requested repository diff.
- `PipelineCodeReviewer` sequences the pipeline through the `CodeReviewAgent` façade.
- `DiffAnalyzer` uses `DiffParser` and `CodeSearchTool` to create bounded context with correct file lines.
- `ToolFindingsProducer` runs `RegexAnalyzer` and the degradable `SpotBugsAnalyzer`.
- `LlmReviewer` receives the diff context, tool evidence, and Hybrid RAG citation candidates for one bounded model call; `JsonRepair` repairs format only.
- `Summarizer` deterministically deduplicates, calibrates, fills high-confidence missing findings, back-fills citations, and severity-sorts output.
- `MarkdownReporter` renders the structured result for humans.

## Interactions

Upstream callers are the review CLI and `EvaluationRunner`. Downstream dependencies are local Git/source files, static analyzers, `HybridRetriever` (BM25, embeddings, optional rerank), and the configured Moonshot chat model. Output is consumed as `ReviewResult` by Markdown rendering or evaluation.

## Flow and Invariants

1. Parse changed files and hunks; all reported lines come from `DiffParser`, never raw patch offsets.
2. Collect snippets and deterministic findings without exposing unrelated or forbidden sample files.
3. Retrieve guideline candidates and give them stable IDs; the model may cite only supplied candidates.
4. Make one bounded model call and repair malformed JSON without adding semantic content.
5. Deterministically normalize output, preserve tool evidence, back-fill valid citations, and sort by severity.

Static analyzers are advisory and may degrade cleanly when compilation is unavailable. Production orchestration must add new review stages to this pipeline rather than restore a free-running `AiServices` tool loop.

## Scenario Refs

- Local commit review: [architecture.md](architecture.md) §Named Scenarios.
- Evaluation invokes this module through [architecture-evaluation-loop.md](architecture-evaluation-loop.md).

## Source Refs

- `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/PipelineCodeReviewer.java`
- `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/DiffAnalyzer.java`
- `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindingsProducer.java`
- `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/LlmReviewer.java`
- `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/Summarizer.java`
- `src/main/java/dev/langchain4j/example/codereview/infra/DiffParser.java`
- `src/main/java/dev/langchain4j/example/codereview/rag/HybridRetriever.java`
- `docs/superpowers/specs/2026-05-31-code-review-agent-w3-design.md`
