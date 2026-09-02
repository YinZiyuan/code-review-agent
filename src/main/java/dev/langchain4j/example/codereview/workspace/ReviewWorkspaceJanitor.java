package dev.langchain4j.example.codereview.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

/** Restricted janitor: only exact, old, directly nested marker-bearing workspaces qualify. */
public final class ReviewWorkspaceJanitor {

    private final Path temporaryParent;
    private final Duration staleAge;
    private final Clock clock;

    public ReviewWorkspaceJanitor(Path temporaryParent, Duration staleAge, Clock clock) {
        this.temporaryParent = Objects.requireNonNull(temporaryParent, "temporaryParent")
                .toAbsolutePath().normalize();
        if (staleAge == null || staleAge.isZero() || staleAge.isNegative()) {
            throw new IllegalArgumentException("staleAge must be positive");
        }
        this.staleAge = staleAge;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int cleanStale() {
        if (!Files.isDirectory(temporaryParent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(temporaryParent)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> children = Files.list(temporaryParent)) {
            for (Path child : children.sorted().toList()) {
                if (eligible(child)) {
                    try {
                        WorkspaceTreeDeleter.markerLast().delete(child);
                        removed++;
                    } catch (IOException ignored) {
                        // Exact marker remains as the retry obligation.
                    }
                }
            }
        } catch (IOException ignored) {
            return removed;
        }
        return removed;
    }

    private boolean eligible(Path child) {
        if (!child.getParent().equals(temporaryParent)
                || !child.getFileName().toString().startsWith(ReviewWorkspace.PREFIX)
                || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(child)) {
            return false;
        }
        Path marker = child.resolve(ReviewWorkspace.MARKER);
        try {
            return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(marker)
                    && Files.readString(marker).equals(ReviewWorkspace.MARKER_CONTENT)
                    && Files.getLastModifiedTime(marker).toInstant()
                    .isBefore(Instant.now(clock).minus(staleAge));
        } catch (IOException exception) {
            return false;
        }
    }
}
