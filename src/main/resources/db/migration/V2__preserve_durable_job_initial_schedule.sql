DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM durable_jobs
        WHERE state <> 'READY'
           OR attempt_count <> 0
           OR lease_owner IS NOT NULL
           OR lease_expires_at IS NOT NULL
           OR last_failure_class IS NOT NULL
           OR updated_at <> created_at
    ) THEN
        RAISE EXCEPTION
            'V2 cannot safely infer durable_jobs.initial_next_attempt_at for legacy jobs that may have executed or been rescheduled; provide an authoritative backfill or manual resolution before retrying this migration';
    END IF;
END
$$;

ALTER TABLE durable_jobs
    ADD COLUMN initial_next_attempt_at TIMESTAMPTZ;

UPDATE durable_jobs
SET initial_next_attempt_at = next_attempt_at;

ALTER TABLE durable_jobs
    ALTER COLUMN initial_next_attempt_at SET NOT NULL;
