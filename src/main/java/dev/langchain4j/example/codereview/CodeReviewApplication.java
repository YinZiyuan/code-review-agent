package dev.langchain4j.example.codereview;

import dev.langchain4j.example.codereview.server.ApplicationMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodeReviewApplication {

    public static void main(String[] args) {
        ApplicationMode.Selection selection = ApplicationMode.select(args);
        SpringApplication application = new SpringApplication(CodeReviewApplication.class);
        application.setWebApplicationType(selection.serverMode() ? WebApplicationType.SERVLET : WebApplicationType.NONE);
        application.setDefaultProperties(selection.serverMode()
                ? Map.of("code-review.runtime", "server")
                : Map.of(
                        "code-review.runtime", "cli",
                        "spring.autoconfigure.exclude", String.join(",",
                                DataSourceAutoConfiguration.class.getName(),
                                FlywayAutoConfiguration.class.getName())));
        if (selection.serverMode()) {
            application.setAdditionalProfiles("server");
        }
        ConfigurableApplicationContext context = application.run(selection.applicationArgs());
        if (!selection.serverMode()) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
