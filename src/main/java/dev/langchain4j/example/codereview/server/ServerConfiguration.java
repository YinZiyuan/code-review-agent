package dev.langchain4j.example.codereview.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.ObservePullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunAdmissionStore;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxStore;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.GitHubWebhookVerifier;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser;
import dev.langchain4j.example.codereview.reviewops.infrastructure.jobs.PostgresDurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.JdbcOutboxStore;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.JdbcPullRequestObservationStore;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.JdbcReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.JsonColumnCodec;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.TransactionalReviewRunAdmissionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "code-review.runtime", havingValue = "server")
@Conditional(WebhookSecretConfiguredCondition.class)
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
        return new ReviewConfigurationSnapshot("pipeline-v3", "configuration-v1", "moonshot-v1-8k", "policy-v1", 3);
    }

    @Bean
    GitHubWebhookVerifier gitHubWebhookVerifier(ServerProperties serverProperties) {
        return new GitHubWebhookVerifier(serverProperties.github().webhookSecret()
                .getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    PullRequestWebhookParser pullRequestWebhookParser(
            ObjectMapper objectMapper,
            ServerProperties serverProperties) {
        return new PullRequestWebhookParser(objectMapper, serverProperties.github());
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
    DurableJobQueue durableJobQueue(JdbcTemplate jdbcTemplate, TransactionOperations transactions, Clock clock) {
        return new PostgresDurableJobQueue(jdbcTemplate, transactions, clock);
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
    PullRequestObservationStore pullRequestObservationStore(
            JdbcTemplate jdbcTemplate,
            ReviewRunAdmissionStore admissions,
            DurableJobQueue jobs,
            TransactionOperations transactions) {
        return new JdbcPullRequestObservationStore(jdbcTemplate, admissions, jobs, transactions);
    }

    @Bean
    ObservePullRequestRevision observePullRequestRevision(
            PullRequestObservationStore observations,
            Clock clock,
            ReviewConfigurationSnapshot configuration) {
        return new ObservePullRequestRevision(observations, clock, configuration);
    }

    @Bean
    FilterRegistrationBean<BoundedWebhookPayloadFilter> boundedWebhookPayloadFilter(
            ServerProperties serverProperties) {
        FilterRegistrationBean<BoundedWebhookPayloadFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new BoundedWebhookPayloadFilter(serverProperties.github().maxWebhookBytes()));
        registration.addUrlPatterns("/webhooks/github");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }
}

final class WebhookSecretConfiguredCondition implements Condition {

    private static final String WEBHOOK_SECRET_PROPERTY = "code-review.server.github.webhook-secret";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String webhookSecret = context.getEnvironment().getProperty(WEBHOOK_SECRET_PROPERTY);
        return webhookSecret != null && !webhookSecret.isBlank();
    }
}
