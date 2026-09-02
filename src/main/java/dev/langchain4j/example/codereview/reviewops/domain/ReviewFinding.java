package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.Objects;
import java.util.Optional;

public final class ReviewFinding {
    private final FindingFingerprint fingerprint;
    private final CodeLocation location;
    private final FindingContent content;
    private final FindingEvidence evidence;
    private PublicationDecision publicationDecision;
    private PublicationReference publicationReference;

    public ReviewFinding(FindingFingerprint fingerprint, CodeLocation location,
                         FindingContent content, FindingEvidence evidence) {
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.location = Objects.requireNonNull(location, "location");
        this.content = Objects.requireNonNull(content, "content");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    public static ReviewFinding reconstitute(FindingFingerprint fingerprint, CodeLocation location,
                                             FindingContent content, FindingEvidence evidence,
                                             PublicationDecision publicationDecision,
                                             PublicationReference publicationReference) {
        if (publicationReference != null && (publicationDecision == null
                || publicationDecision.tier() != PublicationTier.INLINE_COMMENT)) {
            throw new IllegalArgumentException("only an inline finding may record a comment reference");
        }
        ReviewFinding finding = new ReviewFinding(fingerprint, location, content, evidence);
        finding.publicationDecision = publicationDecision;
        finding.publicationReference = publicationReference;
        return finding;
    }

    void acceptPublicationDecision(PublicationDecision decision) {
        if (publicationDecision != null) throw new IllegalStateException("decision already assigned");
        publicationDecision = Objects.requireNonNull(decision, "decision");
    }

    void recordPublicationReference(PublicationReference reference) {
        if (publicationDecision == null || publicationDecision.tier() != PublicationTier.INLINE_COMMENT) {
            throw new IllegalStateException("only an inline finding may record a comment reference");
        }
        if (publicationReference != null) throw new IllegalStateException("reference already assigned");
        publicationReference = Objects.requireNonNull(reference, "reference");
    }

    void clearPublicationReference(PublicationReference expectedReference) {
        Objects.requireNonNull(expectedReference, "expectedReference");
        if (!expectedReference.equals(publicationReference)) {
            throw new IllegalArgumentException(
                    "expectedReference does not match recorded publication reference");
        }
        publicationReference = null;
    }

    public FindingFingerprint fingerprint() { return fingerprint; }
    public CodeLocation location() { return location; }
    public FindingContent content() { return content; }
    public FindingEvidence evidence() { return evidence; }
    public Optional<PublicationDecision> publicationDecision() {
        return Optional.ofNullable(publicationDecision);
    }
    public Optional<PublicationReference> publicationReference() {
        return Optional.ofNullable(publicationReference);
    }
}
