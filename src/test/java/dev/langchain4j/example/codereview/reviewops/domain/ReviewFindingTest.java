package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewFindingTest {

    @Test
    void fingerprintIgnoresLineAndNormalizesTextAndPath() {
        FindingFingerprintFactory factory = new FindingFingerprintFactory();
        FindingContent contentA = new FindingContent(
                FindingSeverity.WARNING, FindingCategory.STABILITY,
                " Null   dereference ", "description", "suggestion");
        FindingContent contentB = new FindingContent(
                FindingSeverity.WARNING, FindingCategory.STABILITY,
                "null dereference", "description", "suggestion");
        FindingEvidence evidence = new FindingEvidence("  MAY BE NULL ", List.of(), "llm_reviewer");

        CodeLocation firstLocation = new CodeLocation("./src\\\\Foo.java", 10, true);
        CodeLocation secondLocation = new CodeLocation("src/Foo.java", 99, true);
        FindingFingerprint first = factory.create(firstLocation, contentA, evidence);
        FindingFingerprint second = factory.create(secondLocation, contentB, evidence);

        assertThat(firstLocation.file()).isEqualTo("src/Foo.java");
        assertThat(secondLocation.file()).isEqualTo("src/Foo.java");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void publicationDecisionAndReferenceCanBeRecordedOnlyOnce() {
        ReviewFinding finding = finding("regex", List.of());
        PublicationDecision decision = new PublicationDecision(PublicationTier.INLINE_COMMENT, "publish-v1");
        finding.acceptPublicationDecision(decision);
        finding.recordPublicationReference(new PublicationReference("REVIEW_COMMENT", "123"));

        assertThat(finding.publicationDecision()).contains(decision);
        assertThatThrownBy(() -> finding.acceptPublicationDecision(decision))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                finding.recordPublicationReference(new PublicationReference("REVIEW_COMMENT", "456")))
                .isInstanceOf(IllegalStateException.class);
    }

    static ReviewFinding finding(String source, List<CitationEvidence> citations) {
        CodeLocation location = new CodeLocation("src/Foo.java", 10, true);
        FindingContent content = new FindingContent(
                FindingSeverity.WARNING, FindingCategory.STABILITY,
                "Null dereference", "description", "suggestion");
        FindingEvidence evidence = new FindingEvidence("value may be null", citations, source);
        return new ReviewFinding(new FindingFingerprintFactory().create(location, content, evidence),
                location, content, evidence);
    }
}
