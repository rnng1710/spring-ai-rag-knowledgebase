package net.topikachu.rag.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.topikachu.rag.agent.AgentResolution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReactiveChatGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void decodeStructuredResponseParsesPureJson() {
        String raw = """
                {"type":"followup","answerMode":"normal","draftAnswer":"","finalInstruction":"","selectedEvidenceIds":["e1"]}
                """;

        AgentResolution resolution = ReactiveChatGateway.decodeStructuredResponse(raw, AgentResolution.class, objectMapper);

        assertEquals("followup", resolution.type());
        assertEquals("normal", resolution.answerMode());
        assertEquals(List.of("e1"), resolution.selectedEvidenceIds());
    }

    @Test
    void decodeStructuredResponseExtractsJsonFromAnalysisAndFence() {
        String raw = """
                基于检索到的证据，我需要继续收口。

                ```json
                {
                  "type":"followup",
                  "answerMode":"normal",
                  "draftAnswer":"",
                  "finalInstruction":"",
                  "selectedEvidenceIds":["e1","e2"]
                }
                ```
                """;

        AgentResolution resolution = ReactiveChatGateway.decodeStructuredResponse(raw, AgentResolution.class, objectMapper);

        assertEquals("followup", resolution.type());
        assertEquals("normal", resolution.answerMode());
        assertEquals(List.of("e1", "e2"), resolution.selectedEvidenceIds());
    }

    @Test
    void decodeStructuredResponseRejectsPlainText() {
        String raw = "你可能是在询问你的高中的名称。";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReactiveChatGateway.decodeStructuredResponse(raw, AgentResolution.class, objectMapper));

        assertEquals("Could not parse structured tool-phase response.", error.getMessage());
    }

    @Test
    void decodeStructuredResponseAcceptsUsedSourceStringArray() {
        String raw = """
                {
                  "answer":"我们的高中叫测试高中。《test.pdf》第 1 页",
                  "answerType":"factual",
                  "usedSources":["ev-1"]
                }
                """;

        SourcedAnswerResult result = ReactiveChatGateway.decodeStructuredResponse(raw, SourcedAnswerResult.class, objectMapper);

        assertEquals("factual", result.answerType());
        assertEquals("ev-1", result.usedSources().get(0));
    }

    @Test
    void decodeWithThinkTags() {
        String raw = """
                <think>
                The user is asking about their high school name. Let me check the context...
                I found the answer in the knowledge base.
                </think>
                {"answer":"我们的高中叫测试高中。《test.pdf》第 1 页","answerType":"factual","usedSources":["ev-1"]}
                """;

        SourcedAnswerResult result = ReactiveChatGateway.decodeStructuredResponse(raw, SourcedAnswerResult.class, objectMapper);

        assertEquals("factual", result.answerType());
        assertEquals("ev-1", result.usedSources().get(0));
    }

    @Test
    void decodeWithThinkTagsAndMarkdownFence() {
        String raw = """
                <think>
                Let me analyze the context to find the answer.
                The relevant information is in evidence ev-2.
                </think>
                Some explanation text here.
                ```json
                {
                  "answer": "根据文档，学校名称是示范高中。《handbook.pdf》第 3 页",
                  "answerType": "factual",
                  "usedSources": ["ev-2"]
                }
                ```
                """;

        SourcedAnswerResult result = ReactiveChatGateway.decodeStructuredResponse(raw, SourcedAnswerResult.class, objectMapper);

        assertEquals("factual", result.answerType());
        assertEquals("ev-2", result.usedSources().get(0));
    }

    @Test
    void stripThinkTagsRemovesThinkBlocks() {
        String input = "<think>\nsome reasoning\n</think>\n{\"answer\":\"hello\"}";
        String result = ReactiveChatGateway.stripThinkTags(input);
        assertEquals("{\"answer\":\"hello\"}", result);
    }

    @Test
    void stripThinkTagsPreservesNonThinkContent() {
        String input = "{\"answer\":\"hello\",\"answerType\":\"factual\"}";
        String result = ReactiveChatGateway.stripThinkTags(input);
        assertEquals(input, result);
    }

    @Test
    void stripThinkTagsHandlesNull() {
        assertNull(ReactiveChatGateway.stripThinkTags(null));
    }
}
