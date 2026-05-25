package dev.langchain4j.example.codereview.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCompilerTest {

    @TempDir
    Path src;

    @Test
    void compilesSelfContainedSingleFile() throws Exception {
        Files.writeString(src.resolve("Foo.java"),
                "public class Foo { public int x() { return 1; } }");
        SourceCompiler compiler = new SourceCompiler();

        Optional<Path> classesDir = compiler.compile(src);

        assertThat(classesDir).isPresent();
        assertThat(classesDir.get().resolve("Foo.class")).exists();
    }

    @Test
    void returnsEmptyWhenSourceReferencesMissingType() throws Exception {
        Files.writeString(src.resolve("UsesMissing.java"),
                "public class UsesMissing { com.example.Missing m; }");
        SourceCompiler compiler = new SourceCompiler();

        Optional<Path> result = compiler.compile(src);

        assertThat(result).isEmpty();
    }

    @Test
    void emptyDirReturnsEmpty() {
        SourceCompiler compiler = new SourceCompiler();
        assertThat(compiler.compile(src)).isEmpty();
    }
}
