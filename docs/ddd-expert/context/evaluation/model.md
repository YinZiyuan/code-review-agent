# Evaluation Strategic Model

## Purpose

Measure review quality reproducibly, compare versioned pipelines, and analyze production feedback offline without contaminating review generation.

## Scope

Included: isolated benchmark samples, expected issues, finding matching, metrics, repeated runs, release comparison, and offline aggregation of production feedback.

Excluded: execution or publication of live GitHub reviews, mutation of `ReviewRun`, online learning, and implicit deployment of threshold changes.

## Essential Language

- **Agent-visible sample input:** `diff.patch` and `source-before/`, the only benchmark data available to review generation.
- **Expected issue:** evaluator-only ground truth used after review generation.
- **Evaluation run:** a versioned execution of selected samples and repetitions under one pipeline configuration.
- **Strict release report:** a comparable release-suite report satisfying the project's sample-size and review-error requirements.

## Aggregate Roots

### EvaluationRun

Owns the selected suite, pipeline/configuration identity, repetitions, per-sample outcomes, aggregate metrics, and variability for one evaluation execution. Existing code may use immutable report records rather than a persisted domain aggregate; tactical change is unnecessary unless production feedback is integrated.

## Strategic Business Rules

1. Review generation may see only the diff and pre-change source made explicitly agent-visible.
2. Expected annotations, post-change source, difficulty, category, and evaluator notes remain outside the agent boundary.
3. Reports with incompatible suite scope or reliability redlines are not presented as directly comparable release results.
4. Repeated-run release metrics report both aggregate means and variability.
5. Production feedback is analyzed as observational evidence; absence of feedback is not a correctness label.
6. A proposed threshold or policy adjustment must receive an explicit configuration version and pass evaluation before deployment.

## Collaboration Contracts

- **Consumed published fact:** `ReviewOutcomeRecorded` from Review Operations.
- **Consumed published fact:** `FindingFeedbackRecorded` from Review Operations.

The initial system may materialize these facts through shared persistence or an export process inside the modular monolith; this does not change their one-way semantic ownership.
