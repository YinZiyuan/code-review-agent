package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import dev.langchain4j.example.codereview.reviewops.application.ExecuteReviewRun;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunJobMismatchException;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunMutationStore;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxStore;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Objects;

public final class TransactionalReviewRunMutationStore implements ReviewRunMutationStore {

    private final ReviewRunRepository reviewRuns;
    private final DurableJobQueue jobs;
    private final OutboxStore outbox;
    private final TransactionOperations transactions;

    public TransactionalReviewRunMutationStore(
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
    public long saveProgress(ReviewRun run, long expectedVersion) {
        requireMutation(run, expectedVersion);
        return Objects.requireNonNull(
                transactions.execute(status -> reviewRuns.update(run, expectedVersion)),
                "transaction result");
    }

    @Override
    public long saveAndEnqueue(
            ReviewRun run,
            long expectedVersion,
            List<DurableJobRequest> requestedJobs,
            List<OutboxEvent> events) {
        requireMutation(run, expectedVersion);
        List<DurableJobRequest> immutableJobs = List.copyOf(
                Objects.requireNonNull(requestedJobs, "jobs"));
        List<OutboxEvent> immutableEvents = List.copyOf(
                Objects.requireNonNull(events, "events"));
        validateIntentOwnership(run, immutableJobs, immutableEvents);

        return Objects.requireNonNull(transactions.execute(status -> {
            long nextVersion = reviewRuns.update(run, expectedVersion);
            immutableJobs.forEach(jobs::enqueue);
            immutableEvents.forEach(outbox::append);
            return nextVersion;
        }), "transaction result");
    }

    private static void requireMutation(ReviewRun run, long expectedVersion) {
        Objects.requireNonNull(run, "run");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
    }

    private static void validateIntentOwnership(
            ReviewRun run,
            List<DurableJobRequest> jobs,
            List<OutboxEvent> events) {
        for (DurableJobRequest job : jobs) {
            Objects.requireNonNull(job, "job");
            if (!run.id().value().equals(job.payloadReference())) {
                throw new ReviewRunJobMismatchException(run.id(), job.payloadReference());
            }
            if (ExecuteReviewRun.DECIDE_PUBLICATION_JOB_TYPE.equals(job.jobType())
                    && run.state() != ReviewRunState.COMPLETED) {
                throw new IllegalArgumentException(
                        "DECIDE_PUBLICATION requires a COMPLETED review run");
            }
        }
        for (OutboxEvent event : events) {
            Objects.requireNonNull(event, "event");
            if (!run.id().value().equals(event.aggregateId())) {
                throw new IllegalArgumentException(
                        "outbox event aggregate must match review run");
            }
        }
    }
}
