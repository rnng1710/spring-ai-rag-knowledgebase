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
}
