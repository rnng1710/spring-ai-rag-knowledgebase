package net.topikachu.rag.service.chat.strategy;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class OllamaChatModelStrategy implements ChatModelStrategy {

    private final ChatClient chatClient;

    // @Qualifier("openAiChatModel") 实际连接 Ollama：Ollama 暴露 OpenAI 兼容 API，Spring Boot 自动配置的 bean 指向本地 Ollama
    public OllamaChatModelStrategy(@Qualifier("openAiChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public String getModelId() {
        return "ollama";
    }

    @Override
    public ChatClient getChatClient() {
        return this.chatClient;
    }
}
