package dev.langchain4j.example.codereview.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A scoped owner for compiler and analyzer artifacts within a review workspace. */
public final class ReviewAnalysisWorkspace implements AutoCloseable {

    private final ReviewWorkspace ownedWorkspace;
    private final Path root;
    private final List<Path> artifacts = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    ReviewAnalysisWorkspace(ReviewWorkspace ownedWorkspace, Path root) {
        this.ownedWorkspace = ownedWorkspace;
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public synchronized Path createClassesDirectory() throws IOException {
        requireOpen();
        Path directory = Files.createTempDirectory(root, "classes-");
        artifacts.add(directory);
        return directory;
    }

    public synchronized Path createReportFile() throws IOException {
        requireOpen();
        Path file = Files.createTempFile(root, "spotbugs-", ".xml");
        artifacts.add(file);
        return file;
    }

    @Override
    public synchronized void close() {
        if (closed.get()) {
            return;
        }
        if (ownedWorkspace != null) {
            ownedWorkspace.close();
            closed.set(true);
            return;
        }
        try {
            for (int index = artifacts.size() - 1; index >= 0; index--) {
                deleteArtifact(artifacts.get(index));
            }
            closed.set(true);
        } catch (IOException exception) {
            throw new ReviewWorkspaceCleanupException();
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("analysis workspace is closed");
        }
    }

    private static void deleteArtifact(Path artifact) throws IOException {
        if (!Files.exists(artifact, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(artifact)
                || !Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(artifact);
            return;
        }
        Files.walkFileTree(artifact, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
