package dev.langchain4j.example.codereview.reviewops.domain;

import java.sql.Connection;

public final class JdbcDependencyFixture {
    private Connection forbiddenJdbcDependency;
}
