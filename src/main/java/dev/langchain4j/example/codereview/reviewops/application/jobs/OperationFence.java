package dev.langchain4j.example.codereview.reviewops.application.jobs;

/** Live lease authority required immediately before an irreversible mutation. */
@FunctionalInterface
public interface OperationFence {

    OperationFence UNFENCED = () -> {
    };

    void requireCurrent();

    static OperationFence unfenced() {
        return UNFENCED;
    }

    final class Lost extends RuntimeException {
        public Lost() {
            super("job lease authority was lost");
        }
    }
}
