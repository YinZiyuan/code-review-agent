package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalRecorderTest {

    @Test
    void records_and_clears_per_thread() {
        RetrievalRecorder recorder = new RetrievalRecorder();
        Content c = Content.from(TextSegment.from("hello",
                Metadata.from(Map.of("citation_id", "x#y"))));

        recorder.record(List.of(c));
        assertThat(recorder.snapshot()).containsExactly(c);

        recorder.clear();
        assertThat(recorder.snapshot()).isEmpty();
    }

    @Test
    void thread_isolated() throws Exception {
        RetrievalRecorder recorder = new RetrievalRecorder();
        Content c = Content.from(TextSegment.from("hello",
                Metadata.from(Map.of("citation_id", "x#y"))));

        Thread t = new Thread(() -> recorder.record(List.of(c)));
        t.start();
        t.join();

        assertThat(recorder.snapshot()).isEmpty();
    }
}
