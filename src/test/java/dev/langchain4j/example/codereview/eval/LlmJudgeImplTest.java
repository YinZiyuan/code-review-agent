package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmJudgeImplTest {

    @Test
    void issue_matching_prompt_does_not_depend_on_agent_severity() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("{\"match\":true,\"confidence\":0.9,\"reason\":\"same\"}");
        LlmJudgeImpl judge = new LlmJudgeImpl(model, new ObjectMapper());
        ExpectedIssue expected = new ExpectedIssue(
                "I-1", "A.java", 1, null, Category.STABILITY, "npe",
                Severity.CRITICAL, "null dereference", true, List.of("npe"));
        ReviewFinding finding = new ReviewFinding(
                "F-1", "A.java", 1, null, Severity.SUGGESTION, Category.STABILITY,
                "Null dereference", "null dereference", "fix", "x", List.of(), "llm_reviewer");

        assertThat(judge.judge(expected, finding).match()).isTrue();

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(model).chat(prompt.capture());
        assertThat(prompt.getValue()).doesNotContain("Severity:");
        assertThat(prompt.getValue()).doesNotContain("SUGGESTION");
    }
}
