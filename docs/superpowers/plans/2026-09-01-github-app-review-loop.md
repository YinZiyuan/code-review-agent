# GitHub App Review Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver Issue #4 as a production-shaped, non-blocking GitHub App path from signed pull-request webhook through durable review execution to idempotent Check Run and inline-comment publication.

**Architecture:** Extend the existing modular monolith with a Servlet `serve` mode, thin GitHub adapters, application use cases, and the already-merged PostgreSQL review-run/job/outbox foundation. Webhook intake commits only verified delivery and review intent; leased workers call the existing deterministic `CodeReviewAgent`; publication always rechecks the authoritative head SHA and persists each confirmed GitHub artifact before retrying anything else.

**Tech Stack:** Java 17, Spring Boot 3.5.6 MVC/Actuator, picocli, Spring JDBC transactions, PostgreSQL 17/Flyway, Java `HttpClient` or Spring `RestClient`, Jackson, JUnit 5, AssertJ, MockMvc, Testcontainers PostgreSQL, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-29-github-app-production-review-design.md`

## Global Constraints

- Keep `review`, `eval`, and `sample` startup database-free and `WebApplicationType.NONE`.
- `serve` uses blocking Spring MVC because Git, JDBC, analyzers, and the model path are blocking.
- Domain code must not depend on Spring, JDBC, GitHub clients, LangChain4j, or evaluator-only types.
- Accept only signed `pull_request` events for `opened`, `reopened`, and `synchronize`; return quickly with HTTP 202 after transactional admission.
- Never persist or log the webhook secret, App private key, installation token, model API key, authorization header, or full webhook payload.
- Bind every review and publication artifact to the exact observed head SHA; stale runs publish no Check/comment mutations.
- Final review-system failures are neutral and non-blocking; the application never approves, requests changes, or merges a pull request.
- Preserve sample isolation: production Review Operations must not depend on `eval.Annotation`, `eval.ExpectedIssue`, or `eval.Sample`.
- Use Flyway for every schema change and Testcontainers PostgreSQL for locking/transaction behavior.
- Keep external calls outside database transactions; persist state transitions and durable follow-up intents atomically.

---

## File and Responsibility Map

- `CodeReviewApplication.java` and `server/ApplicationMode.java`: choose CLI or server before creating the Spring context.
- `server/GitHubWebhookController.java`: HTTP-only header/body/status mapping.
- `reviewops/application/github/*`: verified webhook facts and GitHub/source/publication ports.
- `reviewops/application/ObservePullRequestRevision.java`: convert verified PR facts into idempotent durable review intent.
- `reviewops/application/ExecuteReviewRun.java`: lease-safe orchestration around exact source preparation and the existing reviewer.
- `reviewops/application/DecideReviewPublication.java`: apply the pure policy and enqueue publication.
- `reviewops/application/PublishReviewOutcome.java`: authoritative-head guard and partial publication reconciliation.
- `reviewops/application/jobs/ReviewJobWorker.java`: lease, dispatch, backoff, and completion of durable jobs.
- `reviewops/infrastructure/github/*`: HMAC, GitHub App auth, REST, exact-SHA archive preparation, Checks, and comments.
- `reviewops/infrastructure/persistence/*`: transactional delivery admission and review-run mutations.
- `server/ServerConfiguration.java`: server-only wiring, scheduling, health, and metrics.

---

### Task 1: Add explicit CLI and server runtime modes

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/server/ApplicationMode.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/server/ServerProperties.java`
- Create: `src/main/resources/application-server.yml`
- Modify: `src/main/java/dev/langchain4j/example/codereview/CodeReviewApplication.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/cli/CliRunner.java`
- Modify: `src/main/resources/application.yml`
- Modify: `pom.xml`
- Test: `src/test/java/dev/langchain4j/example/codereview/server/ApplicationModeTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/CodeReviewApplicationCliStartupTest.java`

**Interfaces:**
- Produces: `ApplicationMode.select(String[] args): Selection` where `Selection` exposes `serverMode()` and `applicationArgs()`.
- Produces: `ServerProperties(GitHub github, Worker worker)` under `code-review.server`.
- Consumes: Spring profile `server`; `CliRunner` is active only when `code-review.runtime=cli`.

- [ ] **Step 1: Write failing mode-selection and startup tests**

```java
@Test
void serveSelectsServletModeAndRemovesTheModeToken() {
    ApplicationMode.Selection selection = ApplicationMode.select(new String[]{"serve", "--server.port=0"});
    assertThat(selection.serverMode()).isTrue();
    assertThat(selection.applicationArgs()).containsExactly("--server.port=0");
}

@Test
void cliRemainsTheDefault() {
    ApplicationMode.Selection selection = ApplicationMode.select(new String[]{"review", "--help"});
    assertThat(selection.serverMode()).isFalse();
    assertThat(selection.applicationArgs()).containsExactly("review", "--help");
}
```

Extend `CodeReviewApplicationCliStartupTest` to assert `--help` still exits without datasource configuration after adding web/JDBC server dependencies.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `mvn -Dtest=ApplicationModeTest,CodeReviewApplicationCliStartupTest test`

Expected: compilation failure because `ApplicationMode` does not exist.

- [ ] **Step 3: Implement minimal dual-mode bootstrap**

`CodeReviewApplication.main` must select mode before `SpringApplication.run`, set `WebApplicationType.SERVLET` plus profile/property `code-review.runtime=server` for `serve`, and retain `WebApplicationType.NONE` plus `code-review.runtime=cli` otherwise. Add `spring-boot-starter-web` and `spring-boot-starter-actuator`; move server datasource/Flyway requirements into `application-server.yml`. Gate `CliRunner` with `@ConditionalOnProperty(name="code-review.runtime", havingValue="cli", matchIfMissing=true)`.

- [ ] **Step 4: Run focused and full tests**

Run: `mvn -Dtest=ApplicationModeTest,CodeReviewApplicationCliStartupTest test`

Expected: PASS.

Run: `mvn test`

Expected: all existing tests pass without a configured datasource for CLI tests.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/dev/langchain4j/example/codereview/CodeReviewApplication.java \
  src/main/java/dev/langchain4j/example/codereview/cli/CliRunner.java \
  src/main/java/dev/langchain4j/example/codereview/server/ApplicationMode.java \
  src/main/java/dev/langchain4j/example/codereview/server/ServerProperties.java \
  src/main/resources/application.yml src/main/resources/application-server.yml \
  src/test/java/dev/langchain4j/example/codereview/server/ApplicationModeTest.java \
  src/test/java/dev/langchain4j/example/codereview/CodeReviewApplicationCliStartupTest.java
git commit -m "feat(server): add explicit serve runtime"
```

### Task 2: Verify and parse GitHub pull-request webhooks

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/github/VerifiedPullRequestEvent.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/GitHubWebhookVerifier.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/PullRequestWebhookParser.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/GitHubWebhookVerifierTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/PullRequestWebhookParserTest.java`

**Interfaces:**
- Produces: `GitHubWebhookVerifier.verify(byte[] payload, String signature): boolean`.
- Produces: `PullRequestWebhookParser.parse(String deliveryId, String eventName, byte[] payload): ParseResult`.
- Produces: `VerifiedPullRequestEvent(deliveryId, action, installationId, repositoryId, repositoryFullName, pullRequestNumber, headSha, cloneUrl, observedAt)`.

- [ ] **Step 1: Write failing HMAC tests**

Cover valid `sha256=` signature, wrong secret, wrong prefix, malformed hex, null signature, and raw-byte sensitivity. Compute the expected signature in the test with an independent JCA `Mac` call and assert invalid cases return `false` without including secret/payload text in exceptions.

- [ ] **Step 2: Run verifier tests and verify RED**

Run: `mvn -Dtest=GitHubWebhookVerifierTest test`

Expected: compilation failure because the verifier is missing.

- [ ] **Step 3: Implement constant-time verification**

Use `HmacSHA256`, strict 64-character lowercase/uppercase hex decoding after `sha256=`, and `MessageDigest.isEqual`. The constructor accepts a nonblank secret byte array and clones it; no `toString` exposes it.

- [ ] **Step 4: Write failing parser tests**

Use compact JSON fixtures for `opened`, `reopened`, `synchronize`, `closed`, and malformed payloads. Assert interested actions produce the exact IDs/SHA/repository facts; other event types/actions produce `IGNORED`; malformed interested payloads produce `INVALID` with a fixed safe reason code.

- [ ] **Step 5: Implement bounded parsing and run tests**

Use Jackson tree parsing with an explicit maximum payload size from `ServerProperties.GitHub.maxWebhookBytes`. Never retain the original payload in `VerifiedPullRequestEvent`.

Run: `mvn -Dtest=GitHubWebhookVerifierTest,PullRequestWebhookParserTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/application/github \
  src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github \
  src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github
git commit -m "feat(github): verify and parse pull request webhooks"
```

### Task 3: Admit verified deliveries and review intent atomically

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/PullRequestObservationStore.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/ObservePullRequestRevision.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/JdbcPullRequestObservationStore.java`
- Create: `src/main/resources/db/migration/V4__index_github_delivery_handling.sql`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/application/ObservePullRequestRevisionTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/JdbcPullRequestObservationStoreTest.java`
- Modify: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/ReviewOperationsMigrationTest.java`

**Interfaces:**
- Produces: `PullRequestObservationStore.admit(ObservationRequest): ObservationResult`.
- Produces: `ObservePullRequestRevision.observe(VerifiedPullRequestEvent): ObservationResult`.
- `ObservationResult` distinguishes `ADMITTED`, `DUPLICATE_DELIVERY`, and `EXISTING_REVISION` and always returns the authoritative `ReviewRunId` when known.

- [ ] **Step 1: Write failing application tests**

Use an in-memory fake `PullRequestObservationStore`. Assert `ObservePullRequestRevision` creates a deterministic configuration snapshot, a new `ReviewRunId`, a `REVIEW_EXECUTION` job with payload reference equal to the run ID, max attempts equal to configuration, and idempotency key `review-execution:<run-id>`.

- [ ] **Step 2: Verify application RED**

Run: `mvn -Dtest=ObservePullRequestRevisionTest test`

Expected: compilation failure for missing use case/contracts.

- [ ] **Step 3: Implement the application use case**

Inject `PullRequestObservationStore`, `Clock`, and `ReviewConfigurationSnapshot`. Map only the verified facts into `PullRequestRevision`; compute `payloadSha256` before discarding request bytes at the controller boundary.

- [ ] **Step 4: Write PostgreSQL transaction tests**

With Testcontainers, assert:

```java
assertThat(store.admit(request).status()).isEqualTo(ADMITTED);
assertThat(store.admit(request).status()).isEqualTo(DUPLICATE_DELIVERY);
assertThat(reviewRunCount()).isEqualTo(1);
assertThat(durableJobCount()).isEqualTo(1);
```

Also assert a different delivery for the same business identity returns `EXISTING_REVISION`, and injected failures after delivery insert, run insert, or job insert roll back all three tables.

- [ ] **Step 5: Implement JDBC admission**

Inside one `TransactionOperations.execute`, insert `github_deliveries` with `ON CONFLICT DO NOTHING`; query the existing review business identity before insert; insert the new aggregate and enqueue its immutable job only when absent; update `handled_at` only after intent exists. Add an index on `github_deliveries(received_at)` and retain the existing delivery primary key.

- [ ] **Step 6: Run focused and migration tests**

Run: `mvn -Dtest=ObservePullRequestRevisionTest,JdbcPullRequestObservationStoreTest,ReviewOperationsMigrationTest test`

Expected: PASS against PostgreSQL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/application \
  src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence \
  src/main/resources/db/migration/V4__index_github_delivery_handling.sql \
  src/test/java/dev/langchain4j/example/codereview/reviewops
git commit -m "feat(reviewops): admit webhook review intent atomically"
```

### Task 4: Expose the fast webhook HTTP boundary

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/server/GitHubWebhookController.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/server/WebhookExceptionHandler.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/server/ServerConfiguration.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/server/GitHubWebhookControllerTest.java`

**Interfaces:**
- Consumes headers `X-Hub-Signature-256`, `X-GitHub-Delivery`, and `X-GitHub-Event` plus raw `byte[]` body.
- Produces HTTP 202 for admitted, duplicate, existing-revision, and ignored verified events; 401 for invalid signature; 400 for malformed required headers or interested payloads; 413 for payload above the configured bound.

- [ ] **Step 1: Write failing MockMvc tests**

Use `@WebMvcTest(GitHubWebhookController.class)` with mocked verifier/parser/use case. Capture the exact bytes passed to the verifier. Assert signature failure never invokes the parser or admission use case and response bodies contain only fixed error codes.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -Dtest=GitHubWebhookControllerTest test`

Expected: missing controller/configuration classes.

- [ ] **Step 3: Implement controller and safe status mapping**

Keep the controller free of JDBC/GitHub client logic. Add counters for received, signature failure, duplicate, ignored, and invalid events using fixed low-cardinality tags (`event`, `outcome` only).

- [ ] **Step 4: Run focused tests**

Run: `mvn -Dtest=GitHubWebhookControllerTest test`

Expected: PASS and no payload/secret in captured logs.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/server \
  src/test/java/dev/langchain4j/example/codereview/server
git commit -m "feat(server): accept signed GitHub webhooks"
```

### Task 5: Add GitHub App authentication and exact-SHA source preparation

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/github/GitHubInstallationGateway.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/github/PreparedReviewSource.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/github/ReviewSourceProvider.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/GitHubAppJwtFactory.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/GitHubRestClient.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/GitHubArchiveSourceProvider.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/GitHubAppJwtFactoryTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/GitHubRestClientTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/GitHubArchiveSourceProviderTest.java`

**Interfaces:**
- Produces: `GitHubInstallationGateway.token(long installationId): InstallationToken` with in-memory expiry only.
- Produces: `ReviewSourceProvider.prepare(PullRequestRevision): PreparedReviewSource`.
- `PreparedReviewSource` exposes `diffPatch()`, `sourceRoot()`, and `close()` for deterministic cleanup.

- [ ] **Step 1: Write failing JWT/token tests**

Generate an ephemeral RSA key pair. Decode and verify the JWT signature independently; assert `iat`, `exp <= iat + 600`, and numeric `iss`. Stub installation-token responses and assert cached tokens are refreshed before expiry without logging bearer/private-key text.

- [ ] **Step 2: Implement auth with standard JCA**

Parse only PKCS#8 PEM, build RS256 JWT with Jackson/Base64 URL encoding and `SHA256withRSA`, and cache installation tokens in a bounded `ConcurrentHashMap<Long, InstallationToken>` until `expiresAt - refreshSkew`.

- [ ] **Step 3: Write failing exact-source tests**

Stub GitHub endpoints for PR diff and repository zipball at the exact `headSha`. Assert extracted files land under a fresh temp root, the root directory prefix is stripped, `../` and absolute ZIP entries are rejected, size/file-count limits are enforced, and `close()` removes the directory.

- [ ] **Step 4: Implement REST and archive preparation**

Use `RestClient` with explicit GitHub API version/Accept headers. Fetch the PR diff and `/repos/{owner}/{repo}/zipball/{headSha}` using the installation token in an in-memory header. Never put tokens in URLs, process arguments, exception messages, or persisted job payloads.

- [ ] **Step 5: Run adapter tests**

Run: `mvn -Dtest=GitHubAppJwtFactoryTest,GitHubRestClientTest,GitHubArchiveSourceProviderTest test`

Expected: PASS, including ZIP-slip and secret-redaction assertions.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/application/github \
  src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github \
  src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github
git commit -m "feat(github): prepare exact review source safely"
```

### Task 6: Execute persisted review runs through the existing pipeline

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/ReviewRunMutationStore.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/ExecuteReviewRun.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/ReviewFindingMapper.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/TransactionalReviewRunMutationStore.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRun.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRunTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/application/ReviewFindingMapperTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/application/ExecuteReviewRunTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/TransactionalReviewRunMutationStoreTest.java`

**Interfaces:**
- Produces: `ExecuteReviewRun.execute(ReviewRunId): ExecutionOutcome`.
- Produces: `ReviewFindingMapper.map(model.ReviewFinding, FileDiffSet): reviewops.domain.ReviewFinding`.
- Produces: `ReviewRun.recoverInterruptedAttempt(ReviewFailure, Instant)` for a persisted `RUNNING/STARTED` attempt reclaimed after lease expiry.
- `ReviewRunMutationStore.saveAndEnqueue(run, expectedVersion, jobs, events)` performs one transaction.

- [ ] **Step 1: Write the interrupted-attempt domain test**

```java
ReviewRun run = requestedRun();
run.startAttempt(t0);
run.recoverInterruptedAttempt(transientFailure("worker_interrupted"), t1);
assertThat(run.state()).isEqualTo(REQUESTED);
assertThat(run.attempts().get(0).state()).isEqualTo(TRANSIENT_FAILURE);
```

Also assert recovery is rejected outside `RUNNING`, respects max-attempt exhaustion, and stores only a safe fixed message.

- [ ] **Step 2: Verify RED, implement the minimal domain behavior, and rerun**

Run: `mvn -Dtest=ReviewRunTest test`

Expected before implementation: missing method. Expected after implementation: PASS.

- [ ] **Step 3: Write mapper tests**

Assert enum mapping, path normalization, post-change line retention, diff-membership calculation, citation conversion, stable fingerprint generation, and rejection of missing/invalid file or line. Do not pass evaluator annotations into the mapper.

- [ ] **Step 4: Write execution use-case tests**

With fakes for repository/mutation store/source provider/agent/clock, cover success, model timeout as transient, invalid output as terminal, reclaimed `RUNNING` state, source cleanup on all paths, immutable measurements, and enqueue of a `DECIDE_PUBLICATION` job only after a completed aggregate is stored.

- [ ] **Step 5: Implement execution orchestration**

Load the stored run/version, recover an interrupted attempt if necessary, persist `startAttempt`, prepare exact source, invoke `CodeReviewAgent.review("Review the following diff...", sourceRoot)`, map findings, complete/fail the attempt, then atomically save the aggregate with outbox events and the next durable intent. External source/model calls must occur outside transactions.

- [ ] **Step 6: Write and pass transaction tests**

Inject failures after aggregate update, job insert, and outbox append; assert rollback preserves the prior optimistic version and no partial next intent exists.

Run: `mvn -Dtest=ExecuteReviewRunTest,ReviewFindingMapperTest,TransactionalReviewRunMutationStoreTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops \
  src/test/java/dev/langchain4j/example/codereview/reviewops
git commit -m "feat(reviewops): execute durable review runs"
```

### Task 7: Lease and dispatch review jobs with bounded retry

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewJobHandler.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewJobDispatcher.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewJobWorker.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewJobDispatcherTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/application/jobs/ReviewJobWorkerTest.java`

**Interfaces:**
- Produces: `ReviewJobHandler.handle(LeasedJob): JobOutcome`.
- Produces: `ReviewJobDispatcher.dispatch(LeasedJob): JobOutcome` keyed by exact job type.
- Produces: `ReviewJobWorker.runOnce(): WorkerCycleResult` and a server-only scheduled wrapper.

- [ ] **Step 1: Write failing dispatcher tests**

Assert known job types route once, unknown types return terminal failure, and duplicate handler registration fails at construction.

- [ ] **Step 2: Write failing worker tests**

With a fake queue and fixed clock, assert lease recovery happens before leasing, successful jobs call `markSucceeded`, transient failures call `recordFailure` with exponential delay capped by configuration, terminal failures become DEAD, and stale owners/attempt numbers are passed unchanged to fencing methods.

- [ ] **Step 3: Implement worker and scheduling**

Use one bounded scheduled poller in server mode. Add jitter through an injected `BackoffPolicy` whose tests use deterministic zero jitter. Metrics tags are limited to job type and outcome.

- [ ] **Step 4: Run tests**

Run: `mvn -Dtest=ReviewJobDispatcherTest,ReviewJobWorkerTest,PostgresDurableJobQueueTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs \
  src/test/java/dev/langchain4j/example/codereview/reviewops/application/jobs
git commit -m "feat(reviewops): dispatch leased review jobs"
```

### Task 8: Decide publication and enforce the authoritative head guard

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/DecideReviewPublication.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/PublishReviewOutcome.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/github/GitHubPublicationGateway.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/github/CheckRunArtifact.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/github/InlineCommentArtifact.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/application/DecideReviewPublicationTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/application/PublishReviewOutcomeTest.java`

**Interfaces:**
- Produces: `DecideReviewPublication.decide(ReviewRunId)` which stores exact decisions and enqueues `PUBLISH_REVIEW` atomically.
- Produces: `GitHubPublicationGateway.authoritativeRevision(PullRequestRevision)`.
- Produces: `GitHubPublicationGateway.upsertCheck(CheckRunRequest)` and `reconcileInlineComment(InlineCommentRequest)`.
- Produces: `PublishReviewOutcome.publish(ReviewRunId): PublicationOutcome`.

- [ ] **Step 1: Write decision tests**

Assert the existing `FindingPublicationPolicy` is called exactly once with the configured snapshot, decisions cover every finding, the aggregate remains `COMPLETED`, and one idempotent publication job is saved atomically.

- [ ] **Step 2: Write stale-head tests before implementation**

```java
gateway.authoritativeRevisionReturns(new AuthoritativeRevision(otherSha));
PublicationOutcome outcome = publisher.publish(run.id());
assertThat(outcome).isEqualTo(SUPERSEDED);
assertThat(gateway.checkMutations()).isZero();
assertThat(gateway.commentMutations()).isZero();
```

Also cover matching head, already `PUBLISHING` retry, and already terminal state.

- [ ] **Step 3: Implement decision and head authorization**

Call GitHub for authoritative revision before any mutation. Persist `SUPERSEDED` immediately when SHA differs. For a matching `COMPLETED` run, persist `authorizePublication` before artifact calls; for `PUBLISHING`, resume existing progress.

- [ ] **Step 4: Run focused tests**

Run: `mvn -Dtest=DecideReviewPublicationTest,PublishReviewOutcomeTest,FindingPublicationPolicyTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/application \
  src/test/java/dev/langchain4j/example/codereview/reviewops/application
git commit -m "feat(reviewops): authorize review publication"
```

### Task 9: Reconcile Check Runs and inline comments idempotently

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/GitHubPublicationClient.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/CheckRunFormatter.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/InlineCommentFormatter.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/GitHubPublicationClientTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/CheckRunFormatterTest.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github/InlineCommentFormatterTest.java`
- Modify: `src/test/java/dev/langchain4j/example/codereview/reviewops/application/PublishReviewOutcomeTest.java`

**Interfaces:**
- Check external ID is the `ReviewRunId`; check name is a fixed configured value.
- Inline body ends with `<!-- code-review-agent:fingerprint=<64-hex> -->`.
- `reconcileInlineComment` returns the existing artifact when the marker already exists, otherwise creates one bound to `headSha`, `path`, `line`, and `side=RIGHT`.

- [ ] **Step 1: Write formatter tests**

Assert deterministic order, bounded summary/text size, escaped Markdown, no secrets, neutral conclusion for system failure, and exact invisible fingerprint marker.

- [ ] **Step 2: Write REST reconciliation tests**

Stub: existing Check by external ID, no Check, existing comment marker across paginated comments, no comment, rate limit, GitHub 5xx, and partial comment success. Assert confirmed IDs are returned immediately and retries never POST an already confirmed artifact.

- [ ] **Step 3: Implement the GitHub publication adapter**

Use Checks API create/update and PR review-comment endpoints with explicit API-version headers. Map primary/secondary rate limits to retryable failures with server retry timing; map 401/403 permission removal and invalid diff locations to safe terminal classifications.

- [ ] **Step 4: Complete partial-progress orchestration**

After Check confirmation and after every inline comment confirmation, call `ReviewRun.recordPublicationProgress` and persist the new optimistic version before attempting the next artifact. When all inline decisions have references, call `confirmPublication`. On terminal publication failure, persist `recordPublicationFailure` and best-effort update/create one neutral Check with no finding comments.

- [ ] **Step 5: Run focused tests**

Run: `mvn -Dtest=GitHubPublicationClientTest,CheckRunFormatterTest,InlineCommentFormatterTest,PublishReviewOutcomeTest test`

Expected: PASS, including failure injection after each artifact.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/github \
  src/main/java/dev/langchain4j/example/codereview/reviewops/application/PublishReviewOutcome.java \
  src/test/java/dev/langchain4j/example/codereview/reviewops
git commit -m "feat(github): reconcile checks and review comments"
```

### Task 10: Wire server health, end-to-end proof, and operator demo

**Files:**
- Create: `src/test/java/dev/langchain4j/example/codereview/server/GitHubReviewLoopE2ETest.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/server/ServerReadinessTest.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/server/ServerConfiguration.java`
- Modify: `src/main/resources/application-server.yml`
- Modify: `src/test/java/dev/langchain4j/example/codereview/reviewops/ReviewOperationsArchitectureTest.java`
- Create: `compose.yml`
- Create: `.env.example`
- Modify: `README.md`

**Interfaces:**
- E2E fixture uses a real PostgreSQL Testcontainer, HTTP fake GitHub, deterministic fake `CodeReviewAgent`, and the actual webhook/controller/job/application/persistence stack.
- Readiness is UP only when PostgreSQL is reachable and intake wiring is active; GitHub/model outages affect metrics/jobs, not readiness.

- [ ] **Step 1: Write the failing E2E test**

POST a correctly signed `opened` payload; assert HTTP 202; run worker cycles until idle; assert exactly one persisted review run reaches `PUBLISHED`, one Check exists at the exact SHA, expected inline comments exist once, and a replayed delivery creates no additional run/job/artifact.

- [ ] **Step 2: Add failure-path E2E cases**

Cover invalid signature (401/no rows), duplicate delivery, worker reclaim after simulated lease expiry, stale authoritative SHA (SUPERSEDED/no mutations), and a GitHub failure after the first comment followed by a retry with no duplicate comment.

- [ ] **Step 3: Extend architecture and secret tests**

Assert `reviewops.domain` has no Spring/JDBC/GitHub/LangChain4j dependencies, production Review Operations does not depend on evaluator packages, and captured logs/database rows/HTTP fixtures contain none of the configured fake private key, webhook secret, installation token, or model key.

- [ ] **Step 4: Wire server-only beans and operational endpoints**

Register JDBC repositories, admission/mutation stores, worker handlers, GitHub clients, clocks, policies, and scheduler only for `code-review.runtime=server`. Configure `/actuator/health`, `/actuator/health/readiness`, and `/actuator/metrics` without exposing secrets or arbitrary environment values.

- [ ] **Step 5: Add Compose and README demo**

`compose.yml` starts PostgreSQL and the app with environment placeholders. `.env.example` contains names only: `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY`, `GITHUB_WEBHOOK_SECRET`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, and the model key variable. README documents App permissions, webhook URL, `serve`, non-blocking behavior, and a signed fake/demo flow without real credentials.

- [ ] **Step 6: Run full verification**

Run: `mvn test`

Expected: all unit, architecture, startup, PostgreSQL, adapter, and E2E tests pass.

Run: `mvn -q clean package -DskipTests`

Expected: fat jar builds.

Run: `git diff --check`

Expected: no whitespace errors.

Run: `git grep -I -E 'BEGIN (RSA )?PRIVATE KEY|ghs_[A-Za-z0-9]+|sk-[A-Za-z0-9]{20,}' -- . ':!docs/superpowers/plans/2026-09-01-github-app-review-loop.md'`

Expected: no secret material.

- [ ] **Step 7: Commit**

```bash
git add compose.yml .env.example README.md src/main src/test
git commit -m "feat(github): complete production review loop"
```

- [ ] **Step 8: Update Issue #4 and create a focused pull request**

Comment on Issue #4 with test counts, E2E evidence, safe failure demonstrations, and the PR URL. The PR description must state that feedback reconciliation and real-PR benchmarking remain in their dedicated later issues.
