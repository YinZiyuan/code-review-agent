---
last_updated: 2026-08-29
updated_by: superpowers-memory:ingest
triggered_by_plan: null
---

# Architecture

## Pattern Overview

The project is a non-web Spring Boot CLI. Its main architectural pattern is deterministic orchestration around one bounded LLM review call. Parsing, context collection, static analysis, result repair, deduplication, citation back-fill, and evaluation remain ordinary Java code.

## System Context

- **Developer / evaluator:** invokes the `review`, `eval`, or `sample` picocli command.
- **Local Git repository:** supplies the compared diff and source context.
- **Moonshot OpenAI-compatible endpoint:** supplies the review model and evaluation judge.
- **Local guideline corpus and embedding cache:** supply hybrid retrieval evidence without an external vector service.

## Topology and Context Map

`RootCommand` routes CLI work to the runtime review pipeline or evaluation loop. Both depend on the stable `CodeReviewAgent` façade and structured `ReviewResult`; evaluation additionally owns samples, semantic matching, and metrics. The CLI is the only deployable entry point and runs with `web-application-type: none`.

## Module Shards

- [architecture-review-pipeline.md](architecture-review-pipeline.md) — Runtime review modules, sequencing, and finding invariants.
- [architecture-evaluation-loop.md](architecture-evaluation-loop.md) — Evaluation inputs, matching, reporting, and isolation boundary.

## Named Scenarios

1. **Review a local commit:** CLI reads a Git diff, parses real post-change line numbers, gathers deterministic evidence, performs one LLM review, then emits structured and Markdown results. See [architecture-review-pipeline.md](architecture-review-pipeline.md).
2. **Evaluate a pipeline version:** runner loads only agent-visible sample inputs, repeats reviews when configured, matches findings to annotations outside the agent boundary, and writes aggregate JSON metrics. See [architecture-evaluation-loop.md](architecture-evaluation-loop.md).

## Lifecycle and Invariants

- `ReviewFinding.line` always means the line in the new file, as computed from diff hunk headers.
- The agent-visible evaluation input is limited to `diff.patch` and `source-before/`.
- `ReviewResult` is the internal review and evaluation contract; Markdown is presentation only.
- Tool outcomes distinguish `RAN`, `SKIPPED_EXPECTED`, and `FAILED`; expected analyzer skips are not failures.

## Source Refs

- `docs/architecture.md`
- `docs/superpowers/specs/2026-05-17-code-review-agent-design.md`
- `docs/superpowers/specs/2026-05-31-code-review-agent-w3-design.md`
- `src/main/java/dev/langchain4j/example/codereview/cli/RootCommand.java`
- `src/main/java/dev/langchain4j/example/codereview/model/ReviewResult.java`
