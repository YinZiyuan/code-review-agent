package dev.langchain4j.example.codereview.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

@FunctionalInterface
interface WorkspaceTreeDeleter {

    void delete(Path root) throws IOException;

    static WorkspaceTreeDeleter markerLast() {
        return WorkspaceTreeDeleter::deleteMarkerLast;
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
        Files.delete(root);
    }
}
