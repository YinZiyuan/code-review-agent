package dev.langchain4j.example.codereview.workspace;

/** Safe, path-free signal that workspace cleanup must be retried by the janitor. */
public final class ReviewWorkspaceCleanupException extends RuntimeException {

    public ReviewWorkspaceCleanupException() {
        super("Could not remove review workspace", null, false, false);
    }
}
