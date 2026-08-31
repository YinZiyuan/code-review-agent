package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DurableJobQueue {

    UUID enqueue(DurableJobRequest request);

    List<LeasedJob> leaseDue(String owner, Instant now, Duration leaseDuration, int limit);

    void markSucceeded(UUID jobId, String owner, Instant now);

    void recordFailure(UUID jobId, String owner, FailureClass failureClass,
                       Instant nextAttemptAt, Instant now);

    int recoverExpiredLeases(Instant now);
}
