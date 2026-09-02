# Parallel stream B report

Status: **DONE** for C3, I3/ledger Task 6, the assigned I5 pipeline/resource slice, and every Critical/Important item in the independent second-round review.

Branch: `codex/issue4-final-b`

Base checkpoint: `d547608`

Independent review: `.superpowers/parallel-b-review.md`

## Commits

- `127077a` docs: plan review work budget hardening
- `64e7bab` feat: add versioned review work budget
- `02342be` feat: bound review pipeline context and stages
- `1ef58ed` feat: isolate analyzers in bounded processes
- `6214f19` feat: own review artifacts and bound runtime resources
- `0650113` fix: reserve model framing and bound source discovery
- `adcf8cc` test: cover analyzer and cleanup failure interleavings
- `7698e89` docs: report resource hardening verification
- `3004d70` fix: bound source corpus and model context identity
- `033efcf` fix: hard bound analyzer output and cleanup work

## C3 and second-round C1/I1/I2 outcome

- `ReviewWorkBudget` is one immutable, typed, versioned contract. Its SHA-256 hash covers every effective limit owned by B: diff/source/archive/file/line/snippet/finding caps, model/tokenizer/context/framing/completion data, compiler arguments, child-process output/heaps/deadlines, all stage deadlines, reviewer wall time, executor workers/queue, and janitor scan/delete/deadline bounds. `identity()` exposes the real `version:hash` seam to stream C.
- Startup resolves the effective `langchain4j.open-ai.chat-model.model-name` and fails closed unless it exactly matches the allow-listed model, tokenizer id/version, and context size in the budget. The prompt invariant is `measured input + framing reserve + completion reserve <= model context`; the same completion reserve is sent as `maxOutputTokens`.
- `DiffAnalyzer` extracts a deterministic bounded identifier set and invokes `CodeSearchTool` once. The search performs one sorted Java census, rejects file/source/archive-entry excess before content reads, then streams bounded lines with total byte accounting, a monotonic deadline, and per-byte interruption checks. It never uses `readAllLines`; partial or rejected context carries an explicit safe status into tool findings and the prompt.
- Prompt inputs are canonicalized at the boundary: context files/snippets, violations, citations, and identifiers have stable ordering. Five fresh JVMs with rotated input order produce the same prompt SHA.
- `PipelineCodeReviewer` caps every stage by both its own deadline and the remaining typed reviewer deadline. `PipelineStageExecutor` is wired from the budget's worker/queue values rather than constants.

Adversarial coverage includes 2,000 no-match files plus one match (one corpus pass), a sparse oversized Java file rejected with zero content bytes read, overlong source lines, timeout/cancellation, a timed-out scan releasing a one-worker stage executor for the next task, oversized Unicode prompt/context, and non-empty cross-JVM prompt determinism.

## I3, ledger Task 6, and second-round I3/I4 outcome

- Every review attempt owns one exact-marker workspace containing archive, extracted source, javac classes/argument file, and SpotBugs report. The top-level prepared source closes it in `finally`; analyzer scopes reuse it and remove their artifacts on success, failure, timeout, and cancellation.
- javac and SpotBugs run as child processes with typed wall time, heap, output, and cancellation bounds. The runner drains stdout/stderr separately, never logs or returns stderr diagnostics, kills the full process tree as soon as either stream exceeds the byte cap, and reports fixed safe outcomes. Exit 137/OOM-equivalent, timeout, cancellation, output flood, compile failure, and missing tools are non-blocking analyzer results.
- SpotBugs emits XML to the bounded stdout collector. The report is materialized only after a successful, non-truncated process result, so XML cannot grow unbounded on disk before inspection.
- Compiler argument paths are streamed into a workspace-owned response file with interruption and a typed byte cap; the former quadratic concatenation is gone.
- Normal cleanup deletes the marker last. Failure exposes only a path-free exception and leaves/restores the marker. The restart/hourly janitor uses a non-materializing `DirectoryStream`, checks cancellation and a monotonic deadline, caps inspected children/deletion attempts/tree entries, records a closed outcome vocabulary, never follows symlinks, and resumes marker-bearing partial deletions on later runs.

Adversarial coverage includes real stdout flood termination at the exact capture cap, secret-bearing stderr, process-tree timeout, cancellation, compiler argument overflow, cleanup failure with a simultaneous pipeline failure, restart recovery, populations larger than one janitor batch, partial tree deletion over several runs, deadline/cancellation, invalid markers, and workspace-named symlinks.

## Second-round I5/I6 and metrics semantics

- Removed the unused duplicate `code-review.orchestration.reviewer-timeout` and `parallelism` settings. Actual reviewer timeout, four-worker/default worker count, and 16-slot/default queue capacity now come only from `ReviewWorkBudget` and therefore alter its hash and gauges when changed.
- `JsonRepair` logs only fixed events (`model_json_parse_failed` and `model_json_base64_decode_failed`). Terminal repair exceptions have a fixed message, no parser message, raw token, prompt, diagnostic, throwable cause, or path; token counts remain available for billing.
- B's `code_review_model_tokens_billed_total{direction,call_scope="main_and_repair"}` is a **process-lifetime counter** for provider-billed main and repair calls, including calls that may fail before durable persistence; it resets on process restart. `code_review_pipeline_prompt_tokens_estimated_total` is the main prompt estimate.
- Stream C's database-derived operator metric must remain distinctly named `review_attempt_tokens_persisted`: a **durable retained-history gauge** with restart/retention semantics. It must not reuse B's name or imply provider billing equivalence.

## Cross-stream integration contracts

### Stream A exact stale-revision adapter

A checkpoint `b2a543c` owns:

- `dev.langchain4j.example.codereview.reviewops.application.github.StaleReviewRevisionException`
- constructor `StaleReviewRevisionException(AuthoritativeRevision)`
- accessor `authoritativeRevision()`

B intentionally did not copy A production code or edit A-owned `ExecuteReviewRun`. The exact apply-ready adapter is:

`.superpowers/sdd/2026-09-01-github-app-review-loop/stream-a-stale-revision-adapter.patch`

It validates the authoritative head as a full SHA and changes only a valid exact-head mismatch to:

```java
throw new StaleReviewRevisionException(new AuthoritativeRevision(authoritativeHead));
```

Malformed/non-full metadata remains `DETERMINISTIC_INPUT`. `git apply --check` passes on B. After A+B composition, add/retain tests in `GitHubRestClientTest` and both `GitHubArchiveSourceProviderTest` mismatch paths that assert the exception's `authoritativeRevision()`.

### Stream C snapshot identity

Inject the actual `ReviewWorkBudget` bean into C's `ReviewConfigurationSnapshotFactory` and consume `budget.identity()` (or its `version()` plus `configurationHash()` components) directly. Remove `ReviewIdentityProperties.workBudgetIdentity` and `REVIEW_WORK_BUDGET_IDENTITY`; do not separately hash the removed legacy orchestration settings. No C-owned snapshot, datasource, migration, retention, or logging production code was edited here.

## Verification

- Focused second-round suites: PASS, including corpus/search, prompt determinism, executor release, process isolation, compiler bounds, SpotBugs wiring, JsonRepair redaction, budget binding/hash/gauges, and janitor bounds.
- Fresh full `mvn test` on final HEAD: **588 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**, 1:08 min, completed `2026-09-02T17:50:31+08:00`.
- Fresh `docker build -t code-review-agent:issue4-final-b-r2 .`: PASS; image id starts `sha256:12219b2ecd17`, size 442,648,716 bytes.
- `./scripts/smoke-review-runtime.sh code-review-agent:issue4-final-b-r2`: PASS; the shipped non-root image ran javac and SpotBugs against a compilable fixture with the configured JVM limits.
- Compose effective limits (validated with the API-key variable removed): app `1 CPU / 1 GiB / 256 PIDs`; PostgreSQL `0.5 CPU / 512 MiB / 128 PIDs`.
- `git diff --check d547608..HEAD`: PASS.
- Branch-added production lines contain no credential-shaped or literal secret values. Two whole-tree pattern hits are pre-existing baseline material (`UserService`'s intentional credential-shaped review fixture and the GitHub PEM delimiter constant), unchanged from `d547608`; no B change introduced them.

## Conflict-risk files

- `src/main/resources/application.yml`: removed the three-line unused legacy orchestration block. Stream C should retain its unrelated server settings and hash only effective budget fields.
- `src/main/java/dev/langchain4j/example/codereview/config/CodeReviewProperties.java`: removed only the unused `Orchestration` component/record.
- `pom.xml`: keeps B's explicit `jtokkit:1.1.0`; merge additively with C dependencies.
- `src/main/java/dev/langchain4j/example/codereview/server/ServerConfiguration.java`: B's source-provider wiring uses `ReviewWorkBudget` and `ReviewWorkspaceFactory`; preserve C's datasource/observability beans.
- `src/main/java/dev/langchain4j/example/codereview/config/AgentConfig.java`: bounded javac/SpotBugs bean wiring.
- `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/GitHubArchiveSourceProvider.java`: workspace/source lifecycle only; no publication/supersession logic.

Review Operations aggregate/job/publication/supersession code and C-owned persistence/snapshot/logging production code remain untouched.
