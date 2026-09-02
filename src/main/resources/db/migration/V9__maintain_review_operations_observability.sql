-- Installing triggers and seeding rollups is a one-time maintenance operation, not a
-- request/worker query. It receives a separate bounded migration policy.
SET LOCAL statement_timeout = '15min';
SET LOCAL lock_timeout = '5s';

ALTER TABLE "${flyway:defaultSchema}".review_runs
    ADD COLUMN state_entered_at TIMESTAMPTZ;

-- Historical active-state entry time cannot be reconstructed. Starting its clock at upgrade
-- avoids immediately declaring healthy in-flight work stale. Future values are authoritative.
UPDATE "${flyway:defaultSchema}".review_runs
SET state_entered_at = CASE
    WHEN state = 'REQUESTED' THEN requested_at
    WHEN state IN ('RUNNING', 'PUBLISHING') THEN clock_timestamp()
    ELSE coalesce(finished_at, requested_at)
END;

ALTER TABLE "${flyway:defaultSchema}".review_runs
    ALTER COLUMN state_entered_at SET NOT NULL;

CREATE FUNCTION "${flyway:defaultSchema}".review_run_state_entry_clock()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.state_entered_at := NEW.requested_at;
    ELSIF NEW.state IS DISTINCT FROM OLD.state THEN
        NEW.state_entered_at := clock_timestamp();
    ELSE
        NEW.state_entered_at := OLD.state_entered_at;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER review_run_state_entry_clock
BEFORE INSERT OR UPDATE OF state ON "${flyway:defaultSchema}".review_runs
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".review_run_state_entry_clock();

CREATE TABLE "${flyway:defaultSchema}".review_operations_metric_rollup (
    metric_name TEXT NOT NULL,
    dimension_value TEXT NOT NULL,
    metric_value BIGINT NOT NULL CHECK (metric_value >= 0),
    PRIMARY KEY (metric_name, dimension_value)
);

INSERT INTO "${flyway:defaultSchema}".review_operations_metric_rollup
    (metric_name, dimension_value, metric_value)
VALUES
    ('durable_jobs', 'READY', 0),
    ('durable_jobs', 'LEASED', 0),
    ('durable_jobs', 'SUCCEEDED', 0),
    ('durable_jobs', 'DEAD', 0),
    ('review_runs', 'REQUESTED', 0),
    ('review_runs', 'RUNNING', 0),
    ('review_runs', 'COMPLETED', 0),
    ('review_runs', 'PUBLISHING', 0),
    ('review_runs', 'PUBLISHED', 0),
    ('review_runs', 'FAILED', 0),
    ('review_runs', 'SUPERSEDED', 0),
    ('retained_history_tokens', 'input', 0),
    ('retained_history_tokens', 'output', 0),
    ('publication_findings', 'INLINE_COMMENT', 0),
    ('publication_findings', 'CHECK_SUMMARY', 0),
    ('publication_findings', 'RETAIN_ONLY', 0),
    ('publication_comments', 'confirmed', 0),
    ('outbox', 'unpublished', 0);

-- State changes append transactional deltas instead of locking one hot counter row. The
-- scheduled refresher folds a fixed-size batch into the compact rollup and deletes only
-- those internal deltas after they have been applied atomically.
CREATE TABLE "${flyway:defaultSchema}".review_operations_metric_delta (
    delta_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    metric_name TEXT NOT NULL,
    dimension_value TEXT NOT NULL,
    metric_delta BIGINT NOT NULL CHECK (metric_delta <> 0),
    FOREIGN KEY (metric_name, dimension_value)
        REFERENCES "${flyway:defaultSchema}".review_operations_metric_rollup
            (metric_name, dimension_value)
);

CREATE FUNCTION "${flyway:defaultSchema}".review_metric_record(
    adjusted_metric TEXT,
    adjusted_dimension TEXT,
    delta BIGINT)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    IF adjusted_dimension IS NULL OR delta = 0 THEN
        RETURN;
    END IF;
    INSERT INTO "${flyway:defaultSchema}".review_operations_metric_delta (
        metric_name, dimension_value, metric_delta)
    VALUES (adjusted_metric, adjusted_dimension, delta);
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".review_metric_flush(batch_limit INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    processed INTEGER;
BEGIN
    IF batch_limit IS NULL OR batch_limit < 1 THEN
        RAISE EXCEPTION 'metric delta batch limit must be positive';
    END IF;
    WITH claimed AS MATERIALIZED (
        SELECT delta_id, metric_name, dimension_value, metric_delta
        FROM "${flyway:defaultSchema}".review_operations_metric_delta
        ORDER BY delta_id
        LIMIT batch_limit
        FOR UPDATE SKIP LOCKED
    ), aggregated AS (
        SELECT metric_name, dimension_value, sum(metric_delta) AS metric_delta
        FROM claimed
        GROUP BY metric_name, dimension_value
        HAVING sum(metric_delta) <> 0
    ), applied AS (
        UPDATE "${flyway:defaultSchema}".review_operations_metric_rollup AS current
        SET metric_value = current.metric_value + aggregated.metric_delta
        FROM aggregated
        WHERE current.metric_name = aggregated.metric_name
          AND current.dimension_value = aggregated.dimension_value
        RETURNING 1
    ), removed AS (
        DELETE FROM "${flyway:defaultSchema}".review_operations_metric_delta AS queued
        USING claimed
        WHERE queued.delta_id = claimed.delta_id
          AND (SELECT count(*) FROM applied) >= 0
        RETURNING 1
    )
    SELECT count(*) INTO processed FROM removed;
    RETURN processed;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".review_job_metric_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM "${flyway:defaultSchema}".review_metric_record('durable_jobs', NEW.state, 1);
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        PERFORM "${flyway:defaultSchema}".review_metric_record('durable_jobs', OLD.state, -1);
        RETURN OLD;
    ELSIF NEW.state IS DISTINCT FROM OLD.state THEN
        PERFORM "${flyway:defaultSchema}".review_metric_record('durable_jobs', OLD.state, -1);
        PERFORM "${flyway:defaultSchema}".review_metric_record('durable_jobs', NEW.state, 1);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER review_job_metric_transition
AFTER INSERT OR UPDATE OF state OR DELETE ON "${flyway:defaultSchema}".durable_jobs
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".review_job_metric_transition();

CREATE FUNCTION "${flyway:defaultSchema}".review_run_metric_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM "${flyway:defaultSchema}".review_metric_record('review_runs', NEW.state, 1);
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        PERFORM "${flyway:defaultSchema}".review_metric_record('review_runs', OLD.state, -1);
        RETURN OLD;
    ELSIF NEW.state IS DISTINCT FROM OLD.state THEN
        PERFORM "${flyway:defaultSchema}".review_metric_record('review_runs', OLD.state, -1);
        PERFORM "${flyway:defaultSchema}".review_metric_record('review_runs', NEW.state, 1);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER review_run_metric_transition
AFTER INSERT OR UPDATE OF state OR DELETE ON "${flyway:defaultSchema}".review_runs
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".review_run_metric_transition();

CREATE FUNCTION "${flyway:defaultSchema}".review_attempt_metric_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    input_delta BIGINT;
    output_delta BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        input_delta := coalesce(NEW.input_tokens, 0);
        output_delta := coalesce(NEW.output_tokens, 0);
    ELSIF TG_OP = 'DELETE' THEN
        input_delta := -coalesce(OLD.input_tokens, 0);
        output_delta := -coalesce(OLD.output_tokens, 0);
    ELSE
        input_delta := coalesce(NEW.input_tokens, 0) - coalesce(OLD.input_tokens, 0);
        output_delta := coalesce(NEW.output_tokens, 0) - coalesce(OLD.output_tokens, 0);
    END IF;
    PERFORM "${flyway:defaultSchema}".review_metric_record(
        'retained_history_tokens', 'input', input_delta);
    PERFORM "${flyway:defaultSchema}".review_metric_record(
        'retained_history_tokens', 'output', output_delta);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER review_attempt_metric_transition
AFTER INSERT OR UPDATE OF input_tokens, output_tokens OR DELETE
ON "${flyway:defaultSchema}".review_attempts
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".review_attempt_metric_transition();

CREATE FUNCTION "${flyway:defaultSchema}".review_finding_metric_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    old_confirmed BOOLEAN := false;
    new_confirmed BOOLEAN := false;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        PERFORM "${flyway:defaultSchema}".review_metric_record(
            'publication_findings', OLD.publication_tier, -1);
        old_confirmed := coalesce(OLD.publication_tier = 'INLINE_COMMENT'
            AND OLD.artifact_type = 'REVIEW_COMMENT'
            AND OLD.artifact_external_id IS NOT NULL, false);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        PERFORM "${flyway:defaultSchema}".review_metric_record(
            'publication_findings', NEW.publication_tier, 1);
        new_confirmed := coalesce(NEW.publication_tier = 'INLINE_COMMENT'
            AND NEW.artifact_type = 'REVIEW_COMMENT'
            AND NEW.artifact_external_id IS NOT NULL, false);
    END IF;
    PERFORM "${flyway:defaultSchema}".review_metric_record(
        'publication_comments', 'confirmed', new_confirmed::integer - old_confirmed::integer);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER review_finding_metric_transition
AFTER INSERT OR UPDATE OF publication_tier, artifact_type, artifact_external_id OR DELETE
ON "${flyway:defaultSchema}".review_findings
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".review_finding_metric_transition();

CREATE FUNCTION "${flyway:defaultSchema}".review_outbox_metric_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    old_unpublished INTEGER := 0;
    new_unpublished INTEGER := 0;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_unpublished := (OLD.published_at IS NULL)::integer;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_unpublished := (NEW.published_at IS NULL)::integer;
    END IF;
    PERFORM "${flyway:defaultSchema}".review_metric_record(
        'outbox', 'unpublished', new_unpublished - old_unpublished);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER review_outbox_metric_transition
AFTER INSERT OR UPDATE OF published_at OR DELETE ON "${flyway:defaultSchema}".outbox_events
FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".review_outbox_metric_transition();

-- Trigger creation takes a write-conflicting lock held until commit. These one-time seeds
-- therefore cannot miss or double-count a concurrent transition.
INSERT INTO "${flyway:defaultSchema}".review_operations_metric_rollup
SELECT 'durable_jobs', state, count(*) FROM "${flyway:defaultSchema}".durable_jobs GROUP BY state
ON CONFLICT (metric_name, dimension_value) DO UPDATE
SET metric_value = EXCLUDED.metric_value;

INSERT INTO "${flyway:defaultSchema}".review_operations_metric_rollup
SELECT 'review_runs', state, count(*) FROM "${flyway:defaultSchema}".review_runs GROUP BY state
ON CONFLICT (metric_name, dimension_value) DO UPDATE
SET metric_value = EXCLUDED.metric_value;

INSERT INTO "${flyway:defaultSchema}".review_operations_metric_rollup
SELECT 'retained_history_tokens', direction, token_count
FROM (
    SELECT 'input' AS direction, coalesce(sum(input_tokens), 0) AS token_count
    FROM "${flyway:defaultSchema}".review_attempts
    UNION ALL
    SELECT 'output', coalesce(sum(output_tokens), 0)
    FROM "${flyway:defaultSchema}".review_attempts
) totals
ON CONFLICT (metric_name, dimension_value) DO UPDATE
SET metric_value = EXCLUDED.metric_value;

INSERT INTO "${flyway:defaultSchema}".review_operations_metric_rollup
SELECT 'publication_findings', publication_tier, count(*)
FROM "${flyway:defaultSchema}".review_findings
WHERE publication_tier IS NOT NULL
GROUP BY publication_tier
ON CONFLICT (metric_name, dimension_value) DO UPDATE
SET metric_value = EXCLUDED.metric_value;

INSERT INTO "${flyway:defaultSchema}".review_operations_metric_rollup
SELECT 'publication_comments', 'confirmed', count(*)
FROM "${flyway:defaultSchema}".review_findings
WHERE publication_tier = 'INLINE_COMMENT'
  AND artifact_type = 'REVIEW_COMMENT'
  AND artifact_external_id IS NOT NULL
ON CONFLICT (metric_name, dimension_value) DO UPDATE
SET metric_value = EXCLUDED.metric_value;

INSERT INTO "${flyway:defaultSchema}".review_operations_metric_rollup
SELECT 'outbox', 'unpublished', count(*)
FROM "${flyway:defaultSchema}".outbox_events
WHERE published_at IS NULL
ON CONFLICT (metric_name, dimension_value) DO UPDATE
SET metric_value = EXCLUDED.metric_value;
