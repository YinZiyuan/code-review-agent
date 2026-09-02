package dev.langchain4j.example.codereview.server;

import java.util.Objects;

/** Fixed, non-user-controlled operation vocabulary shared by lifecycle and pipeline call sites. */
public record ReviewOperationSignal(
        ReviewOperationLogger.Event event,
        ReviewOperationLogger.Action action,
        ReviewOperationLogger.Outcome outcome,
        ReviewOperationLogger.SafeCode safeCode) {

    public ReviewOperationSignal {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(safeCode, "safeCode");
    }
}
