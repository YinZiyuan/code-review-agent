# GitHub Review Domain Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the framework-free Review Operations domain model for `ReviewRun`, findings, publication policy, and developer feedback with exhaustive state and architecture tests.

**Architecture:** Add a new `reviewops.domain` package that owns lifecycle and publication rules without Spring, persistence, GitHub, or LangChain4j dependencies. Existing `CodeReviewAgent` and CLI behavior remain unchanged; later slices will map existing `ReviewResult` into these domain objects through Application adapters.

**Tech Stack:** Java 17, JUnit 5, AssertJ, ArchUnit 1.5.0, Maven.

**Spec:** `docs/superpowers/specs/2026-08-29-github-app-production-review-design.md`

## Global Constraints

- Preserve the existing deterministic `DiffAnalyzer → ToolFindingsProducer → LlmReviewer → Summarizer` pipeline and `CodeReviewAgent` façade.
- `reviewops.domain` must not import Spring, JDBC, GitHub libraries, LangChain4j, or `reviewops.infrastructure`.
- One `ReviewRun` contains multiple technical `ReviewAttempt` entities; retry never creates a new business run.
- `ReviewFinding.line` semantics remain post-change file lines derived from `DiffParser`.
- A stale or `SUPERSEDED` run can never enter publication.
- Domain behavior receives authoritative external answers as supplied facts; it does not call GitHub or another external port.
- All collections exposed from Domain objects are immutable snapshots.
- Tests are written before implementation and every task ends with its own commit.
- This plan implements Delivery Slice 1 only; PostgreSQL, Webhook, workers, and GitHub adapters get separate plans after this slice passes review.

## Architecture Gate

- **Gate level:** Level 3 foundation for a new Review Operations bounded context.
- **Bounded context / capability:** Review Operations; review lifecycle, publication authorization, and finding feedback.
- **Stable language / authority:** `ReviewRun`, `ReviewAttempt`, `ReviewFinding`, `PublicationDecision`, `FindingFeedback`; GitHub remains authoritative for head SHA and reactions.
- **Affected aggregates/services:** `ReviewRun`, `FindingFeedback`, `FindingPublicationPolicy`.
- **Invariants:** valid transitions only; one active attempt; immutable completed findings; stale runs never publish; publication decisions are versioned and deterministic; feedback never mutates findings.
- **Technical classification:** Domain-facing rules in `reviewops.domain`; Application orchestration and all mechanisms remain outside this plan.
- **Layer ownership:** Domain.
- **New inward interfaces:** none.
- **Domain-owned ports:** none; `AuthoritativeRevision` and reaction observations are supplied facts.
- **Cross-aggregate decisions:** none; feedback references a finding identity but never loads or mutates `ReviewRun` inside the Aggregate.
- **Domain event:** `ReviewRunCompleted`, consumed later by `DecideReviewPublicationHandler`; this plan only collects/drains the event.
- **Proceed:** yes; strategic and tactical artifacts are accepted.

## File Map

All production files live under:

`src/main/java/dev/langchain4j/example/codereview/reviewops/domain/`

All unit tests live under:

`src/test/java/dev/langchain4j/example/codereview/reviewops/domain/`

The new files are grouped by behavior rather than by technical mechanism:

- Identity/configuration: `ReviewRunId`, `PullRequestRevision`, `AuthoritativeRevision`, `ReviewConfigurationSnapshot`, `ReviewRunState`.
- Attempts: `ReviewAttempt`, `ReviewAttemptState`, `ReviewFailure`, `FailureClass`, `ExecutionMeasurements`.
- Findings: `ReviewFinding`, `FindingFingerprint`, `FindingFingerprintFactory`, content/evidence/location values, publication values.
- Aggregate/event: `ReviewRun`, `DomainEvent`, `ReviewRunCompleted`.
- Policy: `PublicationPolicySnapshot`, `FindingPublicationPolicy`.
- Feedback: `FindingFeedback`, its identity/reference/actor/observation/audit values.
- Architecture test: `ReviewOperationsArchitectureTest` plus the ArchUnit test dependency in `pom.xml`.

---

### Task 1: Review identity and immutable configuration

**Files:**

- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRunId.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/PullRequestRevision.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/AuthoritativeRevision.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewConfigurationSnapshot.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRunState.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewIdentityTest.java`

**Interfaces:**

- Consumes: Java records and `UUID` only.
- Produces: `ReviewRunId.newId()`, `PullRequestRevision`, `AuthoritativeRevision`, `ReviewConfigurationSnapshot`, `ReviewRunState` for all later tasks.

- [ ] **Step 1: Write the failing identity/configuration tests**

```java
package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewIdentityTest {

    @Test
    void reviewRunIdCreatesNonNullUuid() {
        assertThat(ReviewRunId.newId().value()).isNotNull();
    }

    @Test
    void pullRequestRevisionRejectsInvalidIdentity() {
        assertThatThrownBy(() -> new PullRequestRevision(0, 2, 3, "abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PullRequestRevision(1, 2, 0, "abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PullRequestRevision(1, 2, 3, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configurationRequiresTwoRetriesAsThreeTotalAttempts() {
        ReviewConfigurationSnapshot snapshot =
                new ReviewConfigurationSnapshot("w4-tuned", "moonshot-v1-8k", "publish-v1", 3);

        assertThat(snapshot.maxReviewAttempts()).isEqualTo(3);
        assertThatThrownBy(() ->
                new ReviewConfigurationSnapshot("w4-tuned", "moonshot-v1-8k", "publish-v1", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authoritativeRevisionComparesByHeadSha() {
        PullRequestRevision revision = new PullRequestRevision(1, 2, 3, "abc123");
        assertThat(new AuthoritativeRevision("abc123").matches(revision)).isTrue();
        assertThat(new AuthoritativeRevision("def456").matches(revision)).isFalse();
    }
}
```

- [ ] **Step 2: Run the test and verify it fails because the domain types do not exist**

Run: `mvn -q -Dtest=ReviewIdentityTest test`

Expected: test compilation fails with missing `ReviewRunId`, `PullRequestRevision`, or related symbols.

- [ ] **Step 3: Implement the immutable identity/configuration values**

```java
// ReviewRunId.java
package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.Objects;
import java.util.UUID;

public record ReviewRunId(UUID value) {
    public ReviewRunId { Objects.requireNonNull(value, "value"); }
    public static ReviewRunId newId() { return new ReviewRunId(UUID.randomUUID()); }
}
```

```java
// PullRequestRevision.java
package dev.langchain4j.example.codereview.reviewops.domain;

public record PullRequestRevision(
        long installationId, long repositoryId, int pullRequestNumber, String headSha) {
    public PullRequestRevision {
        if (installationId <= 0 || repositoryId <= 0 || pullRequestNumber <= 0) {
            throw new IllegalArgumentException("GitHub identities must be positive");
        }
        if (headSha == null || headSha.isBlank()) {
            throw new IllegalArgumentException("headSha must not be blank");
        }
    }
}
```

```java
// AuthoritativeRevision.java
package dev.langchain4j.example.codereview.reviewops.domain;

public record AuthoritativeRevision(String headSha) {
    public AuthoritativeRevision {
        if (headSha == null || headSha.isBlank()) {
            throw new IllegalArgumentException("headSha must not be blank");
        }
    }

    public boolean matches(PullRequestRevision revision) {
        return headSha.equals(revision.headSha());
    }
}
```

```java
// ReviewConfigurationSnapshot.java
package dev.langchain4j.example.codereview.reviewops.domain;

public record ReviewConfigurationSnapshot(
        String pipelineVersion, String modelName, String policyVersion, int maxReviewAttempts) {
    public ReviewConfigurationSnapshot {
        requireText(pipelineVersion, "pipelineVersion");
        requireText(modelName, "modelName");
        requireText(policyVersion, "policyVersion");
        if (maxReviewAttempts < 1) {
            throw new IllegalArgumentException("maxReviewAttempts must be at least 1");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
```

```java
// ReviewRunState.java
package dev.langchain4j.example.codereview.reviewops.domain;

public enum ReviewRunState {
    REQUESTED, RUNNING, COMPLETED, PUBLISHING, PUBLISHED, FAILED, SUPERSEDED
}
```

- [ ] **Step 4: Run the focused test**

Run: `mvn -q -Dtest=ReviewIdentityTest test`

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/domain \
  src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewIdentityTest.java
git commit -m "feat(reviewops): add review identity values"
```

---

### Task 2: ReviewAttempt lifecycle and failure evidence

**Files:**

- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FailureClass.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewFailure.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ExecutionMeasurements.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewAttemptState.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewAttempt.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewAttemptTest.java`

**Interfaces:**

- Consumes: no prior domain behavior.
- Produces: `ReviewAttempt.start`, `succeed`, `failTransient`, `failTerminal`, and immutable execution evidence used by `ReviewRun`.

- [ ] **Step 1: Write failing attempt lifecycle tests**

```java
package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewAttemptTest {
    private static final Instant START = Instant.parse("2026-08-30T00:00:00Z");
    private static final Instant END = START.plusSeconds(5);
    private static final ExecutionMeasurements METRICS =
            new ExecutionMeasurements(5000, 100, 20, Map.of("spotbugs", "RAN"));

    @Test
    void successfulAttemptIsTerminal() {
        ReviewAttempt attempt = ReviewAttempt.start(1, START);
        attempt.succeed(METRICS, END);

        assertThat(attempt.state()).isEqualTo(ReviewAttemptState.SUCCEEDED);
        assertThat(attempt.measurements()).contains(METRICS);
        assertThatThrownBy(() -> attempt.failTransient(
                new ReviewFailure("timeout", FailureClass.TRANSIENT, "timed out"), METRICS, END))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureMethodsRejectWrongFailureClass() {
        ReviewAttempt attempt = ReviewAttempt.start(1, START);
        ReviewFailure terminal = new ReviewFailure("bad_diff", FailureClass.TERMINAL, "bad diff");

        assertThatThrownBy(() -> attempt.failTransient(terminal, METRICS, END))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void measurementsAreDefensivelyCopied() {
        ExecutionMeasurements measurements =
                new ExecutionMeasurements(1, 2, 3, new java.util.HashMap<>(Map.of("regex", "RAN")));
        assertThatThrownBy(() -> measurements.toolStates().put("spotbugs", "FAILED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

- [ ] **Step 2: Run the test and confirm missing-type compilation failure**

Run: `mvn -q -Dtest=ReviewAttemptTest test`

Expected: compilation fails for `ReviewAttempt` and related types.

- [ ] **Step 3: Implement failure values and ReviewAttempt**

```java
// FailureClass.java
package dev.langchain4j.example.codereview.reviewops.domain;

public enum FailureClass { TRANSIENT, TERMINAL }
```

```java
// ReviewFailure.java
package dev.langchain4j.example.codereview.reviewops.domain;

public record ReviewFailure(String code, FailureClass classification, String safeMessage) {
    public ReviewFailure {
        if (code == null || code.isBlank() || safeMessage == null || safeMessage.isBlank()) {
            throw new IllegalArgumentException("failure code and safeMessage are required");
        }
        java.util.Objects.requireNonNull(classification, "classification");
    }
}
```

```java
// ExecutionMeasurements.java
package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.Map;

public record ExecutionMeasurements(
        long latencyMs, int inputTokens, int outputTokens, Map<String, String> toolStates) {
    public ExecutionMeasurements {
        if (latencyMs < 0 || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("measurements must be non-negative");
        }
        toolStates = toolStates == null ? Map.of() : Map.copyOf(toolStates);
    }
}
```

```java
// ReviewAttemptState.java
package dev.langchain4j.example.codereview.reviewops.domain;

public enum ReviewAttemptState {
    STARTED, SUCCEEDED, TRANSIENT_FAILURE, TERMINAL_FAILURE
}
```

```java
// ReviewAttempt.java
package dev.langchain4j.example.codereview.reviewops.domain;

import java.time.Instant;
import java.util.Optional;

public final class ReviewAttempt {
    private final int attemptNumber;
    private final Instant startedAt;
    private ReviewAttemptState state;
    private Instant endedAt;
    private ExecutionMeasurements measurements;
    private ReviewFailure failure;

    private ReviewAttempt(int attemptNumber, Instant startedAt) {
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        this.attemptNumber = attemptNumber;
        this.startedAt = java.util.Objects.requireNonNull(startedAt, "startedAt");
        this.state = ReviewAttemptState.STARTED;
    }

    public static ReviewAttempt start(int attemptNumber, Instant startedAt) {
        return new ReviewAttempt(attemptNumber, startedAt);
    }

    public void succeed(ExecutionMeasurements measurements, Instant endedAt) {
        finish(ReviewAttemptState.SUCCEEDED, measurements, null, endedAt);
    }

    public void failTransient(ReviewFailure failure, ExecutionMeasurements measurements, Instant endedAt) {
        requireFailureClass(failure, FailureClass.TRANSIENT);
        finish(ReviewAttemptState.TRANSIENT_FAILURE, measurements, failure, endedAt);
    }

    public void failTerminal(ReviewFailure failure, ExecutionMeasurements measurements, Instant endedAt) {
        requireFailureClass(failure, FailureClass.TERMINAL);
        finish(ReviewAttemptState.TERMINAL_FAILURE, measurements, failure, endedAt);
    }

    private void finish(ReviewAttemptState next, ExecutionMeasurements measurements,
                        ReviewFailure failure, Instant endedAt) {
        if (state != ReviewAttemptState.STARTED) throw new IllegalStateException("attempt is terminal");
        this.measurements = java.util.Objects.requireNonNull(measurements, "measurements");
        this.endedAt = java.util.Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) throw new IllegalArgumentException("endedAt precedes startedAt");
        this.failure = failure;
        this.state = next;
    }

    private static void requireFailureClass(ReviewFailure failure, FailureClass expected) {
        if (failure == null || failure.classification() != expected) {
            throw new IllegalArgumentException("failure classification must be " + expected);
        }
    }

    public int attemptNumber() { return attemptNumber; }
    public Instant startedAt() { return startedAt; }
    public ReviewAttemptState state() { return state; }
    public Optional<Instant> endedAt() { return Optional.ofNullable(endedAt); }
    public Optional<ExecutionMeasurements> measurements() { return Optional.ofNullable(measurements); }
    public Optional<ReviewFailure> failure() { return Optional.ofNullable(failure); }
}
```

- [ ] **Step 4: Run the attempt tests**

Run: `mvn -q -Dtest=ReviewAttemptTest test`

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/domain \
  src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewAttemptTest.java
git commit -m "feat(reviewops): model review attempts"
```

---

### Task 3: Identified findings and stable fingerprinting

**Files:**

- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FindingSeverity.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FindingCategory.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/CodeLocation.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/CitationEvidence.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FindingContent.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FindingEvidence.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FindingFingerprint.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FindingFingerprintFactory.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/PublicationTier.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/PublicationDecision.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/PublicationReference.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewFinding.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewFindingTest.java`

**Interfaces:**

- Consumes: `ReviewRunId` only indirectly in later tasks.
- Produces: stable `FindingFingerprint`, immutable finding content/evidence, one-time publication decisions and references.

- [ ] **Step 1: Write failing finding identity and immutability tests**

```java
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

        FindingFingerprint first = factory.create(new CodeLocation("./src\\Foo.java", 10, true), contentA, evidence);
        FindingFingerprint second = factory.create(new CodeLocation("src/Foo.java", 99, true), contentB, evidence);

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
```

- [ ] **Step 2: Run the finding test and verify missing-type compilation failure**

Run: `mvn -q -Dtest=ReviewFindingTest test`

Expected: compilation fails for the new finding types.

- [ ] **Step 3: Implement finding values and fingerprint algorithm**

Create the enums exactly as follows:

```java
public enum FindingSeverity { CRITICAL, WARNING, SUGGESTION }
public enum FindingCategory { SECURITY, PERFORMANCE, STABILITY, CONCURRENCY, TEST, STYLE, OTHER }
public enum PublicationTier { INLINE_COMMENT, CHECK_SUMMARY, RETAIN_ONLY }
```

Each enum is a public type in its matching filename and package.

Implement the records:

```java
public record CodeLocation(String file, int line, boolean changedLine) {
    public CodeLocation {
        if (file == null || file.isBlank()) throw new IllegalArgumentException("file is required");
        if (line < 1) throw new IllegalArgumentException("line must be positive");
    }
}

public record CitationEvidence(String id, String source, String section) {
    public CitationEvidence {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("citation id is required");
    }
}

public record FindingContent(FindingSeverity severity, FindingCategory category,
                             String title, String description, String suggestion) {
    public FindingContent {
        java.util.Objects.requireNonNull(severity, "severity");
        java.util.Objects.requireNonNull(category, "category");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        description = description == null ? "" : description;
        suggestion = suggestion == null ? "" : suggestion;
    }
}

public record FindingEvidence(String evidence, java.util.List<CitationEvidence> citations, String source) {
    public FindingEvidence {
        evidence = evidence == null ? "" : evidence;
        citations = citations == null ? java.util.List.of() : java.util.List.copyOf(citations);
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
    }
}

public record FindingFingerprint(String value) {
    public FindingFingerprint {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint must be lowercase SHA-256 hex");
        }
    }
}

public record PublicationDecision(PublicationTier tier, String policyVersion) {
    public PublicationDecision {
        java.util.Objects.requireNonNull(tier, "tier");
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion is required");
        }
    }
}

public record PublicationReference(String artifactType, String externalId) {
    public PublicationReference {
        if (artifactType == null || artifactType.isBlank() || externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("artifactType and externalId are required");
        }
    }
}
```

Use the matching package declaration and one public record per matching file.

Implement `FindingFingerprintFactory` with the spec's normalization:

```java
package dev.langchain4j.example.codereview.reviewops.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class FindingFingerprintFactory {
    public FindingFingerprint create(CodeLocation location, FindingContent content, FindingEvidence evidence) {
        String canonical = normalizePath(location.file()) + "\n"
                + content.category().name() + "\n"
                + normalizeText(content.title()) + "\n"
                + normalizeText(evidence.evidence());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new FindingFingerprint(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String normalizePath(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized.replaceAll("/+", "/");
    }

    private static String normalizeText(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
```

Implement `ReviewFinding`:

```java
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

    public void acceptPublicationDecision(PublicationDecision decision) {
        if (publicationDecision != null) throw new IllegalStateException("decision already assigned");
        publicationDecision = Objects.requireNonNull(decision, "decision");
    }

    public void recordPublicationReference(PublicationReference reference) {
        if (publicationDecision == null || publicationDecision.tier() != PublicationTier.INLINE_COMMENT) {
            throw new IllegalStateException("only an inline finding may record a comment reference");
        }
        if (publicationReference != null) throw new IllegalStateException("reference already assigned");
        publicationReference = Objects.requireNonNull(reference, "reference");
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
```

- [ ] **Step 4: Run the finding tests**

Run: `mvn -q -Dtest=ReviewFindingTest test`

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/domain \
  src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewFindingTest.java
git commit -m "feat(reviewops): identify review findings"
```

---

### Task 4: ReviewRun Aggregate and completion event

**Files:**

- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/DomainEvent.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRunCompleted.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRun.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRunTest.java`

**Interfaces:**

- Consumes: all identity, attempt, failure, finding, decision, and reference types from Tasks 1–3.
- Produces: the complete `ReviewRun` lifecycle API and drained `ReviewRunCompleted` event for later Application work.

- [ ] **Step 1: Write a failing transition-table test suite**

Create `ReviewRunTest` with these concrete tests and shared helpers:

```java
package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRunTest {
    private static final Instant T0 = Instant.parse("2026-08-30T00:00:00Z");
    private static final ExecutionMeasurements METRICS =
            new ExecutionMeasurements(10, 1, 1, Map.of());

    @Test
    void transientFailureReturnsToRequestedUntilAttemptsExhausted() {
        ReviewRun run = requested(2);
        run.startAttempt(T0);
        run.recordTransientAttemptFailure(transientFailure(), METRICS, T0.plusSeconds(1));
        assertThat(run.state()).isEqualTo(ReviewRunState.REQUESTED);

        run.startAttempt(T0.plusSeconds(2));
        run.recordTransientAttemptFailure(transientFailure(), METRICS, T0.plusSeconds(3));
        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.attempts()).hasSize(2);
    }

    @Test
    void completingReviewFreezesFindingsAndRecordsOneEvent() {
        ReviewRun run = requested(3);
        run.startAttempt(T0);
        ReviewFinding finding = ReviewFindingTest.finding("regex", List.of());
        run.completeReview(List.of(finding), METRICS, T0.plusSeconds(1));

        assertThat(run.state()).isEqualTo(ReviewRunState.COMPLETED);
        assertThat(run.findings()).containsExactly(finding);
        assertThat(run.drainEvents()).containsExactly(
                new ReviewRunCompleted(run.id(), T0.plusSeconds(1)));
        assertThat(run.drainEvents()).isEmpty();
        assertThatThrownBy(() -> run.completeReview(List.of(), METRICS, T0.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void staleAuthoritativeRevisionSupersedesCompletedRun() {
        ReviewRun run = completed();
        run.authorizePublication(new AuthoritativeRevision("new-sha"), T0.plusSeconds(2));
        assertThat(run.state()).isEqualTo(ReviewRunState.SUPERSEDED);
        assertThatThrownBy(() -> run.confirmPublication("check-1", Map.of(), T0.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentRevisionCanPublishAfterEveryFindingHasDecision() {
        ReviewRun run = completed();
        Map<FindingFingerprint, PublicationDecision> decisions = Map.of(
                run.findings().get(0).fingerprint(),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "publish-v1"));
        run.acceptPublicationDecisions(decisions);
        run.authorizePublication(new AuthoritativeRevision("sha"), T0.plusSeconds(2));
        run.confirmPublication("check-1", Map.of(), T0.plusSeconds(3));

        assertThat(run.state()).isEqualTo(ReviewRunState.PUBLISHED);
        assertThat(run.checkRunExternalId()).contains("check-1");
    }

    @Test
    void decisionsMustCoverExactlyTheCompletedFindings() {
        ReviewRun run = completed();
        assertThatThrownBy(() -> run.acceptPublicationDecisions(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ReviewRun requested(int maxAttempts) {
        return ReviewRun.request(ReviewRunId.newId(),
                new PullRequestRevision(1, 2, 3, "sha"),
                new ReviewConfigurationSnapshot("pipeline", "model", "publish-v1", maxAttempts), T0);
    }

    private static ReviewRun completed() {
        ReviewRun run = requested(3);
        run.startAttempt(T0);
        run.completeReview(List.of(ReviewFindingTest.finding("regex", List.of())),
                METRICS, T0.plusSeconds(1));
        run.drainEvents();
        return run;
    }

    private static ReviewFailure transientFailure() {
        return new ReviewFailure("timeout", FailureClass.TRANSIENT, "timed out");
    }
}
```

- [ ] **Step 2: Run the aggregate tests and verify missing-type compilation failure**

Run: `mvn -q -Dtest=ReviewRunTest test`

Expected: compilation fails for `ReviewRun`, `DomainEvent`, and `ReviewRunCompleted`.

- [ ] **Step 3: Implement event types**

```java
// DomainEvent.java
package dev.langchain4j.example.codereview.reviewops.domain;

import java.time.Instant;

public interface DomainEvent {
    Instant occurredAt();
}
```

```java
// ReviewRunCompleted.java
package dev.langchain4j.example.codereview.reviewops.domain;

import java.time.Instant;
import java.util.Objects;

public record ReviewRunCompleted(ReviewRunId reviewRunId, Instant occurredAt) implements DomainEvent {
    public ReviewRunCompleted {
        Objects.requireNonNull(reviewRunId, "reviewRunId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
```

- [ ] **Step 4: Implement ReviewRun with explicit guards**

The class must have the exact public API exercised above plus:

```java
public void recordTerminalAttemptFailure(ReviewFailure failure,
        ExecutionMeasurements measurements, Instant endedAt)
public void recordPublicationFailure(ReviewFailure failure, Instant failedAt)
public void supersede(AuthoritativeRevision currentRevision, Instant supersededAt)
public Optional<ReviewFailure> finalFailure()
public Optional<Instant> finishedAt()
```

Implement behavior using these guard rules:

```java
private void requireState(ReviewRunState expected) {
    if (state != expected) {
        throw new IllegalStateException("expected " + expected + " but was " + state);
    }
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

public void completeReview(List<ReviewFinding> completedFindings,
        ExecutionMeasurements measurements, Instant completedAt) {
    requireState(ReviewRunState.RUNNING);
    currentAttempt().succeed(measurements, completedAt);
    Map<FindingFingerprint, ReviewFinding> unique = completedFindings.stream()
            .collect(java.util.stream.Collectors.toMap(
                    ReviewFinding::fingerprint, java.util.function.Function.identity(),
                    (left, right) -> { throw new IllegalArgumentException("duplicate fingerprint"); },
                    java.util.LinkedHashMap::new));
    findings = List.copyOf(unique.values());
    state = ReviewRunState.COMPLETED;
    events.add(new ReviewRunCompleted(id, completedAt));
}

public void acceptPublicationDecisions(Map<FindingFingerprint, PublicationDecision> decisions) {
    requireState(ReviewRunState.COMPLETED);
    java.util.Set<FindingFingerprint> expected = findings.stream()
            .map(ReviewFinding::fingerprint).collect(java.util.stream.Collectors.toSet());
    if (!expected.equals(decisions.keySet())) {
        throw new IllegalArgumentException("decisions must cover exactly all findings");
    }
    findings.forEach(f -> f.acceptPublicationDecision(decisions.get(f.fingerprint())));
}

public void authorizePublication(AuthoritativeRevision authoritative, Instant checkedAt) {
    requireState(ReviewRunState.COMPLETED);
    java.util.Objects.requireNonNull(checkedAt, "checkedAt");
    if (findings.stream().anyMatch(f -> f.publicationDecision().isEmpty())) {
        throw new IllegalStateException("publication decisions are incomplete");
    }
    state = authoritative.matches(revision)
            ? ReviewRunState.PUBLISHING : ReviewRunState.SUPERSEDED;
    if (state == ReviewRunState.SUPERSEDED) finishedAt = checkedAt;
}
```

`confirmPublication` requires `PUBLISHING`, checks that every supplied comment reference belongs to an `INLINE_COMMENT` finding, records each reference, stores the non-blank Check Run ID, sets `PUBLISHED`, and records `finishedAt`. `supersede` accepts only a nonmatching supplied revision and transitions `REQUESTED|RUNNING|COMPLETED|PUBLISHING` to `SUPERSEDED`; terminal states reject the call. `drainEvents` returns `List.copyOf(events)` and then clears the internal list. Every list/map getter returns `List.copyOf` or `Map.copyOf`.

- [ ] **Step 5: Run aggregate and preceding domain tests**

Run: `mvn -q -Dtest='ReviewIdentityTest,ReviewAttemptTest,ReviewFindingTest,ReviewRunTest' test`

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/domain \
  src/test/java/dev/langchain4j/example/codereview/reviewops/domain/ReviewRunTest.java
git commit -m "feat(reviewops): enforce review run lifecycle"
```

---

### Task 5: Deterministic FindingPublicationPolicy v1

**Files:**

- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/PublicationPolicySnapshot.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FindingPublicationPolicy.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/domain/FindingPublicationPolicyTest.java`

**Interfaces:**

- Consumes: completed `ReviewFinding` values from Task 3.
- Produces: `Map<FindingFingerprint, PublicationDecision> decide(List<ReviewFinding>, PublicationPolicySnapshot)` consumed by `ReviewRun.acceptPublicationDecisions`.

- [ ] **Step 1: Write failing policy tests for every tier and the five-comment cap**

```java
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
    void onlyFiveCandidatesRemainInline() {
        List<ReviewFinding> findings = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> finding("regex", FindingSeverity.WARNING, true, List.of(), "evidence " + i))
                .toList();
        long inline = policy.decide(findings, snapshot).values().stream()
                .filter(d -> d.tier() == PublicationTier.INLINE_COMMENT).count();
        assertThat(inline).isEqualTo(5);
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
}
```

- [ ] **Step 2: Run policy tests and verify missing-type compilation failure**

Run: `mvn -q -Dtest=FindingPublicationPolicyTest test`

Expected: compilation fails for `FindingPublicationPolicy` and `PublicationPolicySnapshot`.

- [ ] **Step 3: Implement policy snapshot and deterministic classification**

```java
// PublicationPolicySnapshot.java
package dev.langchain4j.example.codereview.reviewops.domain;

public record PublicationPolicySnapshot(String version, int maxInlineComments) {
    public PublicationPolicySnapshot {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version is required");
        if (maxInlineComments < 0) throw new IllegalArgumentException("maxInlineComments must not be negative");
    }
}
```

Implement `FindingPublicationPolicy.decide` as a two-pass algorithm:

```java
public Map<FindingFingerprint, PublicationDecision> decide(
        List<ReviewFinding> findings, PublicationPolicySnapshot snapshot) {
    List<ReviewFinding> inlineCandidates = findings.stream()
            .filter(this::isInlineCandidate)
            .sorted(priority())
            .toList();
    Set<FindingFingerprint> selected = inlineCandidates.stream()
            .limit(snapshot.maxInlineComments())
            .map(ReviewFinding::fingerprint)
            .collect(java.util.stream.Collectors.toSet());

    Map<FindingFingerprint, PublicationDecision> decisions = new java.util.LinkedHashMap<>();
    for (ReviewFinding finding : findings) {
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
    return Map.copyOf(decisions);
}
```

`isInlineCandidate` returns false for unchanged lines, `SUGGESTION`, or blank evidence. It returns true for `regex|spotbugs` with `CRITICAL|WARNING`; it returns true for other sources only with `CRITICAL|WARNING` and at least one citation. `priority` sorts by severity (`CRITICAL` before `WARNING`), deterministic source before model source, then file and line. Use explicit rank methods rather than enum ordinal.

- [ ] **Step 4: Run policy and aggregate tests**

Run: `mvn -q -Dtest='FindingPublicationPolicyTest,ReviewRunTest' test`

Expected: PASS.

- [ ] **Step 5: Commit Task 5**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/domain \
  src/test/java/dev/langchain4j/example/codereview/reviewops/domain/FindingPublicationPolicyTest.java
git commit -m "feat(reviewops): classify finding publication"
```

---

### Task 6: FindingFeedback Aggregate reconciliation

**Files:**

- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/GitHubActor.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/DeveloperVisibleFindingReference.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FindingFeedbackId.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FeedbackState.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/ObservedReaction.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FeedbackAuditEntry.java`
- Create: `src/main/java/dev/langchain4j/example/codereview/reviewops/domain/FindingFeedback.java`
- Test: `src/test/java/dev/langchain4j/example/codereview/reviewops/domain/FindingFeedbackTest.java`

**Interfaces:**

- Consumes: `ReviewRunId` and `FindingFingerprint` identity references.
- Produces: an independently persisted `FindingFeedback` root whose `reconcile(Optional<ObservedReaction>, Instant)` behavior supports scheduled GitHub reaction scans.

- [ ] **Step 1: Write failing feedback lifecycle tests**

```java
package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FindingFeedbackTest {
    private static final Instant T0 = Instant.parse("2026-08-30T00:00:00Z");
    private static final DeveloperVisibleFindingReference REF =
            new DeveloperVisibleFindingReference(ReviewRunId.newId(),
                    new FindingFingerprint("a".repeat(64)));
    private static final GitHubActor ACTOR = new GitHubActor(42, "octocat");

    @Test
    void reactionCanBeRevisedWithdrawnAndRestored() {
        FindingFeedback feedback = FindingFeedback.record(REF, ACTOR,
                new ObservedReaction(100, FeedbackState.HELPFUL, T0));
        feedback.reconcile(Optional.of(
                new ObservedReaction(101, FeedbackState.FALSE_POSITIVE, T0.plusSeconds(1))),
                T0.plusSeconds(1));
        feedback.reconcile(Optional.empty(), T0.plusSeconds(2));
        feedback.reconcile(Optional.of(
                new ObservedReaction(102, FeedbackState.HELPFUL, T0.plusSeconds(3))),
                T0.plusSeconds(3));

        assertThat(feedback.state()).isEqualTo(FeedbackState.HELPFUL);
        assertThat(feedback.audit()).extracting(FeedbackAuditEntry::current)
                .containsExactly(FeedbackState.HELPFUL, FeedbackState.FALSE_POSITIVE,
                        FeedbackState.WITHDRAWN, FeedbackState.HELPFUL);
    }

    @Test
    void repeatedObservationIsIdempotent() {
        ObservedReaction reaction = new ObservedReaction(100, FeedbackState.HELPFUL, T0);
        FindingFeedback feedback = FindingFeedback.record(REF, ACTOR, reaction);
        feedback.reconcile(Optional.of(reaction), T0.plusSeconds(1));
        assertThat(feedback.audit()).hasSize(1);
    }

    @Test
    void withdrawingAnAlreadyWithdrawnAssessmentIsIdempotent() {
        FindingFeedback feedback = FindingFeedback.record(REF, ACTOR,
                new ObservedReaction(100, FeedbackState.HELPFUL, T0));
        feedback.reconcile(Optional.empty(), T0.plusSeconds(1));
        feedback.reconcile(Optional.empty(), T0.plusSeconds(2));
        assertThat(feedback.audit()).hasSize(2);
    }
}
```

- [ ] **Step 2: Run feedback tests and verify missing-type compilation failure**

Run: `mvn -q -Dtest=FindingFeedbackTest test`

Expected: compilation fails for the feedback types.

- [ ] **Step 3: Implement feedback values**

```java
public enum FeedbackState { HELPFUL, FALSE_POSITIVE, WITHDRAWN }

public record GitHubActor(long id, String login) {
    public GitHubActor {
        if (id <= 0 || login == null || login.isBlank()) {
            throw new IllegalArgumentException("valid GitHub actor is required");
        }
    }
}

public record DeveloperVisibleFindingReference(
        ReviewRunId reviewRunId, FindingFingerprint findingFingerprint) {
    public DeveloperVisibleFindingReference {
        java.util.Objects.requireNonNull(reviewRunId, "reviewRunId");
        java.util.Objects.requireNonNull(findingFingerprint, "findingFingerprint");
    }
}

public record FindingFeedbackId(
        ReviewRunId reviewRunId, FindingFingerprint findingFingerprint, long actorId) {
    public FindingFeedbackId {
        java.util.Objects.requireNonNull(reviewRunId, "reviewRunId");
        java.util.Objects.requireNonNull(findingFingerprint, "findingFingerprint");
        if (actorId <= 0) throw new IllegalArgumentException("actorId must be positive");
    }
}

public record ObservedReaction(long reactionId, FeedbackState classification, java.time.Instant createdAt) {
    public ObservedReaction {
        if (reactionId <= 0) throw new IllegalArgumentException("reactionId must be positive");
        if (classification == FeedbackState.WITHDRAWN) {
            throw new IllegalArgumentException("absence, not a reaction, represents withdrawal");
        }
        java.util.Objects.requireNonNull(classification, "classification");
        java.util.Objects.requireNonNull(createdAt, "createdAt");
    }
}

public record FeedbackAuditEntry(
        FeedbackState previous, FeedbackState current, java.time.Instant changedAt, Long reactionId) {
    public FeedbackAuditEntry {
        java.util.Objects.requireNonNull(current, "current");
        java.util.Objects.requireNonNull(changedAt, "changedAt");
    }
}
```

Put each public declaration in its matching file and package.

- [ ] **Step 4: Implement FindingFeedback reconciliation**

```java
package dev.langchain4j.example.codereview.reviewops.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class FindingFeedback {
    private final FindingFeedbackId id;
    private final DeveloperVisibleFindingReference findingReference;
    private final GitHubActor actor;
    private final List<FeedbackAuditEntry> audit = new ArrayList<>();
    private FeedbackState state;
    private Long reactionId;

    private FindingFeedback(DeveloperVisibleFindingReference reference,
                            GitHubActor actor, ObservedReaction reaction) {
        this.findingReference = Objects.requireNonNull(reference, "reference");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.id = new FindingFeedbackId(reference.reviewRunId(),
                reference.findingFingerprint(), actor.id());
        apply(reaction.classification(), reaction.reactionId(), reaction.createdAt());
    }

    public static FindingFeedback record(DeveloperVisibleFindingReference reference,
                                         GitHubActor actor, ObservedReaction reaction) {
        return new FindingFeedback(reference, actor, Objects.requireNonNull(reaction, "reaction"));
    }

    public void reconcile(Optional<ObservedReaction> observation, Instant observedAt) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(observedAt, "observedAt");
        if (observation.isEmpty()) {
            if (state != FeedbackState.WITHDRAWN) apply(FeedbackState.WITHDRAWN, null, observedAt);
            return;
        }
        ObservedReaction reaction = observation.get();
        if (Objects.equals(reactionId, reaction.reactionId()) && state == reaction.classification()) return;
        apply(reaction.classification(), reaction.reactionId(), observedAt);
    }

    private void apply(FeedbackState next, Long nextReactionId, Instant changedAt) {
        audit.add(new FeedbackAuditEntry(state, next, changedAt, nextReactionId));
        state = next;
        reactionId = nextReactionId;
    }

    public FindingFeedbackId id() { return id; }
    public DeveloperVisibleFindingReference findingReference() { return findingReference; }
    public GitHubActor actor() { return actor; }
    public FeedbackState state() { return state; }
    public Optional<Long> reactionId() { return Optional.ofNullable(reactionId); }
    public List<FeedbackAuditEntry> audit() { return List.copyOf(audit); }
}
```

The GitHub adapter, not this Aggregate, resolves multiple supported reactions from one actor by newest `createdAt`, then highest reaction ID as the deterministic tie-breaker. It supplies only that current observation or absence.

- [ ] **Step 5: Run feedback and all domain tests**

Run: `mvn -q -Dtest='ReviewIdentityTest,ReviewAttemptTest,ReviewFindingTest,ReviewRunTest,FindingPublicationPolicyTest,FindingFeedbackTest' test`

Expected: PASS.

- [ ] **Step 6: Commit Task 6**

```bash
git add src/main/java/dev/langchain4j/example/codereview/reviewops/domain \
  src/test/java/dev/langchain4j/example/codereview/reviewops/domain/FindingFeedbackTest.java
git commit -m "feat(reviewops): reconcile finding feedback"
```

---

### Task 7: Enforce Domain dependency boundaries and run the slice gate

**Files:**

- Modify: `pom.xml`
- Create: `src/test/java/dev/langchain4j/example/codereview/reviewops/ReviewOperationsArchitectureTest.java`

**Interfaces:**

- Consumes: all production packages created in Tasks 1–6.
- Produces: an executable architecture constraint preventing framework/mechanism leakage into `reviewops.domain`.

- [ ] **Step 1: Add a failing ArchUnit test before adding its dependency**

```java
package dev.langchain4j.example.codereview.reviewops;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "dev.langchain4j.example.codereview.reviewops",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ReviewOperationsArchitectureTest {

    @ArchTest
    static final ArchRule domain_has_no_framework_or_mechanism_dependencies = noClasses()
            .that().resideInAPackage("..reviewops.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "dev.langchain4j.model..",
                    "dev.langchain4j.service..",
                    "dev.langchain4j.data..",
                    "dev.langchain4j.rag..",
                    "dev.langchain4j.store..",
                    "..reviewops.application..",
                    "..reviewops.infrastructure..");
}
```

- [ ] **Step 2: Run the architecture test and confirm dependency compilation failure**

Run: `mvn -q -Dtest=ReviewOperationsArchitectureTest test`

Expected: test compilation fails because ArchUnit is not yet declared.

- [ ] **Step 3: Add ArchUnit 1.5.0 as a test-only dependency**

Add inside `<dependencies>` in `pom.xml`:

```xml
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>1.5.0</version>
    <scope>test</scope>
</dependency>
```

Version 1.5.0 is the current official ArchUnit release checked when this plan was written. Source: [TNG/ArchUnit releases](https://github.com/TNG/ArchUnit/releases).

- [ ] **Step 4: Run the architecture test**

Run: `mvn -q -Dtest=ReviewOperationsArchitectureTest test`

Expected: PASS.

- [ ] **Step 5: Run the complete unit suite**

Run: `mvn test`

Expected: BUILD SUCCESS with all existing tests and new Review Operations tests passing. Live model integration tests must remain skipped or isolated according to existing project conventions; no `MOONSHOT_API_KEY` is required for ordinary unit tests.

- [ ] **Step 6: Build the fat jar without tests and smoke the unchanged CLI help**

Run: `mvn -q clean package -DskipTests`

Expected: exit code 0 and `target/code-review-agent-1.0.0.jar` exists.

Run: `java -jar target/code-review-agent-1.0.0.jar --help`

Expected: help still lists `review`, `eval`, and `sample`; no server command is introduced in this slice.

- [ ] **Step 7: Commit Task 7**

```bash
git add pom.xml \
  src/test/java/dev/langchain4j/example/codereview/reviewops/ReviewOperationsArchitectureTest.java
git commit -m "test(reviewops): enforce domain boundaries"
```

## Slice Completion Review

Before starting the PostgreSQL/persistent-job plan, verify all of the following from the committed code:

- [ ] Every `ReviewRun` transition from the accepted `domain-objects.md` is represented and tested.
- [ ] `ReviewAttempt` is the only retry unit; no retry creates another `ReviewRun`.
- [ ] Every completed finding has one stable fingerprint and accepts at most one decision/reference.
- [ ] `FindingPublicationPolicy` implements all five v1 rules and the five-inline cap.
- [ ] `FindingFeedback` distinguishes never observed, active assessment, and withdrawn assessment.
- [ ] `ReviewRunCompleted` is collected by Domain and drained once; no handler or infrastructure is added yet.
- [ ] Domain has no external/framework imports according to ArchUnit.
- [ ] `mvn test` and the fat-jar CLI smoke pass.
- [ ] Review the implementation against `docs/ddd-expert/context/review-operations/domain-objects.md`; any necessary business-model correction returns to Tactical Design rather than being hidden in code.
