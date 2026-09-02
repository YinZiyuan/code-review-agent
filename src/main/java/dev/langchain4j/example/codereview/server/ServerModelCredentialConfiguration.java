package dev.langchain4j.example.codereview.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "code-review.runtime", havingValue = "server")
public class ServerModelCredentialConfiguration {

    @Bean
    Validation serverModelCredentialValidation(Environment environment) {
        String apiKey = environment.getProperty("langchain4j.open-ai.chat-model.api-key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("model API key must be configured in server mode");
        }
        return Validation.PASSED;
    }

    enum Validation {
        PASSED
    }
}
