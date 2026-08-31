package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FindingPublicationPolicyTest {
    private final FindingPublicationPolicy policy = new FindingPublicationPolicy();
    private final PublicationPolicySnapshot snapshot = new PublicationPolicySnapshot("publish-v1", 5);

    @Test
    void invalidChangedLineIsRetainedOnly() {
        ReviewFinding finding = finding("llm_reviewer", FindingSeverity.CRITICAL, false,
                List.of(new CitationEvidence("C1", "security", "nulls")), "evidence");
        assertThat(policy.decide(List.of(finding), snapshot).get(finding.fingerprint()).tier())
                .isEqualTo(PublicationTier.RETAIN_ONLY);
    }

    @Test
    void deterministicWarningWithEvidenceIsInline() {
        ReviewFinding finding = finding("regex", FindingSeverity.WARNING, true, List.of(), "evidence");
        assertThat(policy.decide(List.of(finding), snapshot).get(finding.fingerprint()).tier())
                .isEqualTo(PublicationTier.INLINE_COMMENT);
    }

    @Test
    void llmWarningNeedsEvidenceAndCitationForInline() {
        ReviewFinding cited = finding("llm_reviewer", FindingSeverity.WARNING, true,
                List.of(new CitationEvidence("C1", "security", "nulls")), "evidence");
        ReviewFinding uncited = finding("llm_reviewer", FindingSeverity.WARNING, true,
                List.of(), "evidence two");

        var decisions = policy.decide(List.of(cited, uncited), snapshot);
        assertThat(decisions.get(cited.fingerprint()).tier()).isEqualTo(PublicationTier.INLINE_COMMENT);
        assertThat(decisions.get(uncited.fingerprint()).tier()).isEqualTo(PublicationTier.CHECK_SUMMARY);
    }

    @Test
    void unknownCitedSourcesFailClosedToCheckSummary() {
        ReviewFinding unknown = finding("agent_reviewer", FindingSeverity.WARNING, true,
                List.of(new CitationEvidence("C1", "security", "nulls")), "unknown evidence");
        ReviewFinding wrongCase = finding("LLM_REVIEWER", FindingSeverity.CRITICAL, true,
                List.of(new CitationEvidence("C2", "security", "bounds")), "wrong case evidence");

        var decisions = policy.decide(List.of(unknown, wrongCase), snapshot);

        assertThat(decisions.get(unknown.fingerprint()).tier()).isEqualTo(PublicationTier.CHECK_SUMMARY);
        assertThat(decisions.get(wrongCase.fingerprint()).tier()).isEqualTo(PublicationTier.CHECK_SUMMARY);
    }

    @Test
    void onlyFiveCandidatesRemainInline() {
        List<ReviewFinding> findings = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> finding("regex", FindingSeverity.WARNING, true, List.of(), "evidence " + i))
                .toList();
        long inline = policy.decide(findings, snapshot).values().stream()
                .filter(d -> d.tier() == PublicationTier.INLINE_COMMENT).count();
        assertThat(inline).isEqualTo(5);
    }

    @Test
    void inlinePriorityUsesCanonicalFilePaths() {
        PublicationPolicySnapshot oneInline = new PublicationPolicySnapshot("publish-v1", 1);
        ReviewFinding laterCanonicalPath = findingAt("regex", "./z/Foo.java", "z evidence");
        ReviewFinding earlierCanonicalPath = findingAt("regex", "a/Foo.java", "a evidence");

        var decisions = policy.decide(List.of(laterCanonicalPath, earlierCanonicalPath), oneInline);

        assertThat(decisions.get(earlierCanonicalPath.fingerprint()).tier())
                .isEqualTo(PublicationTier.INLINE_COMMENT);
        assertThat(decisions.get(laterCanonicalPath.fingerprint()).tier())
                .isEqualTo(PublicationTier.CHECK_SUMMARY);
    }

    private static ReviewFinding finding(String source, FindingSeverity severity,
            boolean changedLine, List<CitationEvidence> citations, String evidenceText) {
        CodeLocation location = new CodeLocation("src/" + evidenceText.hashCode() + ".java", 10, changedLine);
        FindingContent content = new FindingContent(severity, FindingCategory.STABILITY,
                "Issue " + evidenceText, "description", "suggestion");
        FindingEvidence evidence = new FindingEvidence(evidenceText, citations, source);
        return new ReviewFinding(new FindingFingerprintFactory().create(location, content, evidence),
                location, content, evidence);
    }

    private static ReviewFinding findingAt(String source, String file, String evidenceText) {
        CodeLocation location = new CodeLocation(file, 10, true);
        FindingContent content = new FindingContent(FindingSeverity.WARNING, FindingCategory.STABILITY,
                "Issue " + evidenceText, "description", "suggestion");
        FindingEvidence evidence = new FindingEvidence(evidenceText, List.of(), source);
        return new ReviewFinding(new FindingFingerprintFactory().create(location, content, evidence),
                location, content, evidence);
    }
}
