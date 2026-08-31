# W4 Sample Distribution

## Baseline: 20 reverse samples

| Category | Difficulty | Count |
| --- | --- | --- |
| concurrency | hard | 1 |
| concurrency | medium | 3 |
| performance | easy | 1 |
| performance | medium | 3 |
| security | easy | 3 |
| security | medium | 1 |
| stability | easy | 3 |
| stability | medium | 2 |
| test | easy | 2 |
| test | medium | 1 |

## W4 target

The existing set has reasonable category spread but only one hard sample. W4 additions should:

- Add hard cases across concurrency, performance, stability, and security.
- Add more test-category cases, including at least one hard test-design failure.
- Add synthetic true-negative and near-miss samples to pressure precision, not only recall.

## Final: 40 release samples

| Category | Difficulty | Count |
| --- | --- | --- |
| concurrency | hard | 4 |
| concurrency | medium | 4 |
| performance | easy | 2 |
| performance | hard | 2 |
| performance | medium | 5 |
| security | easy | 3 |
| security | hard | 2 |
| security | medium | 3 |
| stability | easy | 3 |
| stability | hard | 2 |
| stability | medium | 3 |
| style | easy | 1 |
| test | easy | 2 |
| test | hard | 2 |
| test | medium | 2 |

The added set includes 10 reverse-style defect-introducing samples and 10 synthetic edge cases:

- True negatives: `synthetic-001..003`
- Near misses: `synthetic-004..006`
- Line/cross-file tricky cases: `synthetic-007..010`
