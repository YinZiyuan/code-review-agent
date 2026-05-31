package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.rag.CitationKeywordInjector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class Summarizer {

    private final CitationKeywordInjector citationInjector;

    public Summarizer(CitationKeywordInjector citationInjector) {
        this.citationInjector = citationInjector;
    }

    public ReviewResult summarize(ReviewResult draft, ToolFindings tools, List<Citation> citationCandidates) {
        List<ReviewFinding> base = draft.findings() == null
                ? new ArrayList<>()
                : new ArrayList<>(draft.findings());

        for (Violation violation : tools.violations()) {
            if (violation.severity() == Severity.SUGGESTION || covered(base, violation)) {
                continue;
            }
            base.add(toFinding(violation, base.size() + 1));
        }

        List<ReviewFinding> deduped = dedup(base);
        List<ReviewFinding> withCitations = citationInjector.inject(deduped, citationCandidates);
        return new ReviewResult(
                draft.summary() == null ? "" : draft.summary(),
                sort(withCitations),
                tools.statuses());
    }

    private boolean covered(List<ReviewFinding> findings, Violation violation) {
        for (ReviewFinding finding : findings) {
            if (sameFile(finding.file(), violation.file())
                    && finding.line() != null
                    && Math.abs(finding.line() - violation.line()) <= 2) {
                return true;
            }
        }
        return false;
    }

    private boolean sameFile(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b) || a.endsWith("/" + b) || b.endsWith("/" + a);
    }

    private ReviewFinding toFinding(Violation violation, int index) {
        return new ReviewFinding(
                String.format("F-%03d", index),
                violation.file(),
                violation.line(),
                new int[]{violation.line(), violation.line()},
                violation.severity(),
                Category.OTHER,
                violation.rule(),
                violation.message(),
                "Address the static-analyzer finding.",
                violation.message(),
                List.of(),
                toolSource(violation));
    }

    private String toolSource(Violation violation) {
        return violation.rule() != null && violation.rule().startsWith("spotbugs")
                ? "spotbugs"
                : "regex";
    }

    private List<ReviewFinding> dedup(List<ReviewFinding> findings) {
        Map<String, ReviewFinding> byKey = new LinkedHashMap<>();
        for (ReviewFinding finding : findings) {
            String key = bucketKey(finding);
            ReviewFinding existing = byKey.get(key);
            if (existing == null || sevRank(finding.severity()) < sevRank(existing.severity())) {
                byKey.put(key, finding);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private String bucketKey(ReviewFinding finding) {
        String file = finding.file() == null ? "" : finding.file();
        int lineBucket = finding.line() == null ? -1 : finding.line() / 5;
        String title = finding.title() == null ? "" : finding.title().toLowerCase(Locale.ROOT);
        String titleHead = title.length() > 30 ? title.substring(0, 30) : title;
        return file + "|" + lineBucket + "|" + titleHead;
    }

    private List<ReviewFinding> sort(List<ReviewFinding> findings) {
        List<ReviewFinding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator
                .comparingInt((ReviewFinding f) -> sevRank(f.severity()))
                .thenComparing(f -> f.file() == null ? "" : f.file())
                .thenComparingInt(f -> f.line() == null ? Integer.MAX_VALUE : f.line()));
        return sorted;
    }

    private static int sevRank(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case SUGGESTION -> 2;
        };
    }
}
