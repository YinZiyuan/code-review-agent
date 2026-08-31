CREATE TABLE review_runs (
    id UUID PRIMARY KEY,
    installation_id BIGINT NOT NULL CHECK (installation_id > 0),
    repository_id BIGINT NOT NULL CHECK (repository_id > 0),
    pull_request_number INTEGER NOT NULL CHECK (pull_request_number > 0),
    head_sha TEXT NOT NULL,
    pipeline_version TEXT NOT NULL,
    configuration_version TEXT NOT NULL,
    model_name TEXT NOT NULL,
    policy_version TEXT NOT NULL,
    max_review_attempts INTEGER NOT NULL CHECK (max_review_attempts > 0),
    requested_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('REQUESTED','RUNNING','COMPLETED','PUBLISHING','PUBLISHED','FAILED','SUPERSEDED')),
    check_run_external_id TEXT,
    failure_code TEXT,
    failure_class TEXT CHECK (failure_class IN ('TRANSIENT','TERMINAL')),
    failure_safe_message TEXT,
    CONSTRAINT ck_review_runs_failure_group CHECK (
        (state <> 'FAILED'
            AND failure_code IS NULL AND failure_class IS NULL AND failure_safe_message IS NULL)
        OR
        (state = 'FAILED'
            AND failure_code IS NOT NULL AND btrim(failure_code) <> ''
            AND failure_class = 'TERMINAL'
            AND failure_safe_message IS NOT NULL AND btrim(failure_safe_message) <> '')
    ),
    finished_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (installation_id, repository_id, pull_request_number, head_sha, pipeline_version, configuration_version)
);

CREATE TABLE github_deliveries (
    delivery_id TEXT PRIMARY KEY,
    event_name TEXT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    handled_at TIMESTAMPTZ
);

CREATE TABLE review_attempts (
    review_run_id UUID NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    state TEXT NOT NULL CHECK (state IN ('STARTED','SUCCEEDED','TRANSIENT_FAILURE','TERMINAL_FAILURE','CANCELLED')),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    latency_ms BIGINT CHECK (latency_ms >= 0),
    input_tokens INTEGER CHECK (input_tokens >= 0),
    output_tokens INTEGER CHECK (output_tokens >= 0),
    tool_states JSONB,
    CONSTRAINT ck_review_attempts_measurements_group CHECK (
        (state IN ('STARTED','CANCELLED')
            AND latency_ms IS NULL AND input_tokens IS NULL
            AND output_tokens IS NULL AND tool_states IS NULL)
        OR
        (state IN ('SUCCEEDED','TRANSIENT_FAILURE','TERMINAL_FAILURE')
            AND latency_ms IS NOT NULL AND input_tokens IS NOT NULL
            AND output_tokens IS NOT NULL AND tool_states IS NOT NULL)
    ),
    CONSTRAINT ck_review_attempts_tool_states_object CHECK (
        tool_states IS NULL OR jsonb_typeof(tool_states) = 'object'
    ),
    failure_code TEXT,
    failure_class TEXT CHECK (failure_class IN ('TRANSIENT','TERMINAL')),
    failure_safe_message TEXT,
    CONSTRAINT ck_review_attempts_failure_group CHECK (
        (state IN ('STARTED','SUCCEEDED','CANCELLED')
            AND failure_code IS NULL AND failure_class IS NULL AND failure_safe_message IS NULL)
        OR
        (state = 'TRANSIENT_FAILURE'
            AND failure_code IS NOT NULL AND btrim(failure_code) <> ''
            AND failure_class = 'TRANSIENT'
            AND failure_safe_message IS NOT NULL AND btrim(failure_safe_message) <> '')
        OR
        (state = 'TERMINAL_FAILURE'
            AND failure_code IS NOT NULL AND btrim(failure_code) <> ''
            AND failure_class = 'TERMINAL'
            AND failure_safe_message IS NOT NULL AND btrim(failure_safe_message) <> '')
    ),
    PRIMARY KEY (review_run_id, attempt_number)
);

CREATE TABLE review_findings (
    review_run_id UUID NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
    fingerprint CHAR(64) NOT NULL,
    file_path TEXT NOT NULL,
    post_change_line INTEGER NOT NULL CHECK (post_change_line > 0),
    changed_line BOOLEAN NOT NULL,
    severity TEXT NOT NULL CHECK (severity IN ('CRITICAL','WARNING','SUGGESTION')),
    category TEXT NOT NULL CHECK (category IN ('SECURITY','PERFORMANCE','STABILITY','CONCURRENCY','TEST','STYLE','OTHER')),
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    suggestion TEXT NOT NULL,
    evidence TEXT NOT NULL,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT ck_review_findings_citations_array CHECK (jsonb_typeof(citations) = 'array'),
    source TEXT NOT NULL,
    publication_tier TEXT CHECK (publication_tier IN ('INLINE_COMMENT','CHECK_SUMMARY','RETAIN_ONLY')),
    publication_policy_version TEXT,
    CONSTRAINT ck_review_findings_publication_decision_group CHECK (
        (publication_tier IS NULL AND publication_policy_version IS NULL)
        OR
        (publication_tier IS NOT NULL AND publication_policy_version IS NOT NULL
            AND btrim(publication_policy_version) <> '')
    ),
    artifact_type TEXT,
    artifact_external_id TEXT,
    CONSTRAINT ck_review_findings_publication_reference_group CHECK (
        (artifact_type IS NULL AND artifact_external_id IS NULL)
        OR
        (artifact_type IS NOT NULL AND btrim(artifact_type) <> ''
            AND artifact_external_id IS NOT NULL AND btrim(artifact_external_id) <> ''
            AND publication_tier IS NOT NULL AND publication_tier = 'INLINE_COMMENT')
    ),
    PRIMARY KEY (review_run_id, fingerprint)
);

CREATE TABLE finding_feedback (
    review_run_id UUID NOT NULL,
    finding_fingerprint CHAR(64) NOT NULL,
    actor_id BIGINT NOT NULL CHECK (actor_id > 0),
    actor_login TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('HELPFUL','FALSE_POSITIVE','WITHDRAWN')),
    github_reaction_id BIGINT,
    audit_entries JSONB NOT NULL DEFAULT '[]'::jsonb,
    first_recorded_at TIMESTAMPTZ NOT NULL,
    last_changed_at TIMESTAMPTZ NOT NULL,
    withdrawn_at TIMESTAMPTZ,
    PRIMARY KEY (review_run_id, finding_fingerprint, actor_id),
    FOREIGN KEY (review_run_id, finding_fingerprint)
        REFERENCES review_findings(review_run_id, fingerprint) ON DELETE CASCADE
);

CREATE TABLE durable_jobs (
    id UUID PRIMARY KEY,
    job_type TEXT NOT NULL,
    payload_reference UUID NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('READY','LEASED','SUCCEEDED','DEAD')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner TEXT,
    lease_expires_at TIMESTAMPTZ,
    last_failure_class TEXT CHECK (last_failure_class IN ('TRANSIENT','TERMINAL')),
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_durable_jobs_due
    ON durable_jobs (next_attempt_at, created_at)
    WHERE state = 'READY';

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    last_failure TEXT
);

CREATE INDEX idx_outbox_events_unpublished
    ON outbox_events (occurred_at, event_id)
    WHERE published_at IS NULL;
