SELECT set_config(
    'code_review.previous_statement_timeout', current_setting('statement_timeout'), false);
SELECT set_config(
    'code_review.previous_lock_timeout', current_setting('lock_timeout'), false);
SET statement_timeout = '15min';
SET lock_timeout = '2s';

DROP INDEX CONCURRENTLY IF EXISTS "${flyway:defaultSchema}".idx_review_runs_active_state_entry;
CREATE INDEX CONCURRENTLY idx_review_runs_active_state_entry
    ON "${flyway:defaultSchema}".review_runs (state, state_entered_at)
    WHERE state IN ('RUNNING', 'PUBLISHING');

SELECT set_config(
    'lock_timeout', current_setting('code_review.previous_lock_timeout'), false);
SELECT set_config(
    'statement_timeout', current_setting('code_review.previous_statement_timeout'), false);
RESET code_review.previous_lock_timeout;
RESET code_review.previous_statement_timeout;
