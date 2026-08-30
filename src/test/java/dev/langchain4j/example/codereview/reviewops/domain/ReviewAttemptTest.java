package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewAttemptTest {
    private static final Instant START = Instant.parse("2026-08-30T00:00:00Z");
    private static final Instant END = START.plusSeconds(5);
    private static final ExecutionMeasurements METRICS =
            new ExecutionMeasurements(5000, 100, 20, Map.of("spotbugs", "RAN"));

    @Test
    void successfulAttemptIsTerminal() {
        ReviewAttempt attempt = ReviewAttempt.start(1, START);
        attempt.succeed(METRICS, END);

        assertThat(attempt.state()).isEqualTo(ReviewAttemptState.SUCCEEDED);
        assertThat(attempt.measurements()).contains(METRICS);
        assertThatThrownBy(() -> attempt.failTransient(
                new ReviewFailure("timeout", FailureClass.TRANSIENT, "timed out"), METRICS, END))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureMethodsRejectWrongFailureClass() {
        ReviewAttempt attempt = ReviewAttempt.start(1, START);
        ReviewFailure terminal = new ReviewFailure("bad_diff", FailureClass.TERMINAL, "bad diff");

        assertThatThrownBy(() -> attempt.failTransient(terminal, METRICS, END))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void measurementsAreDefensivelyCopied() {
        ExecutionMeasurements measurements =
                new ExecutionMeasurements(1, 2, 3, new java.util.HashMap<>(Map.of("regex", "RAN")));
        assertThatThrownBy(() -> measurements.toolStates().put("spotbugs", "FAILED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
