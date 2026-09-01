package dev.langchain4j.example.codereview.reviewops.application.github;

import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationReference;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface GitHubPublicationGateway {

    AuthoritativeRevision authoritativeRevision(PullRequestRevision revision);

    CheckRunArtifact upsertCheck(CheckRunRequest request);

    InlineCommentArtifact reconcileInlineComment(InlineCommentRequest request);

    record CheckRunRequest(
            ReviewRunId reviewRunId,
            PullRequestRevision revision,
            List<PublicationFinding> findings,
            Optional<String> existingExternalId) {

        public CheckRunRequest {
            Objects.requireNonNull(reviewRunId, "reviewRunId");
            Objects.requireNonNull(revision, "revision");
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
            existingExternalId = existingExternalId == null
                    ? Optional.empty() : existingExternalId;
            existingExternalId.ifPresent(value -> {
                if (value.isBlank()) {
                    throw new IllegalArgumentException("existingExternalId must not be blank");
                }
            });
        }
    }

    record InlineCommentRequest(
            ReviewRunId reviewRunId,
            PullRequestRevision revision,
            PublicationFinding finding) {

        public InlineCommentRequest {
            Objects.requireNonNull(reviewRunId, "reviewRunId");
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(finding, "finding");
        }
    }

    record PublicationFinding(
            FindingFingerprint fingerprint,
            CodeLocation location,
            FindingContent content,
            FindingEvidence evidence,
            PublicationDecision decision,
            Optional<PublicationReference> existingReference) {

        public PublicationFinding {
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(decision, "decision");
            existingReference = existingReference == null
                    ? Optional.empty() : existingReference;
        }
    }
}
