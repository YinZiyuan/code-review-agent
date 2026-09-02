package dev.langchain4j.example.codereview.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.LongSupplier;

@FunctionalInterface
interface WorkspaceTreeDeleter {

    enum DeleteOutcome {
        REMOVED,
        DEFERRED
    }

    void delete(Path root) throws IOException;

    static WorkspaceTreeDeleter markerLast() {
        return WorkspaceTreeDeleter::deleteMarkerLast;
    }

    static DeleteOutcome deleteMarkerLastBounded(
            Path root, int maxEntries, long deadlineNanos, LongSupplier nanoTime)
            throws IOException {
        Objects.requireNonNull(nanoTime, "nanoTime");
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return DeleteOutcome.REMOVED;
        }
        Path marker = root.resolve(ReviewWorkspace.MARKER);
        int[] entries = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    if (file.equals(marker)) {
                        return FileVisitResult.CONTINUE;
                    }
                    checkBound(entries, maxEntries, deadlineNanos, nanoTime);
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                        throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    if (!directory.equals(root)) {
                        checkBound(entries, maxEntries, deadlineNanos, nanoTime);
                        Files.delete(directory);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (DeletionDeferred deferred) {
            return DeleteOutcome.DEFERRED;
        }
        if (Thread.currentThread().isInterrupted() || nanoTime.getAsLong() >= deadlineNanos) {
            return DeleteOutcome.DEFERRED;
        }
        Files.deleteIfExists(marker);
        try {
            Files.delete(root);
        } catch (IOException rootFailure) {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(root)) {
                try {
                    Files.writeString(marker, ReviewWorkspace.MARKER_CONTENT);
                } catch (IOException markerFailure) {
                    rootFailure.addSuppressed(markerFailure);
                }
            }
            throw rootFailure;
        }
        return DeleteOutcome.REMOVED;
    }

    private static void checkBound(
            int[] entries, int maxEntries, long deadlineNanos, LongSupplier nanoTime)
            throws DeletionDeferred {
        if (Thread.currentThread().isInterrupted()
                || nanoTime.getAsLong() >= deadlineNanos
                || entries[0] >= maxEntries) {
            throw new DeletionDeferred();
        }
        entries[0]++;
    }

    private static void deleteMarkerLast(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path marker = root.resolve(ReviewWorkspace.MARKER);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.equals(marker)) {
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                if (!directory.equals(root)) {
                    Files.delete(directory);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Files.deleteIfExists(marker);
        try {
            Files.delete(root);
        } catch (IOException rootFailure) {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(root)) {
                try {
                    Files.writeString(marker, ReviewWorkspace.MARKER_CONTENT);
                } catch (IOException markerFailure) {
                    rootFailure.addSuppressed(markerFailure);
                }
            }
            throw rootFailure;
        }
    }

    final class DeletionDeferred extends IOException {
        private DeletionDeferred() {
            super("bounded workspace deletion deferred");
        }
    }
}
