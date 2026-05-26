# Code Review Agent

A LangChain4j-based Java agent that reviews git diffs and produces structured findings, evaluated against a small baseline sample set.

## Status (W2 in progress)

| Version | Recall | Precision | FP Rate | Samples |
|---------|--------|-----------|---------|---------|
| v0-baseline | 60% | 50% | 50% | 5 reverse-style samples |
| v1-spotbugs-search | pending | pending | pending | 20 reverse-style samples |
| v2-rag-hybrid | pending | pending | pending | 20 reverse-style samples |

Full report: [`eval/reports/v0-baseline.json`](eval/reports/v0-baseline.json)

W2 implementation adds SpotBugs integration, CodeSearchTool, 20 reverse-style samples, 8 guideline domains, hybrid BM25/vector retrieval, LLM reranking, and citation metadata. See [`docs/learnings/w2-notes.md`](docs/learnings/w2-notes.md) for verification notes and eval status.

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
  --version v2-rag-hybrid \
  --pipeline w2-hybrid-rerank
```

See [`eval/samples/README.md`](eval/samples/README.md) for sample format.

## Architecture (W2 snapshot)

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
│GitDiff │ │ Hybrid RAG   │  │  EvalReport    │
│RuleChk │ │ BGE + BM25   │  │ eval/reports/  │
└───┬────┘ └──────────────┘  └────────────────┘
    ▼
┌─────────┐ ┌────────────┐
│GitClient│ │DiffParser  │── real file line numbers
└─────────┘ └────────────┘
```

Single-agent pipeline today (W2), now with SpotBugs, CodeSearchTool, hybrid retrieval, reranking, and citation metadata. W3 splits this into `DiffAnalyzer → ToolFindings → LlmReviewer → Summarizer`; W3-stretch adds parallel Security/Performance/Test reviewers.

## Design

Full design spec: [`docs/superpowers/specs/2026-05-17-code-review-agent-design.md`](docs/superpowers/specs/2026-05-17-code-review-agent-design.md)
