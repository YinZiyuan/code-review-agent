package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import dev.langchain4j.example.codereview.reviewops.application.ReviewRunAdmissionStore;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunJobMismatchException;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxStore;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Objects;

public final class TransactionalReviewRunAdmissionStore implements ReviewRunAdmissionStore {

    private final ReviewRunRepository reviewRuns;
    private final DurableJobQueue jobs;
    private final OutboxStore outbox;
    private final TransactionOperations transactions;

    public TransactionalReviewRunAdmissionStore(
            ReviewRunRepository reviewRuns,
            DurableJobQueue jobs,
            OutboxStore outbox,
            TransactionOperations transactions) {
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void admit(ReviewRun reviewRun,
                      DurableJobRequest executionJob,
                      List<OutboxEvent> outboxEvents) {
        Objects.requireNonNull(reviewRun, "reviewRun");
        Objects.requireNonNull(executionJob, "executionJob");
        if (!reviewRun.id().value().equals(executionJob.payloadReference())) {
            throw new ReviewRunJobMismatchException(
                    reviewRun.id(), executionJob.payloadReference());
        }
        List<OutboxEvent> immutableEvents = List.copyOf(
                Objects.requireNonNull(outboxEvents, "outboxEvents"));

        transactions.executeWithoutResult(status -> {
            reviewRuns.insert(reviewRun);
            jobs.enqueue(executionJob);
            immutableEvents.forEach(outbox::append);
        });
    }
}
