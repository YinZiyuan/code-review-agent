package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class LlmJudgeImpl implements LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeImpl.class);

    private final ChatModel chatModel;
    private final ObjectMapper mapper;

    public LlmJudgeImpl(ChatModel chatModel, ObjectMapper mapper) {
        this.chatModel = chatModel;
        this.mapper = mapper;
    }

    @Override
    public JudgeVerdict judge(ExpectedIssue expected, ReviewFinding agent) {
        String prompt = """
                You are evaluating whether two code review findings describe the SAME issue.

                Expected issue:
                  Description: %s
                  Category: %s
                  Alternative phrasings: %s

                Agent finding:
                  Description: %s
                  Severity: %s

                Do these describe the SAME underlying problem? Answer with JSON only:
                {"match": true|false, "confidence": 0.0-1.0, "reason": "..."}
                """.formatted(
                expected.description(),
                expected.category(),
                String.join("; ", expected.alternativeDescriptions() == null ? List.of() : expected.alternativeDescriptions()),
                agent.description(),
                agent.severity()
        );

        String raw = chatModel.chat(prompt);
        try {
            String json = raw.replaceAll("(?s)```(?:json)?", "")
                    .replace("```", "")
                    .trim();
            return mapper.readValue(json, JudgeVerdict.class);
        } catch (Exception e) {
            log.warn("Judge response not parseable: {}", raw);
            return new JudgeVerdict(false, 0.0, "judge parse error: " + e.getMessage());
        }
    }
}
