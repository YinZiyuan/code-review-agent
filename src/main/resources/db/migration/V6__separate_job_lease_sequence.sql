ALTER TABLE durable_jobs
    ADD COLUMN lease_sequence INTEGER NOT NULL DEFAULT 0 CHECK (lease_sequence >= 0);

UPDATE durable_jobs
SET lease_sequence = attempt_count;
