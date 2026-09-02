SET statement_timeout = '15min';
SET lock_timeout = '2s';

DROP INDEX CONCURRENTLY IF EXISTS "${flyway:defaultSchema}".idx_review_runs_active_state_entry;
CREATE INDEX CONCURRENTLY idx_review_runs_active_state_entry
    ON "${flyway:defaultSchema}".review_runs (state, state_entered_at)
    WHERE state IN ('RUNNING', 'PUBLISHING');

RESET lock_timeout;
RESET statement_timeout;
