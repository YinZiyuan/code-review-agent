---
last_updated: 2026-08-29
updated_by: superpowers-memory:ingest
triggered_by_plan: null
---

# Conventions

## Naming Patterns

**Files:** Java production and test files use PascalCase matching their primary type. → `src/main/java/dev/langchain4j/example/codereview/`
**Functions/Methods:** lowerCamelCase. → `src/main/java/dev/langchain4j/example/codereview/`
**Variables/Constants:** lowerCamelCase; constants use UPPER_SNAKE_CASE. → `src/main/java/dev/langchain4j/example/codereview/`
**Types:** PascalCase; pipeline stages and analyzer strategies use responsibility-oriented names. → `src/main/java/dev/langchain4j/example/codereview/agents/pipeline/`

## Code Style

**Formatter:** N/A: no repository formatter plugin is configured in the Maven build. → `pom.xml`
**Linter:** N/A: SpotBugs is runtime review evidence, not a repository-wide style linter. → `src/main/java/dev/langchain4j/example/codereview/analyzer/SpotBugsAnalyzer.java`

## Error Handling

**Strategy:** Degrade optional analyzers explicitly, but propagate or record genuine failures; JSON repair may change format only, never semantic content. → `src/main/java/dev/langchain4j/example/codereview/infra/JsonRepair.java`
**Custom errors:** Prefer existing Java/LangChain4j exceptions and explicit tool state over a custom exception hierarchy. → `src/main/java/dev/langchain4j/example/codereview/model/ToolRunState.java`

## Architecture Rules

- Add review behavior as an explicit pipeline stage; do not reintroduce production `AiServices` tool-loop orchestration. → `docs/superpowers/specs/2026-05-31-code-review-agent-w3-design.md`
- Every finding line refers to the post-change file and must derive from `DiffParser`. → `src/main/java/dev/langchain4j/example/codereview/infra/DiffParser.java`
- `ReviewResult` is the stable internal/evaluation contract; presentation belongs in `MarkdownReporter`. → `src/main/java/dev/langchain4j/example/codereview/model/ReviewResult.java`

## Testing Conventions

**Framework & command:** JUnit 5 via `mvn test`; target one class or method with Maven `-Dtest=Class#method`. → `pom.xml`
**Mock principle:** Keep deterministic domain logic real and isolate only external boundaries. → `src/test/java/dev/langchain4j/example/codereview/`
- Mock: model/network boundaries and external tool output where deterministic fixtures are required.
- Do NOT mock: pure parsers, metrics, matching prefilters, or deterministic summarization behavior.
**Coverage target:** No numeric target; capability changes require tests plus a proportionate evaluation report in `eval/reports/`. → `docs/superpowers/specs/2026-05-17-code-review-agent-design.md`

## Git & Workflow

- Run from the repository root with Java 17 and Maven.
- Treat evaluation as the capability contract: preserve version/pipeline labels and comparable suite metadata.
- Do not overwrite unrelated dirty-worktree changes.

## Cross-cutting concerns

**Configuration:** Strongly typed `code-review.*` settings flow through `CodeReviewProperties`; model endpoint settings live under `langchain4j.open-ai.chat-model.*`. → `src/main/resources/application.yml`
**Evaluation isolation:** Agent code may read only `diff.patch` and `source-before/`; ground truth and descriptive labels remain evaluator-only. → `eval/samples/README.md`
**Citation integrity:** Findings may cite only retrieved candidate IDs; deterministic back-fill must preserve that set. → `src/main/java/dev/langchain4j/example/codereview/rag/CitationKeywordInjector.java`
