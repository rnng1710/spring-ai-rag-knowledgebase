package net.topikachu.rag.service.chat.strategy;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(name = "dashScopeChatModel")
public class DashScopeChatModelStrategy implements ChatModelStrategy {

    private final ChatClient chatClient;

    public DashScopeChatModelStrategy(@Qualifier("dashScopeChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @Override
    public String getModelId() {
        return "dashscope";
    }

    @Override
    public ChatClient getChatClient() {
        return this.chatClient;
    }
}
