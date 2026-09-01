package dev.langchain4j.example.codereview.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.GitHubWebhookVerifier;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "code-review.runtime", havingValue = "server")
@Conditional(WebhookSecretConfiguredCondition.class)
public class ServerConfiguration {

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
}

final class WebhookSecretConfiguredCondition implements Condition {

    private static final String WEBHOOK_SECRET_PROPERTY = "code-review.server.github.webhook-secret";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String webhookSecret = context.getEnvironment().getProperty(WEBHOOK_SECRET_PROPERTY);
        return webhookSecret != null && !webhookSecret.isBlank();
    }
}
