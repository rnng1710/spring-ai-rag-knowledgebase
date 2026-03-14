package net.topikachu.rag.service.chat.strategy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ChatModelStrategyFactoryTest {

    @Test
    void shouldResolveGeminiStrategyByModelId() {
        ChatModel mockModel = mock(ChatModel.class);

        ChatModelStrategy gemini = new GeminiChatModelStrategy(mockModel);
        ChatModelStrategy ollama = new OllamaChatModelStrategy(mockModel);

        ChatModelStrategyFactory factory = new ChatModelStrategyFactory(List.of(ollama, gemini));

        ChatModelStrategy resolved = factory.getStrategy("gemini");
        assertNotNull(resolved);
        assertEquals("gemini", resolved.getModelId());
    }
}

