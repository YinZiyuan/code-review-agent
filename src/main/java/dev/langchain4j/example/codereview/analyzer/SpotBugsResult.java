package dev.langchain4j.example.codereview.analyzer;

import java.util.List;
import java.util.Objects;

/** ran=true means SpotBugs compiled and executed, even if no violations matched. */
public record SpotBugsResult(boolean ran, List<Violation> violations, String safeReason) {
    public SpotBugsResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
        safeReason = Objects.requireNonNull(safeReason, "safeReason");
    }
    public SpotBugsResult(boolean ran, List<Violation> violations) {
        this(ran, violations, ran ? "completed" : "analyzer skipped");
    }

    public static SpotBugsResult skipped(String safeReason) {
        return new SpotBugsResult(false, List.of(), safeReason);
    }
}
