package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.List;
import java.util.Objects;

public final class ReviewRunChildIdentityMismatchException extends RuntimeException {
    private final ReviewRunId reviewRunId;
    private final String childType;
    private final List<String> omittedIdentities;

    public ReviewRunChildIdentityMismatchException(ReviewRunId reviewRunId, String childType,
                                                   List<String> omittedIdentities) {
        super("review run " + Objects.requireNonNull(reviewRunId, "reviewRunId")
                + " update omitted persisted " + requireChildType(childType)
                + " identities: " + requireOmittedIdentities(omittedIdentities));
        this.reviewRunId = reviewRunId;
        this.childType = childType;
        this.omittedIdentities = List.copyOf(omittedIdentities);
    }

    public ReviewRunId reviewRunId() {
        return reviewRunId;
    }

    public String childType() {
        return childType;
    }

    public List<String> omittedIdentities() {
        return omittedIdentities;
    }

    private static String requireChildType(String childType) {
        if (childType == null || childType.isBlank()) {
            throw new IllegalArgumentException("childType must not be blank");
        }
        return childType;
    }

    private static List<String> requireOmittedIdentities(List<String> omittedIdentities) {
        List<String> required = List.copyOf(Objects.requireNonNull(
                omittedIdentities, "omittedIdentities"));
        if (required.isEmpty()) {
            throw new IllegalArgumentException("omittedIdentities must not be empty");
        }
        return required;
    }
}
