package dev.langchain4j.example.codereview.workspace;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Restricted, bounded janitor for old, directly nested marker-bearing workspaces. */
public final class ReviewWorkspaceJanitor {

    private final Path temporaryParent;
    private final ReviewWorkBudget.WorkspaceLimits limits;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final LongSupplier nanoTime;

    public ReviewWorkspaceJanitor(Path temporaryParent, Duration staleAge, Clock clock) {
        this(temporaryParent, new ReviewWorkBudget.WorkspaceLimits(staleAge), clock,
                Metrics.globalRegistry);
    }

    public ReviewWorkspaceJanitor(
            Path temporaryParent,
            ReviewWorkBudget.WorkspaceLimits limits,
            Clock clock,
            MeterRegistry metrics) {
        this(temporaryParent, limits, clock, metrics, System::nanoTime);
    }

    ReviewWorkspaceJanitor(
            Path temporaryParent,
            ReviewWorkBudget.WorkspaceLimits limits,
            Clock clock,
            MeterRegistry metrics,
            LongSupplier nanoTime) {
        this.temporaryParent = Objects.requireNonNull(temporaryParent, "temporaryParent")
                .toAbsolutePath().normalize();
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public int cleanStale() {
        if (!Files.isDirectory(temporaryParent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(temporaryParent)) {
            return 0;
        }
        long deadline = deadlineAfter(limits.cleanupDeadline().toNanos());
        int removed = 0;
        int inspected = 0;
        int deletionAttempts = 0;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(temporaryParent)) {
            for (Path child : children) {
                StopReason stop = stopReason(deadline);
                if (stop != null) {
                    record(stop.metricValue);
                    return removed;
                }
                if (inspected >= limits.maxChildrenInspected()) {
                    record("cap_reached");
                    return removed;
                }
                inspected++;
                record("scanned");
                if (!eligible(child)) {
                    continue;
                }
                if (deletionAttempts >= limits.maxDeletionsPerRun()) {
                    record("cap_reached");
                    return removed;
                }
                deletionAttempts++;
                try {
                    WorkspaceTreeDeleter.DeleteOutcome outcome =
                            WorkspaceTreeDeleter.deleteMarkerLastBounded(
                                    child,
                                    limits.maxEntriesDeletedPerRun(),
                                    deadline,
                                    nanoTime);
                    if (outcome == WorkspaceTreeDeleter.DeleteOutcome.REMOVED) {
                        removed++;
                        record("removed");
                    } else {
                        record("cap_reached");
                    }
                } catch (IOException failure) {
                    record("failed");
                    // Exact marker remains as the path-free durable retry obligation.
                }
            }
        } catch (IOException failure) {
            record("failed");
        }
        return removed;
    }

    private boolean eligible(Path child) {
        if (!Objects.equals(child.getParent(), temporaryParent)
                || !child.getFileName().toString().startsWith(ReviewWorkspace.PREFIX)
                || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(child)) {
            return false;
        }
        Path marker = child.resolve(ReviewWorkspace.MARKER);
        try {
            return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(marker)
                    && Files.size(marker) == ReviewWorkspace.MARKER_CONTENT.length()
                    && Files.readString(marker).equals(ReviewWorkspace.MARKER_CONTENT)
                    && Files.getLastModifiedTime(marker).toInstant()
                    .isBefore(Instant.now(clock).minus(limits.staleAge()));
        } catch (IOException exception) {
            return false;
        }
    }

    private StopReason stopReason(long deadline) {
        if (Thread.currentThread().isInterrupted()) {
            return StopReason.CANCELLED;
        }
        return nanoTime.getAsLong() >= deadline ? StopReason.DEADLINE : null;
    }

    private long deadlineAfter(long durationNanos) {
        long now = nanoTime.getAsLong();
        return durationNanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationNanos;
    }

    private void record(String outcome) {
        metrics.counter("code.review.workspace.janitor", "outcome", outcome).increment();
    }

    private enum StopReason {
        CANCELLED("cancelled"),
        DEADLINE("deadline");

        private final String metricValue;

        StopReason(String metricValue) {
            this.metricValue = metricValue;
        }
    }
}
