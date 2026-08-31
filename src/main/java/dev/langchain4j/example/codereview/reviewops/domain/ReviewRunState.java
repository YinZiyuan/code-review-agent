package dev.langchain4j.example.codereview.reviewops.domain;

public enum ReviewRunState {
    REQUESTED, RUNNING, COMPLETED, PUBLISHING, PUBLISHED, FAILED, SUPERSEDED
}
