# Architecture

## Runtime Review Pipeline

```mermaid
flowchart TD
  A[ReviewCommand] --> B[GitDiffTool]
  B --> C[PipelineCodeReviewer]
  C --> D[DiffAnalyzer]
  D --> E[DiffParser]
  D --> F[CodeSearchTool context]
  C --> G[ToolFindingsProducer]
  G --> H[RegexAnalyzer]
  G --> I[SpotBugsAnalyzer]
  C --> J[LlmReviewer]
  J --> K[HybridRetriever]
  K --> L[BM25 + embeddings + rerank]
  J --> M[JsonRepair]
  C --> N[Summarizer]
  N --> O[CitationKeywordInjector]
  N --> P[ReviewResult]
  P --> Q[MarkdownReporter]
```

The review command sends one explicit diff request through `PipelineCodeReviewer`. The pipeline exposes only the raw diff and `source-before/` context to the reviewer. Ground truth files used by eval are never part of the review prompt.

## Evaluation Loop

```mermaid
flowchart TD
  A[eval/samples] --> B[Sample.load]
  B --> C[EvaluationRunner]
  C --> D[CodeReviewAgent.review]
  D --> E[ReviewResult]
  E --> F[Matcher]
  F --> G[LlmJudge]
  F --> H[SampleMetrics]
  H --> I[per_run_metrics]
  I --> J[mean metrics + stddev]
  J --> K[eval/reports/version.json]
  K --> L[scripts/plot_metrics.py]
  L --> M[docs/eval-metrics.md + SVG]
```

`EvaluationRunner` can repeat each sample with `--runs N`. For `N > 1`, report-level metrics are the mean of per-run aggregates, and `metrics_std_dev` captures run-to-run variance.

## Key Boundaries

| Boundary | Allowed | Forbidden |
| --- | --- | --- |
| Agent input | `diff.patch`, `source-before/` snippets | `annotation.json`, `source-after/`, `meta.category`, `meta.difficulty`, `meta.notes` |
| LLM output | JSON matching `ReviewResult` | Markdown fences, prose, invented citation IDs |
| Tool status | `RAN`, `SKIPPED_EXPECTED`, `FAILED` | Treating expected compile skips as analyzer failures |

## Why This Shape

The project started as a simpler LLM review agent, then moved toward deterministic orchestration as evaluation exposed false positives, hidden prompt inputs, and hard-to-debug tool behavior. The current shape keeps the creative part in one bounded reviewer call and leaves parsing, context gathering, tool accounting, deduplication, citation back-fill, and metrics to ordinary code.
