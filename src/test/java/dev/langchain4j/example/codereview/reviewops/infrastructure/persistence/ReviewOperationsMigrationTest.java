package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewOperationsMigrationTest extends PostgresIntegrationSupport {

    @Test
    void migratesTheReviewOperationsSchemaWithItsBusinessKeysAndDeliveryIndexes() throws SQLException {
        assertThat(tableNames()).containsExactlyInAnyOrder(
                "github_deliveries",
                "review_runs",
                "review_attempts",
                "review_findings",
                "finding_feedback",
                "durable_jobs",
                "outbox_events");
        assertThat(constraintColumns("review_runs", "u"))
                .contains(List.of(
                        "installation_id",
                        "repository_id",
                        "pull_request_number",
                        "head_sha",
                        "pipeline_version",
                        "configuration_version"));
        assertThat(constraintColumns("review_attempts", "p"))
                .contains(List.of("review_run_id", "attempt_number"));
        assertThat(constraintColumns("review_findings", "p"))
                .contains(List.of("review_run_id", "fingerprint"));
        assertThat(constraintColumns("durable_jobs", "u"))
                .contains(List.of("idempotency_key"));
        assertThat(unpublishedOutboxIndexExists()).isTrue();
    }

    private List<String> tableNames() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_type = 'BASE TABLE'
                       AND table_name IN ('github_deliveries', 'review_runs', 'review_attempts', 'review_findings',
                                          'finding_feedback', 'durable_jobs', 'outbox_events')
                     """);
             var resultSet = statement.executeQuery()) {
            var tables = new ArrayList<String>();
            while (resultSet.next()) {
                tables.add(resultSet.getString("table_name"));
            }
            return tables;
        }
    }

    private List<List<String>> constraintColumns(String tableName, String constraintType) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT array_agg(attribute.attname ORDER BY key_columns.ordinality) AS columns
                     FROM pg_constraint constraint_definition
                     JOIN pg_class table_definition ON table_definition.oid = constraint_definition.conrelid
                     JOIN pg_namespace schema_definition ON schema_definition.oid = table_definition.relnamespace
                     CROSS JOIN unnest(constraint_definition.conkey) WITH ORDINALITY
                         AS key_columns(attribute_number, ordinality)
                     JOIN pg_attribute attribute
                         ON attribute.attrelid = table_definition.oid
                        AND attribute.attnum = key_columns.attribute_number
                     WHERE schema_definition.nspname = 'public'
                       AND table_definition.relname = ?
                       AND constraint_definition.contype = ?
                     GROUP BY constraint_definition.oid
                     """)) {
            statement.setString(1, tableName);
            statement.setString(2, constraintType);
            try (var resultSet = statement.executeQuery()) {
                var constraints = new ArrayList<List<String>>();
                while (resultSet.next()) {
                    constraints.add(List.of((String[]) resultSet.getArray("columns").getArray()));
                }
                return constraints;
            }
        }
    }

    private boolean unpublishedOutboxIndexExists() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1
                         FROM pg_indexes
                         WHERE schemaname = 'public'
                           AND tablename = 'outbox_events'
                           AND indexname = 'idx_outbox_events_unpublished'
                           AND indexdef ILIKE '%WHERE (published_at IS NULL)%'
                     )
                     """);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getBoolean(1);
        }
    }
}
