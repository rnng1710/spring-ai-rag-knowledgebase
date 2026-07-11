package net.topikachu.rag.service.chat.strategy;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class GeminiChatModelStrategy implements ChatModelStrategy {

    private final ChatClient chatClient;

    public GeminiChatModelStrategy(@Qualifier("googleGenAiChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public String getModelId() {
        return "gemini";
    }

    @Override
    public ChatClient getChatClient() {
        return this.chatClient;
    }
}
