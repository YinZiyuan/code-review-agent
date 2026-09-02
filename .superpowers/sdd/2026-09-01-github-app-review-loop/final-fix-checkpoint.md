# Final-fix checkpoint

Date: 2026-09-02

Branch: `feature-0901`

Starting commit: `8d05f84fdb489847f4e24119ea49ccd7664a0781`

This is a deliberate WIP checkpoint made when the final-fix effort changed to a
three-worktree parallel plan. It is not a claim that the whole-branch review is
closed.

## Finding status

### Completed

None. Each finding below still lacks at least one acceptance test or required
production boundary from the final review.

### Partially addressed

- **C1 — terminal job convergence.** Added a job-kind-aware final-settlement port,
  transactional PostgreSQL settlement of the aggregate/follow-up intent/job row,
  actual durable `RETRY_SCHEDULED`/`DEAD`/`SUCCEEDED` dispositions, generalized
  exhausted-lease recovery for all known job kinds, and correct worker dead-vs-retry
  accounting. Remaining: exhaustive PostgreSQL/E2E maximum-attempt and final-lease
  cases for every job kind, and truthful cleanup/presentation semantics when an
  exhausted publication already has confirmed inline comments.
- **I1 — neutral terminal execution presentation.** Terminal model/source failures
  and a reclaimed final `RUNNING` attempt now atomically persist `FAILED` plus a
  `PRESENT_REVIEW_FAILURE` intent. The presenter rechecks authoritative head,
  reconciles a neutral Check, publishes zero comments, and uses only a bounded reason
  code in its summary. Failed runs with a confirmed neutral Check can be
  reconstituted. Remaining: real PostgreSQL/fake-GitHub E2E coverage for invalid
  output, deterministic source failure, and exhausted transient execution.
- **C2 — live handler fence foundation.** Added `OperationFence`, fenced dispatcher
  entry, a fresh renewal before handler dispatch, handler-thread binding, and
  interrupt-on-asynchronous-fence-loss. Remaining: carry the fence into every use
  case and immediately before each aggregate and GitHub mutation, use PostgreSQL
  time for all lease authority, add per-run publication serialization, and add the
  specified two-worker exact-artifact tests. The current fence is therefore only an
  entry/cancellation foundation, not complete publication fencing.
- **I5 — final job outcome metric only.** The worker now records the disposition
  returned by durable settlement, so a final transient/rate-limited attempt is
  counted `dead` rather than `retried`/`rate_limited`. The rest of the required
  metrics, timers, gauges, correlated JSON logs, and redaction contracts are not
  started.

### Unstarted

- **C3** — typed/versioned work budget and isolated bounded analyzers/model context.
- **I2** — authoritative-head supersession and stale-source outcome semantics.
- **I3** — one owned temp workspace, reliable cleanup, and safe janitor/obligation.
- **I4** — bounded Hikari/database deadlines, recovery index, and retention.
- **I6** — runtime-derived immutable configuration snapshot/business identity.
- **M1** — persisted comment verification and safe stale-ID replacement.

## Shared interfaces and contracts introduced

- `DurableJobQueue.settleFailure(...)` and `FailureDisposition` expose the state
  actually persisted instead of making the worker infer it from the requested retry.
- `FinalJobFailureSettlement` is the application callback invoked inside the queue's
  final-settlement transaction; it returns an aggregate disposition plus durable
  follow-up jobs.
- `ExpiredJobLeaseRecovery.recoverWithIntents(...)` and `RecoverySettlement` let
  exhausted lease recovery enqueue follow-up intents atomically.
- `OperationFence`, `ReviewJobHandler.handle(job, fence)`, and heartbeat
  `beginHandling`/`endHandling` form the shared C2 seam. Existing handlers currently
  receive only the default entry check; follow-on C2 work must propagate this object
  to mutation boundaries.
- `PRESENT_REVIEW_FAILURE`, `PresentReviewFailure`, and
  `ReviewFailurePresentationJobHandler` form the neutral failure-presentation path.
- `ReviewRun.recordJobSystemFailure(...)` and
  `recordFailurePresentationCheck(...)` are new aggregate behaviors used by C1/I1.

## Files touched

Production:

- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/ExecuteReviewRun.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/PresentReviewFailure.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/RecoverExpiredReviewExecution.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/SettleReviewJobFailure.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/DurableJobQueue.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ExpiredJobLeaseRecovery.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/FinalJobFailureSettlement.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/OperationFence.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewFailurePresentationJobHandler.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewJobDispatcher.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewJobHandler.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewJobWorker.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ScheduledLeaseHeartbeat.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRun.java`
- `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/jobs/PostgresDurableJobQueue.java`
- `src/main/java/dev/langchain4j/example/codereview/server/ServerConfiguration.java`

Tests:

- `src/test/java/dev/langchain4j/example/codereview/reviewops/application/ExecuteReviewRunTest.java`
- `src/test/java/dev/langchain4j/example/codereview/reviewops/application/PresentReviewFailureTest.java`
- `src/test/java/dev/langchain4j/example/codereview/reviewops/application/RecoverExpiredReviewJobTest.java`
- `src/test/java/dev/langchain4j/example/codereview/reviewops/application/SettleReviewJobFailureTest.java`
- `src/test/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewJobDispatcherTest.java`
- `src/test/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewJobWorkerTest.java`
- `src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRunReconstitutionTest.java`
- `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/jobs/PostgresDurableJobQueueTest.java`
- `src/test/java/dev/langchain4j/example/codereview/server/ServerReadinessTest.java`

## TDD record

- RED: `mvn -Dtest=ExecuteReviewRunTest#invalidPipelineOutputAtomicallyRequestsNeutralFailurePresentationAndClosesSource test` — expected the new atomic failure intent; only progress persistence occurred.
- GREEN: `mvn -Dtest=ExecuteReviewRunTest#invalidPipelineOutputAtomicallyRequestsNeutralFailurePresentationAndClosesSource+exhaustedFormatRepairIsARequiredTerminalOutputFailure test` — 2 passed.
- RED: `mvn -Dtest=PresentReviewFailureTest test` — presenter did not exist.
- GREEN: `mvn -Dtest=PresentReviewFailureTest,ExecuteReviewRunTest test` — 11 passed.
- RED: `mvn -Dtest=ReviewJobWorkerTest#transientFailureAtDeliveryBoundIsReportedDeadInsteadOfRetried test` — final delivery was reported as retried.
- GREEN: same command — 1 passed.
- RED/GREEN: focused `SettleReviewJobFailureTest`, `PostgresDurableJobQueueTest`, dispatcher, server wiring, and expired-recovery tests first failed on their missing ports/beans and then passed after the corresponding production slice.
- RED: `mvn -Dtest=ExecuteReviewRunTest#reclaimedFinalRunningAttemptAtomicallyRequestsNeutralFailurePresentation test` — `FAILED` was saved without the neutral intent.
- GREEN: same command — 1 passed.
- RED: `mvn -Dtest=ReviewJobWorkerTest#handlerReceivesALiveFenceAndFenceLossPreventsFurtherMutation+asynchronousFenceLossInterruptsTheBoundHandler test` — the live fence/session hooks did not exist.
- GREEN: same command — 2 passed.
- RED: `mvn -Dtest=ReviewRunReconstitutionTest#reconstitutesFailedExecutionAfterNeutralCheckWasConfirmed+reconstitutesFailedDecisionAfterNeutralCheckWasConfirmed test` — both restored neutral-Check states were rejected.
- GREEN: same command — 2 passed.

## Checkpoint verification

- Pre-change branch baseline: `mvn test` — 518 tests passed, zero failures/errors/skips.
- Current focused suite:
  `mvn -Dtest=ExecuteReviewRunTest,PresentReviewFailureTest,RecoverExpiredReviewJobTest,SettleReviewJobFailureTest,ReviewJobWorkerTest,ReviewJobDispatcherTest,PostgresDurableJobQueueTest,ServerReadinessTest,ReviewRunTest,ReviewRunReconstitutionTest test`
  — 118 tests passed, zero failures/errors/skips; `BUILD SUCCESS`.
- `git diff --check` — passed.
- Staged added-line credential-signature and literal-credential assignment scans —
  no matches.
- `git diff --cached --name-only -- eval/reports` — no staged local evaluation
  reports.
- No migration was added; existing V1-V6 compatibility remains unchanged in this checkpoint.
- A post-change full suite, Docker smoke, Compose health run, and final exhaustive
  concurrency/E2E verification remain for the integrated final-fix branch.

## Known continuation concerns

- The neutral presenter currently states that no comments were published. Before C1
  is declared complete, the publication-exhaustion path must preserve the accepted
  partial-comment cleanup ruling and produce a truthful warning when comments may
  remain.
- `OperationFence` is not yet passed beyond handler entry. Do not treat this
  checkpoint as satisfying the at-most-once remote-artifact guarantee.
- Lease timestamps still use the injected process clock; PostgreSQL-clock authority
  remains part of C2 continuation work.
