package dev.langchain4j.example.codereview.agents;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.rag.CitationKeywordInjector;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.example.codereview.rag.RetrievalRecorder;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.output.OutputParsingException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardedCodeReviewAgentTest {

    @Test
    void output_parsing_exception_triggers_repair_with_raw_text() {
        CodeReviewAgent inner = mock(CodeReviewAgent.class);
        JsonRepair repair = mock(JsonRepair.class);
        RetrievalRecorder recorder = new RetrievalRecorder();
        CitationTracker tracker = new CitationTracker();
        CitationKeywordInjector injector = new CitationKeywordInjector();

        String rawText = "{\"summary\":\"x\",\"findings\":[],\"tool_status\":[]}";
        when(inner.review("req"))
                .thenThrow(new OutputParsingException(rawText, new RuntimeException("boom")));
        when(repair.parseOrRepair(rawText, ReviewResult.class))
                .thenReturn(new ReviewResult("x", List.of(), List.of()));

        GuardedCodeReviewAgent guard = new GuardedCodeReviewAgent(inner, repair, recorder, tracker, injector);

        ReviewResult out = guard.review("req");

        assertThat(out.summary()).isEqualTo("x");
    }

    @Test
    void empty_citations_are_backfilled_from_recorded_hits() {
        CodeReviewAgent inner = mock(CodeReviewAgent.class);
        JsonRepair repair = mock(JsonRepair.class);
        RetrievalRecorder recorder = new RetrievalRecorder();
        CitationTracker tracker = new CitationTracker();
        CitationKeywordInjector injector = new CitationKeywordInjector();

        ReviewFinding finding = new ReviewFinding("F-001", "Foo.java", 10, null,
                Severity.WARNING, Category.SECURITY,
                "SQL injection via concatenation", "Use parameterized queries",
                "fix", "evidence", List.of(), "llm_reviewer");
        when(inner.review("req"))
                .thenReturn(new ReviewResult("s", List.of(finding), List.of()));

        recorder.record(List.of(Content.from(TextSegment.from(
                "Parameterized queries content...",
                Metadata.from(Map.of(
                        "citation_id", "sql-guidelines#parameterized-queries",
                        "source_file", "sql-guidelines.txt",
                        "section", "Parameterized Queries"))))));

        GuardedCodeReviewAgent guard = new GuardedCodeReviewAgent(inner, repair, recorder, tracker, injector);

        ReviewResult out = guard.review("req");

        assertThat(out.findings().get(0).citations()).hasSize(1);
        assertThat(out.findings().get(0).citations().get(0).id())
                .isEqualTo("sql-guidelines#parameterized-queries");
    }
}
