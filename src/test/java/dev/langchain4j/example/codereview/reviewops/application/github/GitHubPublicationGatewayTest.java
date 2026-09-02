package dev.langchain4j.example.codereview.reviewops.application.github;

import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubPublicationGatewayTest {

    private static final ReviewRunId RUN_ID = ReviewRunId.newId();
    private static final PullRequestRevision REVISION = new PullRequestRevision(
            10, 20, 30, "0123456789abcdef0123456789abcdef01234567");

    @Test
    void successfulCheckPresentationCarriesExplicitImmutableOutcome() {
        var presentation = GitHubPublicationGateway.CheckPresentation.success(
                "Review completed with one finding.");
        var request = new GitHubPublicationGateway.CheckRunRequest(
                RUN_ID,
                REVISION,
                presentation,
                List.of(publicationFinding()),
                Optional.empty());

        assertThat(request.reconciliationExternalId()).isEqualTo(RUN_ID);
        assertThat(request.presentation().outcome())
                .isEqualTo(GitHubPublicationGateway.CheckOutcome.SUCCESS);
        assertThat(request.presentation().status())
                .isEqualTo(GitHubPublicationGateway.CheckStatus.COMPLETED);
        assertThat(request.presentation().conclusion())
                .isEqualTo(GitHubPublicationGateway.CheckConclusion.SUCCESS);
        assertThat(request.presentation().safeSummary())
                .isEqualTo("Review completed with one finding.");
        assertThatThrownBy(() -> request.findings().add(publicationFinding()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void successfulCheckDoesNotInferFailureFromAnEmptyFindingList() {
        var request = new GitHubPublicationGateway.CheckRunRequest(
                RUN_ID,
                REVISION,
                GitHubPublicationGateway.CheckPresentation.success("No findings."),
                List.of(),
                Optional.empty());

        assertThat(request.findings()).isEmpty();
        assertThat(request.presentation().outcome())
                .isEqualTo(GitHubPublicationGateway.CheckOutcome.SUCCESS);
        assertThat(request.presentation().conclusion())
                .isEqualTo(GitHubPublicationGateway.CheckConclusion.SUCCESS);
    }

    @Test
    void systemFailureCheckIsExplicitlyCompletedNeutralAndContainsOnlySafeSummary() {
        var request = new GitHubPublicationGateway.CheckRunRequest(
                RUN_ID,
                REVISION,
                GitHubPublicationGateway.CheckPresentation.neutralSystemFailure(
                        "Review could not be completed safely."),
                List.of(),
                Optional.empty());

        assertThat(request.presentation().outcome())
                .isEqualTo(GitHubPublicationGateway.CheckOutcome.NEUTRAL_SYSTEM_FAILURE);
        assertThat(request.presentation().status())
                .isEqualTo(GitHubPublicationGateway.CheckStatus.COMPLETED);
        assertThat(request.presentation().conclusion())
                .isEqualTo(GitHubPublicationGateway.CheckConclusion.NEUTRAL);
        assertThat(request.presentation().safeSummary())
                .isEqualTo("Review could not be completed safely.");
        assertThat(request.findings()).isEmpty();
    }

    @Test
    void existingConfirmedGithubArtifactIdIsSeparateFromDeterministicReconciliationKey() {
        var request = new GitHubPublicationGateway.CheckRunRequest(
                RUN_ID,
                REVISION,
                GitHubPublicationGateway.CheckPresentation.success("No findings."),
                List.of(),
                Optional.of("987654321"));
        var artifact = new CheckRunArtifact("987654321");

        assertThat(request.reconciliationExternalId()).isEqualTo(RUN_ID);
        assertThat(request.existingGitHubArtifactId()).contains("987654321");
        assertThat(artifact.githubArtifactId()).isEqualTo("987654321");
    }

    @Test
    void checkContractRejectsInvalidIdentifiersAndPresentationFacts() {
        assertThatThrownBy(() -> new CheckRunArtifact(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("githubArtifactId");
        assertThatThrownBy(() -> new InlineCommentArtifact(
                new FindingFingerprint("a".repeat(64)), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("githubArtifactId");
        assertThatThrownBy(() -> new GitHubPublicationGateway.CheckRunRequest(
                null,
                REVISION,
                GitHubPublicationGateway.CheckPresentation.success("No findings."),
                List.of(),
                Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reconciliationExternalId");
        assertThatThrownBy(() -> new GitHubPublicationGateway.CheckRunRequest(
                RUN_ID,
                REVISION,
                GitHubPublicationGateway.CheckPresentation.success("No findings."),
                List.of(),
                Optional.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existingGitHubArtifactId");
        assertThatThrownBy(() -> GitHubPublicationGateway.CheckPresentation.success(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safeSummary");
        assertThatThrownBy(() -> GitHubPublicationGateway.CheckPresentation.success(
                "x".repeat(GitHubPublicationGateway.CheckPresentation.MAX_SAFE_SUMMARY_CHARACTERS + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safeSummary");
        assertThatThrownBy(() -> new GitHubPublicationGateway.CheckRunRequest(
                RUN_ID,
                REVISION,
                GitHubPublicationGateway.CheckPresentation.neutralSystemFailure(
                        "Review could not be completed safely."),
                List.of(publicationFinding()),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system failure");
    }

    private static GitHubPublicationGateway.PublicationFinding publicationFinding() {
        return new GitHubPublicationGateway.PublicationFinding(
                new FindingFingerprint("a".repeat(64)),
                new CodeLocation("src/Foo.java", 10, true),
                new FindingContent(
                        FindingSeverity.WARNING,
                        FindingCategory.STABILITY,
                        "Issue",
                        "Description",
                        "Suggestion"),
                new FindingEvidence("Evidence", List.of(), "regex"),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "policy-v1"),
                Optional.empty());
    }
}
