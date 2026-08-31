---
last_updated: 2026-08-29
updated_by: superpowers-memory:ingest
triggered_by_plan: null
---

# Decisions

## Decision Families

N/A: the project currently has one cross-cutting architectural decision.

## ADR-001: Deterministic orchestration with one bounded LLM call
**Decision:** Production review uses an explicit `DiffAnalyzer → ToolFindingsProducer → LlmReviewer → Summarizer` pipeline behind `CodeReviewAgent`.
**Trade-off:** Less autonomous tool exploration in exchange for auditable inputs, stable evaluation, bounded latency, and deterministic post-processing.
**Affects:** [architecture-review-pipeline.md](architecture-review-pipeline.md), [architecture-evaluation-loop.md](architecture-evaluation-loop.md), [features.md](features.md).
→ [adr/ADR-001-deterministic-review-pipeline.md](adr/ADR-001-deterministic-review-pipeline.md)
