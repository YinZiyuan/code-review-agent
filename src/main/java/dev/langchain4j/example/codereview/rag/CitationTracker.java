package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.rag.content.Content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CitationTracker {

    public List<Citation> toCitations(List<Content> contents) {
        if (contents == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<Citation> citations = new ArrayList<>();
        for (Content content : contents) {
            var metadata = content.textSegment().metadata();
            String id = metadata.getString("citation_id");
            if (id == null || id.isBlank() || !seen.add(id)) {
                continue;
            }
            citations.add(new Citation(id, metadata.getString("source_file"), metadata.getString("section")));
        }
        return citations;
    }
}
