package dev.langchain4j.example.codereview.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Emits only fixed operation vocabulary plus validated correlation identifiers. */
public final class ReviewOperationLogger {

    private final Logger logger;

    public ReviewOperationLogger() {
        this(LoggerFactory.getLogger("review.operations"));
    }

    ReviewOperationLogger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void log(
            ReviewCorrelation correlation,
            Event event,
            Outcome outcome,
            SafeCode safeCode) {
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(safeCode, "safeCode");
        Map<String, String> prior = MDC.getCopyOfContextMap();
        try {
            MDC.clear();
            put("delivery_id", correlation.deliveryId());
            put("review_run_id", text(correlation.reviewRunId()));
            put("repository_id", text(correlation.repositoryId()));
            put("pull_request_number", text(correlation.pullRequestNumber()));
            put("head_sha", correlation.headSha());
            put("job_id", text(correlation.jobId()));
            put("pipeline_version", correlation.pipelineVersion());
            put("configuration_version", correlation.configurationVersion());
            put("event", event.value);
            put("outcome", outcome.value);
            if (safeCode != SafeCode.NONE) {
                put("safe_code", safeCode.value);
            }
            logger.info("review_operation");
        } finally {
            MDC.clear();
            if (prior != null) {
                MDC.setContextMap(prior);
            }
        }
    }

    private static void put(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    public enum Event {
        WEBHOOK("webhook"),
        JOB("job"),
        REVIEW_RUN("review_run"),
        PIPELINE("pipeline"),
        EXTERNAL_CALL("external_call"),
        PUBLICATION("publication"),
        OBSERVABILITY("observability"),
        RETENTION("retention");

        private final String value;

        Event(String value) {
            this.value = value;
        }
    }

    public enum Outcome {
        STARTED("started"),
        SUCCEEDED("succeeded"),
        ACCEPTED("accepted"),
        REJECTED("rejected"),
        RETRIED("retried"),
        DEAD("dead"),
        SUPERSEDED("superseded"),
        STALE_PREVENTED("stale_prevented"),
        FAILED("failed");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }
    }

    public enum SafeCode {
        NONE("none"),
        RETRY_EXHAUSTED("retry_exhausted"),
        DATABASE_UNAVAILABLE("database_unavailable"),
        INVALID_REQUEST("invalid_request"),
        DEPENDENCY_TIMEOUT("dependency_timeout"),
        STALE_REVISION("stale_revision");

        private final String value;

        SafeCode(String value) {
            this.value = value;
        }
    }
}
