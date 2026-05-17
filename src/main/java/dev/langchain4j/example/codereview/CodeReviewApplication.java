package dev.langchain4j.example.codereview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodeReviewApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(CodeReviewApplication.class, args)));
    }
}
