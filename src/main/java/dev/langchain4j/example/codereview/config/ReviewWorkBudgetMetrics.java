package dev.langchain4j.example.codereview.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;

/** Read-only metric projection of the effective immutable review resource contract. */
public final class ReviewWorkBudgetMetrics {

    public ReviewWorkBudgetMetrics(ReviewWorkBudget budget, MeterRegistry metrics) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(metrics, "metrics");
        Gauge.builder("code.review.work.budget.info", budget, ignored -> 1)
                .tag("version", budget.version())
                .tag("configuration_hash", budget.configurationHash())
                .register(metrics);

        limit(metrics, budget, "max_diff_bytes", budget.input().maxDiffBytes());
        limit(metrics, budget, "max_changed_files", budget.input().maxChangedFiles());
        limit(metrics, budget, "max_java_source_files", budget.input().maxJavaSourceFiles());
        limit(metrics, budget, "max_java_source_bytes", budget.input().maxJavaSourceBytes());
        limit(metrics, budget, "max_archive_bytes", budget.input().maxArchiveBytes());
        limit(metrics, budget, "max_expanded_bytes", budget.input().maxExpandedBytes());
        limit(metrics, budget, "max_archive_entries", budget.input().maxArchiveEntries());
        limit(metrics, budget, "max_snippets", budget.input().maxSnippets());
        limit(metrics, budget, "max_findings", budget.input().maxFindings());
        limit(metrics, budget, "max_diff_tokens", budget.prompt().maxDiffTokens());
        limit(metrics, budget, "model_context_tokens", budget.prompt().modelContextTokens());
        limit(metrics, budget, "completion_reserve_tokens",
                budget.prompt().completionReserveTokens());
        limit(metrics, budget, "max_process_output_bytes", budget.process().maxOutputBytes());
        limit(metrics, budget, "compiler_max_heap_mb", budget.process().compilerMaxHeapMb());
        limit(metrics, budget, "analyzer_max_heap_mb", budget.process().analyzerMaxHeapMb());
        limit(metrics, budget, "diff_analysis_deadline_millis",
                budget.stages().diffAnalysis().toMillis());
        limit(metrics, budget, "tool_analysis_deadline_millis",
                budget.stages().toolAnalysis().toMillis());
        limit(metrics, budget, "review_model_deadline_millis",
                budget.stages().reviewModel().toMillis());
        limit(metrics, budget, "summarization_deadline_millis",
                budget.stages().summarization().toMillis());
        limit(metrics, budget, "compiler_deadline_millis", budget.stages().compiler().toMillis());
        limit(metrics, budget, "spotbugs_deadline_millis", budget.stages().spotbugs().toMillis());
        limit(metrics, budget, "workspace_stale_age_millis",
                budget.workspace().staleAge().toMillis());
    }

    private static void limit(
            MeterRegistry metrics, ReviewWorkBudget budget, String name, double value) {
        Gauge.builder("code.review.work.budget.limit", budget, ignored -> value)
                .tag("limit", name)
                .register(metrics);
    }
}
