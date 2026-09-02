package dev.langchain4j.example.codereview.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewWorkBudgetTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class, ReviewWorkBudgetConfiguration.class);

    @Test
    void bindsOneVersionedImmutableBudgetWithSafeDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ReviewWorkBudget budget = context.getBean(ReviewWorkBudget.class);

            assertThat(budget.version()).isEqualTo("review-work-v1");
            assertThat(budget.input().maxDiffBytes()).isEqualTo(5 * 1024 * 1024);
            assertThat(budget.input().maxChangedFiles()).isEqualTo(500);
            assertThat(budget.input().maxJavaSourceFiles()).isEqualTo(2_000);
            assertThat(budget.input().maxJavaSourceBytes()).isEqualTo(32L * 1024 * 1024);
            assertThat(budget.input().maxJavaSourceLineBytes()).isEqualTo(64 * 1024);
            assertThat(budget.input().maxSnippets()).isEqualTo(200);
            assertThat(budget.input().maxFindings()).isEqualTo(200);
            assertThat(budget.input().maxArchiveBytes()).isEqualTo(100 * 1024 * 1024);
            assertThat(budget.input().maxExpandedBytes()).isEqualTo(500L * 1024 * 1024);
            assertThat(budget.input().maxArchiveEntries()).isEqualTo(50_000);
            assertThat(budget.prompt().modelContextTokens()).isEqualTo(8_192);
            assertThat(budget.prompt().completionReserveTokens()).isEqualTo(2_048);
            assertThat(budget.prompt().inputFramingReserveTokens()).isEqualTo(16);
            assertThat(budget.prompt().maxDiffTokens()).isEqualTo(4_096);
            assertThat(budget.prompt().modelId()).isEqualTo("moonshot-v1-8k");
            assertThat(budget.prompt().tokenizerId()).isEqualTo("cl100k_base");
            assertThat(budget.prompt().tokenizerVersion()).isEqualTo("jtokkit-1.1.0");
            assertThat(budget.process().maxOutputBytes()).isEqualTo(64 * 1024);
            assertThat(budget.process().compilerMaxHeapMb()).isEqualTo(256);
            assertThat(budget.process().analyzerMaxHeapMb()).isEqualTo(256);
            assertThat(budget.stages().reviewModel()).isEqualTo(Duration.ofSeconds(60));
            assertThat(budget.workspace().staleAge()).isEqualTo(Duration.ofHours(24));
            assertThat(budget.workspace().maxChildrenInspected()).isEqualTo(1_024);
            assertThat(budget.workspace().maxDeletionsPerRun()).isEqualTo(64);
            assertThat(budget.workspace().maxEntriesDeletedPerRun()).isEqualTo(10_000);
            assertThat(budget.workspace().cleanupDeadline()).isEqualTo(Duration.ofSeconds(5));
            assertThat(budget.execution().reviewerTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(budget.execution().stageWorkers()).isEqualTo(4);
            assertThat(budget.execution().stageQueueCapacity()).isEqualTo(16);
            assertThat(budget.configurationHash()).matches("[0-9a-f]{64}");
            assertThat(budget.identity()).isEqualTo(
                    "review-work-v1:" + budget.configurationHash());
        });
    }

    @Test
    void rejectsRuntimeModelThatDoesNotMatchTheValidatedTokenizerContract() {
        contextRunner.withPropertyValues(
                        "langchain4j.open-ai.chat-model.model-name=unknown-model")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void everyExecutionAndTokenizerCapChangesTheStableIdentity() {
        ReviewWorkBudget defaults = new ReviewWorkBudgetProperties(
                null, null, null, null, null, null, null).toBudget();
        contextRunner.withPropertyValues(
                        "langchain4j.open-ai.chat-model.model-name=moonshot-v1-8k",
                        "code-review.work-budget.execution.reviewer-timeout=7s",
                        "code-review.work-budget.execution.stage-workers=2",
                        "code-review.work-budget.execution.stage-queue-capacity=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ReviewWorkBudget changed = context.getBean(ReviewWorkBudget.class);
                    assertThat(changed.execution()).isEqualTo(
                            new ReviewWorkBudget.ExecutionLimits(Duration.ofSeconds(7), 2, 3));
                    assertThat(changed.configurationHash()).isNotEqualTo(defaults.configurationHash());
                });
    }

    @Test
    void semanticOverrideChangesStableHashAndBindsEveryStageDeadline() {
        contextRunner.withPropertyValues(
                        "code-review.work-budget.version=review-work-v7",
                        "code-review.work-budget.input.max-findings=17",
                        "code-review.work-budget.stages.diff-analysis=3s",
                        "code-review.work-budget.stages.tool-analysis=4s",
                        "code-review.work-budget.stages.review-model=5s",
                        "code-review.work-budget.stages.summarization=6s",
                        "code-review.work-budget.stages.compiler=7s",
                        "code-review.work-budget.stages.spotbugs=8s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ReviewWorkBudget changed = context.getBean(ReviewWorkBudget.class);
                    ReviewWorkBudget defaults = new ReviewWorkBudgetProperties(
                            null, null, null, null, null, null).toBudget();

                    assertThat(changed.version()).isEqualTo("review-work-v7");
                    assertThat(changed.input().maxFindings()).isEqualTo(17);
                    assertThat(changed.stages()).isEqualTo(new ReviewWorkBudget.StageDeadlines(
                            Duration.ofSeconds(3), Duration.ofSeconds(4), Duration.ofSeconds(5),
                            Duration.ofSeconds(6), Duration.ofSeconds(7), Duration.ofSeconds(8)));
                    assertThat(changed.configurationHash())
                            .isNotEqualTo(defaults.configurationHash());
                });
    }

    @Test
    void rejectsBudgetsThatCannotReserveCompletionContextOrBoundResources() {
        assertThatThrownBy(() -> new ReviewWorkBudget(
                "v1",
                new ReviewWorkBudget.InputLimits(1, 1, 1, 1, 1, 1, 1, 1, 1),
                new ReviewWorkBudget.PromptLimits(10, 100, 100, 1),
                new ReviewWorkBudget.ProcessLimits(1, 1, 1),
                positiveDeadlines(),
                new ReviewWorkBudget.WorkspaceLimits(Duration.ofSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completionReserveTokens");

        assertThatThrownBy(() -> new ReviewWorkBudget(
                "v1",
                new ReviewWorkBudget.InputLimits(1, 1, 1, 1, 1, 1, 1, 0, 1),
                new ReviewWorkBudget.PromptLimits(1, 3, 1, 1),
                new ReviewWorkBudget.ProcessLimits(1, 1, 1),
                positiveDeadlines(),
                new ReviewWorkBudget.WorkspaceLimits(Duration.ofSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSnippets");
    }

    @Test
    void hashContainsNoEnvironmentOrSecretMaterial() {
        String secret = "credential-that-must-never-enter-budget-identity";
        System.setProperty("review.work.budget.secret.fixture", secret);
        try {
            ReviewWorkBudget budget = new ReviewWorkBudgetProperties(
                    null, null, null, null, null, null).toBudget();
            assertThat(budget.configurationHash()).doesNotContain(secret);
            assertThat(budget.toString()).doesNotContain(secret);
        } finally {
            System.clearProperty("review.work.budget.secret.fixture");
        }
    }

    private static ReviewWorkBudget.StageDeadlines positiveDeadlines() {
        Duration second = Duration.ofSeconds(1);
        return new ReviewWorkBudget.StageDeadlines(second, second, second, second, second, second);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReviewWorkBudgetProperties.class)
    static class TestConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
