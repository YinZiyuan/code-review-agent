package dev.langchain4j.example.codereview.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewOperationLoggerTest {

    @Test
    void emitsJsonCorrelationWithoutRawErrorsSourcesOrSecrets() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Logger logger = (Logger) LoggerFactory.getLogger("review-operation-test");
        LoggerContext context = logger.getLoggerContext();
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.setIncludeCallerData(false);
        encoder.start();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(output);
        appender.start();
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        ReviewOperationLogger operationLogger = new ReviewOperationLogger(logger);
        UUID reviewRunId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000222");

        operationLogger.log(
                new ReviewCorrelation(
                        "delivery-123", reviewRunId, 73L, 12,
                        "0123456789abcdef0123456789abcdef01234567", jobId,
                        "pipeline-v3", "sha256-abc123"),
                ReviewOperationLogger.Event.JOB,
                ReviewOperationLogger.Outcome.DEAD,
                ReviewOperationLogger.SafeCode.RETRY_EXHAUSTED);
        appender.stop();
        logger.detachAppender(appender);

        String json = output.toString(StandardCharsets.UTF_8);
        JsonNode event = new ObjectMapper().readTree(json);
        assertThat(event.path("message").asText()).isEqualTo("review_operation");
        assertThat(event.path("delivery_id").asText()).isEqualTo("delivery-123");
        assertThat(event.path("review_run_id").asText()).isEqualTo(reviewRunId.toString());
        assertThat(event.path("repository_id").asText()).isEqualTo("73");
        assertThat(event.path("pull_request_number").asText()).isEqualTo("12");
        assertThat(event.path("head_sha").asText())
                .isEqualTo("0123456789abcdef0123456789abcdef01234567");
        assertThat(event.path("job_id").asText()).isEqualTo(jobId.toString());
        assertThat(event.path("pipeline_version").asText()).isEqualTo("pipeline-v3");
        assertThat(event.path("configuration_version").asText()).isEqualTo("sha256-abc123");
        assertThat(event.path("event").asText()).isEqualTo("job");
        assertThat(event.path("outcome").asText()).isEqualTo("dead");
        assertThat(event.path("safe_code").asText()).isEqualTo("retry_exhausted");
        assertThat(json)
                .doesNotContain("exception", "stack_trace", "source", "password", "secret", "token");
    }

    @Test
    void dropsUntrustedCorrelationTextInsteadOfLoggingIt() {
        ReviewCorrelation correlation = new ReviewCorrelation(
                "token=ghs_secret-value", null, null, null,
                "not-a-sha containing source text", null,
                "pipeline\npassword=secret", "configuration version with spaces");

        assertThat(correlation.deliveryId()).isNull();
        assertThat(correlation.headSha()).isNull();
        assertThat(correlation.pipelineVersion()).isNull();
        assertThat(correlation.configurationVersion()).isNull();
    }
}
