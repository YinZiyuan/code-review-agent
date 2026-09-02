ALTER TABLE review_runs
    DROP CONSTRAINT uq_review_runs_business_identity;

CREATE UNIQUE INDEX uq_review_runs_business_identity
    ON review_runs (
        installation_id,
        repository_id,
        pull_request_number,
        head_sha,
        pipeline_version,
        configuration_version)
    WHERE state <> 'SUPERSEDED';
