package dev.langchain4j.example.codereview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CodeReviewApplicationCliStartupTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void defaultCliExecutableHandlesHelpWithoutDatasourceConfiguration() throws Exception {
        CliResult result = runCli("root-help.log", "--help");

        assertThat(result.completed()).as("CLI output:%n%s", result.output()).isTrue();
        assertThat(result.exitCode()).as("CLI output:%n%s", result.output()).isZero();
        assertThat(result.output()).contains("Usage: code-review-agent");
        assertThat(result.output()).doesNotContain("Failed to configure a DataSource");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void reviewSubcommandHelpExitsWithoutInvokingExternalReviewWork() throws Exception {
        CliResult result = runCli("review-help.log", "review", "--help");

        assertThat(result.completed()).as("CLI output:%n%s", result.output()).isTrue();
        assertThat(result.exitCode()).as("CLI output:%n%s", result.output()).isZero();
        assertThat(result.output()).contains("Usage: code-review-agent review");
        assertThat(result.output()).doesNotContain("Repository :");
    }

    private CliResult runCli(String outputFileName, String... arguments) throws Exception {
        Path outputFile = temporaryDirectory.resolve(outputFileName);
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                testClassPath(),
                CodeReviewApplication.class.getName()));
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(outputFile.toFile());
        removeDatasourceConfiguration(processBuilder.environment());

        Process process = processBuilder.start();
        boolean completed = process.waitFor(45, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output = Files.readString(outputFile);
        return new CliResult(completed, process.exitValue(), output);
    }

    private static String testClassPath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    private static void removeDatasourceConfiguration(Map<String, String> environment) {
        environment.keySet().removeIf(name ->
                name.startsWith("SPRING_DATASOURCE_")
                        || name.startsWith("SPRING_FLYWAY_")
                        || name.equals("SPRING_AUTOCONFIGURE_EXCLUDE")
                        || name.equals("SPRING_APPLICATION_JSON")
                        || name.equals("DATABASE_URL"));
    }

    private record CliResult(boolean completed, int exitCode, String output) {
    }
}
