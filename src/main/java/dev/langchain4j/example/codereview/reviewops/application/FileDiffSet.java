package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.infra.DiffParser;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FileDiffSet {

    private final Map<String, Set<Integer>> addedLinesByFile;

    private FileDiffSet(Map<String, Set<Integer>> addedLinesByFile) {
        Map<String, Set<Integer>> immutable = new LinkedHashMap<>();
        addedLinesByFile.forEach((file, lines) -> immutable.put(file, Set.copyOf(lines)));
        this.addedLinesByFile = Map.copyOf(immutable);
    }

    public static FileDiffSet from(List<DiffParser.FileDiff> fileDiffs) {
        Objects.requireNonNull(fileDiffs, "fileDiffs");
        Map<String, Set<Integer>> addedLinesByFile = new LinkedHashMap<>();
        for (DiffParser.FileDiff fileDiff : fileDiffs) {
            Objects.requireNonNull(fileDiff, "fileDiff");
            String file = normalizeFilePath(fileDiff.path());
            Set<Integer> lines = addedLinesByFile.computeIfAbsent(file, ignored -> new LinkedHashSet<>());
            for (DiffParser.AddedLine addedLine : fileDiff.addedLines()) {
                Objects.requireNonNull(addedLine, "addedLine");
                if (addedLine.lineNumber() < 1) {
                    throw new IllegalArgumentException("diff line must be positive");
                }
                lines.add(addedLine.lineNumber());
            }
        }
        return new FileDiffSet(addedLinesByFile);
    }

    public boolean containsAddedLine(String file, int postChangeLine) {
        Set<Integer> addedLines = addedLinesByFile.get(normalizeFilePath(file));
        return addedLines != null && addedLines.contains(postChangeLine);
    }

    static String normalizeFilePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("file is required");
        }
        String slashNormalized = value.replace('\\', '/').replaceAll("/+", "/");
        if (slashNormalized.startsWith("/") || slashNormalized.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("file must be repository relative");
        }
        List<String> segments = java.util.Arrays.stream(slashNormalized.split("/"))
                .filter(segment -> !segment.isEmpty() && !segment.equals("."))
                .toList();
        if (segments.isEmpty() || segments.stream().anyMatch(segment -> segment.equals(".."))) {
            throw new IllegalArgumentException("file must be a safe repository-relative path");
        }
        if (segments.stream().anyMatch(FileDiffSet::containsControlCharacter)) {
            throw new IllegalArgumentException("file contains an invalid character");
        }
        return String.join("/", segments);
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character));
    }
}
