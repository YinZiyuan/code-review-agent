package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.tools.CodeSearchTool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DiffAnalyzer {

    private static final Pattern METHOD_CALL =
            Pattern.compile("\\b([a-z][A-Za-z0-9]+)\\s*\\(");
    private static final Pattern TYPE_NAME =
            Pattern.compile("\\b([A-Z][A-Za-z0-9]+)\\b");
    private static final Set<String> STOPWORDS =
            Set.of("if", "for", "new", "return", "this", "super", "true", "false",
                    "null", "String", "Integer", "Long", "Boolean", "Object", "List",
                    "Map", "Set", "Override");
    private static final int MAX_HITS_PER_FILE = 20;
    private static final int MAX_IDENTIFIERS_PER_FILE = 6;

    private final DiffParser parser;
    private final CodeSearchTool search;

    public DiffAnalyzer(DiffParser parser, CodeSearchTool search) {
        this.parser = parser;
        this.search = search;
    }

    public ReviewContext analyze(String rawDiff, Path sourceRoot) {
        List<DiffParser.FileDiff> files = parser.parse(rawDiff);
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return new ReviewContext(rawDiff, files, Map.of(), sourceRoot);
        }

        Map<String, List<CodeSnippet>> byFile = new LinkedHashMap<>();
        for (DiffParser.FileDiff file : files) {
            List<CodeSnippet> snippets = snippetsFor(file, sourceRoot);
            if (!snippets.isEmpty()) {
                byFile.put(file.path(), List.copyOf(snippets));
            }
        }
        return new ReviewContext(rawDiff, files, byFile, sourceRoot);
    }

    private List<CodeSnippet> snippetsFor(DiffParser.FileDiff file, Path sourceRoot) {
        List<CodeSnippet> snippets = new ArrayList<>();
        for (String identifier : extractIdentifiers(file)) {
            String raw = search.grep(sourceRoot.toString(), identifier);
            if (raw == null || raw.startsWith("No matches") || raw.startsWith("Not a directory")) {
                continue;
            }
            for (String line : raw.split("\n")) {
                if (line.isBlank() || line.startsWith("[truncated")) {
                    continue;
                }
                CodeSnippet snippet = parseGrepLine(line);
                if (snippet != null) {
                    snippets.add(snippet);
                }
                if (snippets.size() >= MAX_HITS_PER_FILE) {
                    break;
                }
            }
            if (snippets.size() >= MAX_HITS_PER_FILE) {
                break;
            }
        }
        return snippets;
    }

    private Set<String> extractIdentifiers(DiffParser.FileDiff file) {
        Set<String> out = new LinkedHashSet<>();
        for (DiffParser.AddedLine line : file.addedLines()) {
            collect(METHOD_CALL.matcher(line.content()), out);
            collect(TYPE_NAME.matcher(line.content()), out);
            if (out.size() >= MAX_IDENTIFIERS_PER_FILE) {
                break;
            }
        }
        return out;
    }

    private void collect(Matcher matcher, Set<String> out) {
        while (matcher.find() && out.size() < MAX_IDENTIFIERS_PER_FILE) {
            String name = matcher.group(1);
            if (!STOPWORDS.contains(name)) {
                out.add(name);
            }
        }
    }

    private CodeSnippet parseGrepLine(String line) {
        int firstColon = line.indexOf(':');
        if (firstColon < 0) {
            return null;
        }
        int secondColon = line.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            return null;
        }
        try {
            return new CodeSnippet(
                    line.substring(0, firstColon),
                    Integer.parseInt(line.substring(firstColon + 1, secondColon).trim()),
                    line.substring(secondColon + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
