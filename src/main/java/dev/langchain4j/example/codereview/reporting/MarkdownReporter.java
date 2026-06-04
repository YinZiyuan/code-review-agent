package dev.langchain4j.example.codereview.reporting;

import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.ToolStatus;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class MarkdownReporter {

    public String render(ReviewResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Code Review Report\n\n");
        sb.append("### Summary\n").append(result.summary() == null ? "" : result.summary()).append("\n\n");

        sb.append("### Issues Found\n");
        if (result.findings() == null || result.findings().isEmpty()) {
            sb.append("No issues found.\n\n");
        } else {
            List<ReviewFinding> sorted = result.findings().stream()
                    .sorted(Comparator.comparing(ReviewFinding::severity))
                    .toList();
            for (ReviewFinding f : sorted) {
                sb.append("- **[").append(f.severity()).append("]** `")
                        .append(f.file()).append(":").append(f.line() == null ? "?" : f.line())
                        .append("` - ").append(f.title()).append("\n");
                sb.append("  - ").append(f.description()).append("\n");
                if (f.suggestion() != null && !f.suggestion().isBlank()) {
                    sb.append("  - **Suggestion:** ").append(f.suggestion()).append("\n");
                }
                if (f.evidence() != null && !f.evidence().isBlank()) {
                    sb.append("  - **Evidence:** ").append(f.evidence()).append("\n");
                }
                if (f.citations() != null && !f.citations().isEmpty()) {
                    String cites = f.citations().stream()
                            .map(c -> "`" + c.source() + "` section " + c.section())
                            .collect(Collectors.joining(", "));
                    sb.append("  - **Citations:** ").append(cites).append("\n");
                }
            }
            sb.append("\n");
        }

        if (result.toolStatus() != null && !result.toolStatus().isEmpty()) {
            sb.append("### Tool Status\n");
            for (ToolStatus ts : result.toolStatus()) {
                sb.append("- `").append(ts.tool()).append("`: ").append(ts.state());
                if (ts.reason() != null) sb.append(" - ").append(ts.reason());
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
