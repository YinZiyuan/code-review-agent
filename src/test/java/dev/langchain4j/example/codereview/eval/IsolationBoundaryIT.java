package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IsolationBoundaryIT {

    @TempDir
    Path workDir;

    @Test
    void agentReceivesOnlyDiffAndSourceBefore() throws Exception {
        Path samples = workDir.resolve("samples");
        Path sample = samples.resolve("iso-001");
        Files.createDirectories(sample.resolve("source-before"));
        Files.createDirectories(sample.resolve("source-after"));
        Files.writeString(sample.resolve("diff.patch"),
                "diff --git a/A.java b/A.java\n+int x = secretDiffToken;\n");
        Files.writeString(sample.resolve("source-before").resolve("A.java"), "class A {}\n");
        Files.writeString(sample.resolve("source-after").resolve("A.java"), "class A { int sourceAfterToken; }\n");
        Files.writeString(sample.resolve("meta.json"),
                "{\"id\":\"iso-001\",\"category\":\"SECURITY_metaToken\","
                        + "\"difficulty\":\"hard_metaToken\",\"notes\":\"notesToken\"}");
        Files.writeString(sample.resolve("annotation.json"),
                "{\"expected_issues\":[{\"id\":\"I-1\",\"file\":\"A.java\",\"line\":1,\"line_range\":[1,1],"
                        + "\"category\":\"SECURITY\",\"severity\":\"CRITICAL\","
                        + "\"description\":\"annotationGroundTruthToken\","
                        + "\"must_detect\":true,\"alternative_descriptions\":[]}],"
                        + "\"should_not_report\":[],\"notes\":\"x\"}");

        AtomicReference<String> seenRequest = new AtomicReference<>();
        AtomicReference<Path> seenRoot = new AtomicReference<>();
        CodeReviewAgent agent = (request, sourceRoot) -> {
            seenRequest.set(request);
            seenRoot.set(sourceRoot);
            return ReviewResult.empty("none");
        };
        Matcher matcher = new Matcher((expected, finding) -> new LlmJudge.JudgeVerdict(false, 0.0, "no"), 5);
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        new EvaluationRunner(agent, matcher, mapper)
                .run(samples, workDir.resolve("reports"), "iso", Map.of("pipeline", "test"));

        assertThat(seenRequest.get()).contains("secretDiffToken");
        assertThat(seenRequest.get())
                .doesNotContain("annotationGroundTruthToken")
                .doesNotContain("sourceAfterToken")
                .doesNotContain("metaToken")
                .doesNotContain("notesToken");
        assertThat(seenRoot.get().getFileName().toString()).isEqualTo("source-before");
    }
}
