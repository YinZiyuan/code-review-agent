package dev.langchain4j.example.codereview.agents.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewContextTest {

    @Test
    void context_by_file_is_immutable() {
        ReviewContext ctx = new ReviewContext(
                "diff", List.of(),
                Map.of("Foo.java", List.of(new CodeSnippet("Foo.java", 1, "x"))),
                Path.of("/tmp"));

        assertThatThrownBy(() -> ctx.contextByFile().put("Bar.java", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void code_snippet_holds_three_fields() {
        CodeSnippet snip = new CodeSnippet("Foo.java", 42, "return x;");
        assertThat(snip.file()).isEqualTo("Foo.java");
        assertThat(snip.line()).isEqualTo(42);
        assertThat(snip.text()).isEqualTo("return x;");
    }
}
