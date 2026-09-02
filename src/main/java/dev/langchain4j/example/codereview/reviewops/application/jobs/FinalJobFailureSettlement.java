package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface FinalJobFailureSettlement {

    FinalFailureSettlement settleFinalFailure(
            LeasedJob job,
            FailureClass failureClass,
            String safeCode,
            Instant settledAt);

    record FinalFailureSettlement(
            DurableJobQueue.FailureDisposition disposition,
            List<DurableJobRequest> followUpJobs) {

        public FinalFailureSettlement {
            Objects.requireNonNull(disposition, "disposition");
            followUpJobs = List.copyOf(Objects.requireNonNull(followUpJobs, "followUpJobs"));
        }
    }
}
