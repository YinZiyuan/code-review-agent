package dev.langchain4j.example.codereview.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationModeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ServerPropertiesConfiguration.class);

    @Test
    void serveSelectsServletModeAndRemovesTheModeToken() {
        ApplicationMode.Selection selection = ApplicationMode.select(new String[]{"serve", "--server.port=0"});

        assertThat(selection.serverMode()).isTrue();
        assertThat(selection.applicationArgs()).containsExactly("--server.port=0");
    }

    @Test
    void cliRemainsTheDefault() {
        ApplicationMode.Selection selection = ApplicationMode.select(new String[]{"review", "--help"});

        assertThat(selection.serverMode()).isFalse();
        assertThat(selection.applicationArgs()).containsExactly("review", "--help");
    }

    @Test
    void bindsPositiveWebhookPayloadLimitForWebhookVerification() {
        contextRunner.withPropertyValues("code-review.server.github.max-webhook-bytes=1048576")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ServerProperties.class).github().maxWebhookBytes()).isEqualTo(1_048_576);
                });
    }

    @Test
    void rejectsNonPositiveWebhookPayloadLimitBeforeWebhookHandling() {
        contextRunner.withPropertyValues("code-review.server.github.max-webhook-bytes=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsPositiveGitHubHttpDeadlines() {
        contextRunner.withPropertyValues(
                        "code-review.server.github.connect-timeout=750ms",
                        "code-review.server.github.read-timeout=3s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ServerProperties.GitHub github =
                            context.getBean(ServerProperties.class).github();
                    assertThat(github.connectTimeout()).isEqualTo(Duration.ofMillis(750));
                    assertThat(github.readTimeout()).isEqualTo(Duration.ofSeconds(3));
                });
    }

    @Test
    void rejectsUnboundedGitHubHttpDeadlines() {
        contextRunner.withPropertyValues("code-review.server.github.connect-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("code-review.server.github.read-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsBoundedWorkerLifecycleConfiguration() {
        contextRunner.withPropertyValues(
                        "code-review.server.worker.poll-interval=2s",
                        "code-review.server.worker.batch-size=7",
                        "code-review.server.worker.recovery-batch-size=5",
                        "code-review.server.worker.lease-duration=4m",
                        "code-review.server.worker.heartbeat-interval=30s",
                        "code-review.server.worker.initial-backoff=11s",
                        "code-review.server.worker.max-backoff=3m",
                        "code-review.server.worker.jitter-ratio=0.15")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ServerProperties.Worker worker = context.getBean(ServerProperties.class).worker();
                    assertThat(worker.pollInterval()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(worker.batchSize()).isEqualTo(7);
                    assertThat(worker.recoveryBatchSize()).isEqualTo(5);
                    assertThat(worker.leaseDuration()).isEqualTo(Duration.ofMinutes(4));
                    assertThat(worker.heartbeatInterval()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(worker.initialBackoff()).isEqualTo(Duration.ofSeconds(11));
                    assertThat(worker.maxBackoff()).isEqualTo(Duration.ofMinutes(3));
                    assertThat(worker.jitterRatio()).isEqualTo(0.15);
                });
    }

    @Test
    void rejectsWorkerSettingsThatWouldBusySpinOrLoseHeartbeats() {
        contextRunner.withPropertyValues("code-review.server.worker.poll-interval=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "code-review.server.worker.lease-duration=30s",
                        "code-review.server.worker.heartbeat-interval=30s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "code-review.server.worker.initial-backoff=2m",
                        "code-review.server.worker.max-backoff=1m")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("code-review.server.worker.recovery-batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsSafeBoundedRetentionMaintenanceDefaultsAndOverrides() {
        contextRunner.run(context -> {
            ReviewOperationsMaintenanceProperties maintenance =
                    context.getBean(ReviewOperationsMaintenanceProperties.class);
            assertThat(maintenance.retentionAge()).isEqualTo(Duration.ofDays(30));
            assertThat(maintenance.batchSize()).isEqualTo(500);
            assertThat(maintenance.interval()).isEqualTo(Duration.ofHours(1));
        });
        contextRunner.withPropertyValues(
                        "code-review.server.maintenance.retention-age=14d",
                        "code-review.server.maintenance.batch-size=25",
                        "code-review.server.maintenance.interval=15m")
                .run(context -> {
                    ReviewOperationsMaintenanceProperties maintenance =
                            context.getBean(ReviewOperationsMaintenanceProperties.class);
                    assertThat(maintenance.retentionAge()).isEqualTo(Duration.ofDays(14));
                    assertThat(maintenance.batchSize()).isEqualTo(25);
                    assertThat(maintenance.interval()).isEqualTo(Duration.ofMinutes(15));
                });
    }

    @Test
    void rejectsRetentionSettingsThatWouldDisableBoundsOrBusySpin() {
        contextRunner.withPropertyValues("code-review.server.maintenance.retention-age=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("code-review.server.maintenance.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("code-review.server.maintenance.interval=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsBoundedObservabilityRefreshAndStalenessSettings() {
        contextRunner.withPropertyValues(
                        "code-review.server.observability.refresh-interval=20s",
                        "code-review.server.observability.stale-threshold=12m",
                        "code-review.server.observability.failure-backoff-max=2m")
                .run(context -> {
                    ReviewObservabilityProperties observability =
                            context.getBean(ReviewObservabilityProperties.class);
                    assertThat(observability.refreshInterval()).isEqualTo(Duration.ofSeconds(20));
                    assertThat(observability.staleThreshold()).isEqualTo(Duration.ofMinutes(12));
                    assertThat(observability.failureBackoffMax()).isEqualTo(Duration.ofMinutes(2));
                });
    }

    @Test
    void rejectsObservabilitySettingsThatWouldBusySpinOrMisclassifyEveryRun() {
        contextRunner.withPropertyValues("code-review.server.observability.refresh-interval=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("code-review.server.observability.stale-threshold=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("code-review.server.observability.failure-backoff-max=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "code-review.server.observability.refresh-interval=30s",
                        "code-review.server.observability.failure-backoff-max=29s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsExplicitRuntimeIdentityIncludingWorkBudgetSeam() {
        contextRunner.withPropertyValues(
                        "code-review.server.identity.pipeline-version=pipeline-v4",
                        "code-review.server.identity.prompt-version=review-prompt-v2",
                        "code-review.server.identity.policy-version=policy-v3",
                        "code-review.server.identity.work-budget-identity=budget-sha256-abc",
                        "code-review.server.identity.max-review-attempts=4",
                        "code-review.server.identity.max-inline-comments=7")
                .run(context -> {
                    ReviewIdentityProperties identity = context.getBean(ReviewIdentityProperties.class);
                    assertThat(identity.pipelineVersion()).isEqualTo("pipeline-v4");
                    assertThat(identity.promptVersion()).isEqualTo("review-prompt-v2");
                    assertThat(identity.policyVersion()).isEqualTo("policy-v3");
                    assertThat(identity.workBudgetIdentity()).isEqualTo("budget-sha256-abc");
                    assertThat(identity.maxReviewAttempts()).isEqualTo(4);
                    assertThat(identity.maxInlineComments()).isEqualTo(7);
                });
    }

    @Test
    void bindsFiniteDatabaseBoundsFromTypedServerConfiguration() {
        contextRunner.withPropertyValues(
                        "code-review.server.database.maximum-pool-size=12",
                        "code-review.server.database.minimum-idle=2",
                        "code-review.server.database.acquisition-timeout=3s",
                        "code-review.server.database.validation-timeout=750ms",
                        "code-review.server.database.connect-timeout-seconds=4",
                        "code-review.server.database.socket-timeout-seconds=20",
                        "code-review.server.database.cancel-timeout-seconds=3",
                        "code-review.server.database.statement-timeout=12s",
                        "code-review.server.database.lock-timeout=3s",
                        "code-review.server.database.idle-transaction-timeout=20s",
                        "code-review.server.database.transaction-timeout=11s")
                .run(context -> {
                    DatabaseBoundsProperties database =
                            context.getBean(DatabaseBoundsProperties.class);
                    assertThat(database.maximumPoolSize()).isEqualTo(12);
                    assertThat(database.minimumIdle()).isEqualTo(2);
                    assertThat(database.acquisitionTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(database.validationTimeout()).isEqualTo(Duration.ofMillis(750));
                    assertThat(database.connectTimeoutSeconds()).isEqualTo(4);
                    assertThat(database.socketTimeoutSeconds()).isEqualTo(20);
                    assertThat(database.cancelTimeoutSeconds()).isEqualTo(3);
                    assertThat(database.statementTimeout()).isEqualTo(Duration.ofSeconds(12));
                    assertThat(database.lockTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(database.idleTransactionTimeout()).isEqualTo(Duration.ofSeconds(20));
                    assertThat(database.transactionTimeout()).isEqualTo(Duration.ofSeconds(11));
                });
    }

    @Test
    void rejectsDisabledNegativeOrExcessiveDatabaseBounds() {
        assertDatabaseConfigurationFails("maximum-pool-size=0");
        assertDatabaseConfigurationFails("minimum-idle=-1");
        assertDatabaseConfigurationFails("acquisition-timeout=0s");
        assertDatabaseConfigurationFails("validation-timeout=-1ms");
        assertDatabaseConfigurationFails("connect-timeout-seconds=0");
        assertDatabaseConfigurationFails("socket-timeout-seconds=0");
        assertDatabaseConfigurationFails("cancel-timeout-seconds=0");
        assertDatabaseConfigurationFails("statement-timeout=0s");
        assertDatabaseConfigurationFails("lock-timeout=0s");
        assertDatabaseConfigurationFails("idle-transaction-timeout=0s");
        assertDatabaseConfigurationFails("transaction-timeout=0s");
        assertDatabaseConfigurationFails("maximum-pool-size=65");
        assertDatabaseConfigurationFails("socket-timeout-seconds=301");
        assertDatabaseConfigurationFails("statement-timeout=11m");
    }

    private void assertDatabaseConfigurationFails(String property) {
        contextRunner.withPropertyValues("code-review.server.database." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            ServerProperties.class,
            ReviewOperationsMaintenanceProperties.class,
            ReviewObservabilityProperties.class,
            ReviewIdentityProperties.class,
            DatabaseBoundsProperties.class
    })
    static class ServerPropertiesConfiguration {
    }
}
