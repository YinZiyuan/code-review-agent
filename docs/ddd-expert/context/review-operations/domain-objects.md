# Review Operations Domain Objects

## ReviewRun

### ReviewRun — Aggregate Root (`ReviewRunID`)

- **Definition:** One business review of a specific pull-request revision under immutable pipeline, model, and publication-policy configuration; it exclusively owns review lifecycle and publication authority.
- **Facts:**
  - `PullRequestRevision` — GitHub installation, repository, pull-request number, and head SHA governed by the run.
  - `ReviewConfigurationSnapshot` — immutable pipeline, model, and publication-policy versions used by the run.
  - `ReviewAttempts` — ordered execution attempts belonging to this review intent.
  - `ReviewFindings` — the immutable completed review result, with later publication decisions and artifact references attached to the identified findings.
  - `ExecutionOutcome` — final failure classification or successful execution statistics and timestamps.
  - `PublicationOutcome` — accepted publication decisions, GitHub artifact references, and publication timestamps.
- **Lifecycle State:**
  - `REQUESTED` — admitted and eligible for an execution attempt.
  - `RUNNING` — one execution attempt is active.
  - `COMPLETED` — an immutable review result exists and publication decisions may be formed.
  - `PUBLISHING` — publication was authorized against the latest authoritative revision and is being reconciled with GitHub.
  - `PUBLISHED` — the authorized GitHub artifacts were successfully created or idempotently confirmed; terminal.
  - `FAILED` — execution or publication reached a non-retryable or exhausted failure; terminal and non-blocking by default.
  - `SUPERSEDED` — a newer pull-request revision permanently removed this run's publication authority; terminal.
- **Behavior:**
  - `Start review attempt` — ReviewRun starts a new ReviewAttempt, transitioning ReviewRun state from `REQUESTED` to `RUNNING`.
  - `Record transient attempt failure` — ReviewRun closes the active ReviewAttempt and, when retry allowance remains, transitions ReviewRun state from `RUNNING` to `REQUESTED`.
  - `Record terminal failure` — ReviewRun records an exhausted or deterministic failure, transitioning ReviewRun state from `RUNNING` or `PUBLISHING` to `FAILED`.
  - `Complete review` — ReviewRun closes the active ReviewAttempt and accepts identified ReviewFindings, transitioning ReviewRun state from `RUNNING` to `COMPLETED`.
  - `Accept publication decisions` — ReviewRun attaches one immutable PublicationDecision to each completed ReviewFinding.
  - `Authorize publication` — ReviewRun compares a supplied AuthoritativeRevision with its PullRequestRevision, transitioning from `COMPLETED` to `PUBLISHING` when current or to `SUPERSEDED` when stale.
  - `Confirm publication` — ReviewRun records reconciled GitHub artifact references, transitioning ReviewRun state from `PUBLISHING` to `PUBLISHED`.
  - `Supersede obsolete work` — ReviewRun accepts that a newer revision exists, transitioning an active pre-publication state to `SUPERSEDED`.
- **Domain-owned Ports:** None; authoritative GitHub revision is supplied as a Domain Fact and external mechanisms remain outside the Aggregate.
- **Domain Events:**
  - `ReviewRunCompleted` — recorded by `Complete review`.
    - **Consumed by:** `DecideReviewPublicationHandler` — form and persist publication decisions, then request asynchronous publication.

### ReviewAttempt — Entity (`AttemptNumber` within `ReviewRun`)

- **Definition:** One ordered technical execution attempt for the ReviewRun's unchanged business review intent; it never owns findings or publication authority.
- **Facts:**
  - `Timing` — attempt start and completion times.
  - `ExecutionResult` — success or classified transient/terminal failure.
  - `ExecutionMeasurements` — latency, token usage, and tool states needed for audit and operations.
- **Lifecycle State:**
  - `STARTED` — execution is active.
  - `SUCCEEDED` — execution produced an accepted review result; terminal.
  - `TRANSIENT_FAILURE` — execution ended with a retry-eligible failure; terminal.
  - `TERMINAL_FAILURE` — execution ended with a deterministic or exhausted failure; terminal.
- **Behavior:**
  - `Record successful execution` — ReviewAttempt records successful measurements, transitioning ReviewAttempt state from `STARTED` to `SUCCEEDED`.
  - `Record transient execution failure` — ReviewAttempt records retry-eligible failure evidence, transitioning ReviewAttempt state from `STARTED` to `TRANSIENT_FAILURE`.
  - `Record terminal execution failure` — ReviewAttempt records non-retryable failure evidence, transitioning ReviewAttempt state from `STARTED` to `TERMINAL_FAILURE`.
- **Domain-owned Ports:** None.
- **Domain Events:** None.

### ReviewFinding — Entity (`FindingFingerprint` within `ReviewRun`)

- **Definition:** One stable, independently publishable and feedback-addressable issue inside a completed ReviewRun.
- **Facts:**
  - `FindingContent` — immutable structured issue content, category, severity, explanation, and suggested correction.
  - `CodeLocation` — normalized file path and post-change line location used for developer presentation.
  - `FindingEvidence` — deterministic tool evidence, citation evidence, and generation source.
  - `PublicationDecision` — immutable visibility assigned by the accepted publication policy.
  - `PublicationReference` — the reconciled GitHub artifact identity when the finding is externally visible.
- **Lifecycle State:** No explicit Lifecycle State; its original review content is immutable and its existence follows the ReviewRun lifecycle.
- **Behavior:**
  - `Accept publication decision` — ReviewFinding accepts its one PublicationDecision without changing original review content.
  - `Record publication reference` — ReviewFinding records the idempotently reconciled GitHub artifact that presents it.
- **Domain-owned Ports:** None.
- **Domain Events:** None.

### FindingPublicationPolicy — Domain Service

- **Definition:** The deterministic Review Operations rule that assigns developer-visible treatment to completed findings under one versioned policy snapshot and run-level publication constraints.
- **Behavior:**
  - `Classify finding visibility` — FindingPublicationPolicy evaluates ReviewFinding evidence and returns `INLINE_COMMENT`, `CHECK_SUMMARY`, or `RETAIN_ONLY` as a PublicationDecision.
  - `Apply run-level publication constraints` — FindingPublicationPolicy resolves constraints shared by a run, such as the accepted maximum number of inline comments, without calling external mechanisms.
- **Domain-owned Ports:** None.
- **Domain Events:** None.

## FindingFeedback

### FindingFeedback — Aggregate Root (`ReviewRunID + FindingFingerprint + GitHubActor`)

- **Definition:** One GitHub developer's current explicit assessment of one developer-visible ReviewFinding, retaining withdrawal and revision audit without modifying the original review result.
- **Facts:**
  - `FindingReference` — identity reference to the ReviewRun and ReviewFinding being assessed.
  - `GitHubActor` — the developer authority that supplied the reaction.
  - `FeedbackClassification` — the actor's current `HELPFUL`, `FALSE_POSITIVE`, or withdrawn assessment.
  - `FeedbackAudit` — first-recorded, last-changed, and withdrawal times plus the minimal classification history.
  - `GitHubReactionReference` — external reaction identity used for idempotent scheduled reconciliation.
- **Lifecycle State:**
  - `HELPFUL` — the actor currently marks the finding as useful.
  - `FALSE_POSITIVE` — the actor currently marks the finding as incorrect or not actionable.
  - `WITHDRAWN` — the actor removed the explicit assessment; this is distinct from never having supplied feedback.
- **Behavior:**
  - `Record helpful assessment` — FindingFeedback records the actor's useful assessment, creating or transitioning its state to `HELPFUL`.
  - `Record false-positive assessment` — FindingFeedback records the actor's incorrect/not-actionable assessment, creating or transitioning its state to `FALSE_POSITIVE`.
  - `Revise assessment` — FindingFeedback changes between `HELPFUL` and `FALSE_POSITIVE` while retaining the prior classification in its audit.
  - `Withdraw assessment` — FindingFeedback records reaction removal, transitioning its state from `HELPFUL` or `FALSE_POSITIVE` to `WITHDRAWN`.
  - `Restore assessment` — FindingFeedback accepts a new explicit reaction after withdrawal, transitioning its state from `WITHDRAWN` to `HELPFUL` or `FALSE_POSITIVE`.
  - `Ignore repeated reaction observation` — FindingFeedback preserves current state when scheduled reconciliation observes the same GitHub reaction again.
- **Domain-owned Ports:** None; Application supplies the verified ReviewFinding reference, visibility fact, actor, and reaction fact.
- **Domain Events:** None; after persistence, Application exposes `FindingFeedbackRecorded` as an explicit published fact for Evaluation.
