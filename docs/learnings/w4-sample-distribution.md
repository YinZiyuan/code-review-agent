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

