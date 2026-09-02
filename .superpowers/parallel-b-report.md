# Parallel stream B report

Status: **DONE for C3, I3/Task 6, and the assigned I5 pipeline/resource slice.**

Branch: `codex/issue4-final-b`

Base checkpoint: `d547608`

## Commits

- `127077a docs: plan review work budget hardening`
- `64e7bab feat: add versioned review work budget`
- `02342be feat: bound review pipeline context and stages`
- `1ef58ed feat: isolate analyzers in bounded processes`
- `6214f19 feat: own review artifacts and bound runtime resources`
- `0650113 fix: reserve model framing and bound source discovery`
- `adcf8cc test: cover analyzer and cleanup failure interleavings`

## C3 outcome

- Added one immutable `ReviewWorkBudget` (`review-work-v1`) with a stable, non-secret SHA-256 configuration hash. It covers diff bytes/tokens, changed-file count, Java file/source-byte totals, archive/download/expansion/entry totals, global snippets/findings, model context, completion and input-framing reserves, subprocess output, javac/SpotBugs heap, all four pipeline stage deadlines, both analyzer deadlines, and workspace stale age. Stream C can consume `version()` and `configurationHash()` when constructing `ReviewConfigurationSnapshot`.
- `DiffAnalyzer`, `ToolFindingsProducer`, and `Summarizer` enforce deterministic global file/snippet/finding limits.
- Added CL100K tokenizer-backed, deterministic hunk selection and section truncation. The prompt budget subtracts both the requested completion allowance and a fixed typed chat-framing allowance; `LlmReviewer` sets `maxOutputTokens` to the same completion reserve.
- Each pipeline stage has a wall-clock deadline, cancellation, a bounded four-worker/16-queued-task executor, and safe capacity failure. A model/tool operation that ignores interruption cannot make the queue grow without bound.
- javac now runs as a child process using the current JDK's executable, a source argument file, `-proc:none`, `-J-Xmx`, the compiler deadline, recursively terminated descendants, and bounded/discarded diagnostics. Java discovery stops immediately at the file/byte bound.
- SpotBugs uses the same bounded process runner, explicit `-maxHeap`, its own deadline, bounded stdout/stderr, and a bounded XML report. Timeout, cancellation, missing binary, exit 137/OOM-equivalent, compile failure, and oversize report are safe non-blocking tool outcomes. No raw compiler/analyzer diagnostic is logged or returned as the safe reason.
- `Dockerfile` constrains the runtime JVM with container-aware heap/direct-memory bounds and exit-on-OOM. `compose.yml` gives app and PostgreSQL explicit memory, CPU, and PID limits.

## I3 and ledger Task 6 outcome

- `GitHubArchiveSourceProvider` now creates one `code-review-work-v1-*` workspace before exact-revision preparation. That root owns the exact marker, archive, extracted `source/`, compiler classes, javac argument file, and SpotBugs report.
- The prepared source remains the top-level `AutoCloseable` owner. Analyzer scopes reuse that workspace and remove their own classes/report on success, compile failure, analyzer failure, timeout, and cancellation. CLI/external-source analysis receives a standalone marker-bearing workspace with the same cleanup contract.
- Cleanup deletes the marker last. Any failure exposes only the path-free `ReviewWorkspaceCleanupException`, leaves/restores the exact marker, and therefore retains a safe local retry obligation even when a pipeline failure is already primary and cleanup is suppressed.
- Startup and hourly cleanup inspect only direct temporary-root children with the exact reserved prefix, exact small marker content, real-directory/no-symlink checks, and configured age threshold. It never follows a workspace-named symlink or persists an arbitrary path. This closes the ledger's Task 6 retry-owner gap.

## I5 pipeline/resource slice

- `code.review.pipeline.stage.duration`: fixed `stage` and `outcome` tags.
- `code.review.pipeline.process.duration`: fixed `process` and `outcome` tags.
- `code.review.pipeline.tokens`: `prompt_estimated`, `input_actual`, and `output_actual` kinds.
- `code.review.pipeline.prompt`: `full` or `truncated` outcome.
- `code.review.work.budget.info`: effective version/hash.
- `code.review.work.budget.limit`: fixed-name gauges for every input, prompt, heap, output, deadline, and cleanup-age bound.
- Identifiers, paths, source, diagnostics, and secrets are not metric tags.

## Adversarial verification

- Final full suite: `mvn test` — **569 tests, 0 failures/errors/skips**, including Testcontainers and server E2E (`2026-09-02 16:34 +08:00`).
- Focused tests cover many-file/source-byte limits, deterministic oversized Unicode prompt/context truncation, completion/framing reserve, bounded process output, real process-tree timeout, cancellation/interrupt restoration, javac exit-137 equivalent, secret-bearing syntax errors, SpotBugs timeout/failure, success/compile-failure/analyzer-failure cleanup, simultaneous pipeline+cleanup failure, restart recovery, wrong/missing markers, and symlink escape safety.
- `docker compose -f compose.yml config --format json` plus `jq` verified effective byte/CPU/PID limits.
- `docker build -t code-review-agent:issue4-final-b .` — success.
- `./scripts/smoke-review-runtime.sh code-review-agent:issue4-final-b` — PASS; the shipped non-root image ran bounded javac and SpotBugs against a compilable fixture.
- `git diff --check d547608..HEAD` — clean.

## Cross-stream stale-revision integration

Stream A's `StaleReviewRevisionException` does not exist at this branch's checkpoint. Cherry-picking A's 30-file checkpoint would pollute ownership and create conflicts, so no A code was copied. After A checkpoint `b2a543c` is integrated, replace the generic mismatch in `GitHubRestClient.requireExactPullRequestHead` with:

```java
import dev.langchain4j.example.codereview.reviewops.application.github.StaleReviewRevisionException;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;

validateFullCommitSha(authoritativeHead);
if (!revision.headSha().equals(authoritativeHead)) {
    throw new StaleReviewRevisionException(new AuthoritativeRevision(authoritativeHead));
}
```

Keep invalid/non-full authoritative SHA metadata on the existing deterministic-invalid-metadata path. Update the exact-head mismatch tests in `GitHubRestClientTest` and the two provider mismatch cases to assert `StaleReviewRevisionException.authoritativeRevision()` equals `new AuthoritativeRevision(authoritativeHead)`. `ExecuteReviewRun` must remain Stream A's owner; its new catch will then durably transition the run to `SUPERSEDED`.

## Conflict-risk files and exact hunks

- `pom.xml:35-39`: explicit `jtokkit:1.1.0` dependency.
- `ServerConfiguration.java:5`, `:54`, and `:292-299`: imports `ReviewWorkBudget`/`ReviewWorkspaceFactory` and replaces six source-provider constants with the typed dependencies. This is the only intentional ServerConfiguration hunk and is likely to overlap streams A/C.
- `AgentConfig.java:52-91`: bounded javac/SpotBugs process and analyzer bean wiring.
- `GitHubArchiveSourceProvider.java`: constructor changes from primitive limits/temp path to `ReviewWorkspaceFactory` + `ReviewWorkBudget`; preparation lifecycle now belongs to the workspace.
- `Dockerfile`, `compose.yml`, and `scripts/smoke-review-runtime.sh`: runtime/resource wiring.
- `ReviewWorkBudget*` files are new/focused; no shared application YAML was edited.
- Pipeline constructors changed to accept the budget, prompt assembler, stage executor, and meter registry; affected tests were updated.

No Review Operations aggregate/job/publication/supersession production code, datasource/migration/retention code, snapshot identity, or central structured logging dependency was edited.
