package dev.langchain4j.example.codereview.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ServerModelCredentialConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ServerModelCredentialConfiguration.class);

    @Test
    void serverModeRejectsAMissingOrBlankModelCredential() {
        contextRunner.withPropertyValues("code-review.runtime=server")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "code-review.runtime=server",
                        "langchain4j.open-ai.chat-model.api-key=   ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void serverModeAcceptsANonBlankModelCredentialWithoutRetainingItInTheMarker() {
        contextRunner.withPropertyValues(
                        "code-review.runtime=server",
                        "langchain4j.open-ai.chat-model.api-key=server-test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ServerModelCredentialConfiguration.Validation.class)
                                    .toString())
                            .doesNotContain("server-test-secret");
                });
    }

    @Test
    void cliModeDoesNotRequireAServerCredential() {
        contextRunner.withPropertyValues("code-review.runtime=cli")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
