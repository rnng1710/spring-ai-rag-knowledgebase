package net.topikachu.rag.service.chat.strategy;

import org.springframework.ai.chat.client.ChatClient;

public interface ChatModelStrategy {
    /**
     * 获取模型的唯一标识符，例如 "ollama" 或 "deepseek"
     */
    String getModelId();

    /**
     * 获取基于当前模型构建的 ChatClient 实例
     */
    ChatClient getChatClient();

    /**
     * 获取针对当前模型优化过的 System Prompt 模板
     */
    String getSystemPromptTemplate();
}
