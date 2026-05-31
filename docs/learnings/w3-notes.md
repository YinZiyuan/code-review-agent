# W3 Notes

## Implemented

- Added `JsonRepair` as a Java-side parse-or-repair guard for malformed model JSON.
- Added W3a retrieval capture and citation back-fill around the W2 `AiServices` agent, then removed that wrapper in W3b after the pipeline replacement.
- Added `--suite` and `--samples` to the eval CLI, cleared noisy `DEBUG` system properties on eval entry, and added retry for transient HTTP timeouts.
- Produced W3a eval reports for `v1-spotbugs-search` and `v2-rag-hybrid` on 20 dev samples.
- Added immutable pipeline records: `ReviewContext`, `CodeSnippet`, and `ToolFindings`.
- Converted `CodeSearchTool` and `GitDiffTool` into plain directly-called components; deleted `RuleCheckerTool`.
- Added `DiffAnalyzer` for deterministic diff parsing, identifier extraction, and source-before grep context.
- Added `ToolFindingsProducer` to merge regex analyzer output and best-effort SpotBugs findings.
- Added `LlmReviewer` for the single bounded `ChatModel` review call with Hybrid RAG citation candidates.
- Added `Summarizer` for deterministic duplicate removal, static-finding fill, citation back-fill, and severity/file/line sorting.
- Replaced the production `AiServices` agent with `PipelineCodeReviewer`.
- Produced the W3b `v3-pipeline` eval report on 20 dev samples and tagged it as `w3-pipeline`.

## Verification

- Baseline before W3 work: `mvn test` passed with 59 tests.
- W3b pipeline implementation: `mvn -q test` passed with 79 tests before the v3 report was generated.
- `mvn -q clean package -DskipTests` passed before smoke and dev eval.
- v3 smoke eval passed with recall 1.00, precision 1.00, and fp_rate 0.00.
- `rg "review error"` found no matches in the committed v1, v2, and v3 reports at the time each report was accepted.

## Eval Results

| Version | Pipeline | Recall | Precision | FP Rate | Avg Latency |
| --- | --- | --- | --- | --- | --- |
| `v1-spotbugs-search` | `w2-spotbugs-codesearch` | 0.50 | 0.3125 | 0.6875 | 34618.9 ms |
| `v2-rag-hybrid` | `w2-hybrid-rerank` | 0.65 | 0.3714 | 0.6286 | 8353.9 ms |
| `v3-pipeline` | `w3-pipeline` | 0.70 | 0.6667 | 0.3333 | 4530.7 ms |

## Task Notes

- T1: `JsonRepair` needed both direct parsing and a repair prompt. In later eval it also needed to decode LangChain4j `OutputParsingException` messages that wrap the raw model output as base64.
- T2-T4: W3a `RetrievalRecorder`, `CitationKeywordInjector`, and `GuardedCodeReviewAgent` stabilized the single-agent path long enough to run v1/v2, but they were intentionally deprecated by W3b.
- T5: `--samples` made partial evals practical, and `--suite smoke|dev` made report commands explicit.
- T6: The chat timeout moved from 60s to 90s for W3a hybrid RAG eval; W3b later reduced average latency by shrinking orchestration.
- T7: v1 was run from the pre-hybrid W2 commit because that CLI did not yet support `--pipeline`.
- T8-T10: The pipeline starts by making the agent-visible world explicit: raw diff, parsed file diffs, source root, and grep context over `source-before/`.
- T11: Tool status is now deterministic data emitted by `ToolFindingsProducer`, including graceful SpotBugs skips when samples are not buildable or SpotBugs cannot run.
- T12: `LlmReviewer` uses `ChatModel` directly and parses through `JsonRepair`, avoiding hidden tool-call decisions.
- T13: `Summarizer` owns output hygiene so the LLM does not need to be trusted for deduplication, static-finding preservation, or citation fill.
- T14: `PipelineCodeReviewer` became the sole production `CodeReviewAgent`; `@Tool`, `@SystemMessage`, and `AiServices` are no longer part of the main review path.
- T15: The v3 dev eval completed after a user pause; the process was no longer running when resumed, and `eval/reports/v3-pipeline.json` was present with 20 dev samples.

## Follow-ups

- W4 should run a larger release suite before treating the v3 numbers as stable.
- `severity_accuracy` in v3 is lower than v2, so severity calibration is the next obvious tuning target.
- `tool_success_rate` is still affected by SpotBugs skips on non-buildable sample fixtures; decide whether that metric should distinguish expected skips from analyzer failures.
