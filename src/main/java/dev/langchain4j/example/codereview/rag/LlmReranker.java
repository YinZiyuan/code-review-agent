package dev.langchain4j.example.codereview.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

public class LlmReranker implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ContentRetriever upstream;
    private final ChatModel model;
    private final int topK;

    public LlmReranker(ContentRetriever upstream, ChatModel model, int topK) {
        this.upstream = upstream;
        this.model = model;
        this.topK = topK;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> candidates = upstream.retrieve(query);
        if (candidates.size() <= topK) {
            return candidates;
        }
        try {
            double[] scores = rate(query.text(), candidates);
            record Scored(Content content, double score, int originalIndex) {
            }
            return IntStream.range(0, candidates.size())
                    .mapToObj(i -> new Scored(candidates.get(i), i < scores.length ? scores[i] : 0.0, i))
                    .sorted(Comparator.<Scored>comparingDouble(Scored::score).reversed()
                            .thenComparingInt(Scored::originalIndex))
                    .limit(topK)
                    .map(Scored::content)
                    .toList();
        } catch (Exception e) {
            log.warn("LlmReranker fell back to upstream order: {}", e.toString());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    private double[] rate(String query, List<Content> candidates) throws Exception {
        StringBuilder prompt = new StringBuilder("""
                Rate each candidate's relevance to the query on a 0.0-1.0 scale.
                Respond with JSON: {"scores":[<num>, <num>, ...]} in the same order.

                Query:
                """).append(query).append("\n\nCandidates:\n");
        for (int i = 0; i < candidates.size(); i++) {
            String text = candidates.get(i).textSegment().text();
            if (text.length() > 400) {
                text = text.substring(0, 400) + "...";
            }
            prompt.append(i).append(") ").append(text).append('\n');
        }

        var response = model.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt.toString()))
                .build());
        String body = response.aiMessage().text();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalStateException("no JSON in reranker output");
        }

        JsonNode scoresNode = MAPPER.readTree(body.substring(start, end + 1)).get("scores");
        if (scoresNode == null || !scoresNode.isArray()) {
            throw new IllegalStateException("no scores array");
        }
        double[] scores = new double[scoresNode.size()];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = scoresNode.get(i).asDouble();
        }
        return scores;
    }
}
