-- This migration intentionally does not inherit the short request/worker statement timeout.
-- The lock timeout remains finite so startup fails safely instead of waiting indefinitely.
SET statement_timeout = '15min';
SET lock_timeout = '2s';

-- A failed concurrent build can leave an invalid index behind. Dropping first makes a
-- Flyway repair + retry deterministic whether the prior attempt stopped before or during build.
DROP INDEX CONCURRENTLY IF EXISTS "${flyway:defaultSchema}".idx_durable_jobs_expired_lease;
CREATE INDEX CONCURRENTLY idx_durable_jobs_expired_lease
    ON "${flyway:defaultSchema}".durable_jobs (lease_expires_at, id)
    WHERE state = 'LEASED';

DROP INDEX CONCURRENTLY IF EXISTS "${flyway:defaultSchema}".idx_durable_jobs_terminal_retention;
CREATE INDEX CONCURRENTLY idx_durable_jobs_terminal_retention
    ON "${flyway:defaultSchema}".durable_jobs (updated_at, id)
    WHERE state IN ('SUCCEEDED', 'DEAD');

DROP INDEX CONCURRENTLY IF EXISTS "${flyway:defaultSchema}".idx_outbox_events_published_retention;
CREATE INDEX CONCURRENTLY idx_outbox_events_published_retention
    ON "${flyway:defaultSchema}".outbox_events (published_at, event_id)
    WHERE published_at IS NOT NULL;

DROP INDEX CONCURRENTLY IF EXISTS "${flyway:defaultSchema}".idx_github_deliveries_handled_retention;
CREATE INDEX CONCURRENTLY idx_github_deliveries_handled_retention
    ON "${flyway:defaultSchema}".github_deliveries (handled_at, delivery_id)
    WHERE handled_at IS NOT NULL;

RESET lock_timeout;
RESET statement_timeout;
