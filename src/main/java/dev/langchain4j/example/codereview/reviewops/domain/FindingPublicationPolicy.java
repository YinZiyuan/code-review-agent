package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Deterministically classifies completed findings for publication. */
public final class FindingPublicationPolicy {
    public java.util.Map<FindingFingerprint, PublicationDecision> decide(
            List<ReviewFinding> findings, PublicationPolicySnapshot snapshot) {
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(snapshot, "snapshot");

        List<ReviewFinding> inlineCandidates = findings.stream()
                .filter(this::isInlineCandidate)
                .sorted(priority())
                .toList();
        Set<FindingFingerprint> selected = inlineCandidates.stream()
                .limit(snapshot.maxInlineComments())
                .map(ReviewFinding::fingerprint)
                .collect(Collectors.toSet());

        LinkedHashMap<FindingFingerprint, PublicationDecision> decisions = new LinkedHashMap<>();
        for (ReviewFinding finding : findings) {
            Objects.requireNonNull(finding, "finding");
            PublicationTier tier;
            if (!finding.location().changedLine()) {
                tier = PublicationTier.RETAIN_ONLY;
            } else if (selected.contains(finding.fingerprint())) {
                tier = PublicationTier.INLINE_COMMENT;
            } else {
                tier = PublicationTier.CHECK_SUMMARY;
            }
            decisions.put(finding.fingerprint(), new PublicationDecision(tier, snapshot.version()));
        }
        return Collections.unmodifiableMap(decisions);
    }

    private boolean isInlineCandidate(ReviewFinding finding) {
        if (finding == null || !finding.location().changedLine()
                || finding.content().severity() == FindingSeverity.SUGGESTION
                || finding.evidence().evidence().isBlank()) {
            return false;
        }
        FindingSeverity severity = finding.content().severity();
        if (severity != FindingSeverity.CRITICAL && severity != FindingSeverity.WARNING) {
            return false;
        }
        String source = finding.evidence().source();
        return isDeterministicSource(source) || !finding.evidence().citations().isEmpty();
    }

    private static boolean isDeterministicSource(String source) {
        return source.equals("regex") || source.equals("spotbugs");
    }

    private static Comparator<ReviewFinding> priority() {
        return Comparator
                .comparingInt((ReviewFinding finding) -> severityRank(finding.content().severity()))
                .thenComparingInt(finding -> sourceRank(finding.evidence().source()))
                .thenComparing(finding -> finding.location().file())
                .thenComparingInt(finding -> finding.location().line())
                .thenComparing(finding -> finding.fingerprint().value());
    }

    private static int severityRank(FindingSeverity severity) {
        return switch (severity) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case SUGGESTION -> 2;
        };
    }

    private static int sourceRank(String source) {
        return isDeterministicSource(source) ? 0 : 1;
    }
}
