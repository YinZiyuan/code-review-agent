package dev.langchain4j.example.codereview.reviewops.application.github;

import java.nio.file.Path;

public interface PreparedReviewSource extends AutoCloseable {

    String diffPatch();

    Path sourceRoot();

    @Override
    void close();
}
