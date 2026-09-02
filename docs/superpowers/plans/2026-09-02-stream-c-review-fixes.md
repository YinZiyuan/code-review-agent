# Stream C Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all four Important and two Minor stream-C review findings without editing stream A/B-owned production classes.

**Architecture:** Make V7 a nontransactional, concurrent, restart-safe index migration with migration-specific deadlines. Add a transactional V8 migration whose triggers maintain authoritative run state-entry time and a fixed-cardinality metric rollup; publish its data through an all-or-nothing in-memory snapshot with failure backoff. Bind every operational database deadline through validated server properties, and derive model endpoint identity only from safe host metadata plus an explicit non-secret deployment identity. Expose a narrow work-budget identity provider seam for stream B.

**Tech Stack:** Java 17, Spring Boot 3.5, Flyway, HikariCP, PostgreSQL 17/Testcontainers, Micrometer, JUnit 5, AssertJ.

**Spec:** `.superpowers/parallel-c-review.md`

## Global Constraints

- Work only in `.worktrees/issue4-final-c`; do not push, merge, or spawn subagents.
- Do not edit stream A-owned lifecycle/job/publication/GitHub production classes or stream B-owned pipeline/analyzer/workspace production classes.
- Write and observe a failing regression test before each production behavior change.
- Preserve review audit history; token gauges must explicitly mean retained database history.

---

### Task 1: Validated database bounds and executable deadline tests

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/server/DatabaseBoundsProperties.java`
- Modify: `src/main/resources/application-server.yml`
- Modify: `src/test/java/dev/langchain4j/example/codereview/server/ApplicationModeTest.java`
- Modify: `src/test/java/dev/langchain4j/example/codereview/server/ServerDatabaseBoundsTest.java`

**Interfaces:**
- Produces: typed `DatabaseBoundsProperties` consumed through YAML property indirection by Hikari, PostgreSQL, and Spring transactions.

- [ ] Add binding tests that reject zero, negative, and excessive pool/network/query/lock/transaction deadlines.
- [ ] Run the binding tests and confirm they fail because the typed properties do not exist.
- [ ] Add the validated properties and make datasource/transaction YAML consume the same values.
- [ ] Run binding tests green.
- [ ] Add real `pg_sleep`, conflicting row-lock, and Spring transaction-timeout assertions.
- [ ] Run the deadline tests red against shortened test overrides, then green after wiring.
- [ ] Commit the bounded-database fix.

### Task 2: Online and retryable V7

**Files:**
- Modify: `src/main/resources/db/migration/V7__bound_review_operations_maintenance.sql`
- Create: `src/main/resources/db/migration/V7__bound_review_operations_maintenance.sql.conf`
- Modify: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/persistence/ReviewOperationsMigrationTest.java`

**Interfaces:**
- Produces: four valid partial indexes built with `CREATE INDEX CONCURRENTLY` outside a transaction and independent migration statement/lock deadlines.

- [ ] Add tests for nontransactional configuration, index validity, representative populated data, concurrent writer progress, forced lock-timeout failure, Flyway repair, and successful retry.
- [ ] Run them and confirm failure on ordinary transactional index creation.
- [ ] Convert V7 to explicit migration timeouts plus `DROP/CREATE INDEX CONCURRENTLY` restart-safe pairs and add Flyway script configuration.
- [ ] Run migration tests green and commit.

### Task 3: Authoritative state timing and bounded rollups

**Files:**
- Create: `src/main/resources/db/migration/V8__maintain_review_operations_observability.sql`
- Modify: `src/main/java/dev/langchain4j/example/codereview/reviewops/infrastructure/observability/ReviewOperationsMetrics.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/server/ReviewObservabilityProperties.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/server/ScheduledReviewOperationsMetrics.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/server/ServerConfiguration.java`
- Modify: `src/main/resources/application-server.yml`
- Modify: `src/test/java/dev/langchain4j/example/codereview/reviewops/infrastructure/observability/ReviewOperationsMetricsTest.java`
- Create: `src/test/java/dev/langchain4j/example/codereview/server/ScheduledReviewOperationsMetricsTest.java`
- Modify: migration and application binding tests.

**Interfaces:**
- Produces: `review_runs.state_entered_at`, fixed-cardinality `review_operations_metric_rollup`, transactional maintenance triggers, and atomic cached metric snapshots.

- [ ] Add migration tests proving state timestamps change only on state transitions and rollups remain correct across insert/update/delete.
- [ ] Add metrics tests proving old requests newly entering RUNNING/PUBLISHING are fresh, recent requests stranded in-state become stale, SQL reads only compact/indexed sources, and failed refreshes publish no partial values.
- [ ] Add scheduler tests proving database failures impose bounded exponential retry backoff.
- [ ] Run tests red.
- [ ] Add V8 trigger/rollup schema, refactor metrics to one `AtomicReference` snapshot, rename tokens to `code_review_retained_history_tokens`, and implement retry backoff.
- [ ] Run focused tests green and commit.

### Task 4: Safe endpoint and direct work-budget identity seams

**Files:**
- Create: `src/main/java/dev/langchain4j/example/codereview/server/ReviewWorkBudgetIdentityProvider.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/server/ReviewIdentityProperties.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/server/ReviewConfigurationSnapshotFactory.java`
- Modify: `src/main/java/dev/langchain4j/example/codereview/server/ServerConfiguration.java`
- Modify: `src/main/resources/application-server.yml`
- Modify: identity, binding, logging/correlation, and server-wiring tests.

**Interfaces:**
- Produces: `ReviewWorkBudgetIdentityProvider.configurationHash()`; stream B supplies `reviewWorkBudget::configurationHash` as one adapter bean after merge.

- [ ] Add failing tests rejecting endpoint user-info, query/fragment credentials, and credential-like/high-entropy path segments.
- [ ] Add failing tests proving endpoint identity is explicit/non-secret and an injected work-budget provider changes the business hash.
- [ ] Add safe endpoint validation, explicit deployment identity, and provider injection with the legacy property only as a fallback.
- [ ] Add correlation factory/vocabulary tests for A/B-owned call-site integration without modifying their classes.
- [ ] Run focused tests green and commit.

### Task 5: Verification and handoff

**Files:**
- Modify: `.superpowers/parallel-c-report.md`

- [ ] Run all focused migration, database, metrics, logging, identity, admission, and wiring tests.
- [ ] Run `mvn test` and confirm zero failures/errors/skips.
- [ ] Review the complete diff for ownership, raw secrets/errors, unbounded queries, migration retry behavior, and conflicts.
- [ ] Update the report with every finding status and the exact stream-B adapter bean.
- [ ] Commit the report and confirm a clean worktree.
