package dev.langchain4j.example.codereview.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class ReviewWorkspaceFactory {

    private final Path temporaryParent;
    private final WorkspaceTreeDeleter deleter;

    public ReviewWorkspaceFactory(Path temporaryParent) {
        this(temporaryParent, WorkspaceTreeDeleter.markerLast());
    }

    ReviewWorkspaceFactory(Path temporaryParent, WorkspaceTreeDeleter deleter) {
        this.temporaryParent = Objects.requireNonNull(temporaryParent, "temporaryParent")
                .toAbsolutePath().normalize();
        this.deleter = Objects.requireNonNull(deleter, "deleter");
    }

    public ReviewWorkspace create() throws IOException {
        requireRealTemporaryParent();
        Path root = Files.createTempDirectory(temporaryParent, ReviewWorkspace.PREFIX);
        try {
            Files.writeString(
                    root.resolve(ReviewWorkspace.MARKER),
                    ReviewWorkspace.MARKER_CONTENT,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            Files.createDirectory(root.resolve("source"));
            return new ReviewWorkspace(root, deleter);
        } catch (IOException | RuntimeException exception) {
            try {
                WorkspaceTreeDeleter.markerLast().delete(root);
            } catch (IOException ignored) {
                // The reserved prefix and absent/valid marker keep later cleanup restricted.
            }
            throw exception;
        }
    }

    public Path temporaryParent() {
        return temporaryParent;
    }

    public ReviewAnalysisWorkspace analysisFor(Path sourceDirectory) throws IOException {
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Path source = sourceDirectory.toAbsolutePath().normalize();
        Path candidateRoot = source.getParent();
        if (candidateRoot != null
                && source.getFileName().toString().equals("source")
                && candidateRoot.getParent() != null
                && candidateRoot.getParent().equals(temporaryParent)
                && validMarker(candidateRoot)) {
            return new ReviewAnalysisWorkspace(null, candidateRoot);
        }
        ReviewWorkspace owned = create();
        return new ReviewAnalysisWorkspace(owned, owned.root());
    }

    private static boolean validMarker(Path root) {
        if (!root.getFileName().toString().startsWith(ReviewWorkspace.PREFIX)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            return false;
        }
        Path marker = root.resolve(ReviewWorkspace.MARKER);
        try {
            return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(marker)
                    && Files.readString(marker).equals(ReviewWorkspace.MARKER_CONTENT);
        } catch (IOException exception) {
            return false;
        }
    }

    private void requireRealTemporaryParent() {
        if (!Files.isDirectory(temporaryParent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(temporaryParent)) {
            throw new IllegalArgumentException("temporary workspace parent must be a real directory");
        }
    }
}
