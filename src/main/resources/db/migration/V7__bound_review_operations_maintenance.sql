CREATE INDEX idx_durable_jobs_expired_lease
    ON durable_jobs (lease_expires_at, id)
    WHERE state = 'LEASED';

CREATE INDEX idx_durable_jobs_terminal_retention
    ON durable_jobs (updated_at, id)
    WHERE state IN ('SUCCEEDED', 'DEAD');

CREATE INDEX idx_outbox_events_published_retention
    ON outbox_events (published_at, event_id)
    WHERE published_at IS NOT NULL;

CREATE INDEX idx_github_deliveries_handled_retention
    ON github_deliveries (handled_at, delivery_id)
    WHERE handled_at IS NOT NULL;
