package dev.langchain4j.example.codereview.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum Category {
    SECURITY, PERFORMANCE, STABILITY, CONCURRENCY, TEST, STYLE,
    @JsonEnumDefaultValue OTHER
}
