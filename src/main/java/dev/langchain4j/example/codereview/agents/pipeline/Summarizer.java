package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
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
import java.util.Map;

@Component
public class Summarizer {

    private final CitationKeywordInjector citationInjector;
    private final ReviewWorkBudget budget;

    public Summarizer(CitationKeywordInjector citationInjector, ReviewWorkBudget budget) {
        this.citationInjector = citationInjector;
        this.budget = budget;
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
        List<ReviewFinding> calibrated = deduped.stream()
                .map(this::calibrateSeverity)
                .toList();
        List<ReviewFinding> trustedCitations = validateCitations(calibrated, citationCandidates);
        List<ReviewFinding> withCitations = citationInjector.inject(trustedCitations, citationCandidates);
        List<ReviewFinding> sorted = sort(withCitations);
        int findingCount = Math.min(sorted.size(), budget.input().maxFindings());
        return new ReviewResult(
                draft.summary() == null ? "" : draft.summary(),
                sorted.subList(0, findingCount),
                tools.statuses());
    }

    private List<ReviewFinding> validateCitations(
            List<ReviewFinding> findings, List<Citation> citationCandidates) {
        Map<String, Citation> candidatesById = new LinkedHashMap<>();
        if (citationCandidates != null) {
            for (Citation candidate : citationCandidates) {
                if (candidate != null && candidate.id() != null && !candidate.id().isBlank()) {
                    candidatesById.putIfAbsent(candidate.id(), candidate);
                }
            }
        }
        return findings.stream()
                .map(finding -> withTrustedCitations(finding, candidatesById))
                .toList();
    }

    private ReviewFinding withTrustedCitations(
            ReviewFinding finding, Map<String, Citation> candidatesById) {
        Map<String, Citation> trustedById = new LinkedHashMap<>();
        if (finding.citations() != null) {
            for (Citation citation : finding.citations()) {
                if (citation != null && citation.id() != null) {
                    Citation candidate = candidatesById.get(citation.id());
                    if (candidate != null) {
                        trustedById.putIfAbsent(candidate.id(), candidate);
                    }
                }
            }
        }
        return new ReviewFinding(
                finding.id(), finding.file(), finding.line(), finding.lineRange(),
                finding.severity(), finding.category(), finding.title(), finding.description(),
                finding.suggestion(), finding.evidence(), List.copyOf(trustedById.values()),
                finding.source());
    }

    private ReviewFinding calibrateSeverity(ReviewFinding finding) {
        if (finding.category() == null || finding.severity() == null) {
            return finding;
        }
        Severity calibrated = switch (finding.category()) {
            case SECURITY -> Severity.CRITICAL;
            case PERFORMANCE, STABILITY, CONCURRENCY, TEST -> Severity.WARNING;
            case STYLE, OTHER -> finding.severity();
        };
        if (calibrated == finding.severity()) {
            return finding;
        }
        return new ReviewFinding(
                finding.id(), finding.file(), finding.line(), finding.lineRange(),
                calibrated, finding.category(), finding.title(), finding.description(),
                finding.suggestion(), finding.evidence(), finding.citations(), finding.source());
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
        return file + "|" + lineBucket + "|" + finding.category();
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
