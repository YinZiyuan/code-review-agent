---
status: accepted
date: 2026-05-31
last_updated: 2026-08-29
updated_by: superpowers-memory:ingest
---

# ADR-001: Deterministic orchestration with one bounded LLM call

## Context

The earlier LangChain4j `AiServices` agent decided when to call tools. Evaluation exposed hard-to-audit prompt inputs, variable tool behavior, malformed JSON, duplicated findings, and unclear citation provenance. The project needs reproducible comparisons across pipeline versions and must prove that benchmark answers never enter the reviewer context.

## Decision

Keep `CodeReviewAgent` as the stable façade, but implement it with `PipelineCodeReviewer`. Ordinary Java stages own diff parsing, context bounds, static tool execution, citation candidates, output repair, deduplication, evidence back-fill, and sorting. Only `LlmReviewer` makes a bounded creative model call.

## Alternatives Rejected

### Continue the autonomous `AiServices` tool loop

This preserved a simpler agent-demo shape, but left tool selection and effective context under model control. It made errors and evaluation regressions harder to attribute, and weakened the isolation story because the complete input path was not explicit.

### Introduce parallel specialized reviewer agents immediately

Security, performance, and test reviewers could improve specialization, but would multiply model calls, latency, failure modes, and deduplication work before the benchmark was stable. The roadmap retains this as a later experiment after stronger real-data evaluation.

## Consequences

- Pipeline stages and their inputs can be unit-tested independently.
- New reviewer stages must be inserted into the explicit pipeline.
- The system gives up open-ended autonomous exploration and bears more deterministic orchestration code.
- Evaluation can audit exactly which context reaches the model and can compare stable pipeline labels.

## Sources

- `docs/superpowers/specs/2026-05-31-code-review-agent-w3-design.md`
- `docs/superpowers/plans/2026-05-31-code-review-agent-w3.md`
- `docs/architecture.md`
- `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/PipelineCodeReviewer.java`
