package dev.langchain4j.example.codereview.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricsTest {

    @Test
    void recallIsTpOverTpPlusFn() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 3, 1, 1, 2, 3, 100, 100, 50, 5, 0),
                new SampleMetrics("s2", 1, 0, 2, 1, 1, 200, 200, 80, 3, 0)
        );
        assertThat(Metrics.recall(ms)).isCloseTo(4.0 / 7.0, within(0.0001));
    }

    @Test
    void precisionIsTpOverTpPlusFp() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 3, 1, 0, 2, 3, 100, 100, 50, 5, 0),
                new SampleMetrics("s2", 1, 2, 0, 1, 1, 200, 200, 80, 3, 0)
        );
        assertThat(Metrics.precision(ms)).isCloseTo(4.0 / 7.0, within(0.0001));
    }

    @Test
    void fpRateIsFpOverTotalReported() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 3, 1, 0, 0, 0, 0, 0, 0, 0, 0),
                new SampleMetrics("s2", 1, 2, 0, 0, 0, 0, 0, 0, 0, 0)
        );
        assertThat(Metrics.fpRate(ms)).isCloseTo(3.0 / 7.0, within(0.0001));
    }

    @Test
    void severityAccuracyIsMatchesOverComparisons() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 0, 0, 0, 4, 5, 0, 0, 0, 0, 0),
                new SampleMetrics("s2", 0, 0, 0, 3, 5, 0, 0, 0, 0, 0)
        );
        assertThat(Metrics.severityAccuracy(ms)).isCloseTo(0.7, within(0.0001));
    }

    @Test
    void zeroDenominatorReturnsZero() {
        SampleMetrics empty = new SampleMetrics("s1", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThat(Metrics.recall(List.of(empty))).isEqualTo(0.0);
        assertThat(Metrics.precision(List.of(empty))).isEqualTo(0.0);
        assertThat(Metrics.fpRate(List.of(empty))).isEqualTo(0.0);
        assertThat(Metrics.severityAccuracy(List.of(empty))).isEqualTo(0.0);
    }

    @Test
    void avgLatencyAndTokens() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 0, 0, 0, 0, 0, 100, 1000, 500, 10, 0),
                new SampleMetrics("s2", 0, 0, 0, 0, 0, 300, 3000, 1500, 8, 0)
        );
        assertThat(Metrics.avgLatencyMs(ms)).isEqualTo(200.0);
        assertThat(Metrics.avgInputTokens(ms)).isEqualTo(2000.0);
        assertThat(Metrics.avgOutputTokens(ms)).isEqualTo(1000.0);
    }

    @Test
    void toolSuccessRate() {
        List<SampleMetrics> ms = List.of(
                new SampleMetrics("s1", 0, 0, 0, 0, 0, 0, 0, 0, 10, 1),
                new SampleMetrics("s2", 0, 0, 0, 0, 0, 0, 0, 0, 10, 0)
        );
        assertThat(Metrics.toolSuccessRate(ms)).isCloseTo(0.95, within(0.0001));
    }
}
