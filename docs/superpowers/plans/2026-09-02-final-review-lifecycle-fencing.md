# Final Review Lifecycle and Fencing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close final-review findings C1, C2, I1, I2, and M1 plus the job/lifecycle/publication portion of I5 without discarding checkpoint `d547608`.

**Architecture:** Keep the durable job row and its monotonically increasing lease sequence as the PostgreSQL-backed operation fence. Propagate that live fence from each job handler into every application use case and GitHub publication mutation, renew synchronously at each irreversible boundary, and make PostgreSQL time authoritative for lease state. Resolve supersession from GitHub's authoritative head rather than receipt order, and reconcile persisted comments with the same missing-artifact semantics used for Checks.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Maven, PostgreSQL/Testcontainers, Spring `RestClient`, Micrometer, JUnit 5, AssertJ.

**Spec:** `.superpowers/sdd/2026-09-01-github-app-review-loop/final-review.md` and `docs/superpowers/specs/2026-08-29-github-app-production-review-design.md`

## Global Constraints

- Work only in `.worktrees/issue4-final-a`; do not push or merge.
- Do not edit analyzer/pipeline/source-workspace/Docker/Compose or datasource-pool/snapshot/logging-dependency surfaces.
- Preserve sample isolation: agent inputs remain only `diff.patch` and `source-before/`.
- Every behavior change follows RED, GREEN, REFACTOR and receives focused verification before the next finding.
- GitHub HTTP calls remain outside database transactions; low-cardinality metrics never tag run, repository, PR, SHA, or failure text.

---

### Task 1: Durable terminal convergence and truthful failure presentation

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/SettleReviewJobFailure.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/PresentReviewFailure.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRun.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/application/SettleReviewJobFailureTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/application/PresentReviewFailureTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/ReviewJobWorkerPostgresIntegrationTest.java`

**Interfaces:**
- Consumes: checkpoint `FinalJobFailureSettlement`, `PRESENT_REVIEW_FAILURE`, and aggregate failure state.
- Produces: job-kind-aware final settlement that recognizes durable decision/publication effects and a presenter that retracts confirmed comments or truthfully sets `codeCommentsMayRemain`.

- [ ] **Step 1:** Add a failing application test showing an exhausted publication with a persisted comment reaches `FAILED`, retains the reference until confirmed deletion, and asks the neutral presenter for a warning when cleanup cannot be confirmed.
- [ ] **Step 2:** Run the exact new test and confirm it fails because `PresentReviewFailure` neither retracts comments nor reports them.
- [ ] **Step 3:** Add minimal aggregate replacement/clearing behavior and presenter cleanup using exact fingerprint/reference validation; make the failure Check use `neutralSystemFailure(summary, commentsMayRemain)`.
- [ ] **Step 4:** Add failing PostgreSQL tests for final delivery and final lease expiry for `REVIEW_EXECUTION`, `DECIDE_PUBLICATION`, `PUBLISH_REVIEW`, `SUPERSEDE_OBSOLETE_RUNS`, and `PRESENT_REVIEW_FAILURE`, with literal expected job/run states.
- [ ] **Step 5:** Complete checkpoint settlement/recovery logic so each job is `DEAD` or `SUCCEEDED` consistently with its owning run and follow-up intent in one transaction.
- [ ] **Step 6:** Run the focused settlement, recovery, presenter, execution, and PostgreSQL tests to green.
- [ ] **Step 7:** Commit the C1/I1 slice.

### Task 2: Live lease fencing with PostgreSQL-authoritative time

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewJobHandler.java`
- Modify: all five `reviewops/application/jobs/*JobHandler.java` implementations
- Modify: `ExecuteReviewRun.java`, `DecideReviewPublication.java`, `PublishReviewOutcome.java`, `PresentReviewFailure.java`, `SupersedeObsoleteReviewRuns.java`
- Modify: `GitHubPublicationGateway.java`, `GitHubPublicationClient.java`
- Modify: `PostgresDurableJobQueue.java`, `ScheduledLeaseHeartbeat.java`
- Test: matching unit tests, `PostgresDurableJobQueueTest.java`, `ReviewJobWorkerPostgresIntegrationTest.java`, and `GitHubReviewLoopE2ETest.java`

**Interfaces:**
- Consumes: `OperationFence.requireCurrent()` and queue lease identity `(job id, owner, lease sequence)`.
- Produces: fenced overloads such as `publish(ReviewRunId, OperationFence)` and `upsertCheck(CheckRunRequest, OperationFence)`; each adapter calls the fence directly before POST/PATCH/DELETE.

- [ ] **Step 1:** Add failing handler/use-case tests whose fence is lost between a read/reconciliation step and each DB or GitHub mutation; assert no mutation occurs.
- [ ] **Step 2:** Run those tests and confirm the existing default handler path bypasses the fence after entry.
- [ ] **Step 3:** Propagate the fence through every production handler and call `requireCurrent()` immediately before every aggregate persistence/enqueue operation.
- [ ] **Step 4:** Add failing adapter tests that lose the fence after Check/comment listing and before POST/PATCH/DELETE; the production change that must fail them is removing the last-boundary renewal.
- [ ] **Step 5:** Implement fenced GitHub gateway overloads and renew inside the client directly before each mutating exchange.
- [ ] **Step 6:** Add failing Testcontainers tests proving a far-future/far-past process clock cannot acquire, renew, expire, or settle a lease early.
- [ ] **Step 7:** Replace queue lease comparisons and expiry calculations with `CURRENT_TIMESTAMP` plus a bounded duration parameter; retain caller `Instant` only for non-authority scheduling/audit inputs.
- [ ] **Step 8:** Add a controlled two-worker fake-GitHub test: worker A loses renewal after reconciliation, worker B recovers, and exactly one Check/comment plus one terminal run result exists.
- [ ] **Step 9:** Run all fence, queue, publication, PostgreSQL, and E2E tests to green.
- [ ] **Step 10:** Commit the C2 slice.

### Task 3: Authoritative supersession and stale execution outcome

**Files:**
- Modify: `SupersedeObsoleteReviewRuns.java`, `ObsoleteReviewRunStore.java`, `PostgresObsoleteReviewRunStore.java`
- Modify: `ExecuteReviewRun.java` and the smallest GitHub failure classification seam needed to represent stale source preparation.
- Test: `SupersedeObsoleteReviewRunsTest.java`, `SupersedeObsoleteReviewRunsPostgresIntegrationTest.java`, `ExecuteReviewRunTest.java`, `GitHubReviewLoopE2ETest.java`

**Interfaces:**
- Consumes: `GitHubPublicationGateway.authoritativeRevision(PullRequestRevision)` outside transactions.
- Produces: current source run is superseded when it does not match GitHub; only the run matching GitHub can supersede every active different-head candidate independent of receipt time.

- [ ] **Step 1:** Add failing unit tests for A then B, delayed A after B, equal receipt times, and force-push back to A.
- [ ] **Step 2:** Confirm they fail because candidate selection and revalidation compare `requestedAt`.
- [ ] **Step 3:** Read authoritative head before mutation; supersede the source itself when stale; otherwise select/revalidate all active different-head runs without timestamp ordering.
- [ ] **Step 4:** Add a failing execution test showing exact-head mismatch yields `SUPERSEDED`, no failure intent, and no neutral Check job.
- [ ] **Step 5:** Introduce the narrow stale-revision classification/outcome seam and persist supersession under the live fence.
- [ ] **Step 6:** Run supersession unit/PostgreSQL/E2E and execution tests to green.
- [ ] **Step 7:** Commit the I2 slice.

### Task 4: Persisted comment self-healing

**Files:**
- Modify: `InlineCommentArtifact.java`, `GitHubPublicationGateway.java`, `GitHubPublicationClient.java`, `PublishReviewOutcome.java`, `ReviewRun.java`
- Test: `GitHubPublicationClientTest.java`, `PublishReviewOutcomeTest.java`, `PublishReviewOutcomePostgresIntegrationTest.java`

**Interfaces:**
- Consumes: exact marker, App id, repository/PR/head/path/line, and persisted `PublicationReference`.
- Produces: comment reconciliation result `CONFIRMED`, `RECONCILED`, `CREATED`, or `REPLACED_MISSING`, plus aggregate replacement guarded by the expected stale ID.

- [ ] **Step 1:** Add a failing adapter test: GET persisted comment returns 404, marker search returns none, one replacement POST occurs, and retry validates the replacement.
- [ ] **Step 2:** Add failing cases rejecting wrong App, head, path, line, side, and marker for a persisted ID.
- [ ] **Step 3:** Implement persisted-ID GET/validation, exact-marker search fallback, and safe recreation under the live operation fence.
- [ ] **Step 4:** Add a failing aggregate/application test proving a `REPLACED_MISSING` comment replaces the old durable reference before publication finishes.
- [ ] **Step 5:** Implement expected-old-ID guarded replacement on `ReviewRun` and persist it immediately.
- [ ] **Step 6:** Run adapter/application/PostgreSQL publication tests to green.
- [ ] **Step 7:** Commit the M1 slice.

### Task 5: Job, lifecycle, stale-prevention, and publication metrics

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/ReviewOperationsTelemetry.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/metrics/MicrometerReviewOperationsTelemetry.java`
- Modify: owned application use cases and the minimal `ServerConfiguration.java` bean wiring.
- Test: use-case metric contract tests and existing worker metric tests.

**Interfaces:**
- Consumes: Micrometer `MeterRegistry` only in the infrastructure adapter.
- Produces: low-cardinality counters/timers for terminal lifecycle outcomes, superseded source/candidates, prevented stale publication/presentation, publication outcome/tier/comment count, and publication/GitHub stages; worker `retried`, `rate_limited`, and `dead` remain disposition-driven.

- [ ] **Step 1:** Add failing `SimpleMeterRegistry` contract tests for retry-bound `dead`, authoritative supersession, prevented stale mutation, Check/comment publication, and terminal failure presentation.
- [ ] **Step 2:** Implement a narrow telemetry port and Micrometer adapter with fixed meter/tag names and no identifiers or error text.
- [ ] **Step 3:** Time application publication/supersession/failure-presentation boundaries and increment counters only after the corresponding durable or remote fact is confirmed.
- [ ] **Step 4:** Wire the adapter in the smallest possible `ServerConfiguration` hunk and preserve source-compatible constructors for focused tests.
- [ ] **Step 5:** Run metric contract tests plus all owned focused tests.
- [ ] **Step 6:** Commit the I5 slice.

### Task 6: Final verification, self-review, and report

**Files:**
- Create: `.superpowers/parallel-a-report.md`
- Modify: this plan only to mark completed steps if useful.

**Interfaces:**
- Consumes: all binding outcomes and fresh command output.
- Produces: clean committed branch and an integration report listing conflict-risk files.

- [ ] **Step 1:** Run all owned focused unit, Testcontainers PostgreSQL, fake-GitHub concurrency, and E2E tests.
- [ ] **Step 2:** Run `mvn test`, `mvn -q clean package -DskipTests`, and `git diff --check`.
- [ ] **Step 3:** Self-review the full `d547608..HEAD` diff for unsafe failure classification, missing fence boundaries, test tautologies, secrets, and out-of-scope edits.
- [ ] **Step 4:** Write `.superpowers/parallel-a-report.md` with C1/C2/I1/I2/M1/I5 evidence, exact tests, commits, and conflict risks.
- [ ] **Step 5:** Commit the report/plan if not already committed and verify `git status --short` is empty.
