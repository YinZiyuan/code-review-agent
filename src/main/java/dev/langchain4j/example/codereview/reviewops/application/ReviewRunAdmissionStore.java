package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;

import java.util.List;

public interface ReviewRunAdmissionStore {

    void admit(ReviewRun reviewRun,
               DurableJobRequest executionJob,
               List<OutboxEvent> outboxEvents);
}
