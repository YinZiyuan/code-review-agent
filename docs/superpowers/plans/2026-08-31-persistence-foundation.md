# Persistence Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the accepted Review Operations aggregate in PostgreSQL and provide production-shaped durable jobs, outbox storage, optimistic concurrency, and atomic admission writes.

**Architecture:** Domain-owned reconstitution and repository contracts keep persistence annotations and JDBC outside `reviewops.domain`. Infrastructure adapters use Spring JDBC and PostgreSQL-specific locking; an Application-owned admission port defines the atomic boundary that saves one new `ReviewRun`, its first durable job, and outbox facts in one local transaction.

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring JDBC, Flyway, PostgreSQL 17, Jackson JSON, JUnit 5, AssertJ, Testcontainers PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-08-29-github-app-production-review-design.md`

## Global Constraints

- Flyway is the only schema-change entry point.
- Domain objects have no Spring, JDBC, persistence, GitHub SDK, LangChain4j, or Evaluation dependencies.
- The business identity is `installation_id + repository_id + pull_request_number + head_sha + pipeline_version + configuration_version`.
- A technical retry is a new `ReviewAttempt` under the same `ReviewRun`.
- `ReviewRun` save and its durable-job/outbox inserts commit or roll back together.
- Durable job payloads contain internal identifiers only; no GitHub secret, token, source archive, or raw webhook payload.
- PostgreSQL row locking uses `FOR UPDATE SKIP LOCKED`; H2 is forbidden for lock and transaction tests.
- Integration tests use PostgreSQL 17 through Testcontainers and prove migrations, round trips, uniqueness, optimistic locking, exclusive leasing, lease recovery, and rollback.
- New production behavior follows strict RED → GREEN → REFACTOR; each test must name the production break it catches and exercise real JDBC/PostgreSQL behavior.
- This persistence slice uses `spring-jdbc` with an explicitly supplied `DataSource`. Boot-managed connection pooling and datasource/Flyway auto-configuration belong to the later `serve` runtime bootstrap slice, where CLI and server contexts can be selected before startup.

---

### Task 1: Domain persistence and reconstitution contract

**Files:**
- Modify: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewConfigurationSnapshot.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewAttempt.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewFinding.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRun.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRunRepository.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRunConcurrencyException.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/DuplicateReviewRunException.java`
- Modify: `src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewIdentityTest.java`
- Modify: `src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRunTest.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRunReconstitutionTest.java`

**Interfaces:**
- Produces: `ReviewRunRepository.find`, `insert`, and `update`; `StoredReviewRun`; public reconstitution factories that record no Domain events.
- Consumes: accepted `ReviewRun`, `ReviewAttempt`, and `ReviewFinding` state from the Domain model.

- [ ] **Step 1: Write failing identity and reconstitution tests**

Add a literal `configurationVersion` assertion to `ReviewIdentityTest`. In `ReviewRunReconstitutionTest`, build a completed run with one successful attempt, one finding, one publication decision, and no publication reference; reconstruct it from the exposed facts and assert equal identity/configuration/state/children while `drainEvents()` is empty. Add invalid-state cases: `RUNNING` without a `STARTED` last attempt and `PUBLISHED` without `finishedAt` must throw `IllegalArgumentException`.

- [ ] **Step 2: Run the RED tests**

Run:

```bash
mvn -Dtest=ReviewIdentityTest,ReviewRunReconstitutionTest test
```

Expected: compilation fails because `configurationVersion` and reconstitution factories do not exist.

- [ ] **Step 3: Add the configuration identity and reconstitution API**

Change the configuration record to this exact field order:

```java
public record ReviewConfigurationSnapshot(
        String pipelineVersion,
        String configurationVersion,
        String modelName,
        String policyVersion,
        int maxReviewAttempts) { }
```

Preserve the existing non-blank checks and require `configurationVersion` to be non-blank. Add public static `reconstitute(...)` factories to `ReviewAttempt`, `ReviewFinding`, and `ReviewRun`. Factories copy lists/maps, validate the same structural invariants as normal behavior, and never append a Domain event. `ReviewRun.reconstitute` derives `commentReferences` from findings rather than accepting a second mutable representation.

- [ ] **Step 4: Add the Domain repository contract and stable outcomes**

Create:

```java
public interface ReviewRunRepository {
    Optional<StoredReviewRun> find(ReviewRunId id);
    void insert(ReviewRun reviewRun);
    long update(ReviewRun reviewRun, long expectedVersion);

    record StoredReviewRun(ReviewRun reviewRun, long version) {
        public StoredReviewRun {
            Objects.requireNonNull(reviewRun, "reviewRun");
            if (version < 0) throw new IllegalArgumentException("version must be non-negative");
        }
    }
}
```

`DuplicateReviewRunException` represents the unique business identity conflict. `ReviewRunConcurrencyException` contains the `ReviewRunId` and expected version when an update affects zero rows.

- [ ] **Step 5: Run focused and full tests**

```bash
mvn -Dtest=ReviewIdentityTest,ReviewRunReconstitutionTest,ReviewRunTest test
mvn test
```

Expected: 0 failures and existing lifecycle behavior remains unchanged.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/domain src/test/java/dev/langchain4j/example/codereview/reviewops/domain
git commit -m "feat: add review run persistence contract"
```

---

### Task 2: PostgreSQL dependencies and complete Flyway schema

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/db/migration/V1__review_operations_foundation.sql`
- Create: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/PostgresIntegrationSupport.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/ReviewOperationsMigrationTest.java`

**Interfaces:**
- Produces: migrated tables `github_deliveries`, `review_runs`, `review_attempts`, `review_findings`, `finding_feedback`, `durable_jobs`, and `outbox_events`; shared real-PostgreSQL test support.
- Consumes: the business identity and state vocabulary fixed by Task 1.

- [ ] **Step 1: Add managed dependencies**

Add `spring-jdbc`, `flyway-core`, `flyway-database-postgresql`, runtime `org.postgresql:postgresql`, and test-scoped `org.testcontainers:junit-jupiter` plus `org.testcontainers:postgresql`. Use Spring Boot dependency management; do not hard-code versions. Do not enable Boot datasource auto-configuration in the CLI-only runtime; the `serve` bootstrap slice will add a pooled datasource while preserving datasource-free CLI startup.

- [ ] **Step 2: Write the failing migration integration test**

`PostgresIntegrationSupport` starts `postgres:17-alpine`, creates a `HikariDataSource`, and applies `Flyway.configure().dataSource(dataSource).load().migrate()`. `ReviewOperationsMigrationTest` queries `information_schema.tables`, `pg_indexes`, and `pg_constraint` and asserts the seven literal table names, the six-column `review_runs` business unique constraint, the attempt/finding composite uniqueness, the durable-job idempotency uniqueness, and an unpublished partial outbox index.

- [ ] **Step 3: Run the RED migration test**

```bash
mvn -Dtest=ReviewOperationsMigrationTest test
```

Expected: FAIL because the migration does not exist and the required tables are absent.

- [ ] **Step 4: Implement the migration**

Use UUID technical keys, `TIMESTAMPTZ` timestamps, `JSONB` for tool/citation/audit/event payloads, and explicit state checks. The central definitions are:

```sql
CREATE TABLE review_runs (
    id UUID PRIMARY KEY,
    installation_id BIGINT NOT NULL CHECK (installation_id > 0),
    repository_id BIGINT NOT NULL CHECK (repository_id > 0),
    pull_request_number INTEGER NOT NULL CHECK (pull_request_number > 0),
    head_sha TEXT NOT NULL,
    pipeline_version TEXT NOT NULL,
    configuration_version TEXT NOT NULL,
    model_name TEXT NOT NULL,
    policy_version TEXT NOT NULL,
    max_review_attempts INTEGER NOT NULL CHECK (max_review_attempts > 0),
    requested_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('REQUESTED','RUNNING','COMPLETED','PUBLISHING','PUBLISHED','FAILED','SUPERSEDED')),
    check_run_external_id TEXT,
    failure_code TEXT,
    failure_class TEXT CHECK (failure_class IN ('TRANSIENT','TERMINAL')),
    failure_safe_message TEXT,
    finished_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (installation_id, repository_id, pull_request_number, head_sha, pipeline_version, configuration_version)
);

CREATE TABLE github_deliveries (
    delivery_id TEXT PRIMARY KEY,
    event_name TEXT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    handled_at TIMESTAMPTZ
);

CREATE TABLE review_attempts (
    review_run_id UUID NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    state TEXT NOT NULL CHECK (state IN ('STARTED','SUCCEEDED','TRANSIENT_FAILURE','TERMINAL_FAILURE','CANCELLED')),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    latency_ms BIGINT CHECK (latency_ms >= 0),
    input_tokens INTEGER CHECK (input_tokens >= 0),
    output_tokens INTEGER CHECK (output_tokens >= 0),
    tool_states JSONB,
    failure_code TEXT,
    failure_class TEXT CHECK (failure_class IN ('TRANSIENT','TERMINAL')),
    failure_safe_message TEXT,
    PRIMARY KEY (review_run_id, attempt_number)
);

CREATE TABLE review_findings (
    review_run_id UUID NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
    fingerprint CHAR(64) NOT NULL,
    file_path TEXT NOT NULL,
    post_change_line INTEGER NOT NULL CHECK (post_change_line > 0),
    changed_line BOOLEAN NOT NULL,
    severity TEXT NOT NULL CHECK (severity IN ('CRITICAL','WARNING','SUGGESTION')),
    category TEXT NOT NULL CHECK (category IN ('SECURITY','PERFORMANCE','STABILITY','CONCURRENCY','TEST','STYLE','OTHER')),
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    suggestion TEXT NOT NULL,
    evidence TEXT NOT NULL,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    source TEXT NOT NULL,
    publication_tier TEXT CHECK (publication_tier IN ('INLINE_COMMENT','CHECK_SUMMARY','RETAIN_ONLY')),
    publication_policy_version TEXT,
    artifact_type TEXT,
    artifact_external_id TEXT,
    PRIMARY KEY (review_run_id, fingerprint)
);

CREATE TABLE finding_feedback (
    review_run_id UUID NOT NULL,
    finding_fingerprint CHAR(64) NOT NULL,
    actor_id BIGINT NOT NULL CHECK (actor_id > 0),
    actor_login TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('HELPFUL','FALSE_POSITIVE','WITHDRAWN')),
    github_reaction_id BIGINT,
    audit_entries JSONB NOT NULL DEFAULT '[]'::jsonb,
    first_recorded_at TIMESTAMPTZ NOT NULL,
    last_changed_at TIMESTAMPTZ NOT NULL,
    withdrawn_at TIMESTAMPTZ,
    PRIMARY KEY (review_run_id, finding_fingerprint, actor_id),
    FOREIGN KEY (review_run_id, finding_fingerprint)
        REFERENCES review_findings(review_run_id, fingerprint) ON DELETE CASCADE
);

CREATE TABLE durable_jobs (
    id UUID PRIMARY KEY,
    job_type TEXT NOT NULL,
    payload_reference UUID NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('READY','LEASED','SUCCEEDED','DEAD')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner TEXT,
    lease_expires_at TIMESTAMPTZ,
    last_failure_class TEXT CHECK (last_failure_class IN ('TRANSIENT','TERMINAL')),
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_durable_jobs_due
    ON durable_jobs (next_attempt_at, created_at)
    WHERE state = 'READY';

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    last_failure TEXT
);

CREATE INDEX idx_outbox_events_unpublished
    ON outbox_events (occurred_at, event_id)
    WHERE published_at IS NULL;
```

- [ ] **Step 5: Run migration and full tests**

```bash
mvn -Dtest=ReviewOperationsMigrationTest test
mvn test
```

Expected: migration succeeds twice without duplicate DDL and all tests pass.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/db/migration src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence
git commit -m "feat: add review operations database schema"
```

---

### Task 3: JDBC ReviewRun repository

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/JsonColumnCodec.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/JdbcReviewRunRepository.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/JdbcReviewRunRepositoryTest.java`
- Modify: `src/test/java/dev/langchain4j/example/codereview/reviewops/ReviewOperationsArchitectureTest.java`

**Interfaces:**
- Consumes: `ReviewRunRepository` and reconstitution factories from Task 1; migrated tables from Task 2.
- Produces: complete aggregate insert/find/update with optimistic versioning and stable duplicate/concurrency outcomes.

- [ ] **Step 1: Write failing repository tests**

Against real PostgreSQL, prove: REQUESTED round trip; COMPLETED round trip with attempts, measurements/tool states, findings/citations, publication decisions/references, failure/timestamps; duplicate business identity translates to `DuplicateReviewRunException`; two reads at version 0 followed by two updates cause the second update to throw `ReviewRunConcurrencyException`.

- [ ] **Step 2: Run the RED repository test**

```bash
mvn -Dtest=JdbcReviewRunRepositoryTest test
```

Expected: compilation fails because `JdbcReviewRunRepository` does not exist.

- [ ] **Step 3: Implement JSON mapping and aggregate persistence**

`JsonColumnCodec` uses the injected Jackson `ObjectMapper` to encode/decode `Map<String,String>` and `List<CitationEvidence>` and translates malformed persisted JSON to `IllegalStateException` with the original cause.

`JdbcReviewRunRepository` uses `JdbcTemplate` and `TransactionOperations`. `insert` writes the root then all attempts/findings. `update` first executes:

```sql
UPDATE review_runs
SET state = ?, check_run_external_id = ?, failure_code = ?, failure_class = ?,
    failure_safe_message = ?, finished_at = ?, version = version + 1
WHERE id = ? AND version = ?
```

If the count is zero, throw `ReviewRunConcurrencyException`; otherwise replace owned child rows and return `expectedVersion + 1`. `find` loads the root, ordered attempts, and ordered findings, then calls Domain reconstitution. Translate PostgreSQL unique-constraint violations by constraint name, not by parsing localized messages.

- [ ] **Step 4: Strengthen architecture tests**

Add a rule that `..reviewops.infrastructure..` may depend on `..reviewops.application..` and `..reviewops.domain..`, while no Domain class depends on Infrastructure, Spring, JDBC, Flyway, Jackson, or Testcontainers.

- [ ] **Step 5: Run focused and full tests**

```bash
mvn -Dtest=JdbcReviewRunRepositoryTest,ReviewOperationsArchitectureTest,ReviewOperationsArchitectureProofTest test
mvn test
```

Expected: all round-trip and concurrency assertions pass with 0 architecture violations.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence src/test/java/dev/langchain4j/example/codereview/reviewops
git commit -m "feat: persist review run aggregates"
```

---

### Task 4: Durable PostgreSQL job queue

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/DurableJobRequest.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/LeasedJob.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs/DurableJobQueue.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/jobs/PostgresDurableJobQueue.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/jobs/PostgresDurableJobQueueTest.java`

**Interfaces:**
- Consumes: `durable_jobs` schema from Task 2 and `FailureClass` from Domain.
- Produces: idempotent enqueue, exclusive due-job lease, owner-checked completion/failure, bounded retry, and expired-lease recovery.

- [ ] **Step 1: Write failing queue tests**

Use literal times and two independent JDBC connections to prove: duplicate idempotency key returns the original job; future jobs are not leased; two workers leasing concurrently receive disjoint IDs; leasing increments attempt count; only the lease owner can complete; transient failure returns to READY before the bound and becomes DEAD at the bound; terminal failure becomes DEAD immediately; expired leases recover to READY with lease fields cleared.

- [ ] **Step 2: Run the RED queue test**

```bash
mvn -Dtest=PostgresDurableJobQueueTest test
```

Expected: compilation fails because the queue contract and adapter do not exist.

- [ ] **Step 3: Implement the Application-owned contract**

Use these operations:

```java
UUID enqueue(DurableJobRequest request);
List<LeasedJob> leaseDue(String owner, Instant now, Duration leaseDuration, int limit);
void markSucceeded(UUID jobId, String owner, Instant now);
void recordFailure(UUID jobId, String owner, FailureClass failureClass,
                   Instant nextAttemptAt, Instant now);
int recoverExpiredLeases(Instant now);
```

Validate non-blank job type/idempotency/owner, positive limits/durations/max attempts, and internal UUID payload references.

- [ ] **Step 4: Implement PostgreSQL leasing**

Lease in one statement and one transaction:

```sql
WITH due AS (
    SELECT id
    FROM durable_jobs
    WHERE state = 'READY' AND next_attempt_at <= ?
    ORDER BY next_attempt_at, created_at, id
    FOR UPDATE SKIP LOCKED
    LIMIT ?
)
UPDATE durable_jobs AS job
SET state = 'LEASED', lease_owner = ?, lease_expires_at = ?,
    attempt_count = job.attempt_count + 1, updated_at = ?
FROM due
WHERE job.id = due.id
RETURNING job.*;
```

Completion and failure updates include `WHERE state='LEASED' AND lease_owner=?`; zero rows is a stable `IllegalStateException`. Recovery only touches `LEASED` rows whose expiry is not after `now`.

- [ ] **Step 5: Run focused and full tests**

```bash
mvn -Dtest=PostgresDurableJobQueueTest test
mvn test
```

Expected: concurrent workers never receive the same job and all lifecycle tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/application/jobs src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/jobs src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/jobs
git commit -m "feat: add durable postgres job queue"
```

---

### Task 5: Outbox and atomic ReviewRun admission

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/outbox/OutboxEvent.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/outbox/OutboxStore.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/application/ReviewRunAdmissionStore.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/JdbcOutboxStore.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/TransactionalReviewRunAdmissionStore.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/TransactionalReviewRunAdmissionStoreTest.java`

**Interfaces:**
- Consumes: `ReviewRunRepository`, `DurableJobQueue`, and the outbox table.
- Produces: one atomic Application continuation for new-run admission and polling-safe unpublished outbox persistence.

- [ ] **Step 1: Write failing atomicity tests**

Against real PostgreSQL, prove a successful admission creates exactly one run, job, and outbox row. Inject a throwing outbox delegate after the run/job writes and prove all three tables remain empty. Repeat the same idempotency key and business identity and prove no partial duplicate survives. Prove `loadUnpublished(limit)` orders by `occurred_at,event_id`, and `markPublished` removes an event from that query without deleting it.

- [ ] **Step 2: Run the RED atomicity test**

```bash
mvn -Dtest=TransactionalReviewRunAdmissionStoreTest test
```

Expected: compilation fails because outbox and admission contracts do not exist.

- [ ] **Step 3: Implement outbox records and JDBC store**

Use immutable records with this contract:

```java
void append(OutboxEvent event);
List<OutboxEvent> loadUnpublished(int limit);
void markPublished(UUID eventId, Instant publishedAt);
```

`OutboxEvent` contains `eventId`, `aggregateType`, `aggregateId`, `eventType`, canonical JSON payload, and `occurredAt`; it contains no secret or source text. `JdbcOutboxStore` uses the partial index order and never deletes published facts.

- [ ] **Step 4: Implement the atomic admission boundary**

Define:

```java
public interface ReviewRunAdmissionStore {
    void admit(ReviewRun reviewRun,
               DurableJobRequest executionJob,
               List<OutboxEvent> outboxEvents);
}
```

`TransactionalReviewRunAdmissionStore` uses one Spring `TransactionOperations.executeWithoutResult` and calls `ReviewRunRepository.insert`, `DurableJobQueue.enqueue`, then `OutboxStore.append` for each immutable event. All concrete JDBC adapters participate in the same thread-bound `DataSourceTransactionManager` transaction.

- [ ] **Step 5: Run targeted, full, and package verification**

```bash
mvn -Dtest=TransactionalReviewRunAdmissionStoreTest test
mvn test
mvn -q clean package -DskipTests
```

Expected: rollback proof passes, full suite has 0 failures, and the fat jar packages successfully.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/application src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence
git commit -m "feat: add transactional review admission outbox"
```

---

## Final verification

- [ ] Run `mvn test` and confirm 0 failures/errors/skips caused by unavailable PostgreSQL.
- [ ] Run `mvn -q clean package -DskipTests` and confirm exit 0.
- [ ] Inspect `git diff main...HEAD` for secrets, raw payload persistence, Domain framework dependencies, or unrelated files.
- [ ] Run a whole-branch code review with the SDD final-review workflow.
- [ ] Use `superpowers:finishing-a-development-branch` to present merge/push choices; do not merge or push without user authorization.
