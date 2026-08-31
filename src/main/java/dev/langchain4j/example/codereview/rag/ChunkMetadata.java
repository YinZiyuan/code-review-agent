package dev.langchain4j.example.codereview.rag;

public record ChunkMetadata(String sourceFile, String section, String snippet) {

    public String citationId() {
        return sourceFile.replace(".txt", "") + "#" + sectionSlug();
    }

    private String sectionSlug() {
        if (section == null || section.isBlank()) {
            return "intro";
        }
        return section.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
