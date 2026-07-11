package net.topikachu.rag.service.chat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatServiceStrategyBoundaryTest {

    @Test
    void chatServiceDoesNotKnowDeepSeekStrategyType() throws IOException {
        String source = Files.readString(Path.of("src/main/java/net/topikachu/rag/service/chat/ChatService.java"));

        assertFalse(source.contains("DeepSeekChatModelStrategy"));
        assertFalse(source.contains("instanceof DeepSeek"));
    }

    @Test
    void groundedTurnDoesNotKnowDeepSeekStrategyType() throws IOException {
        String source = Files.readString(Path.of("src/main/java/net/topikachu/rag/service/chat/GroundedTurnModule.java"));

        assertFalse(source.contains("DeepSeekChatModelStrategy"));
        assertFalse(source.contains("instanceof DeepSeek"));
    }

    @Test
    void finalChatPathsDoNotBypassGroundedTurn() throws IOException {
        String ragSource = Files.readString(Path.of("src/main/java/net/topikachu/rag/service/chat/ChatService.java"));
        String agentSource = Files.readString(Path.of("src/main/java/net/topikachu/rag/agent/AgentChatService.java"));
        String combined = ragSource + agentSource;

        assertFalse(combined.contains(".subscribe()"));
        assertFalse(combined.contains("fromDocuments("));
        assertFalse(combined.contains("streamFinalAnswer("));
    }
}
