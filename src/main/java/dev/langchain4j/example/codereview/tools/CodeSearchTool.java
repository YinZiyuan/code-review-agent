package dev.langchain4j.example.codereview.tools;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class CodeSearchTool {

    private static final int MAX_HITS = 50;
    private static final int MAX_LINE_LEN = 200;

    public String grep(String rootPath, String needle) {
        Path root = Path.of(rootPath);
        if (!Files.isDirectory(root)) {
            return "Not a directory: " + rootPath;
        }
        if (needle == null || needle.isEmpty()) {
            return "Empty needle.";
        }

        List<String> hits = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()::iterator) {
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                String rel = root.relativize(file).toString().replace('\\', '/');
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!line.contains(needle)) {
                        continue;
                    }
                    String snippet = line.length() > MAX_LINE_LEN
                            ? line.substring(0, MAX_LINE_LEN) + "..."
                            : line;
                    hits.add(rel + ":" + (i + 1) + ": " + snippet);
                    if (hits.size() >= MAX_HITS) {
                        truncated = true;
                        break;
                    }
                }
                if (truncated) {
                    break;
                }
            }
        } catch (IOException e) {
            return "Error walking " + root + ": " + e.getMessage();
        }

        if (hits.isEmpty()) {
            return "No matches for: " + needle;
        }
        StringBuilder out = new StringBuilder();
        hits.forEach(hit -> out.append(hit).append('\n'));
        if (truncated) {
            out.append("[truncated at ").append(MAX_HITS).append(" hits]\n");
        }
        return out.toString();
    }
}
