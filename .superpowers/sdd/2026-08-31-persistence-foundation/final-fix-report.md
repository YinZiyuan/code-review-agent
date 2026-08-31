# Persistence Foundation Final Fix Report

Date: 2026-08-31

Base: `main` at `6fdc1cd`

Starting HEAD: `b8bb54d`

Commit: this commit, `fix: preserve CLI and review audit persistence`

## Outcome

The default Picocli application starts without any datasource configuration, both root and
`review` help paths exit successfully, and `ReviewRun` updates no longer delete established
findings or cascade-delete independent finding feedback/audit history. The four final-review
minor findings are also covered directly.

## Root causes and fixes

### Default CLI required a database

`spring-boot-starter-jdbc` added HikariCP to the runtime classpath. Spring Boot therefore selected
its pooled `DataSource` auto-configuration before Picocli received `--help`; without a JDBC URL or
embedded driver, context refresh failed with `Failed to determine a suitable driver class`.

The fix uses the dependency boundary: the starter was replaced with Boot-managed `spring-jdbc`,
and PostgreSQL tests now create an explicit `DriverManagerDataSource`. This keeps JDBC adapters and
Flyway integration compiling and tested, but does not put a connection pool into the default CLI
runtime. No H2, localhost URL, fallback connection, or swallowed connection failure was added.

Tradeoff: a future server/worker persistence mode must explicitly add its production pool and
provide a configured `DataSource` bean/URL. Once it does, the existing Spring JDBC and Flyway
auto-configuration can activate normally; there is no global auto-configuration exclusion to undo.

`ReviewCommand` also now enables Picocli standard help options so the requested no-external-work
subcommand smoke exits zero rather than treating `--help` as an unknown option.

### ReviewRun update erased feedback audit history

`JdbcReviewRunRepository.update` updated the optimistic root row and then deleted every
`review_finding` before reinserting the aggregate snapshot. V1 correctly has
`finding_feedback(review_run_id, finding_fingerprint)` referencing the finding with
`ON DELETE CASCADE`, so that physical delete silently removed the independent feedback aggregate
and its JSON audit entries.

The FK and cascade remain intact. Update now uses identity-aware PostgreSQL upserts:

- attempts insert by `(review_run_id, attempt_number)` and may only retain an identical terminal
  fact or progress the same `started_at` attempt from `STARTED` to a terminal state;
- findings insert by `(review_run_id, fingerprint)`, require all immutable finding/evidence columns
  to match on conflict, and may only add or retain publication decision/reference fields;
- a zero-row conflict is an error, rolling back the root version update;
- no ordinary aggregate update issues a child `DELETE`.

The PostgreSQL regression inserts feedback with two audit entries, advances the run through
PUBLISHING and PUBLISHED in two optimistic updates, and proves the feedback row, audit JSON,
actor/reaction/state, and timestamps are value-equivalent afterward. Separate assertions
prove new attempt/finding inserts, attempt progress, publication fields, and versions 1 then 2.

### Final-review minor findings

- `configurationVersion` now has a dedicated identity test comparing two otherwise identical
  configuration snapshots; the retry-bound test has a separate behavior-specific name.
- the migration test reads ordered index key columns from PostgreSQL catalogs and requires the
  unpublished outbox index to be exactly `(occurred_at, event_id)`.
- V1 names the six-column business constraint `uq_review_runs_business_identity`; duplicate
  translation surrounds only the root insert. Same-named child and root technical-PK failures are
  proven to remain `DataIntegrityViolationException`.
- the infrastructure-wide SDK allowlist was replaced with a directional application boundary:
  Application cannot depend on Infrastructure, JDBC, Spring, Flyway, or Testcontainers. Domain's
  JDK-only and persistence-framework isolation remains. A synthetic Spring dependency proves the
  Application rule fails while future provider-specific Infrastructure adapters remain open.

## RED / GREEN evidence

1. Default CLI RED: `CodeReviewApplicationCliStartupTest` ran the real `main --help` in a child JVM;
   1 test failed because exit code was 1 and Boot reported datasource configuration failure.
   GREEN: 1/1 passed after the dependency-boundary change.
2. Feedback RED: real PostgreSQL test expected one feedback row after publication updates but read
   zero. GREEN: the feedback regression plus child-progress and optimistic-lock tests passed 3/3.
3. Constraint-name RED: PostgreSQL reported the generated/truncated
   `review_runs_installation_id_repository_id_pull_request_numb_key` instead of the required stable
   name. GREEN: migration and business-duplicate tests passed 2/2.
4. Translation-scope RED: a child CHECK deliberately sharing the business constraint name was
   incorrectly translated to `DuplicateReviewRunException`. GREEN: business duplicate, child
   constraint, and technical PK tests passed 3/3 after narrowing the catch.
5. Outbox-index mutation RED: reversing V1 to `(event_id, occurred_at)` produced the expected
   ordered-column assertion failure. GREEN: restoring `(occurred_at, event_id)` passed.
6. Review subcommand RED: the real `main review --help` child JVM exited 2 with `Unknown option`.
   GREEN: root/subcommand executable regressions passed 2/2, and the subcommand test proves the
   external review path was not invoked.

Focused final run: 52 tests, 0 failures, 0 errors, 0 skipped.

Fresh full run: `mvn test` — 230 tests, 0 failures, 0 errors, 0 skipped.

Fresh package: `mvn -q clean package -DskipTests` — exit 0.

## Fat-jar smoke tests

- `java -jar target/code-review-agent-1.0.0.jar --help` — exit 0; root usage printed.
- `java -jar target/code-review-agent-1.0.0.jar review --help` — exit 0; review usage printed and no
  review execution output appeared.

## Self-review

- `git diff --check` is clean.
- No child-delete SQL remains in `JdbcReviewRunRepository`; V1 feedback FK/cascade was not weakened.
- Root optimistic update and every identity-aware child write participate in the same transaction.
- Duplicate translation is exact-name based and scoped only to `insertRoot`.
- The packaged dependency set contains neither `spring-boot-starter-jdbc` nor HikariCP.
- No embedded database, implicit datasource URL, secret, raw webhook payload, plan edit, or ledger
  edit was introduced.
- Remaining findings after self-review: none.
