# W2 Notes

## Implemented

- Added `SourceCompiler` for best-effort `javac` compilation into a temp classes directory.
- Added `SpotBugsAnalyzer` with XML parsing and graceful skip when sources do not compile or SpotBugs is not installed.
- Added `CodeSearchTool` for local Java substring search over repo/sample source trees.
- Expanded `review-guidelines` from 2 to 8 domains: SQL, performance, API design, exception handling, concurrency, testing, Java best practices, and security.
- Added reverse-style samples `reverse-006` through `reverse-020`, bringing the suite to 20 samples.
- Added Lucene-backed `Bm25Retriever`, `HybridRetriever` with reciprocal-rank fusion, `LlmReranker`, and `CitationTracker`.
- Reworked `KnowledgeBaseIndexer` to produce metadata-bearing chunks and build both vector and BM25 indexes from the same source chunks.
- Added `--pipeline` to `eval` and persisted `ReviewResult.tool_status` into per-sample metrics.

## Verification

- `mvn -q test` passes after the W2 code changes.
- `mvn -q clean package -DskipTests` passes.
- `KnowledgeBaseIndexer` now indexes 52 chunks from 8 guideline documents with cache key `review-guidelines-v2`.
- Sample sanity check with `jq` confirmed all 15 new annotations use valid file/category/severity values.

## Eval Status

- A full `v2-rag-hybrid` eval was attempted on 2026-05-26 with a real `MOONSHOT_API_KEY`.
- The run was intentionally stopped before completion because `DEBUG=release` in the shell caused Spring to log full RestClient request bodies, making the run too noisy.
- The same run also showed transient Moonshot request timeouts and one `OutputParsingException` caused by model output that was not safely parseable as `ReviewResult`.
- No `eval/reports/v1-spotbugs-search.json` or `eval/reports/v2-rag-hybrid.json` was committed. Metrics should be generated in a clean shell with:

```bash
env -u DEBUG java -jar target/code-review-agent-1.0.0.jar eval \
  --version v2-rag-hybrid \
  --pipeline w2-hybrid-rerank
```

## Follow-ups

- Run v1 from the Phase 1 commit before hybrid RAG (`4f7469f`) if a clean `v1-spotbugs-search` milestone is required.
- Add stricter JSON-output handling before trusting long eval runs. Options: model JSON mode if supported by the provider, a repair/retry step around `OutputParsingException`, or moving review generation to lower-level `ChatModel` with explicit parsing.
- Consider reducing eval request size or increasing timeout for 20-sample runs with hybrid RAG and LLM reranking.
