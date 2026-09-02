package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
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

    private static final Pattern METHOD_CALL = Pattern.compile("\\b([a-z][A-Za-z0-9]+)\\s*\\(");
    private static final Pattern TYPE_NAME = Pattern.compile("\\b([A-Z][A-Za-z0-9]+)\\b");
    private static final Set<String> STOPWORDS = Set.of(
            "if", "for", "new", "return", "this", "super", "true", "false",
            "null", "String", "Integer", "Long", "Boolean", "Object", "List",
            "Map", "Set", "Override");
    private static final int MAX_HITS_PER_FILE = 20;
    private static final int MAX_IDENTIFIERS_PER_FILE = 6;
    private static final int MAX_IDENTIFIER_CHARS = 128;

    private final DiffParser parser;
    private final CodeSearchTool search;
    private final ReviewWorkBudget budget;

    public DiffAnalyzer(DiffParser parser, CodeSearchTool search, ReviewWorkBudget budget) {
        this.parser = parser;
        this.search = search;
        this.budget = budget;
    }

    public ReviewContext analyze(String rawDiff, Path sourceRoot) {
        List<DiffParser.FileDiff> parsed = parser.parse(rawDiff);
        int fileCount = Math.min(parsed.size(), budget.input().maxChangedFiles());
        List<DiffParser.FileDiff> files = List.copyOf(parsed.subList(0, fileCount));
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return new ReviewContext(rawDiff, files, Map.of(), sourceRoot,
                    ReviewContext.SourceContextStatus.NOT_AVAILABLE);
        }

        Map<String, Set<String>> identifiersByFile = new LinkedHashMap<>();
        Set<String> allIdentifiers = new LinkedHashSet<>();
        for (DiffParser.FileDiff file : files) {
            Set<String> identifiers = extractIdentifiers(file);
            identifiersByFile.put(file.path(), identifiers);
            allIdentifiers.addAll(identifiers);
        }
        CodeSearchTool.SearchResult corpus = search.search(sourceRoot, allIdentifiers, budget);
        if (corpus.status() != CodeSearchTool.SearchStatus.COMPLETE
                && corpus.status() != CodeSearchTool.SearchStatus.SNIPPET_LIMIT_REACHED) {
            return new ReviewContext(rawDiff, files, Map.of(), sourceRoot, map(corpus.status()));
        }

        Map<String, List<CodeSnippet>> byFile = new LinkedHashMap<>();
        int remaining = budget.input().maxSnippets();
        for (DiffParser.FileDiff file : files) {
            int limit = Math.min(MAX_HITS_PER_FILE, remaining);
            List<CodeSnippet> snippets = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String identifier : identifiersByFile.get(file.path())) {
                for (CodeSearchTool.SearchHit hit
                        : corpus.hits().getOrDefault(identifier, List.of())) {
                    String key = hit.file() + ":" + hit.line() + ":" + hit.text();
                    if (seen.add(key)) {
                        snippets.add(new CodeSnippet(hit.file(), hit.line(), hit.text()));
                    }
                    if (snippets.size() >= limit) {
                        break;
                    }
                }
                if (snippets.size() >= limit) {
                    break;
                }
            }
            if (!snippets.isEmpty()) {
                byFile.put(file.path(), List.copyOf(snippets));
                remaining -= snippets.size();
                if (remaining == 0) {
                    break;
                }
            }
        }
        ReviewContext.SourceContextStatus contextStatus = corpus.status()
                == CodeSearchTool.SearchStatus.SNIPPET_LIMIT_REACHED
                ? ReviewContext.SourceContextStatus.LIMIT_EXCEEDED
                : ReviewContext.SourceContextStatus.COMPLETE;
        return new ReviewContext(rawDiff, files, byFile, sourceRoot, contextStatus);
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
            if (name.length() <= MAX_IDENTIFIER_CHARS && !STOPWORDS.contains(name)) {
                out.add(name);
            }
        }
    }

    private static ReviewContext.SourceContextStatus map(CodeSearchTool.SearchStatus status) {
        return switch (status) {
            case LIMIT_EXCEEDED, SNIPPET_LIMIT_REACHED ->
                    ReviewContext.SourceContextStatus.LIMIT_EXCEEDED;
            case TIMED_OUT -> ReviewContext.SourceContextStatus.TIMED_OUT;
            case CANCELLED -> ReviewContext.SourceContextStatus.CANCELLED;
            case NOT_DIRECTORY, UNAVAILABLE -> ReviewContext.SourceContextStatus.NOT_AVAILABLE;
            case COMPLETE -> ReviewContext.SourceContextStatus.COMPLETE;
        };
    }
}
