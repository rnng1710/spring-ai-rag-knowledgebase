package net.topikachu.rag.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.topikachu.rag.agent.AgentResolution;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
