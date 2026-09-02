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
        log(correlation, new ReviewOperationSignal(
                event, Action.UNSPECIFIED, outcome, safeCode));
    }

    public void log(
            ReviewCorrelation correlation,
            ReviewOperationSignal signal) {
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(signal, "signal");
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
            put("event", signal.event().value);
            put("action", signal.action().value);
            put("outcome", signal.outcome().value);
            if (signal.safeCode() != SafeCode.NONE) {
                put("safe_code", signal.safeCode().value);
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

    /** Detailed operation names anticipated at stream A/B integration boundaries. */
    public enum Action {
        UNSPECIFIED(null),
        WEBHOOK_RECEIVED("webhook_received"),
        RUN_ADMITTED("run_admitted"),
        JOB_LEASED("job_leased"),
        JOB_HEARTBEAT("job_heartbeat"),
        JOB_RETRY_SCHEDULED("job_retry_scheduled"),
        JOB_DEAD_LETTERED("job_dead_lettered"),
        REVIEW_EXECUTED("review_executed"),
        PIPELINE_STAGE("pipeline_stage"),
        MODEL_CALL("model_call"),
        GITHUB_CALL("github_call"),
        PUBLICATION_DECIDED("publication_decided"),
        PUBLICATION_SENT("publication_sent"),
        STALE_WRITE_PREVENTED("stale_write_prevented"),
        OBSERVABILITY_REFRESH("observability_refresh"),
        RETENTION_ARCHIVE("retention_archive");

        private final String value;

        Action(String value) {
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
