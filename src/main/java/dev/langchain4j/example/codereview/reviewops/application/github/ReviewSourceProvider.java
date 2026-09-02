package dev.langchain4j.example.codereview.reviewops.application.github;

import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;

public interface ReviewSourceProvider {

    PreparedReviewSource prepare(PullRequestRevision revision);
}
