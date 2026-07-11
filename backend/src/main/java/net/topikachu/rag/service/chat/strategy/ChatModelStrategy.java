package net.topikachu.rag.service.chat.strategy;

import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.SourcedAnswerPrompts;
import net.topikachu.rag.service.chat.SourcedAnswerResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface ChatModelStrategy {
    /**
     * 获取模型的唯一标识符，例如 "ollama" 或 "deepseek"
     */
    String getModelId();

    /**
     * 获取基于当前模型构建的 ChatClient 实例
     */
    ChatClient getChatClient();

    default Mono<SourcedAnswerResult> callSourcedAnswer(ReactiveChatGateway reactiveChatGateway,
                                                        String context,
                                                        String userInput,
                                                        String conversationId,
                                                        List<Message> historyMessages) {
        return reactiveChatGateway.callStructured(
                getChatClient(),
                SourcedAnswerPrompts.jsonPrompt(),
                Map.of("context", context),
                historyMessages,
                userInput,
                conversationId,
                SourcedAnswerResult.class);
    }
}
