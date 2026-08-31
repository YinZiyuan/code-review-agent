package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class CitationKeywordInjector {

    public List<ReviewFinding> inject(List<ReviewFinding> findings, List<Citation> candidates) {
        if (findings == null || findings.isEmpty() || candidates == null || candidates.isEmpty()) {
            return findings == null ? List.of() : findings;
        }

        List<ReviewFinding> out = new ArrayList<>(findings.size());
        for (ReviewFinding finding : findings) {
            if (finding.citations() != null && !finding.citations().isEmpty()) {
                out.add(finding);
                continue;
            }

            String haystack = (safe(finding.title()) + " " + safe(finding.description()))
                    .toLowerCase(Locale.ROOT);
            List<Citation> matched = new ArrayList<>();
            for (Citation candidate : candidates) {
                String citationText = safe(candidate.id()) + " "
                        + safe(candidate.source()) + " "
                        + safe(candidate.section());
                if (matches(haystack, citationText)) {
                    matched.add(candidate);
                }
            }
            out.add(new ReviewFinding(finding.id(), finding.file(), finding.line(), finding.lineRange(),
                    finding.severity(), finding.category(), finding.title(), finding.description(),
                    finding.suggestion(), finding.evidence(), matched, finding.source()));
        }
        return out;
    }

    private boolean matches(String haystack, String section) {
        if (section == null || section.isBlank()) {
            return false;
        }
        String normalizedSection = section.toLowerCase(Locale.ROOT);
        for (String word : normalizedSection.split("[^a-z0-9]+")) {
            if (word.length() >= 3 && haystack.contains(word)) {
                return true;
            }
        }
        return Arrays.stream(haystack.split("[^a-z0-9]+"))
                .anyMatch(word -> word.length() >= 3 && normalizedSection.contains(word));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
