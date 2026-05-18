package dev.langchain4j.example.codereview.infra;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiffParserTest {

    private final DiffParser parser = new DiffParser();

    @Test
    void parsesSingleFileAddedLines() throws Exception {
        String patch = Files.readString(Path.of("src/test/resources/fixtures/diff-hunks/simple-add.patch"));
        List<DiffParser.FileDiff> files = parser.parse(patch);

        assertThat(files).hasSize(1);
        DiffParser.FileDiff foo = files.get(0);
        assertThat(foo.path()).isEqualTo("Foo.java");
        // "int z = 3;" is added at new-file line 12; "System.out.println(z);" at line 13.
        assertThat(foo.addedLines()).extracting(DiffParser.AddedLine::lineNumber)
                .containsExactly(12, 13);
        assertThat(foo.addedLines()).extracting(DiffParser.AddedLine::content)
                .containsExactly("    int z = 3;", "    System.out.println(z);");
    }

    @Test
    void parsesMultipleFiles() throws Exception {
        String patch = Files.readString(Path.of("src/test/resources/fixtures/diff-hunks/multi-file.patch"));
        List<DiffParser.FileDiff> files = parser.parse(patch);

        assertThat(files).extracting(DiffParser.FileDiff::path)
                .containsExactly("A.java", "B.java");
        assertThat(files.get(0).addedLines()).extracting(DiffParser.AddedLine::lineNumber)
                .containsExactly(2);
        assertThat(files.get(1).addedLines()).extracting(DiffParser.AddedLine::lineNumber)
                .containsExactly(7);
    }

    @Test
    void ignoresFileHeaderLinesStartingWithPlusPlusPlus() throws Exception {
        String patch = Files.readString(Path.of("src/test/resources/fixtures/diff-hunks/simple-add.patch"));
        List<DiffParser.FileDiff> files = parser.parse(patch);
        assertThat(files.get(0).addedLines()).noneMatch(l -> l.content().startsWith("+++"));
    }

    @Test
    void emptyInputProducesEmptyList() {
        assertThat(parser.parse("")).isEmpty();
    }
}
