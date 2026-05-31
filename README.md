# Code Review Agent

A LangChain4j-based Java agent that reviews git diffs and produces structured findings, evaluated against a small baseline sample set.

## Status (W3 pipeline)

| Version | Recall | Precision | FP Rate | Avg Latency | Samples |
|---------|--------|-----------|---------|-------------|---------|
| v0-baseline | 60% | 50% | 50% | - | 5 reverse-style samples |
| v1-spotbugs-search | 50% | 31.25% | 68.75% | 34.62s | 20 reverse-style samples |
| v2-rag-hybrid | 65% | 37.14% | 62.86% | 8.35s | 20 reverse-style samples |
| v3-pipeline | 70% | 66.67% | 33.33% | 4.53s | 20 reverse-style samples |

Reports: [`v0`](eval/reports/v0-baseline.json), [`v1`](eval/reports/v1-spotbugs-search.json), [`v2`](eval/reports/v2-rag-hybrid.json), [`v3`](eval/reports/v3-pipeline.json)

W3 replaces the W2 single AI-service agent with a deterministic review pipeline: diff context collection, static/tool findings, one bounded LLM review call, then deterministic summarization and citation back-fill. See [`docs/learnings/w3-notes.md`](docs/learnings/w3-notes.md) for implementation and evaluation notes.

## Quick Start

```bash
export MOONSHOT_API_KEY=<your-kimi-key>
mvn -q clean package -DskipTests
java -jar target/code-review-agent-1.0.0.jar review . HEAD~1
```

## Evaluation

```bash
java -jar target/code-review-agent-1.0.0.jar eval --version v0-baseline
env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval \
  --version v3-pipeline \
  --pipeline w3-pipeline \
  --suite dev
```

See [`eval/samples/README.md`](eval/samples/README.md) for sample format.

## Architecture (W3 pipeline)

```text
              ┌──────────────┐
   CLI args ─▶│ picocli Root │──▶ review / eval / sample
              └──────┬───────┘
                     │
        ┌────────────┴────────────┐
        ▼                         ▼
┌───────────────┐         ┌────────────────────┐
│ ReviewCommand │         │   EvalCommand      │
└───────┬───────┘         └─────────┬──────────┘
        │                           │
        ▼                           ▼
┌─────────────────────┐    ┌────────────────────┐
│ PipelineCodeReviewer│◀───│ EvaluationRunner   │
│  implements agent   │    │  + Matcher + Judge │
└──────────┬──────────┘    └─────────┬──────────┘
           │                         │
           ▼                         ▼
┌─────────────────────┐      ┌────────────────┐
│ DiffAnalyzer        │      │  EvalReport    │
│ diff parse + grep   │      │ eval/reports/  │
└──────────┬──────────┘      └────────────────┘
           ▼
┌─────────────────────┐
│ ToolFindingsProducer│── Regex + SpotBugs
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ LlmReviewer         │── ChatModel + Hybrid RAG citations
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ Summarizer          │── dedup + fill + severity sort
└─────────────────────┘
```

`CodeReviewAgent` is now a plain interface. No production tool is exposed through LangChain4j `@Tool`, and the main review path no longer uses `AiServices`; the only LLM call is the bounded `LlmReviewer` prompt, parsed through `JsonRepair`.

## Design

Full design spec: [`docs/superpowers/specs/2026-05-17-code-review-agent-design.md`](docs/superpowers/specs/2026-05-17-code-review-agent-design.md)
