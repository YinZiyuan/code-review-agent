---
last_updated: 2026-08-29
updated_by: superpowers-memory:ingest
triggered_by_plan: null
---

# Tech Stack

## Languages & Frameworks

| Technology | Role | Version | Notes |
|-----------|------|---------|-------|
| Java | Application language and runtime | 17 | Matches the target backend-to-AI portfolio and supports a dependency-light CLI. |
| Spring Boot | DI, configuration, lifecycle | 3.5.6 | Provides typed configuration and LangChain4j starter integration while running without a web server. |
| LangChain4j | Chat model, embeddings, and AI integration | 1.15.0-beta25 | Keeps the implementation idiomatic for the Java AI application ecosystem. |
| picocli | Multi-command CLI | 4.7.6 | Integrates with Spring Boot and supplies `review`, `eval`, and `sample` commands. |

## Runtime

**Environment:** Java 17; Moonshot OpenAI-compatible API for model calls.
**Package Manager:** Maven through `pom.xml`.
**Lockfile:** N/A: Maven dependency versions are pinned in the POM and parent BOM.

## Key Dependencies

| Package | Purpose | Why Chosen |
|---------|---------|------------|
| `langchain4j-open-ai-spring-boot-starter` 1.15.0-beta25 | Moonshot chat-model integration | Moonshot exposes an OpenAI-compatible endpoint and the starter fits typed Spring configuration. |
| `langchain4j-easy-rag` 1.15.0-beta25 | Guideline ingestion/retrieval support | Reuses the project's primary AI framework instead of adding a retrieval service. |
| `langchain4j-embeddings-bge-small-en-v15-q` 1.15.0-beta25 | Local guideline embeddings | Small quantized local model avoids an embedding API dependency. |
| Apache Lucene 9.11.1 | BM25 keyword retrieval | Mature in-process Java search with no external service. |
| Spring Boot Test 3.5.6 | JUnit-based verification | Matches the application framework and provides the repository test harness. |

## Build & Dev Tools

| Tool | Purpose |
|------|---------|
| Maven | Build the fat jar and run tests. |
| SpotBugs | Optional/degradable static analysis evidence during reviews. |
| Python plotting script | Generate evaluation Markdown and SVG from JSON reports. |

## Configuration

**Environment:** `MOONSHOT_API_KEY` supplies the model credential; embeddings default to `${user.home}/.code-review-agent/cache`.
**Build:** `pom.xml`; runtime configuration is `src/main/resources/application.yml`.

## Platform Requirements

**Development:** Java 17, Maven, Git; network access is required for live model calls but not for ordinary unit tests.
**Production:** A local command-line JVM process; no HTTP server, database, queue, or external vector store.

## Infrastructure

Moonshot/Kimi is the only hosted runtime dependency. Git repositories, guideline files, evaluation samples/reports, and the embedding cache are local filesystem resources.
