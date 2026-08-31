package dev.langchain4j.example.codereview.reviewops.application;

import org.springframework.jdbc.core.JdbcTemplate;

public final class SpringDependencyFixture {
    private JdbcTemplate forbiddenSpringDependency;
}
