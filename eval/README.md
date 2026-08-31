# Evaluation Quickstart

All commands assume `MOONSHOT_API_KEY` is exported.

## v0 baseline (W1, 5 samples)

Already produced at `eval/reports/v0-baseline.json`. To reproduce on a single sample:

    java -jar target/code-review-agent-1.0.0.jar eval \
        --version v0-baseline \
        --pipeline w1-single-agent \
        --samples reverse-001

## v1 - SpotBugs + CodeSearch (W2 capability, evaluated in W3a, 20 samples)

    rm -f ~/.code-review-agent/cache/review-guidelines-v2.json
    java -jar target/code-review-agent-1.0.0.jar eval \
        --version v1-spotbugs-search \
        --pipeline w2-spotbugs-codesearch \
        --suite dev

Note: v1 turns RAG off-equivalent only conceptually; W3a does NOT add a rerank-off
flag. To run a strict v1 with no RAG hybrid + reranker, temporarily set
`code-review.rag.rerank-enabled: false` and `code-review.rag.bm25-top-k: 0` in
`application.yml`, or revert to commit `4f7469f` (pre-hybrid) before running.

## v2 - Hybrid RAG + LLM reranker (W2 capability, evaluated in W3a, 20 samples)

    rm -f ~/.code-review-agent/cache/review-guidelines-v2.json
    java -jar target/code-review-agent-1.0.0.jar eval \
        --version v2-rag-hybrid \
        --pipeline w2-hybrid-rerank \
        --suite dev

## v3 - Pipeline (W3b, 20 samples)

    java -jar target/code-review-agent-1.0.0.jar eval \
        --version v3-pipeline \
        --pipeline w3-pipeline \
        --suite dev

## Smoke check (any version)

    java -jar target/code-review-agent-1.0.0.jar eval \
        --version smoke \
        --pipeline w2-hybrid-rerank \
        --suite smoke
