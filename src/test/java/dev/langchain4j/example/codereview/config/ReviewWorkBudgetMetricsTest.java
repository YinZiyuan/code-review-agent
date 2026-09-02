package dev.langchain4j.example.codereview.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewWorkBudgetMetricsTest {

    @Test
    void exposesVersionHashAndResourceLimitsWithOnlyFixedLowCardinalityTags() {
        ReviewWorkBudget budget = new ReviewWorkBudgetProperties(
                null, null, null, null, null, null).toBudget();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        new ReviewWorkBudgetMetrics(budget, metrics);

        assertThat(metrics.get("code.review.work.budget.info")
                .tag("version", budget.version())
                .tag("configuration_hash", budget.configurationHash())
                .gauge().value()).isEqualTo(1);
        assertThat(metrics.get("code.review.work.budget.limit")
                .tag("limit", "max_diff_bytes").gauge().value())
                .isEqualTo(budget.input().maxDiffBytes());
        assertThat(metrics.get("code.review.work.budget.limit")
                .tag("limit", "compiler_deadline_millis").gauge().value())
                .isEqualTo(budget.stages().compiler().toMillis());
        assertThat(metrics.get("code.review.work.budget.limit")
                .tag("limit", "analyzer_max_heap_mb").gauge().value())
                .isEqualTo(256);
        assertThat(metrics.get("code.review.work.budget.limit")
                .tag("limit", "stage_workers").gauge().value()).isEqualTo(4);
        assertThat(metrics.get("code.review.work.budget.limit")
                .tag("limit", "stage_queue_capacity").gauge().value()).isEqualTo(16);
        assertThat(metrics.get("code.review.work.budget.limit")
                .tag("limit", "workspace_max_children_inspected").gauge().value())
                .isEqualTo(1_024);
    }
}
