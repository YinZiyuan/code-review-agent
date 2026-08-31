DO $$
DECLARE
    legacy_constraint_name TEXT;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint constraint_definition
        JOIN pg_class table_definition
          ON table_definition.oid = constraint_definition.conrelid
        JOIN pg_namespace schema_definition
          ON schema_definition.oid = table_definition.relnamespace
        CROSS JOIN unnest(constraint_definition.conkey) WITH ORDINALITY
          AS key_columns(attribute_number, ordinality)
        JOIN pg_attribute attribute
          ON attribute.attrelid = table_definition.oid
         AND attribute.attnum = key_columns.attribute_number
        WHERE schema_definition.nspname = current_schema()
          AND table_definition.relname = 'review_runs'
          AND constraint_definition.conname = 'uq_review_runs_business_identity'
          AND constraint_definition.contype = 'u'
        GROUP BY constraint_definition.oid
        HAVING array_agg(attribute.attname ORDER BY key_columns.ordinality) =
               ARRAY['installation_id', 'repository_id', 'pull_request_number',
                     'head_sha', 'pipeline_version', 'configuration_version']::name[]
    ) THEN
        NULL;
    ELSE
        SELECT constraint_definition.conname
        INTO legacy_constraint_name
        FROM pg_constraint constraint_definition
        JOIN pg_class table_definition
          ON table_definition.oid = constraint_definition.conrelid
        JOIN pg_namespace schema_definition
          ON schema_definition.oid = table_definition.relnamespace
        CROSS JOIN unnest(constraint_definition.conkey) WITH ORDINALITY
          AS key_columns(attribute_number, ordinality)
        JOIN pg_attribute attribute
          ON attribute.attrelid = table_definition.oid
         AND attribute.attnum = key_columns.attribute_number
        WHERE schema_definition.nspname = current_schema()
          AND table_definition.relname = 'review_runs'
          AND constraint_definition.conname =
              'review_runs_installation_id_repository_id_pull_request_numb_key'
          AND constraint_definition.contype = 'u'
        GROUP BY constraint_definition.oid, constraint_definition.conname
        HAVING array_agg(attribute.attname ORDER BY key_columns.ordinality) =
               ARRAY['installation_id', 'repository_id', 'pull_request_number',
                     'head_sha', 'pipeline_version', 'configuration_version']::name[];

        IF legacy_constraint_name IS NULL THEN
            RAISE EXCEPTION
                'V3 requires the legacy review_runs business identity unique constraint';
        END IF;

        EXECUTE format(
            'ALTER TABLE review_runs RENAME CONSTRAINT %I TO uq_review_runs_business_identity',
            legacy_constraint_name);
    END IF;
END
$$;
