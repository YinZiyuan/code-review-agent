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
import dev.langchain4j.example.codereview.reviewops.application.jobs.OperationFence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface GitHubPublicationGateway {

    AuthoritativeRevision authoritativeRevision(PullRequestRevision revision);

    default AuthoritativeRevision authoritativeRevision(
            PullRequestRevision revision, OperationFence fence) {
        Objects.requireNonNull(fence, "fence").requireCurrent();
        return authoritativeRevision(revision);
    }

    CheckRunArtifact upsertCheck(CheckRunRequest request);

    default CheckRunArtifact upsertCheck(CheckRunRequest request, OperationFence fence) {
        Objects.requireNonNull(fence, "fence").requireCurrent();
        return upsertCheck(request);
    }

    InlineCommentArtifact reconcileInlineComment(InlineCommentRequest request);

    default InlineCommentArtifact reconcileInlineComment(
            InlineCommentRequest request, OperationFence fence) {
        Objects.requireNonNull(fence, "fence").requireCurrent();
        return reconcileInlineComment(request);
    }

    InlineCommentRetraction retractInlineComment(InlineCommentRetractionRequest request);

    default InlineCommentRetraction retractInlineComment(
            InlineCommentRetractionRequest request, OperationFence fence) {
        Objects.requireNonNull(fence, "fence").requireCurrent();
        return retractInlineComment(request);
    }

    record CheckRunRequest(
            ReviewRunId reconciliationExternalId,
            PullRequestRevision revision,
            CheckPresentation presentation,
            List<PublicationFinding> findings,
            Optional<String> existingGitHubArtifactId) {

        public CheckRunRequest {
            Objects.requireNonNull(reconciliationExternalId, "reconciliationExternalId");
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(presentation, "presentation");
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
            if (presentation.outcome() == CheckOutcome.NEUTRAL_SYSTEM_FAILURE
                    && !findings.isEmpty()) {
                throw new IllegalArgumentException(
                        "system failure Check must not contain review findings");
            }
            existingGitHubArtifactId = existingGitHubArtifactId == null
                    ? Optional.empty() : existingGitHubArtifactId;
            existingGitHubArtifactId.ifPresent(value -> {
                if (value.isBlank()) {
                    throw new IllegalArgumentException(
                            "existingGitHubArtifactId must not be blank");
                }
            });
        }
    }

    enum CheckOutcome {
        SUCCESS,
        NEUTRAL_SYSTEM_FAILURE
    }

    enum CheckStatus {
        COMPLETED
    }

    enum CheckConclusion {
        SUCCESS,
        NEUTRAL
    }

    record CheckPresentation(
            CheckOutcome outcome,
            CheckStatus status,
            CheckConclusion conclusion,
            String safeSummary,
            boolean codeCommentsMayRemain) {

        public static final int MAX_SAFE_SUMMARY_CHARACTERS = 4096;

        public CheckPresentation(
                CheckOutcome outcome,
                CheckStatus status,
                CheckConclusion conclusion,
                String safeSummary) {
            this(outcome, status, conclusion, safeSummary, false);
        }

        public CheckPresentation {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(conclusion, "conclusion");
            if (safeSummary == null || safeSummary.isBlank()) {
                throw new IllegalArgumentException("safeSummary must not be blank");
            }
            if (safeSummary.length() > MAX_SAFE_SUMMARY_CHARACTERS) {
                throw new IllegalArgumentException(
                        "safeSummary exceeds " + MAX_SAFE_SUMMARY_CHARACTERS + " characters");
            }
            if (outcome == CheckOutcome.SUCCESS
                    && (status != CheckStatus.COMPLETED
                    || conclusion != CheckConclusion.SUCCESS
                    || codeCommentsMayRemain)) {
                throw new IllegalArgumentException(
                        "successful Check must be completed with a success conclusion");
            }
            if (outcome == CheckOutcome.NEUTRAL_SYSTEM_FAILURE
                    && (status != CheckStatus.COMPLETED
                    || conclusion != CheckConclusion.NEUTRAL)) {
                throw new IllegalArgumentException(
                        "system failure Check must be completed with a neutral conclusion");
            }
        }

        public static CheckPresentation success(String safeSummary) {
            return new CheckPresentation(
                    CheckOutcome.SUCCESS,
                    CheckStatus.COMPLETED,
                    CheckConclusion.SUCCESS,
                    safeSummary,
                    false);
        }

        public static CheckPresentation neutralSystemFailure(String safeSummary) {
            return new CheckPresentation(
                    CheckOutcome.NEUTRAL_SYSTEM_FAILURE,
                    CheckStatus.COMPLETED,
                    CheckConclusion.NEUTRAL,
                    safeSummary,
                    false);
        }

        public static CheckPresentation neutralSystemFailure(
                String safeSummary, boolean codeCommentsMayRemain) {
            return new CheckPresentation(
                    CheckOutcome.NEUTRAL_SYSTEM_FAILURE,
                    CheckStatus.COMPLETED,
                    CheckConclusion.NEUTRAL,
                    safeSummary,
                    codeCommentsMayRemain);
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

    record InlineCommentRetractionRequest(
            ReviewRunId reviewRunId,
            PullRequestRevision revision,
            FindingFingerprint fingerprint,
            PublicationReference reference) {

        public InlineCommentRetractionRequest {
            Objects.requireNonNull(reviewRunId, "reviewRunId");
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(reference, "reference");
        }
    }

    record InlineCommentRetraction(
            FindingFingerprint fingerprint, String githubArtifactId) {

        public InlineCommentRetraction {
            Objects.requireNonNull(fingerprint, "fingerprint");
            if (githubArtifactId == null || githubArtifactId.isBlank()) {
                throw new IllegalArgumentException("githubArtifactId must not be blank");
            }
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
