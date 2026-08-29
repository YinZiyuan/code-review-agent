# Context Map

## System Purpose

Turn a GitHub pull-request revision into an auditable code-review outcome, publish only findings appropriate for developer attention, and collect explicit feedback without allowing benchmark answers or feedback data to influence an in-flight review.

## Bounded Contexts

### Review Operations — Core

Owns the lifecycle of a review run for one pull-request revision, the decision about which findings may be published, the prevention of stale or duplicate publication, and developer feedback about published findings.

See [Review Operations model](context/review-operations/model.md).

### Evaluation — Supporting

Owns benchmark samples, expected issues, matching, metrics, repeated-run comparison, and offline analysis of production feedback. Evaluation data never becomes input to an in-flight production review.

See [Evaluation model](context/evaluation/model.md).

## External Authorities and Adapters

- **GitHub** is authoritative for installations, repositories, pull requests, head SHAs, reactions, Check Runs, and code-review comments. A GitHub adapter translates supported webhook deliveries into Review Operations commands, periodically reconciles review-comment reactions through the GitHub API, and translates publication intent into GitHub API calls.
- **Git repositories** are authoritative for source and diff content at a requested revision.
- **Configured model providers** perform bounded model inference but do not own review lifecycle or publication decisions.
- **Persistence and job execution mechanisms** store and execute accepted Review Operations semantics; they do not define those semantics.

## Semantic Dependencies

- **Review Operations -> Evaluation:** `ReviewOutcomeRecorded` and `FindingFeedbackRecorded` are producer-owned published facts available for offline metrics and tuning analysis.
- Evaluation may propose a versioned policy/configuration change, but it cannot mutate a running or completed `ReviewRun`. A changed policy enters Review Operations only through an explicitly deployed configuration version.

The GitHub integration is an anti-corruption adapter around an external authority, not a separate business context. The current deployment is a modular monolith; these boundaries do not imply microservices.

## Cross-Context Rules

- The dependency map is acyclic: Review Operations publishes immutable facts to Evaluation; Evaluation does not call back into a live run.
- Benchmark-only fields and labels remain inside Evaluation.
- Production feedback is evidence for offline analysis, not an online-learning command.
