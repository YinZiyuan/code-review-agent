package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegexAnalyzerTest {

    private final RegexAnalyzer analyzer = new RegexAnalyzer();

    private DiffParser.FileDiff fileWith(String... lines) {
        List<DiffParser.AddedLine> added = java.util.stream.IntStream.range(0, lines.length)
                .mapToObj(i -> new DiffParser.AddedLine(i + 10, lines[i]))
                .toList();
        return new DiffParser.FileDiff("Foo.java", added);
    }

    @Test
    void detectsHardcodedCredential() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("String apiKey = \"sk-real-key\";")));
        assertThat(v).hasSize(1);
        assertThat(v.get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(v.get(0).rule()).isEqualTo("hardcoded-credential");
        assertThat(v.get(0).line()).isEqualTo(10);
    }

    @Test
    void detectsSystemOutPrintln() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("System.out.println(\"hi\");")));
        assertThat(v).extracting(Violation::rule).contains("system-out-println");
    }

    @Test
    void detectsPrintStackTrace() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("e.printStackTrace();")));
        assertThat(v).extracting(Violation::rule).contains("print-stack-trace");
    }

    @Test
    void detectsCatchGenericException() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("} catch (Exception e) {")));
        assertThat(v).extracting(Violation::rule).contains("catch-generic-exception");
    }

    @Test
    void detectsTodoFixme() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("// TODO: fix this")));
        assertThat(v).extracting(Violation::rule).contains("unresolved-todo");
    }

    @Test
    void detectsDisabledTest() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("@Disabled(\"flaky on CI\")")));
        assertThat(v).extracting(Violation::rule).contains("disabled-test");
    }

    @Test
    void detectsSecretWrittenToLogs() {
        List<Violation> v = analyzer.analyze(List.of(fileWith(
                "audit(\"login user=\" + userId + \" token=\" + token);")));
        assertThat(v).extracting(Violation::rule).contains("secret-logging");
    }

    @Test
    void detectsUserControlledTokenTtl() {
        List<Violation> v = analyzer.analyze(List.of(fileWith(
                "return tokens.issue(request.userId(), request.getParameter(\"ttl\"));")));
        assertThat(v).extracting(Violation::rule).contains("user-controlled-token-ttl");
    }

    @Test
    void detectsSilentReturnAfterNullGuard() {
        List<Violation> v = analyzer.analyze(List.of(fileWith(
                "if (body == null) {",
                "    return;",
                "}")));
        assertThat(v).extracting(Violation::rule).contains("silent-null-return");
    }

    @Test
    void noViolationsForCleanLine() {
        List<Violation> v = analyzer.analyze(List.of(fileWith("int x = 1;")));
        assertThat(v).isEmpty();
    }

    @Test
    void reportsFileLineNotDiffLine() {
        DiffParser.FileDiff fd = new DiffParser.FileDiff("Bar.java", List.of(
                new DiffParser.AddedLine(100, "int a = 1;"),
                new DiffParser.AddedLine(101, "System.out.println(\"bad\");"),
                new DiffParser.AddedLine(102, "int b = 2;")
        ));
        List<Violation> v = analyzer.analyze(List.of(fd));
        assertThat(v).hasSize(1);
        assertThat(v.get(0).line()).isEqualTo(101);
    }
}
