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

    public static ReviewRun reconstitute(ReviewRunId id, PullRequestRevision revision,
                                         ReviewConfigurationSnapshot configuration, Instant requestedAt,
                                         ReviewRunState state, List<ReviewAttempt> attempts,
                                         List<ReviewFinding> findings, ReviewFailure finalFailure,
                                         Instant finishedAt, String checkRunExternalId) {
        Objects.requireNonNull(state, "state");
        List<ReviewAttempt> restoredAttempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        List<ReviewFinding> restoredFindings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        Map<FindingFingerprint, ReviewFinding> uniqueFindings = uniqueFindings(restoredFindings);
        validateAttemptSequence(restoredAttempts, Objects.requireNonNull(configuration, "configuration"));
        validateFindingPublication(restoredFindings, configuration);
        validateReconstitutedState(state, restoredAttempts, restoredFindings, finalFailure,
                finishedAt, checkRunExternalId, configuration);

        ReviewRun reviewRun = new ReviewRun(id, revision, configuration, requestedAt);
        reviewRun.attempts.addAll(restoredAttempts);
        reviewRun.findings = List.copyOf(uniqueFindings.values());
        restoredFindings.forEach(finding -> finding.publicationReference().ifPresent(reference ->
                reviewRun.commentReferences.put(finding.fingerprint(), reference)));
        reviewRun.state = state;
        reviewRun.finalFailure = finalFailure;
        reviewRun.finishedAt = finishedAt;
        reviewRun.checkRunExternalId = checkRunExternalId;
        return reviewRun;
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

    public void recoverInterruptedAttempt(ReviewFailure failure, Instant recoveredAt) {
        requireState(ReviewRunState.RUNNING);
        if (failure == null || failure.classification() != FailureClass.TRANSIENT) {
            throw new IllegalArgumentException("failure classification must be TRANSIENT");
        }
        ReviewFailure safeFailure = new ReviewFailure(
                failure.code(), FailureClass.TRANSIENT, "review worker was interrupted");
        recordTransientAttemptFailure(
                safeFailure, new ExecutionMeasurements(0, 0, 0, Map.of()), recoveredAt);
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
        Objects.requireNonNull(suppliedCommentReferences, "suppliedCommentReferences");
        Objects.requireNonNull(publishedAt, "publishedAt");

        Set<FindingFingerprint> inlineFingerprints = findings.stream()
                .filter(finding -> finding.publicationDecision().orElseThrow().tier()
                        == PublicationTier.INLINE_COMMENT)
                .map(ReviewFinding::fingerprint)
                .collect(Collectors.toSet());
        if (!inlineFingerprints.equals(suppliedCommentReferences.keySet())) {
            throw new IllegalArgumentException(
                    "comment references must cover exactly all inline findings");
        }
        recordPublicationProgress(checkRunExternalId, suppliedCommentReferences);
        state = ReviewRunState.PUBLISHED;
        finishedAt = publishedAt;
    }

    public void recordPublicationProgress(
            String checkRunExternalId,
            Map<FindingFingerprint, PublicationReference> confirmedCommentReferences) {
        requireState(ReviewRunState.PUBLISHING);
        if (checkRunExternalId == null || checkRunExternalId.isBlank()) {
            throw new IllegalArgumentException("checkRunExternalId must not be blank");
        }
        Objects.requireNonNull(confirmedCommentReferences, "confirmedCommentReferences");
        if (this.checkRunExternalId != null && !this.checkRunExternalId.equals(checkRunExternalId)) {
            throw new IllegalArgumentException("checkRunExternalId conflicts with recorded publication progress");
        }

        Map<FindingFingerprint, ReviewFinding> findingsByFingerprint = findings.stream()
                .collect(Collectors.toMap(ReviewFinding::fingerprint, Function.identity()));
        confirmedCommentReferences.forEach((fingerprint, reference) -> {
            ReviewFinding finding = findingsByFingerprint.get(fingerprint);
            if (finding == null || finding.publicationDecision().isEmpty()
                    || finding.publicationDecision().orElseThrow().tier() != PublicationTier.INLINE_COMMENT) {
                throw new IllegalArgumentException("comment references must belong to inline findings");
            }
            Objects.requireNonNull(reference, "comment reference");
            if (finding.publicationReference().isPresent()
                    && !finding.publicationReference().orElseThrow().equals(reference)) {
                throw new IllegalStateException("reference conflicts with recorded publication progress");
            }
        });
        confirmedCommentReferences.forEach((fingerprint, reference) -> {
            ReviewFinding finding = findingsByFingerprint.get(fingerprint);
            if (finding.publicationReference().isEmpty()) {
                finding.recordPublicationReference(reference);
            }
            commentReferences.put(fingerprint, reference);
        });
        this.checkRunExternalId = checkRunExternalId;
    }

    public void recordPublicationFailure(ReviewFailure failure, Instant failedAt) {
        requireState(ReviewRunState.PUBLISHING);
        settleTerminalPublicationFailure(failure, failedAt);
    }

    public void recordPublicationAuthorizationFailure(ReviewFailure failure, Instant failedAt) {
        requireState(ReviewRunState.COMPLETED);
        if (findings.stream().anyMatch(finding -> finding.publicationDecision().isEmpty())) {
            throw new IllegalStateException("publication decisions are incomplete");
        }
        settleTerminalPublicationFailure(failure, failedAt);
    }

    private void settleTerminalPublicationFailure(ReviewFailure failure, Instant failedAt) {
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
        if (state == ReviewRunState.RUNNING) {
            currentAttempt().cancel(supersededAt);
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

    private static void validateAttemptSequence(List<ReviewAttempt> attempts,
                                                ReviewConfigurationSnapshot configuration) {
        if (attempts.size() > configuration.maxReviewAttempts()) {
            throw new IllegalArgumentException("attempt count exceeds configuration maximum");
        }
        for (int index = 0; index < attempts.size(); index++) {
            ReviewAttempt attempt = attempts.get(index);
            if (attempt.attemptNumber() != index + 1) {
                throw new IllegalArgumentException("attempt numbers must be consecutive");
            }
            if (index < attempts.size() - 1 && attempt.state() != ReviewAttemptState.TRANSIENT_FAILURE) {
                throw new IllegalArgumentException("earlier attempts must be transient failures");
            }
        }
    }

    private static void validateFindingPublication(List<ReviewFinding> findings,
                                                   ReviewConfigurationSnapshot configuration) {
        findings.forEach(finding -> finding.publicationDecision().ifPresent(decision -> {
            if (!configuration.policyVersion().equals(decision.policyVersion())) {
                throw new IllegalArgumentException(
                        "decision policyVersion must match configuration policyVersion");
            }
        }));
    }

    private static void validateReconstitutedState(ReviewRunState state, List<ReviewAttempt> attempts,
                                                   List<ReviewFinding> findings, ReviewFailure finalFailure,
                                                   Instant finishedAt, String checkRunExternalId,
                                                   ReviewConfigurationSnapshot configuration) {
        boolean hasFinishedAt = finishedAt != null;
        boolean hasExternalId = checkRunExternalId != null;
        boolean hasBlankExternalId = hasExternalId && checkRunExternalId.isBlank();
        boolean hasFinalFailure = finalFailure != null;
        boolean allFindingsHaveDecisions = findings.stream()
                .allMatch(finding -> finding.publicationDecision().isPresent());
        boolean noFindingsHaveDecisions = findings.stream()
                .noneMatch(finding -> finding.publicationDecision().isPresent());
        boolean allInlineFindingsHaveReferences = findings.stream()
                .filter(finding -> finding.publicationDecision()
                        .map(decision -> decision.tier() == PublicationTier.INLINE_COMMENT)
                        .orElse(false))
                .allMatch(finding -> finding.publicationReference().isPresent());
        boolean noFindingsHaveReferences = findings.stream()
                .noneMatch(finding -> finding.publicationReference().isPresent());
        ReviewAttemptState lastAttemptState = attempts.isEmpty()
                ? null : attempts.get(attempts.size() - 1).state();

        if (!noFindingsHaveReferences && !hasExternalId) {
            throw new IllegalArgumentException(
                    "publication references require a check run external id");
        }

        switch (state) {
            case REQUESTED -> {
                if ((!attempts.isEmpty() && lastAttemptState != ReviewAttemptState.TRANSIENT_FAILURE)
                        || attempts.size() >= configuration.maxReviewAttempts() || !findings.isEmpty()
                        || hasFinalFailure || hasFinishedAt || hasExternalId) {
                    throw new IllegalArgumentException("requested review has invalid persisted state");
                }
            }
            case RUNNING -> {
                if (lastAttemptState != ReviewAttemptState.STARTED || !findings.isEmpty()
                        || hasFinalFailure || hasFinishedAt || hasExternalId) {
                    throw new IllegalArgumentException("running review must end with a started attempt");
                }
            }
            case COMPLETED -> {
                if (lastAttemptState != ReviewAttemptState.SUCCEEDED
                        || (!noFindingsHaveDecisions && !allFindingsHaveDecisions)
                        || !noFindingsHaveReferences || hasFinalFailure || hasFinishedAt || hasExternalId) {
                    throw new IllegalArgumentException("completed review has invalid persisted state");
                }
            }
            case PUBLISHING -> {
                if (lastAttemptState != ReviewAttemptState.SUCCEEDED || !allFindingsHaveDecisions
                        || hasFinalFailure || hasFinishedAt || hasBlankExternalId) {
                    throw new IllegalArgumentException("publishing review has invalid persisted state");
                }
            }
            case PUBLISHED -> {
                if (lastAttemptState != ReviewAttemptState.SUCCEEDED || !allFindingsHaveDecisions
                        || !allInlineFindingsHaveReferences || hasFinalFailure || !hasFinishedAt
                        || checkRunExternalId == null || checkRunExternalId.isBlank()) {
                    throw new IllegalArgumentException("published review has invalid persisted state");
                }
            }
            case FAILED -> {
                if (finalFailure == null || finalFailure.classification() != FailureClass.TERMINAL
                        || !hasFinishedAt || hasBlankExternalId
                        || !isValidFailedAttemptState(lastAttemptState, attempts.size(),
                        configuration.maxReviewAttempts(), findings, allFindingsHaveDecisions,
                        hasExternalId)) {
                    throw new IllegalArgumentException("failed review has invalid persisted state");
                }
            }
            case SUPERSEDED -> {
                if (!hasFinishedAt || hasFinalFailure || hasBlankExternalId
                        || !isValidSupersededAttemptState(lastAttemptState, findings,
                        noFindingsHaveDecisions, allFindingsHaveDecisions, hasExternalId)) {
                    throw new IllegalArgumentException("superseded review has invalid persisted state");
                }
            }
        }
    }

    private static boolean isValidFailedAttemptState(ReviewAttemptState lastAttemptState, int attemptCount,
                                                     int maxReviewAttempts,
                                                     List<ReviewFinding> findings,
                                                     boolean allFindingsHaveDecisions,
                                                     boolean hasExternalId) {
        if (lastAttemptState == ReviewAttemptState.TERMINAL_FAILURE) {
            return findings.isEmpty() && !hasExternalId;
        }
        if (lastAttemptState == ReviewAttemptState.TRANSIENT_FAILURE) {
            return attemptCount == maxReviewAttempts && findings.isEmpty() && !hasExternalId;
        }
        return lastAttemptState == ReviewAttemptState.SUCCEEDED && allFindingsHaveDecisions;
    }

    private static boolean isValidSupersededAttemptState(ReviewAttemptState lastAttemptState,
                                                         List<ReviewFinding> findings,
                                                         boolean noFindingsHaveDecisions,
                                                         boolean allFindingsHaveDecisions,
                                                         boolean hasExternalId) {
        if (lastAttemptState == null || lastAttemptState == ReviewAttemptState.CANCELLED) {
            return findings.isEmpty() && !hasExternalId;
        }
        return lastAttemptState == ReviewAttemptState.SUCCEEDED
                && (noFindingsHaveDecisions || allFindingsHaveDecisions)
                && (!hasExternalId || allFindingsHaveDecisions);
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
