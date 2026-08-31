# Eval Samples

Each subdirectory is one PR sample. Layout:

```text
<sample-id>/
|-- meta.json
|-- diff.patch
|-- source-before/
|-- source-after/
`-- annotation.json
```

## Sample Types

- `reverse-NNN/`: reverse-style sample where the broken state is reviewed and the fixed state is used only as human ground truth.
- `real-NNN/`: real PR sampled from a public project.
- `synthetic-NNN/`: hand-crafted edge case.

The W4 release suite is entirely hand-built: `reverse-*` and `synthetic-*`
samples. This makes the suite reproducible and useful for regression pressure,
but it is not a random real-PR benchmark and should not be read as production
accuracy on arbitrary repositories.

## Severity / Category Enums

- `severity`: `CRITICAL | WARNING | SUGGESTION`
- `category`: `SECURITY | PERFORMANCE | STABILITY | CONCURRENCY | TEST | STYLE | OTHER`

## Agent Isolation

The evaluation runner only exposes `diff.patch` and `source-before/` to the agent.
`annotation.json`, `source-after/`, and the `category/difficulty/notes` fields of
`meta.json` are forbidden inputs.
