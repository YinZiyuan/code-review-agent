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

## Design

Full design spec: [`docs/superpowers/specs/2026-05-17-code-review-agent-design.md`](docs/superpowers/specs/2026-05-17-code-review-agent-design.md)
