package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunAdmissionStore;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationStatus.ADMITTED;
import static dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationStatus.DUPLICATE_DELIVERY;
import static dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationStatus.EXISTING_REVISION;

public final class JdbcPullRequestObservationStore implements PullRequestObservationStore {

    private final JdbcTemplate jdbcTemplate;
    private final ReviewRunAdmissionStore reviewRunAdmission;
    private final TransactionOperations transactions;

    public JdbcPullRequestObservationStore(
            JdbcTemplate jdbcTemplate,
            ReviewRunAdmissionStore reviewRunAdmission,
            TransactionOperations transactions) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.reviewRunAdmission = Objects.requireNonNull(reviewRunAdmission, "reviewRunAdmission");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public ObservationResult admit(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        return Objects.requireNonNull(transactions.execute(status -> admitInTransaction(request)),
                "transaction result");
    }

    private ObservationResult admitInTransaction(ObservationRequest request) {
        int deliveryInserted = jdbcTemplate.update("""
                        INSERT INTO github_deliveries (
                            delivery_id, event_name, payload_sha256, received_at, handled_at)
                        VALUES (?, ?, ?, ?, NULL)
                        ON CONFLICT (delivery_id) DO NOTHING
                        """,
                request.deliveryId(),
                request.eventName(),
                request.payloadSha256(),
                Timestamp.from(request.receivedAt()));

        if (deliveryInserted == 0) {
            requireMatchingDeliveryFacts(request);
            ReviewRunId authoritative = findExistingReviewRun(request.reviewRun())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recorded delivery has no authoritative review run"));
            return new ObservationResult(DUPLICATE_DELIVERY, authoritative);
        }

        lockBusinessIdentity(request.reviewRun());
        Optional<ReviewRunId> existing = findExistingReviewRun(request.reviewRun());
        if (existing.isPresent()) {
            markHandled(request);
            return new ObservationResult(EXISTING_REVISION, existing.orElseThrow());
        }

        reviewRunAdmission.admit(request.reviewRun(), request.executionJob(), List.of());
        markHandled(request);
        return new ObservationResult(ADMITTED, request.reviewRun().id());
    }

    private void requireMatchingDeliveryFacts(ObservationRequest request) {
        List<DeliveryFacts> deliveries = jdbcTemplate.query("""
                        SELECT event_name, payload_sha256
                        FROM github_deliveries
                        WHERE delivery_id = ?
                        """,
                (resultSet, rowNumber) -> new DeliveryFacts(
                        resultSet.getString("event_name"),
                        resultSet.getString("payload_sha256")),
                request.deliveryId());
        if (deliveries.size() != 1
                || !deliveries.get(0).eventName().equals(request.eventName())
                || !deliveries.get(0).payloadSha256().equals(request.payloadSha256())) {
            throw new IllegalStateException(
                    "Recorded delivery conflicts with supplied event facts");
        }
    }

    private void lockBusinessIdentity(ReviewRun reviewRun) {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text",
                String.class,
                businessIdentityLockKey(reviewRun));
    }

    private Optional<ReviewRunId> findExistingReviewRun(ReviewRun proposed) {
        ReviewConfigurationSnapshot configuration = proposed.configuration();
        List<UUID> ids = jdbcTemplate.query("""
                        SELECT id
                        FROM review_runs
                        WHERE installation_id = ?
                          AND repository_id = ?
                          AND pull_request_number = ?
                          AND head_sha = ?
                          AND pipeline_version = ?
                          AND configuration_version = ?
                        """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                proposed.revision().installationId(),
                proposed.revision().repositoryId(),
                proposed.revision().pullRequestNumber(),
                proposed.revision().headSha(),
                configuration.pipelineVersion(),
                configuration.configurationVersion());
        return ids.stream().findFirst().map(ReviewRunId::new);
    }

    private void markHandled(ObservationRequest request) {
        int updated = jdbcTemplate.update("""
                        UPDATE github_deliveries
                        SET handled_at = ?
                        WHERE delivery_id = ? AND handled_at IS NULL
                        """,
                Timestamp.from(request.reviewRun().requestedAt()),
                request.deliveryId());
        if (updated != 1) {
            throw new IllegalStateException("New delivery could not be marked handled");
        }
    }

    private static String businessIdentityLockKey(ReviewRun reviewRun) {
        return reviewRun.revision().installationId() + "\n"
                + reviewRun.revision().repositoryId() + "\n"
                + reviewRun.revision().pullRequestNumber() + "\n"
                + reviewRun.revision().headSha() + "\n"
                + reviewRun.configuration().pipelineVersion() + "\n"
                + reviewRun.configuration().configurationVersion();
    }

    private record DeliveryFacts(String eventName, String payloadSha256) {
    }
}
