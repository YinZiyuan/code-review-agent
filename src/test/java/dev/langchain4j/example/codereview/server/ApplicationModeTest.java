package dev.langchain4j.example.codereview.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServerProperties.class)
    static class ServerPropertiesConfiguration {
    }
}
