package dev.langchain4j.example.codereview.analyzer;

import java.util.List;

/** ran=true means SpotBugs compiled and executed, even if no violations matched. */
public record SpotBugsResult(boolean ran, List<Violation> violations) {
    public static SpotBugsResult skipped() {
        return new SpotBugsResult(false, List.of());
    }
}
