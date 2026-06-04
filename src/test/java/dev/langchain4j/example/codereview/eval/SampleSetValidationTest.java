package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.langchain4j.example.codereview.infra.DiffParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SampleSetValidationTest {

    @Test
    void everySampleIsWellFormedAndAgentVisibleFieldsParse() throws Exception {
        Path samplesDir = Path.of("eval/samples");
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        DiffParser parser = new DiffParser();

        List<Path> dirs;
        try (var stream = Files.list(samplesDir)) {
            dirs = stream.filter(Files::isDirectory).sorted().toList();
        }
        assertThat(dirs).isNotEmpty();

        for (Path dir : dirs) {
            assertThat(Files.exists(dir.resolve("diff.patch"))).as("%s diff.patch", dir).isTrue();
            assertThat(Files.exists(dir.resolve("meta.json"))).as("%s meta.json", dir).isTrue();
            assertThat(Files.exists(dir.resolve("annotation.json"))).as("%s annotation.json", dir).isTrue();
            assertThat(Files.isDirectory(dir.resolve("source-before"))).as("%s source-before", dir).isTrue();

            Sample sample = Sample.load(dir, mapper);
            sample.annotation().expectedIssues().forEach(issue -> {
                assertThat(issue.category()).as("%s category", dir).isNotNull();
                assertThat(issue.severity()).as("%s severity", dir).isNotNull();
                assertThat(issue.line()).as("%s line", dir).isGreaterThanOrEqualTo(0);
            });

            assertThat(parser.parse(sample.diffPatch())).as("%s parses to >=1 FileDiff", dir).isNotEmpty();
        }
    }
}
