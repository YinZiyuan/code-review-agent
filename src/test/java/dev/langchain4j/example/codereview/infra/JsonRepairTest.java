package dev.langchain4j.example.codereview.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JsonRepairTest {

    record Box(String a, int b) {
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void valid_json_is_parsed_without_calling_llm() {
        ChatModel model = mock(ChatModel.class);
        JsonRepair repair = new JsonRepair(model, mapper);

        Box result = repair.parseOrRepair("{\"a\":\"x\",\"b\":1}", Box.class);

        assertThat(result).isEqualTo(new Box("x", 1));
        verifyNoInteractions(model);
    }

    @Test
    void broken_json_is_repaired_via_llm() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"a\":\"x\",\"b\":1}"))
                        .build());
        JsonRepair repair = new JsonRepair(model, mapper);

        Box result = repair.parseOrRepair("{\"a\":\"x\" \"b\":1}", Box.class);

        assertThat(result).isEqualTo(new Box("x", 1));
    }

    @Test
    void repair_still_broken_rethrows() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("still broken {"))
                        .build());
        JsonRepair repair = new JsonRepair(model, mapper);

        assertThatThrownBy(() -> repair.parseOrRepair("not json at all", Box.class))
                .isInstanceOf(JsonRepair.RepairFailedException.class);
    }
}
