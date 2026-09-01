package dev.langchain4j.example.codereview.reviewops.application.jobs;

import java.util.Objects;

public final class DurableJobIntentConflictException extends RuntimeException {

    private final String idempotencyKey;

    public DurableJobIntentConflictException(String idempotencyKey) {
        super("Durable job idempotency key has a different immutable intent: " + idempotencyKey);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
