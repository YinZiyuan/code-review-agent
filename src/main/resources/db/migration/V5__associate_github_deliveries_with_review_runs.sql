ALTER TABLE github_deliveries
    ADD COLUMN review_run_id UUID;

ALTER TABLE github_deliveries
    ADD CONSTRAINT fk_github_deliveries_review_run
        FOREIGN KEY (review_run_id) REFERENCES review_runs(id);

COMMENT ON COLUMN github_deliveries.review_run_id IS
    'Authoritative review run selected for this delivery; NULL is retained only for pre-V5 rows and requires explicit operator repair before replay';
