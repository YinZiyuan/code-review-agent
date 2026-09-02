package dev.langchain4j.example.codereview.tools;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** One-pass, deterministic and bounded search over an untrusted Java source corpus. */
@Component
public class CodeSearchTool {

    private static final int MAX_HITS_PER_NEEDLE = 50;
    private static final int MAX_LINE_CHARS = 200;
    private static final int MAX_NEEDLE_CHARS = 128;
    private static final int IDENTIFIERS_PER_CHANGED_FILE = 6;

    public enum SearchStatus {
        COMPLETE,
        SNIPPET_LIMIT_REACHED,
        LIMIT_EXCEEDED,
        TIMED_OUT,
        CANCELLED,
        NOT_DIRECTORY,
        UNAVAILABLE
    }

    public record SearchHit(String file, int line, String text) {
        public SearchHit {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(text, "text");
        }
    }

    public record SearchResult(
            SearchStatus status,
            Map<String, List<SearchHit>> hits,
            int filesRead,
            long bytesRead) {
        public SearchResult {
            status = Objects.requireNonNull(status, "status");
            TreeMap<String, List<SearchHit>> stable = new TreeMap<>();
            if (hits != null) {
                hits.forEach((needle, values) -> stable.put(needle, List.copyOf(values)));
            }
            hits = java.util.Collections.unmodifiableMap(stable);
            if (filesRead < 0 || bytesRead < 0) {
                throw new IllegalArgumentException("source census counters must be non-negative");
            }
        }
    }

    private final LongSupplier nanoTime;

    public CodeSearchTool() {
        this(System::nanoTime);
    }

    CodeSearchTool(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public SearchResult search(
            Path root, Collection<String> requestedNeedles, ReviewWorkBudget budget) {
        Objects.requireNonNull(budget, "budget");
        if (cancelled()) {
            return result(SearchStatus.CANCELLED, Map.of(), 0, 0);
        }
        if (root == null
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            return result(SearchStatus.NOT_DIRECTORY, Map.of(), 0, 0);
        }
        List<String> needles = normalizedNeedles(requestedNeedles, budget);
        if (needles.isEmpty()) {
            return result(SearchStatus.COMPLETE, Map.of(), 0, 0);
        }
        long deadline = deadlineAfter(budget.stages().diffAnalysis().toNanos());
        Census census = census(root, budget, deadline);
        if (census.status() != SearchStatus.COMPLETE) {
            return result(census.status(), Map.of(), 0, 0);
        }

        Pattern pattern = Pattern.compile(needles.stream()
                .sorted(Comparator.comparingInt(String::length).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|")));
        TreeMap<String, List<SearchHit>> hits = new TreeMap<>();
        int filesRead = 0;
        long bytesRead = 0;
        for (Path file : census.files()) {
            SearchProgress progress = scanFile(
                    root, file, pattern, hits, filesRead, bytesRead, budget, deadline);
            filesRead = progress.filesRead();
            bytesRead = progress.bytesRead();
            if (progress.status() != SearchStatus.COMPLETE) {
                return result(progress.status(), hits, filesRead, bytesRead);
            }
        }
        return result(SearchStatus.COMPLETE, hits, filesRead, bytesRead);
    }

    /** Compatibility surface for the CLI tool; production analysis uses {@link #search}. */
    public String grep(String rootPath, String needle) {
        if (needle == null || needle.isEmpty()) {
            return "Empty needle.";
        }
        SearchResult result = search(
                Path.of(rootPath), List.of(needle),
                new ReviewWorkBudgetProperties(null, null, null, null, null, null).toBudget());
        if (result.status() == SearchStatus.NOT_DIRECTORY) {
            return "Not a directory: " + rootPath;
        }
        List<SearchHit> values = result.hits().getOrDefault(needle, List.of());
        if (values.isEmpty()) {
            return "No matches for: " + needle;
        }
        StringBuilder out = new StringBuilder();
        values.forEach(hit -> out.append(hit.file()).append(':').append(hit.line())
                .append(": ").append(hit.text()).append('\n'));
        if (result.status() == SearchStatus.LIMIT_EXCEEDED
                || result.status() == SearchStatus.SNIPPET_LIMIT_REACHED
                || values.size() == MAX_HITS_PER_NEEDLE) {
            out.append("[truncated at ").append(values.size()).append(" hits]\n");
        }
        return out.toString();
    }

    private Census census(Path root, ReviewWorkBudget budget, long deadline) {
        List<Path> files = new ArrayList<>();
        long bytes = 0;
        int inspected = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            var paths = walk.iterator();
            while (paths.hasNext()) {
                SearchStatus stop = stopStatus(deadline);
                if (stop != null) {
                    return new Census(stop, List.of());
                }
                if (++inspected > budget.input().maxArchiveEntries()) {
                    return new Census(SearchStatus.LIMIT_EXCEEDED, List.of());
                }
                Path path = paths.next();
                if (!path.getFileName().toString().endsWith(".java")
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (files.size() >= budget.input().maxJavaSourceFiles()) {
                    return new Census(SearchStatus.LIMIT_EXCEEDED, List.of());
                }
                long size = Files.size(path);
                if (size > budget.input().maxJavaSourceBytes() - bytes) {
                    return new Census(SearchStatus.LIMIT_EXCEEDED, List.of());
                }
                files.add(path);
                bytes += size;
            }
        } catch (IOException | RuntimeException failure) {
            return new Census(SearchStatus.UNAVAILABLE, List.of());
        }
        files.sort(Comparator.comparing(path -> normalizedRelative(root, path)));
        return new Census(SearchStatus.COMPLETE, List.copyOf(files));
    }

    private SearchProgress scanFile(
            Path root,
            Path file,
            Pattern pattern,
            Map<String, List<SearchHit>> hits,
            int previousFiles,
            long previousBytes,
            ReviewWorkBudget budget,
            long deadline) {
        int maxLineBytes = budget.input().maxJavaSourceLineBytes();
        byte[] line = new byte[maxLineBytes];
        int length = 0;
        int lineNumber = 1;
        long bytesRead = previousBytes;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            int next;
            while ((next = input.read()) != -1) {
                bytesRead++;
                SearchStatus stop = stopStatus(deadline);
                if (stop != null) {
                    return new SearchProgress(stop, previousFiles, bytesRead);
                }
                if (next == '\n') {
                    if (matchLine(root, file, line, length, lineNumber, pattern, hits, budget)) {
                        return new SearchProgress(
                                SearchStatus.SNIPPET_LIMIT_REACHED, previousFiles + 1, bytesRead);
                    }
                    length = 0;
                    lineNumber++;
                } else {
                    if (length == maxLineBytes) {
                        return new SearchProgress(
                                SearchStatus.LIMIT_EXCEEDED, previousFiles, bytesRead);
                    }
                    line[length++] = (byte) next;
                }
            }
            if (length > 0
                    && matchLine(root, file, line, length, lineNumber, pattern, hits, budget)) {
                return new SearchProgress(
                        SearchStatus.SNIPPET_LIMIT_REACHED, previousFiles + 1, bytesRead);
            }
            return new SearchProgress(SearchStatus.COMPLETE, previousFiles + 1, bytesRead);
        } catch (IOException failure) {
            return new SearchProgress(SearchStatus.UNAVAILABLE, previousFiles, bytesRead);
        }
    }

    private boolean matchLine(
            Path root,
            Path file,
            byte[] bytes,
            int length,
            int lineNumber,
            Pattern pattern,
            Map<String, List<SearchHit>> hits,
            ReviewWorkBudget budget) {
        int textLength = length > 0 && bytes[length - 1] == '\r' ? length - 1 : length;
        String text = new String(bytes, 0, textLength, StandardCharsets.UTF_8);
        Matcher matcher = pattern.matcher(text);
        Set<String> matched = new LinkedHashSet<>();
        while (matcher.find()) {
            matched.add(matcher.group());
        }
        String snippet = text.length() > MAX_LINE_CHARS
                ? text.substring(0, MAX_LINE_CHARS) + "..." : text;
        for (String needle : matched) {
            List<SearchHit> values = hits.computeIfAbsent(needle, ignored -> new ArrayList<>());
            if (values.size() >= MAX_HITS_PER_NEEDLE) {
                continue;
            }
            if (totalHits(hits) >= budget.input().maxSnippets()) {
                return true;
            }
            values.add(new SearchHit(normalizedRelative(root, file), lineNumber, snippet));
        }
        return false;
    }

    private static int totalHits(Map<String, List<SearchHit>> hits) {
        return hits.values().stream().mapToInt(List::size).sum();
    }

    private static List<String> normalizedNeedles(
            Collection<String> requested, ReviewWorkBudget budget) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        int maxNeedles = Math.multiplyExact(
                budget.input().maxChangedFiles(), IDENTIFIERS_PER_CHANGED_FILE);
        return requested.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isEmpty() && value.length() <= MAX_NEEDLE_CHARS)
                .distinct()
                .sorted()
                .limit(maxNeedles)
                .toList();
    }

    private SearchStatus stopStatus(long deadline) {
        if (cancelled()) {
            return SearchStatus.CANCELLED;
        }
        return nanoTime.getAsLong() >= deadline ? SearchStatus.TIMED_OUT : null;
    }

    private static boolean cancelled() {
        return Thread.currentThread().isInterrupted();
    }

    private long deadlineAfter(long durationNanos) {
        long now = nanoTime.getAsLong();
        return durationNanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationNanos;
    }

    private static String normalizedRelative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static SearchResult result(
            SearchStatus status, Map<String, List<SearchHit>> hits, int files, long bytes) {
        return new SearchResult(status, hits, files, bytes);
    }

    private record Census(SearchStatus status, List<Path> files) {
    }

    private record SearchProgress(SearchStatus status, int filesRead, long bytesRead) {
    }
}
