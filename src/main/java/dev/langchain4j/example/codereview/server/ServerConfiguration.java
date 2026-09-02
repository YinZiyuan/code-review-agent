package dev.langchain4j.example.codereview.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.reviewops.application.DecideReviewPublication;
import dev.langchain4j.example.codereview.reviewops.application.ExecuteReviewRun;
import dev.langchain4j.example.codereview.reviewops.application.ObservePullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.application.ObsoleteReviewRunStore;
import dev.langchain4j.example.codereview.reviewops.application.PublishReviewOutcome;
import dev.langchain4j.example.codereview.reviewops.application.PresentReviewFailure;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore;
import dev.langchain4j.example.codereview.reviewops.application.RecoverExpiredReviewExecution;
import dev.langchain4j.example.codereview.reviewops.application.ReviewFindingMapper;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunAdmissionStore;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunMutationStore;
import dev.langchain4j.example.codereview.reviewops.application.SupersedeObsoleteReviewRuns;
import dev.langchain4j.example.codereview.reviewops.application.SettleReviewJobFailure;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.ReviewSourceProvider;
import dev.langchain4j.example.codereview.reviewops.application.jobs.BackoffPolicy;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewDecisionJobHandler;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewExecutionJobHandler;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewFailurePresentationJobHandler;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobDispatcher;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobHandler;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobWorker;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewPublicationJobHandler;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ScheduledLeaseHeartbeat;
import dev.langchain4j.example.codereview.reviewops.application.jobs.SupersedeObsoleteReviewRunsJobHandler;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxStore;
import dev.langchain4j.example.codereview.reviewops.domain.FindingPublicationPolicy;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationPolicySnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.CheckRunFormatter;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.GitHubAppJwtFactory;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.GitHubArchiveSourceProvider;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.GitHubPublicationClient;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.GitHubRestClient;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.GitHubWebhookVerifier;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.InlineCommentFormatter;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser;
import dev.langchain4j.example.codereview.reviewops.infrastructure.jobs.PostgresDurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.JdbcOutboxStore;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.JdbcPullRequestObservationStore;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.JdbcReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.JsonColumnCodec;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.PostgresObsoleteReviewRunStore;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.TransactionalReviewRunAdmissionStore;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.TransactionalReviewRunMutationStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.AdditionalHealthEndpointPath;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "code-review.runtime", havingValue = "server")
public class ServerConfiguration {

    @Bean
    DataSource dataSource(DataSourceProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        if (properties.getDriverClassName() != null) {
            dataSource.setDriverClassName(properties.getDriverClassName());
        }
        return dataSource;
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    TransactionOperations transactionOperations(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ReviewConfigurationSnapshot reviewConfigurationSnapshot() {
        return new ReviewConfigurationSnapshot(
                "pipeline-v3", "configuration-v1", "moonshot-v1-8k", "policy-v1", 3);
    }

    @Bean
    PublicationPolicySnapshot publicationPolicySnapshot() {
        return new PublicationPolicySnapshot("policy-v1", 5);
    }

    @Bean
    FindingPublicationPolicy findingPublicationPolicy() {
        return new FindingPublicationPolicy();
    }

    @Bean
    ReviewFindingMapper reviewFindingMapper() {
        return new ReviewFindingMapper();
    }

    @Bean
    GitHubWebhookVerifier gitHubWebhookVerifier(ServerProperties serverProperties) {
        return new GitHubWebhookVerifier(serverProperties.github().webhookSecret()
                .getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    PullRequestWebhookParser pullRequestWebhookParser(
            ObjectMapper objectMapper,
            ServerProperties serverProperties,
            Clock clock) {
        return new PullRequestWebhookParser(
                objectMapper, serverProperties.github().maxWebhookBytes(), clock);
    }

    @Bean
    JsonColumnCodec jsonColumnCodec(ObjectMapper objectMapper) {
        return new JsonColumnCodec(objectMapper);
    }

    @Bean
    ReviewRunRepository reviewRunRepository(
            JdbcTemplate jdbcTemplate,
            TransactionOperations transactions,
            JsonColumnCodec jsonColumnCodec) {
        return new JdbcReviewRunRepository(jdbcTemplate, transactions, jsonColumnCodec);
    }

    @Bean
    DurableJobQueue durableJobQueue(
            JdbcTemplate jdbcTemplate,
            TransactionOperations transactions,
            Clock clock,
            ReviewRunRepository reviewRuns,
            SettleReviewJobFailure finalFailureSettlement) {
        return new PostgresDurableJobQueue(
                jdbcTemplate,
                transactions,
                clock,
                new RecoverExpiredReviewExecution(reviewRuns),
                finalFailureSettlement);
    }

    @Bean
    SettleReviewJobFailure settleReviewJobFailure(ReviewRunRepository reviewRuns) {
        return new SettleReviewJobFailure(reviewRuns);
    }

    @Bean
    OutboxStore outboxStore(JdbcTemplate jdbcTemplate) {
        return new JdbcOutboxStore(jdbcTemplate);
    }

    @Bean
    ReviewRunAdmissionStore reviewRunAdmissionStore(
            ReviewRunRepository reviewRuns,
            DurableJobQueue jobs,
            OutboxStore outbox,
            TransactionOperations transactions) {
        return new TransactionalReviewRunAdmissionStore(reviewRuns, jobs, outbox, transactions);
    }

    @Bean
    ReviewRunMutationStore reviewRunMutationStore(
            ReviewRunRepository reviewRuns,
            DurableJobQueue jobs,
            OutboxStore outbox,
            TransactionOperations transactions) {
        return new TransactionalReviewRunMutationStore(reviewRuns, jobs, outbox, transactions);
    }

    @Bean
    PullRequestObservationStore pullRequestObservationStore(
            JdbcTemplate jdbcTemplate,
            ReviewRunAdmissionStore admissions,
            DurableJobQueue jobs,
            TransactionOperations transactions) {
        return new JdbcPullRequestObservationStore(jdbcTemplate, admissions, jobs, transactions);
    }

    @Bean
    ObsoleteReviewRunStore obsoleteReviewRunStore(
            JdbcTemplate jdbcTemplate,
            ReviewRunRepository reviewRuns,
            TransactionOperations transactions) {
        return new PostgresObsoleteReviewRunStore(jdbcTemplate, reviewRuns, transactions);
    }

    @Bean
    ObservePullRequestRevision observePullRequestRevision(
            PullRequestObservationStore observations,
            Clock clock,
            ReviewConfigurationSnapshot configuration) {
        return new ObservePullRequestRevision(observations, clock, configuration);
    }

    @Bean
    SupersedeObsoleteReviewRuns supersedeObsoleteReviewRuns(
            ReviewRunRepository reviewRuns,
            ObsoleteReviewRunStore obsoleteRuns,
            Clock clock) {
        return new SupersedeObsoleteReviewRuns(reviewRuns, obsoleteRuns, clock);
    }

    @Bean
    RestClient gitHubHttpClient(
            @Value("${code-review.server.github.api-base-url:https://api.github.com}")
            String apiBaseUrl,
            ServerProperties serverProperties) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalArgumentException("GitHub API base URL must not be blank");
        }
        ServerProperties.GitHub github = serverProperties.github();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(github.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(github.readTimeout());
        return RestClient.builder()
                .baseUrl(apiBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    GitHubAppJwtFactory gitHubAppJwtFactory(
            ServerProperties serverProperties,
            ObjectMapper objectMapper,
            Clock clock) {
        ServerProperties.GitHub github = serverProperties.github();
        return new GitHubAppJwtFactory(github.appId(), github.privateKey(), objectMapper, clock);
    }

    @Bean
    GitHubRestClient gitHubRestClient(
            RestClient gitHubHttpClient,
            GitHubAppJwtFactory jwtFactory,
            ObjectMapper objectMapper,
            Clock clock) {
        return new GitHubRestClient(
                gitHubHttpClient, jwtFactory, objectMapper, clock, Duration.ofSeconds(30), 64);
    }

    @Bean
    ReviewSourceProvider reviewSourceProvider(GitHubRestClient gitHubRestClient) {
        return new GitHubArchiveSourceProvider(
                gitHubRestClient,
                Path.of(System.getProperty("java.io.tmpdir")),
                5L * 1024 * 1024,
                100L * 1024 * 1024,
                500L * 1024 * 1024,
                50_000);
    }

    @Bean
    GitHubPublicationGateway gitHubPublicationGateway(
            RestClient gitHubHttpClient,
            GitHubRestClient installations,
            ObjectMapper objectMapper,
            Clock clock,
            ServerProperties serverProperties) {
        return new GitHubPublicationClient(
                gitHubHttpClient,
                installations,
                objectMapper,
                clock,
                serverProperties.github().appId(),
                "Code Review Agent",
                new CheckRunFormatter(),
                new InlineCommentFormatter());
    }

    @Bean
    ExecuteReviewRun executeReviewRun(
            ReviewRunRepository reviewRuns,
            ReviewRunMutationStore mutations,
            ReviewSourceProvider sources,
            CodeReviewAgent reviewer,
            ReviewFindingMapper findingMapper,
            DiffParser diffParser,
            Clock clock) {
        return new ExecuteReviewRun(
                reviewRuns, mutations, sources, reviewer, findingMapper, diffParser, clock);
    }

    @Bean
    DecideReviewPublication decideReviewPublication(
            ReviewRunRepository reviewRuns,
            ReviewRunMutationStore mutations,
            FindingPublicationPolicy policy,
            PublicationPolicySnapshot policySnapshot) {
        return new DecideReviewPublication(reviewRuns, mutations, policy, policySnapshot);
    }

    @Bean
    PublishReviewOutcome publishReviewOutcome(
            ReviewRunRepository reviewRuns,
            ReviewRunMutationStore mutations,
            GitHubPublicationGateway github,
            Clock clock) {
        return new PublishReviewOutcome(reviewRuns, mutations, github, clock);
    }

    @Bean
    PresentReviewFailure presentReviewFailure(
            ReviewRunRepository reviewRuns,
            ReviewRunMutationStore mutations,
            GitHubPublicationGateway github,
            Clock clock) {
        return new PresentReviewFailure(reviewRuns, mutations, github, clock);
    }

    @Bean
    ReviewExecutionJobHandler reviewExecutionJobHandler(ExecuteReviewRun executeReviewRun) {
        return new ReviewExecutionJobHandler(executeReviewRun);
    }

    @Bean
    ReviewFailurePresentationJobHandler reviewFailurePresentationJobHandler(
            PresentReviewFailure presentReviewFailure) {
        return new ReviewFailurePresentationJobHandler(presentReviewFailure);
    }

    @Bean
    SupersedeObsoleteReviewRunsJobHandler supersedeObsoleteReviewRunsJobHandler(
            SupersedeObsoleteReviewRuns supersedeObsoleteReviewRuns) {
        return new SupersedeObsoleteReviewRunsJobHandler(supersedeObsoleteReviewRuns);
    }

    @Bean
    ReviewDecisionJobHandler reviewDecisionJobHandler(
            DecideReviewPublication decideReviewPublication) {
        return new ReviewDecisionJobHandler(decideReviewPublication);
    }

    @Bean
    ReviewPublicationJobHandler reviewPublicationJobHandler(
            PublishReviewOutcome publishReviewOutcome) {
        return new ReviewPublicationJobHandler(publishReviewOutcome);
    }

    @Bean
    ReviewJobDispatcher reviewJobDispatcher(List<ReviewJobHandler> handlers) {
        return new ReviewJobDispatcher(handlers);
    }

    @Bean
    BackoffPolicy reviewJobBackoff(ServerProperties serverProperties) {
        ServerProperties.Worker worker = serverProperties.worker();
        return BackoffPolicy.exponential(
                worker.initialBackoff(),
                worker.maxBackoff(),
                worker.jitterRatio(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean(destroyMethod = "close")
    ScheduledLeaseHeartbeat reviewJobLeaseHeartbeat(
            DurableJobQueue jobs,
            Clock clock,
            ServerProperties serverProperties) {
        ServerProperties.Worker worker = serverProperties.worker();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "review-job-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        return new ScheduledLeaseHeartbeat(
                jobs,
                clock,
                worker.leaseDuration(),
                worker.heartbeatInterval(),
                scheduler);
    }

    @Bean
    ReviewJobWorker reviewJobWorker(
            DurableJobQueue jobs,
            ReviewJobDispatcher dispatcher,
            BackoffPolicy backoff,
            ScheduledLeaseHeartbeat heartbeat,
            Clock clock,
            MeterRegistry metrics,
            ServerProperties serverProperties) {
        ServerProperties.Worker worker = serverProperties.worker();
        return new ReviewJobWorker(
                jobs,
                dispatcher,
                backoff,
                heartbeat,
                clock,
                metrics,
                new ReviewJobWorker.WorkerSettings(
                        "review-worker-" + UUID.randomUUID(),
                        worker.leaseDuration(),
                        worker.heartbeatInterval(),
                        worker.batchSize(),
                        worker.recoveryBatchSize()));
    }

    @Bean
    HealthIndicator serverLivenessHealthIndicator() {
        return () -> org.springframework.boot.actuate.health.Health.up().build();
    }

    @Bean
    HealthIndicator reviewIntakeHealthIndicator(
            GitHubWebhookController controller,
            ObservePullRequestRevision observations) {
        return () -> org.springframework.boot.actuate.health.Health.up()
                .withDetail("intake", "active")
                .build();
    }

    @Bean
    HealthEndpointGroups serverHealthEndpointGroups() {
        HealthEndpointGroup liveness = healthGroup(Set.of("serverLiveness"));
        HealthEndpointGroup readiness = healthGroup(Set.of("db", "reviewIntake"));
        return HealthEndpointGroups.of(
                liveness,
                Map.of("liveness", liveness, "readiness", readiness));
    }

    @Bean
    FilterRegistrationBean<BoundedWebhookPayloadFilter> boundedWebhookPayloadFilter(
            ServerProperties serverProperties) {
        FilterRegistrationBean<BoundedWebhookPayloadFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new BoundedWebhookPayloadFilter(
                serverProperties.github().maxWebhookBytes()));
        registration.addUrlPatterns("/webhooks/github");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }

    private static HealthEndpointGroup healthGroup(Set<String> members) {
        Set<String> immutableMembers = Set.copyOf(members);
        return new HealthEndpointGroup() {
            @Override
            public boolean isMember(String name) {
                return immutableMembers.contains(name);
            }

            @Override
            public boolean showComponents(SecurityContext securityContext) {
                return false;
            }

            @Override
            public boolean showDetails(SecurityContext securityContext) {
                return false;
            }

            @Override
            public StatusAggregator getStatusAggregator() {
                return StatusAggregator.getDefault();
            }

            @Override
            public HttpCodeStatusMapper getHttpCodeStatusMapper() {
                return HttpCodeStatusMapper.DEFAULT;
            }

            @Override
            public AdditionalHealthEndpointPath getAdditionalPath() {
                return null;
            }
        };
    }
}
