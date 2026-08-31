---
last_updated: 2026-08-29
updated_by: superpowers-memory:ingest
triggered_by_plan: null
---

# Evaluation Loop

## Responsibility

Owns reproducible measurement of reviewer versions against curated samples. It does not let benchmark answers influence review generation and does not claim that the hand-built suite estimates arbitrary production-PR accuracy.

## Main Components

- `Sample.load` separates agent-visible inputs from evaluator-only annotation data.
- `EvaluationRunner` selects suites/samples, executes one or repeated review runs, and writes versioned reports.
- `Matcher` first applies a file/line window and then delegates semantic equivalence to `LlmJudge`.
- `Metrics` aggregates recall, precision, false-positive rate, severity accuracy, latency, token use, and tool success.
- `scripts/plot_metrics.py` turns accepted reports into the durable Markdown/SVG comparison.

## Interactions

The loop calls `CodeReviewAgent` with only `diff.patch` plus `source-before/` as `sourceRoot`. After review returns, evaluator-only `annotation.json` is used for matching. `source-after/` and the `category`, `difficulty`, and `notes` fields of `meta.json` remain outside the agent boundary.

## Sequence and Failure Rules

1. Select `smoke`, `dev`, or `release` samples and load each through `Sample.load`.
2. Run the configured pipeline one or more times; transient connection/timeout handling is bounded.
3. Match findings to expected issues using actual new-file line numbers plus semantic judgment.
4. For repeated runs, compute per-run aggregates, report their mean, and record standard deviation.
5. Write `eval/reports/<version>.json`; release comparisons require compatible suite size and no review-error redline breach.

Expected analyzer skips are excluded from failure counts, while true analyzer exceptions are `FAILED`. Historical small-suite results remain contextual and are not directly plotted against strict 40-sample reports.

## Module Refs

- Runtime reviews are produced by [architecture-review-pipeline.md](architecture-review-pipeline.md).

## Source Refs

- `eval/samples/README.md`
- `src/main/java/dev/langchain4j/example/codereview/eval/Sample.java`
- `src/main/java/dev/langchain4j/example/codereview/eval/EvaluationRunner.java`
- `src/main/java/dev/langchain4j/example/codereview/eval/Matcher.java`
- `src/main/java/dev/langchain4j/example/codereview/eval/Metrics.java`
- `docs/eval-metrics.md`
- `docs/superpowers/specs/2026-06-05-code-review-agent-w4-design.md`
