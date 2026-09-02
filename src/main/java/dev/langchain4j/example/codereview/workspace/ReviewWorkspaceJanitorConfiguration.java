package dev.langchain4j.example.codereview.workspace;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "code-review.runtime", havingValue = "server")
public class ReviewWorkspaceJanitorConfiguration {

    @Bean
    ReviewWorkspaceJanitor reviewWorkspaceJanitor(
            ReviewWorkspaceFactory factory,
            ReviewWorkBudget budget,
            Clock clock,
            MeterRegistry metrics) {
        return new ReviewWorkspaceJanitor(
                factory.temporaryParent(), budget.workspace(), clock, metrics);
    }

    @Bean
    ReviewWorkspaceJanitorScheduler reviewWorkspaceJanitorScheduler(
            ReviewWorkspaceJanitor janitor) {
        return new ReviewWorkspaceJanitorScheduler(janitor);
    }
}
