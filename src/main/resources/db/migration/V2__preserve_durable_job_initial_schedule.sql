ALTER TABLE durable_jobs
    ADD COLUMN initial_next_attempt_at TIMESTAMPTZ;

UPDATE durable_jobs
SET initial_next_attempt_at = next_attempt_at;

ALTER TABLE durable_jobs
    ALTER COLUMN initial_next_attempt_at SET NOT NULL;
