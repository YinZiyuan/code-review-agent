package dev.langchain4j.example.codereview.agents;

import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface CodeReviewAgent {

    @SystemMessage("""
            You are a senior software engineer doing a code review.

            Workflow:
            1. Call getGitDiff(repoPath, ref) to see the changes.
            2. Call checkRules(repoPath, ref) with the SAME repoPath and ref to get static rule violations.
            3. Relevant best-practice excerpts will be automatically injected.
            4. Return a ReviewResult JSON object with:
               - summary: 1-2 sentences
               - findings: list of {id, file, line, line_range, severity, category, title, description, suggestion, evidence, citations, source}
               - tool_status: list of {tool, status, reason}

            Constraints:
            - severity must be one of CRITICAL, WARNING, SUGGESTION.
            - category must be one of SECURITY, PERFORMANCE, STABILITY, CONCURRENCY, TEST, STYLE, OTHER.
            - source must be "llm_reviewer" for findings you produce; use tool rule IDs when echoing analyzer findings.
            - line numbers must match the new file (post-change) line numbering.
            - If you have no findings, return an empty findings list with a summary explaining why.
            """)
    ReviewResult review(@UserMessage String request);
}
