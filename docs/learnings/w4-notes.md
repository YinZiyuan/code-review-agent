# W4 Notes

## What Changed

- Added real repeated-run support to eval reports: `per_run_metrics`, flattened per-sample run IDs, and `metrics_std_dev`.
- Split tool status into `RAN`, `SKIPPED_EXPECTED`, and `FAILED`, so expected SpotBugs skips on intentionally incomplete samples no longer count as analyzer failures.
- Expanded the release suite to 40 hand-built samples: 30 reverse-style defect samples and 10 synthetic edge or true-negative samples.
- Preserved historical v0/v1/v2 baselines separately after strict 40-sample reruns failed the review-error redline.
- Added reproducible metric docs via `scripts/plot_metrics.py`.

## Release Result

Accepted strict release result:

| Version | Samples x Runs | Recall | Precision | FP rate | Severity acc. | Tool success |
| --- | --- | --- | --- | --- | --- | --- |
| v3 / w3-pipeline | 40 x 3 | 70.3% | 61.9% | 38.1% | 50.1% | 100.0% |
| v3.1-tuned / w4-tuned | 40 x 3 | 75.7% | 67.7% | 32.3% | 77.3% | 100.0% |

`v3.1-tuned` is accepted. It passed the no-review-error redline and improved every quality metric over `v3`; recall and precision also had lower run-to-run standard deviation.

## Design Lessons

- Evaluation claims need executable backing. `runs_per_sample` as config text was not enough; the runner now actually repeats and records variance.
- Tool reliability metrics need semantic states. A skipped analyzer because a synthetic sample lacks dependencies is not the same thing as an analyzer crash.
- Prompt-only severity tuning improved a secondary metric while damaging primary metrics. Deterministic category-based calibration plus general high-confidence static rules improved severity accuracy without trading away recall and precision.
- Issue matching should answer whether two findings describe the same defect independently of severity; severity correctness is scored separately.
- Synthetic evals are useful for regression pressure, but they are not a substitute for real PR distributions.

## Interview Notes

- The project demonstrates a shift from autonomous tool use to deterministic orchestration with one bounded LLM call.
- The strongest engineering point is the eval boundary: ground truth exists in the same sample tree but is kept out of `agent.review`.
- The most honest limitation is the hand-built benchmark. The next valuable step is real PR sampling and separate reporting by sample source.
