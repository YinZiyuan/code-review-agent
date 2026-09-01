package dev.langchain4j.example.codereview.reviewops.application.jobs;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ReviewJobDispatcher {

    private final Map<String, ReviewJobHandler> handlers;

    public ReviewJobDispatcher(Collection<? extends ReviewJobHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers");
        Map<String, ReviewJobHandler> byType = new LinkedHashMap<>();
        for (ReviewJobHandler handler : handlers) {
            Objects.requireNonNull(handler, "handler");
            String jobType = requireNonBlank(handler.jobType(), "jobType");
            if (byType.putIfAbsent(jobType, handler) != null) {
                throw new IllegalArgumentException("duplicate review job handler: " + jobType);
            }
        }
        this.handlers = Map.copyOf(byType);
    }

    public ReviewJobHandler.JobOutcome dispatch(LeasedJob job) {
        Objects.requireNonNull(job, "job");
        ReviewJobHandler handler = handlers.get(job.jobType());
        if (handler == null) {
            return ReviewJobHandler.JobOutcome.terminalFailure("unknown_job_type");
        }
        return Objects.requireNonNull(handler.handle(job), "job outcome");
    }

    boolean handles(String jobType) {
        return handlers.containsKey(jobType);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
