---
last_updated: 2026-08-29
updated_by: superpowers-memory:ingest
triggered_by_plan: null
---

# Glossary

## Term Shards

N/A: the project has a small cross-context vocabulary.

**ReviewFinding** — One structured code-review issue with severity, category, file, real new-file line, evidence, and optional citations. → `src/main/java/dev/langchain4j/example/codereview/model/ReviewFinding.java`

**ToolFindings** — Immutable bundle of deterministic analyzer findings and tool execution states passed into review and summarization. → `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/ToolFindings.java`

**Hybrid RAG** — In-process fusion of BM25 and embedding candidates, optionally reranked, used to supply review-guideline citations. → `src/main/java/dev/langchain4j/example/codereview/rag/HybridRetriever.java`

**Agent-visible sample input** — The only benchmark data available to review generation: `diff.patch` and `source-before/`. → `eval/samples/README.md`

**Strict release report** — A repeated-run report over at least 40 samples that satisfies the release redlines and may be compared on the main chart. → `docs/eval-metrics.md`
