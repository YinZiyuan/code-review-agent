package dev.langchain4j.example.codereview.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Marker-bearing owner for every filesystem artifact produced by one review attempt. */
public final class ReviewWorkspace implements AutoCloseable {

    public static final String PREFIX = "code-review-work-v1-";
    public static final String MARKER = ".code-review-workspace-v1";
    public static final String MARKER_CONTENT = "code-review-workspace:v1";

    private final Path root;
    private final WorkspaceTreeDeleter deleter;
    private final AtomicBoolean closed = new AtomicBoolean();

    ReviewWorkspace(Path root, WorkspaceTreeDeleter deleter) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.deleter = Objects.requireNonNull(deleter, "deleter");
    }

    public Path root() {
        return root;
    }

    public Path sourceDirectory() {
        return root.resolve("source");
    }

    public Path archiveFile() {
        return root.resolve("archive.zip");
    }

    public Path createClassesDirectory() throws IOException {
        return Files.createTempDirectory(root, "classes-");
    }

    public Path createReportFile() throws IOException {
        return Files.createTempFile(root, "spotbugs-", ".xml");
    }

    @Override
    public synchronized void close() {
        if (closed.get()) {
            return;
        }
        try {
            deleter.delete(root);
            closed.set(true);
        } catch (IOException exception) {
            throw new ReviewWorkspaceCleanupException();
        }
    }
}
