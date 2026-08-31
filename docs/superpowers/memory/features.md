---
last_updated: 2026-08-29
updated_by: superpowers-memory:ingest
triggered_by_plan: null
---

# Features

## Implemented

### Product Capabilities

#### Structured local diff review

**Enables** — Reviews a local Git comparison and returns categorized, severity-ranked findings with file and new-file line locations.

**Actors / Entry Points** — Developers through `java -jar ... review <repo> <base-ref>` and the `CodeReviewAgent` façade.

**Capability Boundary** — Reviews bounded diff/source context through one deterministic pipeline; it is a CLI, not a hosted pull-request service.

**References** — [architecture-review-pipeline.md](architecture-review-pipeline.md), `README.md`, `src/main/java/dev/langchain4j/example/codereview/cli/ReviewCommand.java`.

#### Evidence-backed review findings

**Enables** — Combines regex and SpotBugs evidence with hybrid guideline retrieval, and attaches traceable citations when supported by retrieved candidates.

**Actors / Entry Points** — Runtime pipeline components and maintainers adding guideline files under `src/main/resources/review-guidelines/`.

**Capability Boundary** — Static tools are advisory and degradable; citation IDs are constrained to retrieved candidates.

**References** — [architecture-review-pipeline.md](architecture-review-pipeline.md), `src/main/java/dev/langchain4j/example/codereview/rag/HybridRetriever.java`.

### User / Operator Workflows

#### Reproducible reviewer evaluation

**Enables** — Runs smoke, development, or release suites, supports repeated runs, and produces versioned metrics with variability.

**Actors / Entry Points** — Maintainers through the `eval` CLI and `eval/reports/*.json`.

**Capability Boundary** — Measures this curated benchmark under strict sample isolation; results are regression evidence, not production-wide accuracy claims.

**References** — [architecture-evaluation-loop.md](architecture-evaluation-loop.md), `eval/README.md`, `docs/eval-metrics.md`.

### Operations

#### Offline-friendly retrieval cache

**Enables** — Persists embeddings under the user cache so guideline indexing need not repeat on every launch.

**Actors / Entry Points** — CLI runtime configured by `code-review.rag.embedding-cache-dir`.

**Capability Boundary** — Uses a local serialized embedding store; no external vector database is operated.

**References** — `src/main/java/dev/langchain4j/example/codereview/infra/EmbeddingCache.java`, [tech-stack.md](tech-stack.md).

## In Progress

N/A: the accepted repository state is the W4 tuned release.

## Planned

### Evaluation Quality

#### Real public PR benchmark partition

**Intent** — Add real public-PR samples as a separately reported benchmark instead of mixing them with synthetic/reverse fixtures.
**Source** — `README.md` §Roadmap.

### Review Architecture

#### Multi-reviewer experiment

**Intent** — Explore specialized reviewers only after the real-data evaluation baseline is stable.
**Source** — `README.md` §Roadmap; `docs/superpowers/specs/2026-05-17-code-review-agent-design.md`.
