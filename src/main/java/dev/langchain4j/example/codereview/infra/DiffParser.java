package dev.langchain4j.example.codereview.infra;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DiffParser {

    private static final Pattern FILE_HEADER = Pattern.compile("^\\+\\+\\+ b/(.+)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    public record AddedLine(int lineNumber, String content) { }

    public record FileDiff(String path, List<AddedLine> addedLines) { }

    public List<FileDiff> parse(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isBlank()) return List.of();

        List<FileDiff> files = new ArrayList<>();
        String currentPath = null;
        List<AddedLine> currentAdded = new ArrayList<>();
        int newLineNum = 0;
        boolean inHunk = false;

        for (String line : unifiedDiff.split("\n", -1)) {
            Matcher fileMatch = FILE_HEADER.matcher(line);
            if (fileMatch.matches()) {
                if (currentPath != null) {
                    files.add(new FileDiff(currentPath, List.copyOf(currentAdded)));
                }
                currentPath = fileMatch.group(1);
                currentAdded = new ArrayList<>();
                inHunk = false;
                continue;
            }

            Matcher hunkMatch = HUNK_HEADER.matcher(line);
            if (hunkMatch.matches()) {
                newLineNum = Integer.parseInt(hunkMatch.group(1));
                inHunk = true;
                continue;
            }

            if (!inHunk || currentPath == null) continue;

            if (line.startsWith("+") && !line.startsWith("+++")) {
                currentAdded.add(new AddedLine(newLineNum, line.substring(1)));
                newLineNum++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                // deleted line, no new-file advancement
            } else {
                // context or empty line in hunk
                newLineNum++;
            }
        }

        if (currentPath != null) {
            files.add(new FileDiff(currentPath, List.copyOf(currentAdded)));
        }
        return files;
    }
}
