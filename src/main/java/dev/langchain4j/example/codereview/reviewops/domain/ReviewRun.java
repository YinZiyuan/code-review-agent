package dev.langchain4j.example.codereview.reviewops.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ReviewRun {
    private final ReviewRunId id;
    private final PullRequestRevision revision;
    private final ReviewConfigurationSnapshot configuration;
    private final Instant requestedAt;
    private final List<ReviewAttempt> attempts = new ArrayList<>();
    private final List<DomainEvent> events = new ArrayList<>();
    private final Map<FindingFingerprint, PublicationReference> commentReferences = new LinkedHashMap<>();
    private ReviewRunState state;
    private List<ReviewFinding> findings = List.of();
    private ReviewFailure finalFailure;
    private Instant finishedAt;
    private String checkRunExternalId;

    private ReviewRun(ReviewRunId id, PullRequestRevision revision,
                      ReviewConfigurationSnapshot configuration, Instant requestedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.revision = Objects.requireNonNull(revision, "revision");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.state = ReviewRunState.REQUESTED;
    }

    public static ReviewRun request(ReviewRunId id, PullRequestRevision revision,
                                    ReviewConfigurationSnapshot configuration, Instant requestedAt) {
        return new ReviewRun(id, revision, configuration, requestedAt);
    }

    public ReviewAttempt startAttempt(Instant startedAt) {
        requireState(ReviewRunState.REQUESTED);
        ReviewAttempt attempt = ReviewAttempt.start(attempts.size() + 1, startedAt);
        attempts.add(attempt);
        state = ReviewRunState.RUNNING;
        return attempt;
    }

    public void recordTransientAttemptFailure(ReviewFailure failure,
                                              ExecutionMeasurements measurements, Instant endedAt) {
        requireState(ReviewRunState.RUNNING);
        currentAttempt().failTransient(failure, measurements, endedAt);
        if (attempts.size() < configuration.maxReviewAttempts()) {
            state = ReviewRunState.REQUESTED;
        } else {
            finalFailure = new ReviewFailure(failure.code(), FailureClass.TERMINAL,
                    "review attempts exhausted: " + failure.safeMessage());
            state = ReviewRunState.FAILED;
            finishedAt = endedAt;
        }
    }

    public void recordTerminalAttemptFailure(ReviewFailure failure,
                                             ExecutionMeasurements measurements, Instant endedAt) {
        requireState(ReviewRunState.RUNNING);
        currentAttempt().failTerminal(failure, measurements, endedAt);
        finalFailure = failure;
        state = ReviewRunState.FAILED;
        finishedAt = endedAt;
    }

    public void completeReview(List<ReviewFinding> completedFindings,
                               ExecutionMeasurements measurements, Instant completedAt) {
        requireState(ReviewRunState.RUNNING);
        Map<FindingFingerprint, ReviewFinding> unique = uniqueFindings(completedFindings);
        currentAttempt().succeed(measurements, completedAt);
        findings = List.copyOf(unique.values());
        state = ReviewRunState.COMPLETED;
        events.add(new ReviewRunCompleted(id, completedAt));
    }

    public void acceptPublicationDecisions(Map<FindingFingerprint, PublicationDecision> decisions) {
        requireState(ReviewRunState.COMPLETED);
        Objects.requireNonNull(decisions, "decisions");
        Set<FindingFingerprint> expected = findings.stream()
                .map(ReviewFinding::fingerprint)
                .collect(Collectors.toSet());
        if (!expected.equals(decisions.keySet())) {
            throw new IllegalArgumentException("decisions must cover exactly all findings");
        }
        findings.forEach(finding -> validatePublicationDecision(decisions.get(finding.fingerprint()), finding));
        findings.forEach(finding -> finding.acceptPublicationDecision(decisions.get(finding.fingerprint())));
    }

    public void authorizePublication(AuthoritativeRevision authoritative, Instant checkedAt) {
        requireState(ReviewRunState.COMPLETED);
        Objects.requireNonNull(authoritative, "authoritative");
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (!authoritative.matches(revision)) {
            state = ReviewRunState.SUPERSEDED;
            finishedAt = checkedAt;
            return;
        }
        if (findings.stream().anyMatch(finding -> finding.publicationDecision().isEmpty())) {
            throw new IllegalStateException("publication decisions are incomplete");
        }
        state = ReviewRunState.PUBLISHING;
    }

    public void confirmPublication(String checkRunExternalId,
                                   Map<FindingFingerprint, PublicationReference> suppliedCommentReferences,
                                   Instant publishedAt) {
        requireState(ReviewRunState.PUBLISHING);
        if (checkRunExternalId == null || checkRunExternalId.isBlank()) {
            throw new IllegalArgumentException("checkRunExternalId must not be blank");
        }
        Objects.requireNonNull(suppliedCommentReferences, "suppliedCommentReferences");
        Objects.requireNonNull(publishedAt, "publishedAt");

        Map<FindingFingerprint, ReviewFinding> findingsByFingerprint = findings.stream()
                .collect(Collectors.toMap(ReviewFinding::fingerprint, Function.identity()));
        suppliedCommentReferences.forEach((fingerprint, reference) -> {
            ReviewFinding finding = findingsByFingerprint.get(fingerprint);
            if (finding == null || finding.publicationDecision().isEmpty()
                    || finding.publicationDecision().orElseThrow().tier() != PublicationTier.INLINE_COMMENT) {
                throw new IllegalArgumentException("comment references must belong to inline findings");
            }
            Objects.requireNonNull(reference, "comment reference");
            if (finding.publicationReference().isPresent()) {
                throw new IllegalStateException("reference already assigned");
            }
        });
        suppliedCommentReferences.forEach((fingerprint, reference) -> {
            findingsByFingerprint.get(fingerprint).recordPublicationReference(reference);
            commentReferences.put(fingerprint, reference);
        });
        this.checkRunExternalId = checkRunExternalId;
        state = ReviewRunState.PUBLISHED;
        finishedAt = publishedAt;
    }

    public void recordPublicationFailure(ReviewFailure failure, Instant failedAt) {
        requireState(ReviewRunState.PUBLISHING);
        requireTerminalFailure(failure);
        Objects.requireNonNull(failedAt, "failedAt");
        finalFailure = failure;
        state = ReviewRunState.FAILED;
        finishedAt = failedAt;
    }

    public void supersede(AuthoritativeRevision currentRevision, Instant supersededAt) {
        Objects.requireNonNull(currentRevision, "currentRevision");
        Objects.requireNonNull(supersededAt, "supersededAt");
        if (currentRevision.matches(revision)) {
            throw new IllegalArgumentException("currentRevision must not match review revision");
        }
        if (state != ReviewRunState.REQUESTED && state != ReviewRunState.RUNNING
                && state != ReviewRunState.COMPLETED && state != ReviewRunState.PUBLISHING) {
            throw new IllegalStateException("cannot supersede " + state);
        }
        state = ReviewRunState.SUPERSEDED;
        finishedAt = supersededAt;
    }

    public List<DomainEvent> drainEvents() {
        List<DomainEvent> drained = List.copyOf(events);
        events.clear();
        return drained;
    }

    public ReviewRunId id() { return id; }
    public PullRequestRevision revision() { return revision; }
    public ReviewConfigurationSnapshot configuration() { return configuration; }
    public Instant requestedAt() { return requestedAt; }
    public ReviewRunState state() { return state; }
    public List<ReviewAttempt> attempts() { return List.copyOf(attempts); }
    public List<ReviewFinding> findings() { return List.copyOf(findings); }
    public Map<FindingFingerprint, PublicationReference> commentReferences() {
        return Map.copyOf(commentReferences);
    }
    public Optional<String> checkRunExternalId() { return Optional.ofNullable(checkRunExternalId); }
    public Optional<ReviewFailure> finalFailure() { return Optional.ofNullable(finalFailure); }
    public Optional<Instant> finishedAt() { return Optional.ofNullable(finishedAt); }

    private ReviewAttempt currentAttempt() {
        return attempts.get(attempts.size() - 1);
    }

    private static Map<FindingFingerprint, ReviewFinding> uniqueFindings(
            List<ReviewFinding> completedFindings) {
        return Objects.requireNonNull(completedFindings, "completedFindings").stream()
                .collect(Collectors.toMap(
                        ReviewFinding::fingerprint, Function.identity(),
                        (left, right) -> {
                            throw new IllegalArgumentException("duplicate fingerprint");
                        },
                        LinkedHashMap::new));
    }

    private void validatePublicationDecision(PublicationDecision decision, ReviewFinding finding) {
        Objects.requireNonNull(decision, "decision");
        if (!configuration.policyVersion().equals(decision.policyVersion())) {
            throw new IllegalArgumentException("decision policyVersion must match configuration policyVersion");
        }
        if (finding.publicationDecision().isPresent()) {
            throw new IllegalStateException("decision already assigned");
        }
    }

    private void requireState(ReviewRunState expected) {
        if (state != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + state);
        }
    }

    private static void requireTerminalFailure(ReviewFailure failure) {
        if (failure == null || failure.classification() != FailureClass.TERMINAL) {
            throw new IllegalArgumentException("failure classification must be TERMINAL");
        }
    }

}
