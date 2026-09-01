package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "code-review.runtime", havingValue = "server")
@ConditionalOnBean(ReviewJobWorker.class)
public class ReviewJobSchedulingConfiguration {

    @Bean
    ScheduledReviewJobPoller scheduledReviewJobPoller(ReviewJobWorker worker) {
        return new ScheduledReviewJobPoller(worker);
    }
}
