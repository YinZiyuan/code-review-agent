package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RetrievalRecorder {

    private final ThreadLocal<List<Content>> hits = ThreadLocal.withInitial(ArrayList::new);

    public void record(List<Content> contents) {
        if (contents != null) {
            hits.get().addAll(contents);
        }
    }

    public List<Content> snapshot() {
        return List.copyOf(hits.get());
    }

    public void clear() {
        hits.remove();
    }
}
