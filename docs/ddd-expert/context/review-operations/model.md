# Review Operations Strategic Model

## Purpose

Safely review the latest known revision of a GitHub pull request, retain an auditable result, publish only findings that satisfy developer-attention policy, and record explicit developer feedback.

## Scope

Included: pull-request revision observation, run admission, execution lifecycle, supersession, bounded retry decisions, finding publication decisions, idempotent publication, and reaction-based feedback.

Excluded: multi-tenant billing, automatic merge, organization-wide policy administration, model training, benchmark ground truth, and microservice topology.

## Essential Language

- **Pull-request revision:** a repository, pull-request number, and authoritative head SHA observed from GitHub.
- **ReviewRun:** one review attempt family for a pull-request revision, pipeline version, and configuration snapshot.
- **Finding:** an immutable structured review issue produced by the review pipeline.
- **PublicationDecision:** the accepted visibility of a finding: inline comment, Check summary, or retained only.
- **Superseded:** a run whose pull-request revision is no longer current and which has permanently lost publication authority.
- **FindingFeedback:** one developer's explicit reaction to one finding without mutation of the original finding.

## Aggregate Roots

### ReviewRun

Identity is derived from GitHub installation, repository, pull-request number, head SHA, pipeline version, and configuration version. Its consistency boundary owns lifecycle state, attempts and failure classification, immutable findings, publication decisions, publication references, and execution telemetry.

Lifecycle states are `REQUESTED`, `RUNNING`, `COMPLETED`, `PUBLISHING`, `PUBLISHED`, `FAILED`, and `SUPERSEDED`. Tactical design will decide the exact transition API and persistence representation.

### FindingFeedback

Identified by review run, finding fingerprint, and GitHub actor. It owns the actor's current explicit classification and its audit timestamps. Changing a reaction updates the feedback fact but never changes the source finding.

## Strategic Business Rules

1. A newly observed head SHA removes publication authority from every older run for the same pull request.
2. A superseded run is terminal. Its underlying work may finish technically, but no result from it may be published.
3. Publication must verify the authoritative GitHub head SHA immediately before writing, independently of earlier supersession handling.
4. Transient external failures may be retried only within a configured bound. Deterministic failures are not retried.
5. A final review-system failure is visible to developers but does not block merge by default and produces no code comments.
6. A high-confidence finding may become an inline comment; a medium-confidence finding may appear only in the Check summary; a low-confidence finding is retained for offline analysis only.
7. Publication confidence is a deterministic policy over available evidence, not an unverified model self-score.
8. The same finding for the same pull-request revision is published at most once; reruns update or reconcile existing GitHub artifacts instead of duplicating them.
9. An absent reaction is not evidence that a finding was correct. A later code change may be recorded as possibly addressed but not automatically as accepted.
10. Feedback affects future offline analysis only. It cannot mutate the original result or an in-flight publication decision.

## Collaboration Contracts

- **Inbound command:** observe a pull-request revision from the GitHub adapter.
- **Inbound command:** execute or retry an admitted review run from the job adapter.
- **Inbound command:** reconcile GitHub review-comment reactions into explicit finding feedback.
- **Outbound semantic capability:** publish or reconcile a review outcome against the authoritative pull-request revision through the GitHub adapter.
- **Published fact:** `ReviewOutcomeRecorded` for Evaluation.
- **Published fact:** `FindingFeedbackRecorded` for Evaluation.

Exact command, fact, port, and domain-event objects remain tactical-design decisions.

## Remaining Non-blocking Uncertainty

- Persistence technology and schema.
- Durable job mechanism and retry scheduling implementation.
- Confidence score formula and initial thresholds.
- GitHub Check/comment reconciliation details and the bounded reaction-reconciliation schedule.
