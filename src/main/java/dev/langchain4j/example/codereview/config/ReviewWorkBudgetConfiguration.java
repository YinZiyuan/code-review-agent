package dev.langchain4j.example.codereview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ReviewWorkBudgetConfiguration {

    @Bean
    ReviewWorkBudget reviewWorkBudget(ReviewWorkBudgetProperties properties) {
        return properties.toBudget();
    }
}
