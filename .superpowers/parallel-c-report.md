# Parallel Stream C Report — I4, I5, I6 Review Fix Round 1

Status: **DONE**

Base checkpoint: `d547608`

Branch: `codex/issue4-final-c`

## Commits

Initial stream:

- `bc09276 fix(server): bound postgres access and retention`
- `9f90437 feat(server): add production review observability`
- `e62aec0 fix(server): derive immutable review configuration identity`
- `9f03f70 docs: record parallel stream c verification`

Review-fix round:

- `3a8f5d7 docs: plan stream c review fixes`
- `ad4dd11 fix(server): validate and execute database deadlines`
- `0cbea12 fix(db): make retention indexes online and retryable`
- `59c9342 fix(observability): bound aggregate database metrics`
- `4e40671 fix(server): make runtime identity explicit and safe`
- `db58b20 fix(db): preserve pooled session deadlines across migration`
- `86645b5 fix(observability): avoid hot-row contention in metric rollups`
- `109363a fix(server): reject token-shaped endpoint paths`

## Review finding status

- **I1 fixed.** V7 is nontransactional and uses `DROP INDEX CONCURRENTLY IF EXISTS` plus `CREATE INDEX CONCURRENTLY`. It applies a separate 15-minute build/2-second lock-wait policy, restores the pooled connection's prior finite settings, and the server explicitly selects Flyway's PostgreSQL session advisory lock because its transaction-scoped lock self-blocks concurrent index builds. Populated-table, concurrent-writer, lock-timeout, repair, retry, index-validity, and boot-pool restoration tests pass.
- **I2 fixed.** V8 adds authoritative `review_runs.state_entered_at`. A database trigger preserves it for unrelated writes and sets it with `clock_timestamp()` on every state transition. Historical active runs begin a new staleness clock at upgrade because their actual transition instant cannot be reconstructed. V9 adds the partial `(state, state_entered_at)` access path for `RUNNING`/`PUBLISHING`, and gauges use that column rather than `requested_at`.
- **I3 fixed.** History-sized scans were replaced by 18 fixed rollup rows. Business transactions append small, transactional state deltas instead of updating hot shared counter rows. Each refresh atomically folds at most 10,000 deltas with `FOR UPDATE SKIP LOCKED`, deletes only successfully folded internal deltas, reads indexed age probes, and publishes one immutable in-memory snapshot only after every query succeeds. Failure preserves the prior snapshot and exponentially backs off to a validated maximum. The delta table is operational bookkeeping, not audit history; review attempts/findings/runs remain governed by the existing audit/retention policy. The retained-history token gauge is named `code_review_retained_history_tokens`, distinct from stream B's process-lifetime counters.
- **I4 fixed.** Typed database properties reject zero, negative, and excessive bounds. Testcontainers tests execute a real over-deadline `pg_sleep`, a real conflicting row-lock wait, and a Spring transaction timeout and assert bounded PostgreSQL/Spring failures. Existing pool-saturation tests continue to bound acquisition, readiness, webhook, and heartbeat behavior.
- **M1 fixed.** `DatabaseBoundsProperties` validates pool size/minimum idle, acquisition/validation durations, connect/socket/cancel seconds, statement/lock/idle-transaction/transaction deadlines, cross-field relationships, and documented upper limits before datasource construction.
- **M2 fixed.** The business hash uses an explicit validated non-secret model deployment identity instead of a full endpoint path. Model base URLs containing user-info, any query/fragment, named credential path components, or token-shaped `sk-*`/GitHub/Slack credential segments are rejected without echoing the URL. Tests cover user-info, query token, named path token, token-shaped path, and URI-shaped deployment identity.

## I4 outcome — bounded PostgreSQL and safe retention

- Spring Boot owns HikariCP; default maximum pool size is 8 with finite acquisition, validation, connection, socket, cancel-signal, statement, lock, idle-transaction, and transaction deadlines.
- V7 supplies partial lease-recovery and safe terminal-job/published-outbox/handled-delivery retention indexes. Scheduled retention uses deterministic bounded batches and `FOR UPDATE SKIP LOCKED`, retaining active jobs, unpublished outbox events, and unhandled deliveries.
- V7/V9 are the only nontransactional concurrent-index migrations. V8's trigger/backfill operation remains transactional with its own 15-minute statement and 5-second lock bound.

## I5 outcome — bounded production observability

- Low-cardinality gauges cover job/run states, queue age, authoritative active-state age, publication tiers, confirmed comments, unpublished outbox depth, and retained audit-history token totals. Identifiers are never metric tags.
- Metric scrapes never perform database work. Refresh cost is capped independently of retained history, snapshot replacement is all-or-nothing, and failures back off rather than hammering the pool.
- JSON logs contain only validated correlation IDs and fixed event/action/outcome/safe-code vocabulary. `ReviewOperationSignal` adds the shared lifecycle, job retry/dead, pipeline, model/GitHub external-call, publication, stale-write, observability, and retention action vocabulary for A/B call-site integration. Logger APIs accept neither raw exceptions nor source text, and throwable serialization remains disabled.
- The full suite's independent two-worker lease test proves observability transitions no longer serialize worker transactions on a hot rollup row.

## I6 outcome — truthful immutable configuration identity

- The snapshot records the effective reviewer model and a stable length-delimited SHA-256 hash over the non-secret semantic allow-list: pipeline/prompt/policy, max inline comments, directly supplied work-budget identity, retry/backoff/jitter, model provider/deployment/name/temperature/max tokens/timeout, RAG behavior, and orchestration timeout/parallelism.
- Model/config/work-budget changes alter business identity. API keys, database/GitHub credentials, endpoint credentials, cache/evaluation paths, and secrets never enter the persisted snapshot or logs.
- `ReviewWorkBudgetIdentityProvider` is an additive direct composition seam. Until B is present, `ObjectProvider` falls back to the safe `work-budget-identity` compatibility property.

### Exact stream-B integration step

After cherry-picking stream B, add one bean (renaming for local house style if necessary):

```java
@Bean
ReviewWorkBudgetIdentityProvider reviewWorkBudgetIdentityProvider(ReviewWorkBudget reviewWorkBudget) {
    return reviewWorkBudget::configurationHash;
}
```

No environment copy is then required: `ServerConfiguration` automatically chooses that provider over the compatibility property. Keep B's `ReviewWorkBudget` implementation as the sole budget calculation; do not duplicate it in C.

## TDD and verification

Observed red before implementation included missing typed properties, real statements exceeding the outer test deadline, missing nontransactional migration metadata, online migration lock-timeout/retry failure, absent state-entry/rollup schema, partial gauge refresh, missing token rename, missing backoff/provider/signal APIs, unsafe endpoint acceptance, pooled deadline reset after Flyway, null trigger deltas, and hot-rollup contention preventing two workers from completing independent leases.

- Focused command: `mvn -q -Dtest=ServerDatabaseBoundsTest,PostgresReviewOperationsRetentionTest,ReviewOperationsMigrationTest,ReviewOperationsMetricsTest,ReviewConfigurationSnapshotFactoryTest,ReviewOperationLoggerTest,ApplicationModeTest,ServerReadinessTest,JdbcPullRequestObservationStoreTest,ScheduledReviewOperationsMetricsTest,PostgresDurableJobQueueTest,JdbcReviewRunRepositoryTest test`
- Focused result: **133 tests, 0 failures, 0 errors, 0 skipped**.
- Full command: `mvn test`
- Full result: **572 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**.
- `git diff --check d547608..HEAD` passes.
- No evaluation run was required for this infrastructure/configuration stream, and checkpoint final-outcome metrics were not duplicated.

## Integration and conflict risks

- `ServerConfiguration.java`: high additive conflict risk. Preserve A/B composition, C's Flyway customizer, Boot datasource/transactions, observability/retention beans, `ObjectProvider<ReviewWorkBudgetIdentityProvider>`, and snapshot construction.
- `application-server.yml`: high additive conflict risk. Preserve A/B settings plus C's typed database, maintenance, observability backoff, explicit model deployment identity, compatibility budget identity, and JSON logging settings.
- `pom.xml`: retain `spring-boot-starter-jdbc`, PostgreSQL/Flyway modules, and `logstash-logback-encoder` while combining A/B dependencies.
- `ApplicationModeTest.java` and `ServerReadinessTest.java`: likely additive test conflicts.
- Migrations introduced here are V7, V8, and V9. If integration already owns one of those versions, renumber the entire unshipped C sequence in order and keep each `.sql.conf` beside its matching nontransactional migration. Do not change a migration checksum after any shared environment has applied it.
- Wire A's job/run/GitHub/publication call sites and B's pipeline/model call sites to `ReviewOperationLogger.log(ReviewCorrelation, ReviewOperationSignal)`. Retain A/B metrics as their owners specify and verify the integrated registry has no duplicate meter IDs.
- No A-owned review domain/job-worker/publication/GitHub-client production file or B-owned analyzer/pipeline/source-workspace/container file was edited.
