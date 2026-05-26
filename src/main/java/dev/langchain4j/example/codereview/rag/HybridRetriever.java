package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HybridRetriever implements ContentRetriever {

    private final ContentRetriever vector;
    private final ContentRetriever bm25;
    private final int rrfK;
    private final int topK;

    public HybridRetriever(ContentRetriever vector, ContentRetriever bm25, int rrfK, int topK) {
        this.vector = vector;
        this.bm25 = bm25;
        this.rrfK = rrfK;
        this.topK = topK;
    }

    @Override
    public List<Content> retrieve(Query query) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Content> byId = new HashMap<>();

        accumulate(safe(vector.retrieve(query)), scores, byId);
        accumulate(safe(bm25.retrieve(query)), scores, byId);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> byId.get(entry.getKey()))
                .toList();
    }

    private void accumulate(List<Content> list, Map<String, Double> scores, Map<String, Content> byId) {
        for (int i = 0; i < list.size(); i++) {
            Content content = list.get(i);
            String id = idOf(content);
            byId.putIfAbsent(id, content);
            scores.merge(id, 1.0 / (rrfK + i + 1), Double::sum);
        }
    }

    private static String idOf(Content content) {
        String id = content.textSegment().metadata().getString("citation_id");
        if (id != null && !id.isBlank()) {
            return id;
        }
        return Integer.toHexString(content.textSegment().text().hashCode());
    }

    private static List<Content> safe(List<Content> contents) {
        return contents == null ? List.of() : contents;
    }
}
