package dev.langchain4j.example.codereview.reviewops.domain;

import java.time.Instant;

public interface DomainEvent {
    Instant occurredAt();
}
