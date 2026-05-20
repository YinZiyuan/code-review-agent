# Code Review Agent

A LangChain4j-based Java agent that reviews git diffs and produces structured findings, evaluated against a small baseline sample set.

## Status (W1)

| Version | Recall | Precision | FP Rate | Samples |
|---------|--------|-----------|---------|---------|
| v0-baseline | 60% | 50% | 50% | 5 reverse-style samples |

Full report: [`eval/reports/v0-baseline.json`](eval/reports/v0-baseline.json)

## Quick Start

```bash
export MOONSHOT_API_KEY=<your-kimi-key>
mvn -q clean package -DskipTests
java -jar target/code-review-agent-1.0.0.jar review . HEAD~1
```

## Evaluation

```bash
java -jar target/code-review-agent-1.0.0.jar eval --version v0-baseline
```

See [`eval/samples/README.md`](eval/samples/README.md) for sample format.

## Architecture (W1 snapshot)

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
│  CodeReviewAgent    │◀───│ EvaluationRunner   │
│ (LangChain4j AiSvc) │    │  + Matcher + Judge │
└──┬────────────┬─────┘    └─────────┬──────────┘
   │ tools      │ RAG                │
   ▼            ▼                    ▼
┌────────┐ ┌──────────────┐  ┌────────────────┐
│GitDiff │ │ Embedding    │  │  EvalReport    │
│RuleChk │ │ Store (BGE)  │  │ eval/reports/  │
└───┬────┘ └──────────────┘  └────────────────┘
    ▼
┌─────────┐ ┌────────────┐
│GitClient│ │DiffParser  │── real file line numbers
└─────────┘ └────────────┘
```

Single-agent pipeline today (W1). W3 splits this into `DiffAnalyzer → ToolFindings → LlmReviewer → Summarizer`; W3-stretch adds parallel Security/Performance/Test reviewers. Full Mermaid diagram lands in W4.

## Design

Full design spec: [`docs/superpowers/specs/2026-05-17-code-review-agent-design.md`](docs/superpowers/specs/2026-05-17-code-review-agent-design.md)
