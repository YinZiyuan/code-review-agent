---
last_updated: 2026-08-29
updated_by: superpowers-memory:ingest
triggered_by_plan: null
---

# Project Knowledge Index

- [architecture.md](architecture.md) — System topology and boundaries.
  Key points: Java CLI; deterministic review pipeline; isolated evaluation loop.
- [architecture-review-pipeline.md](architecture-review-pipeline.md) — Runtime review path.
  Key points: one bounded LLM call; deterministic parsing, tools, summarization, and citations.
- [architecture-evaluation-loop.md](architecture-evaluation-loop.md) — Evaluation path and trust boundary.
  Key points: agent sees only diff and source-before; reports aggregate repeated-run metrics.
- [features.md](features.md) — Current and planned capabilities.
  Key points: local diff review, structured findings, hybrid RAG citations, reproducible evaluation.
- [decisions.md](decisions.md) — Durable architecture decision routing.
  Key points: deterministic pipeline replaced autonomous tool-loop orchestration.
- [conventions.md](conventions.md) — Coding, evaluation, and workflow guardrails.
  Key points: Java 17/Maven; real new-file line numbers; evaluation isolation is mandatory.
- [tech-stack.md](tech-stack.md) — Critical runtime and library choices.
  Key points: Spring Boot 3.5.6, LangChain4j 1.15.0-beta25, Lucene 9.11.1, picocli 4.7.6.
- [glossary.md](glossary.md) — Project-specific terms and aliases.
  Key points: ReviewFinding, ToolFindings, Hybrid RAG, release suite terminology.
