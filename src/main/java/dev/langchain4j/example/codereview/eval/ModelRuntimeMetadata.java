package dev.langchain4j.example.codereview.eval;

public record ModelRuntimeMetadata(
        String provider,
        String baseUrlHost,
        String reviewerModel,
        String rerankerModel,
        String judgeModel
) {
    public static ModelRuntimeMetadata unknown() {
        return new ModelRuntimeMetadata("unknown", "unknown", "unknown", "unknown", "unknown");
    }
}
