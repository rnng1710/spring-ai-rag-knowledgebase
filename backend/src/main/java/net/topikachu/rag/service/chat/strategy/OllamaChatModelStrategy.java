package net.topikachu.rag.service.chat.strategy;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class OllamaChatModelStrategy implements ChatModelStrategy {

    private final ChatClient chatClient;

    public OllamaChatModelStrategy(ChatModel chatModel) {
        // Use the default auto-configured ChatModel (which connects to Ollama)
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

    @Override
    public String getSystemPromptTemplate() {
        return """
                你是一个专业的企业级知识库助手。
                请严格遵守以下规则：
                1. 仅根据提供的 context 内容回答问题，不要加入个人主观臆断。
                2. 如果上下文中没有相关信息，请直接回答“根据已知文档无法回答该问题”，严禁编造。
                3. 回答时请保持客观、简洁，不要输出思考过程。

                Context:
                {context}
                """;
    }
}
